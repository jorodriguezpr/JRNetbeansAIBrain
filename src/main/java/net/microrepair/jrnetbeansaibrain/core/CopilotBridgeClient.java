// JR NetBeans AI Brain (JRNetbeansAIBrain)
// Copyright 2026 Jose Rodriguez Arroyo
// Developer: Jose Rodriguez Arroyo - https://microrepair.net
// Project: https://github.com/jorodriguezpr/JRNetbeansAIBrain
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// SPDX-License-Identifier: Apache-2.0
package net.microrepair.jrnetbeansaibrain.core;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * "Provider" that asks the Microsoft Copilot Windows app through UI Automation (the bridge script
 * shared with jrnetbeanspilot). Copilot has no API and no tool calling, so the conversation is
 * sent as text and the model is told to answer tool calls as JSON blocks, which the agent runs.
 * The Copilot app keeps the conversation, so after the first message only the new messages
 * (tool results, the developer's next question) are sent. Slow and experimental, Windows only.
 */
public final class CopilotBridgeClient extends ChatClient {

    public static final String DEFAULT_EXE = "C:\\Program Files (x86)\\Microsoft\\Copilot\\Application\\copilotapp.exe";
    /** Longest text pasted into Copilot in one go. */
    static final int MAX_PROMPT_CHARS = 30_000;

    private final String exe;
    private final int timeoutSeconds;
    private volatile Process process;
    private int sent;
    private String conversationKey = "";
    private static Path script;

    public CopilotBridgeClient(String copilotExe, int timeoutSeconds) {
        super("Copilot app", timeoutSeconds);
        this.exe = copilotExe == null || copilotExe.isBlank() ? DEFAULT_EXE : copilotExe.trim();
        this.timeoutSeconds = Math.max(60, timeoutSeconds);
    }

    @Override
    public String endpoint() {
        return exe;
    }

    @Override
    protected String host() {
        return "the Copilot app";
    }

    @Override
    public boolean nativeTools() {
        return false;
    }

    @Override
    public void cancel() {
        super.cancel();
        Process p = process;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    /** Copilot: no retries of a whole UI round trip; failures are reported at once. */
    @Override
    public Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException {
        if (cancelled) {
            throw new CancelledException();
        }
        long start = System.currentTimeMillis();
        Reply r = once(messages, tools, stream);
        r.millis = System.currentTimeMillis() - start;
        return r;
    }

    @Override
    protected Reply once(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException {
        String key = keyOf(messages);
        boolean fresh = sent == 0 || sent > messages.size() || !key.equals(conversationKey);
        String prompt = prompt(messages, tools, fresh ? 0 : sent, fresh);

        Map<String, Object> st = run("status", List.of(), stream);
        if (!Boolean.TRUE.equals(st.get("running")) || !Boolean.TRUE.equals(st.get("accessible"))) {
            stream.progress("Starting the Copilot app with accessibility on ...");
            run("launch", List.of(), stream);
        }
        Path file = Files.createTempFile("jrnetbeansaibrain-", ".txt");
        Map<String, Object> res;
        try {
            Files.writeString(file, prompt, StandardCharsets.UTF_8);
            List<String> args = new ArrayList<>(List.of("-PromptFile", file.toString(),
                    "-TimeoutSec", String.valueOf(timeoutSeconds)));
            if (fresh) {
                args.add("-NewChat");
            }
            res = run("ask", args, stream);
        } finally {
            Files.deleteIfExists(file);
        }
        String text = res.get("text") == null ? "" : String.valueOf(res.get("text")).replace("\r\n", "\n");
        StringBuilder content = new StringBuilder(text.trim());
        Object blocks = res.get("blocks");
        if (!text.contains("```") && blocks instanceof List) {
            for (Object o : (List<?>) blocks) {
                if (o instanceof Map) {
                    Map<?, ?> b = (Map<?, ?>) o;
                    content.append("\n\n```").append(b.get("language") == null ? "" : b.get("language")).append('\n')
                            .append(b.get("code") == null ? "" : b.get("code")).append("\n```");
                }
            }
        }
        sent = messages.size() + 1;
        conversationKey = key;
        Reply r = new Reply();
        r.content.append(content);
        stream.content(content.toString());
        r.promptTokens = prompt.length() / 4;
        r.outputTokens = content.length() / 4;
        return r;
    }

    /** Identifies one conversation: its first developer message. */
    private static String keyOf(List<Map<String, Object>> messages) {
        for (Map<String, Object> m : messages) {
            if ("user".equals(m.get("role"))) {
                String c = Json.str(m, "content", "");
                return c.length() > 200 ? c.substring(0, 200) : c;
            }
        }
        return "";
    }

    /** The text pasted into Copilot: on a new chat the instructions and tools, then the new messages. */
    static String prompt(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int from, boolean fresh) {
        StringBuilder b = new StringBuilder();
        if (fresh) {
            for (Map<String, Object> m : messages) {
                if ("system".equals(m.get("role"))) {
                    b.append(Json.str(m, "content", "")).append("\n\n");
                }
            }
            if (tools != null && !tools.isEmpty()) {
                b.append("# Tools\nYou cannot run tools yourself: to use one, answer with ONLY one or more JSON code "
                        + "blocks like this, and nothing else:\n```json\n{\"name\": \"read_file\", \"arguments\": "
                        + "{\"path\": \"routes/web.php\"}}\n```\nThe results come back in the next message. When "
                        + "you have what you need, answer normally without JSON tool blocks.\nAvailable tools:\n");
                for (Map<String, Object> t : tools) {
                    Object fo = t.get("function");
                    Map<?, ?> f = fo instanceof Map ? (Map<?, ?>) fo : t;
                    b.append("- ").append(f.get("name")).append('(');
                    Object params = f.get("parameters");
                    List<String> names = new ArrayList<>();
                    if (params instanceof Map && ((Map<?, ?>) params).get("properties") instanceof Map) {
                        for (Object n : ((Map<?, ?>) ((Map<?, ?>) params).get("properties")).keySet()) {
                            names.add(String.valueOf(n));
                        }
                    }
                    String desc = String.valueOf(f.get("description"));
                    int dot = desc.indexOf(". ");
                    b.append(String.join(", ", names)).append("): ").append(dot > 0 ? desc.substring(0, dot + 1) : desc)
                            .append('\n');
                }
                b.append('\n');
            }
            b.append("# Conversation\n");
        }
        List<Map<String, Object>> news = new ArrayList<>();
        for (int k = Math.max(0, from); k < messages.size(); k++) {
            if (!"system".equals(messages.get(k).get("role"))) {
                news.add(messages.get(k));
            }
        }
        int budget = Math.max(4000, MAX_PROMPT_CHARS - b.length() - 400);
        int perTool = Math.max(1500, budget / Math.max(1, news.size()));
        for (Map<String, Object> m : news) {
            String role = Json.str(m, "role", "user");
            String c = Json.str(m, "content", "");
            if ("tool".equals(role)) {
                if (c.length() > perTool) {
                    c = c.substring(0, perTool * 2 / 3) + "\n[... cut ...]\n" + c.substring(c.length() - perTool / 3);
                }
                b.append("Result of ").append(Json.str(m, "tool_name", "the tool")).append(":\n```\n").append(c)
                        .append("\n```\n\n");
            } else if ("assistant".equals(role)) {
                b.append("Your earlier answer:\n").append(c).append("\n\n");
            } else {
                b.append(fresh || from > 0 ? "Developer: " : "").append(c).append("\n\n");
            }
        }
        b.append(tools != null && !tools.isEmpty()
                ? "Answer now: JSON tool blocks only, or your final answer."
                : "Answer now (no more tools).");
        String s = b.toString();
        return s.length() > MAX_PROMPT_CHARS ? s.substring(s.length() - MAX_PROMPT_CHARS) : s;
    }

    // ---------------------------------------------------------------- the bridge process

    private static synchronized Path scriptPath() throws IOException {
        if (script != null && Files.isRegularFile(script)) {
            return script;
        }
        byte[] data;
        try (InputStream in = CopilotBridgeClient.class.getResourceAsStream(
                "/net/microrepair/jrnetbeansaibrain/copilot-bridge.ps1")) {
            if (in == null) {
                throw new IOException("copilot-bridge.ps1 is missing from the module");
            }
            data = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 12);
        } catch (Exception e) {
            hash = Integer.toHexString(Arrays.hashCode(data));
        }
        String base = System.getenv("LOCALAPPDATA");
        Path dir = Paths.get(base != null ? base : System.getProperty("java.io.tmpdir"), "JRNetbeansAIBrain");
        Files.createDirectories(dir);
        Path p = dir.resolve("copilot-bridge-" + hash + ".ps1");
        if (!Files.isRegularFile(p) || !Arrays.equals(Files.readAllBytes(p), data)) {
            Files.write(p, data);
        }
        script = p;
        return p;
    }

    /** Run one bridge action; returns its result object, or throws with the bridge's error. */
    Map<String, Object> run(String action, List<String> extra, Stream stream) throws IOException {
        if (!CommandRunner.WINDOWS) {
            throw new ApiException(400, "The Copilot app bridge works only on Windows.");
        }
        List<String> cmd = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA",
                "-ExecutionPolicy", "Bypass", "-File", scriptPath().toString(), "-Action", action, "-CopilotExe", exe));
        cmd.addAll(extra);
        if (cancelled) {
            throw new CancelledException();
        }
        Process p = new ProcessBuilder(cmd).start();
        process = p;
        p.getOutputStream().close();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread errReader = new Thread(() -> {
            try (InputStream es = p.getErrorStream()) {
                es.transferTo(err);
            } catch (IOException ignored) {
                // process ended
            }
        }, "JRNetbeansAIBrain-copilot-stderr");
        errReader.setDaemon(true);
        errReader.start();
        Map<String, Object> result = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                Map<String, Object> obj = Json.parseObjectOrNull(line.trim());
                if (obj == null) {
                    continue;
                }
                if ("progress".equals(obj.get("type"))) {
                    stream.progress(String.valueOf(obj.get("message")));
                } else if ("result".equals(obj.get("type"))) {
                    result = obj;
                }
            }
        } catch (IOException e) {
            // stream closed (stop)
        } finally {
            try {
                p.waitFor();
                errReader.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
        if (cancelled) {
            throw new CancelledException();
        }
        if (result == null) {
            String se = err.toString(StandardCharsets.UTF_8).trim();
            throw new IOException("The Copilot bridge returned no result." + (se.isEmpty() ? "" : " " + se));
        }
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            String code = String.valueOf(result.get("code"));
            String msg = "Copilot app: " + result.get("error");
            // A UI failure is not fixed by asking again at once.
            throw new ApiException("TIMEOUT".equals(code) || "NO_ANSWER".equals(code) ? 504 : 400, msg);
        }
        return result;
    }

    /** Is the Copilot app running and readable? (for "Test connection") */
    public String status() throws IOException {
        Map<String, Object> st = run("status", List.of(), new Stream() { });
        boolean running = Boolean.TRUE.equals(st.get("running"));
        boolean accessible = Boolean.TRUE.equals(st.get("accessible"));
        return !running ? "The Copilot app is not running (it is started when you ask)."
                : accessible ? "The Copilot app is running and readable - ready."
                : "The Copilot app runs without accessibility; it is restarted with it when you ask.";
    }
}
