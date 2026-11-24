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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.microrepair.jrnetbeansaibrain.core.Workspace.ToolError;

/**
 * The agent loop: send the conversation to the model, run the tools it asks for, send the results
 * back, and repeat until it answers without tools (or the step limit is reached).
 *
 * <p>Guards against the usual failures of smaller models:
 * <ul>
 * <li>tool calls written as text instead of real tool calls are recognised and run;</li>
 * <li>arguments sent as a JSON string, unknown tools and missing arguments get a clear error back;</li>
 * <li>the same call repeated again and again is stopped;</li>
 * <li>an empty answer gets one reminder; long conversations drop old tool output to stay in context.</li>
 * </ul>
 * Not thread-safe: run one {@link #send} at a time (the UI does that).
 */
public final class Agent {

    /** Events for the chat window. Called on the agent's (background) thread. */
    public interface Listener {
        default void stepStart(int step) {
        }

        default void thinking(String text) {
        }

        default void content(String text) {
        }

        default void retrying(String why, int attempt) {
        }

        default void toolStart(String name, String label) {
        }

        default void toolEnd(String name, boolean ok, String result) {
        }

        default void notice(String text) {
        }

        /** A status line from a slow provider (the Copilot app bridge). */
        default void progress(String text) {
        }

        /** A line of output from a running command. */
        default void toolProgress(String line) {
        }

        /** The model wrote or updated its plan (checklist text). */
        default void plan(String steps) {
        }

        default void done(Stats stats) {
        }
    }

    /** Totals for one {@link #send}. */
    public static final class Stats {
        public int steps;
        public int toolCalls;
        public int promptTokens;
        public int outputTokens;
        public long millis;
        public boolean stepLimit;
    }

    private static final int CONTEXT_BUDGET_CHARS = 260_000;
    private static final Pattern TAGGED = Pattern.compile(
            "<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", Pattern.DOTALL);
    private static final Pattern FENCED = Pattern.compile(
            "```(?:json|tool_call|tool)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

    private volatile ChatClient client;
    private int callSeq;
    private final Tools tools;
    private final java.util.function.Supplier<String> systemPrompt;
    private int maxSteps;
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private volatile boolean stop;

    public Agent(ChatClient client, Tools tools, java.util.function.Supplier<String> systemPrompt, int maxSteps) {
        this.client = client;
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxSteps = Math.max(1, maxSteps);
    }

    public ChatClient client() {
        return client;
    }

    /** Continue this conversation with another provider or model. */
    public void setClient(ChatClient c) {
        client = c;
    }

    /** Conversation so far, without the system prompt. */
    public List<Map<String, Object>> history() {
        return messages;
    }

    public void reset() {
        messages.clear();
    }

    public void cancel() {
        stop = true;
        client.cancel();
        tools.cancel();
    }

    public Tools tools() {
        return tools;
    }

    public void setMaxSteps(int n) {
        maxSteps = Math.max(1, n);
    }

    /**
     * Send one user message and run the loop until the model is done.
     *
     * @throws IOException network/provider errors (after retries) or {@link ChatClient.CancelledException}
     */
    public Stats send(String userText, Listener l) throws IOException {
        stop = false;
        ChatClient client = this.client;
        client.resetCancel();
        Stats st = new Stats();
        long start = System.currentTimeMillis();
        messages.add(Json.obj("role", "user", "content", userText));
        Map<String, Integer> seen = new HashMap<>();
        tools.setSinks(l::toolProgress, l::plan);
        boolean nudged = false;
        try {
            for (int step = 1; ; step++) {
                if (stop) {
                    throw new ChatClient.CancelledException();
                }
                boolean last = step > maxSteps;
                if (last) {
                    st.stepLimit = true;
                    messages.add(Json.obj("role", "user", "content",
                            "You reached the limit of " + maxSteps + " tool steps for this request. Do not call "
                            + "more tools. Answer now with what you found, and say what is still left to do."));
                    l.notice("Step limit reached (" + maxSteps + "); asking for a summary.");
                }
                compact();
                l.stepStart(step);
                st.steps = step;
                // Claude needs the tool list whenever the history holds tool calls; its last-step calls are ignored.
                boolean sendTools = !last || client instanceof AnthropicClient;
                ChatClient.Reply r = client.chat(withSystem(), sendTools ? tools.definitions() : null,
                        new ChatClient.Stream() {
                            @Override
                            public void thinking(String t) {
                                l.thinking(t);
                            }

                            @Override
                            public void content(String t) {
                                l.content(t);
                            }

                            @Override
                            public void retrying(String why, int attempt) {
                                l.retrying(why, attempt);
                            }

                            @Override
                            public void progress(String text) {
                                l.progress(text);
                            }
                        });
                st.promptTokens += r.promptTokens;
                st.outputTokens += r.outputTokens;

                String content = r.content.toString();
                List<Call> calls = new ArrayList<>();
                for (Map<String, Object> tc : r.toolCalls) {
                    calls.add(Call.from(tc));
                }
                if (last) {
                    calls.clear();
                } else if (calls.isEmpty()) {
                    calls = textCalls(content);
                    if (!calls.isEmpty() && client.nativeTools()) {
                        l.notice("The model wrote its tool call as text; running it.");
                    }
                }

                Map<String, Object> assistant = Json.obj("role", "assistant", "content", content);
                if (!calls.isEmpty()) {
                    List<Map<String, Object>> tcs = new ArrayList<>();
                    for (int k = 0; k < calls.size(); k++) {
                        Call c = calls.get(k);
                        if (c.id.isEmpty()) {
                            c = new Call(c.name, c.args, c.badArgs, "call_" + (++callSeq));
                            calls.set(k, c);
                        }
                        tcs.add(Json.obj("id", c.id, "function", Json.obj("name", c.name, "arguments", c.argsForHistory())));
                    }
                    assistant.put("tool_calls", tcs);
                }
                messages.add(assistant);

                if (calls.isEmpty()) {
                    if (content.isBlank() && !nudged && !last) {
                        nudged = true;
                        messages.add(Json.obj("role", "user", "content",
                                "Your last answer was empty. Continue: call a tool or give your answer."));
                        l.notice("Empty answer; asking the model to continue.");
                        continue;
                    }
                    if ("length".equals(r.doneReason)) {
                        l.notice("The answer was cut because it reached the length limit.");
                    }
                    break;
                }
                for (Call c : calls) {
                    if (stop) {
                        throw new ChatClient.CancelledException();
                    }
                    st.toolCalls++;
                    String result = runTool(c, seen, l);
                    messages.add(Json.obj("role", "tool", "tool_name", c.name, "tool_call_id", c.id, "content", result));
                }
            }
        } finally {
            st.millis = System.currentTimeMillis() - start;
        }
        l.done(st);
        return st;
    }

    /** Repair tool names with leaked template text, e.g. "write_file&lt;/think&gt;&lt;tool_call&gt;write_file". */
    String fixName(String name) {
        if (tools.get(name) != null) {
            return name;
        }
        String n = name.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        if (tools.get(n) != null) {
            return n;
        }
        String best = null;
        int bestAt = -1;
        for (String known : tools.names()) {
            int at = n.lastIndexOf(known);
            if (at >= 0 && (at > bestAt || at == bestAt && best != null && known.length() > best.length())) {
                best = known;
                bestAt = at;
            }
        }
        return best != null ? best : name;
    }

    private String runTool(Call raw, Map<String, Integer> seen, Listener l) {
        Call c = raw;
        String fixed = fixName(raw.name);
        if (!fixed.equals(raw.name)) {
            c = new Call(fixed, raw.args, raw.badArgs, raw.id);
        }
        Tools.Tool t = tools.get(c.name);
        if (t == null) {
            String msg = "Error: there is no tool named '" + c.name + "'. Available tools: "
                    + String.join(", ", tools.names()) + ".";
            l.toolStart(c.name, "Unknown tool " + c.name);
            l.toolEnd(c.name, false, msg);
            return msg;
        }
        if (c.badArgs != null) {
            String msg = "Error: the arguments are not valid JSON (" + c.badArgs + "). Send them again as a JSON "
                    + "object.";
            l.toolStart(c.name, t.name());
            l.toolEnd(c.name, false, msg);
            return msg;
        }
        String label;
        try {
            label = t.label(c.args);
        } catch (RuntimeException ex) {
            label = t.name();
        }
        l.toolStart(c.name, label);
        String key = c.name + " " + Json.write(c.args);
        int n = seen.merge(key, 1, Integer::sum);
        if (n > 2) {
            String msg = "Error: you already ran exactly this call " + (n - 1) + " times in this request, and its "
                    + "result is above. Use that result, or do something different.";
            l.toolEnd(c.name, false, msg);
            return msg;
        }
        try {
            String out = Tools.cut(t.run(c.args), Tools.MAX_RESULT_CHARS);
            l.toolEnd(c.name, true, out);
            return out;
        } catch (ToolError ex) {
            String msg = "Error: " + ex.getMessage();
            l.toolEnd(c.name, false, msg);
            return msg;
        } catch (IOException | RuntimeException ex) {
            String msg = "Error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
            l.toolEnd(c.name, false, msg);
            return msg;
        }
    }

    private List<Map<String, Object>> withSystem() {
        List<Map<String, Object>> all = new ArrayList<>(messages.size() + 1);
        all.add(Json.obj("role", "system", "content", systemPrompt.get()));
        all.addAll(messages);
        return all;
    }

    /** Drop the content of old tool results when the conversation gets too long for the model. */
    void compact() {
        long total = 0;
        for (Map<String, Object> m : messages) {
            total += Json.str(m, "content", "").length();
        }
        if (total <= CONTEXT_BUDGET_CHARS) {
            return;
        }
        int lastUser = -1;
        for (int k = messages.size() - 1; k >= 0; k--) {
            if ("user".equals(messages.get(k).get("role"))) {
                lastUser = k;
                break;
            }
        }
        for (int k = 0; k < messages.size() && total > CONTEXT_BUDGET_CHARS * 0.7; k++) {
            Map<String, Object> m = messages.get(k);
            if (!"tool".equals(m.get("role")) || (k > lastUser && lastUser >= 0 && k > messages.size() - 6)) {
                continue;
            }
            String c = Json.str(m, "content", "");
            if (c.length() < 400) {
                continue;
            }
            total -= c.length();
            String stub = "[Old tool result removed to save space. Run the tool again if you need it.]";
            m.put("content", stub);
            total += stub.length();
        }
    }

    // ---------------------------------------------------------------- tool calls

    /** One tool call, with arguments already turned into a map. */
    static final class Call {
        final String name;
        final Map<String, Object> args;
        final String badArgs;
        final String id;

        Call(String name, Map<String, Object> args, String badArgs) {
            this(name, args, badArgs, "");
        }

        Call(String name, Map<String, Object> args, String badArgs, String id) {
            this.name = name == null ? "" : name.trim();
            this.args = args == null ? new HashMap<>() : args;
            this.badArgs = badArgs;
            this.id = id == null ? "" : id.trim();
        }

        Map<String, Object> argsForHistory() {
            return args;
        }

        @SuppressWarnings("unchecked")
        static Call from(Map<String, Object> tc) {
            Object fo = tc.get("function");
            Map<String, Object> f = fo instanceof Map ? (Map<String, Object>) fo : tc;
            String name = Json.str(f, "name", "");
            String id = tc.get("id") == null ? "" : tc.get("id").toString();
            Object a = f.containsKey("arguments") ? f.get("arguments") : f.get("parameters");
            if (a == null || a instanceof Map) {
                return new Call(name, (Map<String, Object>) a, null, id);
            }
            String s = a.toString().trim();
            if (s.isEmpty()) {
                return new Call(name, null, null, id);
            }
            Map<String, Object> parsed = Json.parseObjectOrNull(s);
            if (parsed == null) {
                parsed = Json.parseObjectOrNull(repairJson(s));
            }
            return parsed == null ? new Call(name, null, s.length() > 120 ? s.substring(0, 120) + "..." : s, id)
                    : new Call(name, parsed, null, id);
        }
    }

    /** Fix the two most common JSON slips: trailing commas and a missing closing brace. */
    static String repairJson(String s) {
        String t = s.trim().replaceAll(",\\s*([}\\]])", "$1");
        int open = 0;
        boolean inStr = false;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '"' && (k == 0 || t.charAt(k - 1) != '\\')) {
                inStr = !inStr;
            } else if (!inStr && c == '{') {
                open++;
            } else if (!inStr && c == '}') {
                open--;
            }
        }
        StringBuilder b = new StringBuilder(t);
        while (open-- > 0) {
            b.append('}');
        }
        return b.toString();
    }

    /** Tool calls that the model wrote into its text (tags, a JSON block, or a bare JSON object). */
    List<Call> textCalls(String content) {
        List<Call> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (Pattern p : new Pattern[] {TAGGED, FENCED}) {
            Matcher m = p.matcher(content);
            while (m.find()) {
                Call c = asCall(m.group(1));
                if (c != null) {
                    out.add(c);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        String t = content.trim();
        if (t.startsWith("{") && t.endsWith("}")) {
            Call c = asCall(t);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private Call asCall(String json) {
        Map<String, Object> m = Json.parseObjectOrNull(json);
        if (m == null) {
            m = Json.parseObjectOrNull(repairJson(json));
        }
        if (m == null) {
            return null;
        }
        Call c = Call.from(m);
        return tools.get(c.name) == null ? null : c;
    }
}
