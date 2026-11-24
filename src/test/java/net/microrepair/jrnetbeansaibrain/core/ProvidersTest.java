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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Provider conversions and streamed-answer parsing, against a scripted local server. */
class ProvidersTest {

    @TempDir
    Path dir;

    /** A conversation in the common format: question, two tool calls, two results, then a follow-up. */
    static List<Map<String, Object>> conversation() {
        List<Map<String, Object>> m = new ArrayList<>();
        m.add(Json.obj("role", "system", "content", "SYS"));
        m.add(Json.obj("role", "user", "content", "Q1"));
        m.add(Json.obj("role", "assistant", "content", "", "tool_calls", List.of(
                Json.obj("id", "call_1", "function", Json.obj("name", "read_file", "arguments", Json.obj("path", "a.php"))),
                Json.obj("id", "call_2", "function", Json.obj("name", "grep", "arguments", Json.obj("pattern", "x"))))));
        m.add(Json.obj("role", "tool", "tool_name", "read_file", "tool_call_id", "call_1", "content", "R1"));
        m.add(Json.obj("role", "tool", "tool_name", "grep", "tool_call_id", "call_2", "content", "R2"));
        m.add(Json.obj("role", "user", "content", "Q2"));
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void openAiConversion() {
        List<Map<String, Object>> o = OpenAiClient.toOpenAi(conversation());
        assertEquals(6, o.size());
        Map<String, Object> a = o.get(2);
        assertEquals(null, a.get("content"));
        Map<String, Object> tc = ((List<Map<String, Object>>) a.get("tool_calls")).get(0);
        assertEquals("call_1", tc.get("id"));
        assertEquals("{\"path\":\"a.php\"}", ((Map<String, Object>) tc.get("function")).get("arguments"));
        assertEquals("call_2", o.get(4).get("tool_call_id"));
        assertFalse(o.get(3).containsKey("tool_name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anthropicConversion() {
        Map<String, Object> c = AnthropicClient.toAnthropic(conversation());
        assertEquals("SYS", c.get("system"));
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) c.get("messages");
        assertEquals(3, msgs.size(), "user, assistant, user (results + Q2 merged)");
        List<Map<String, Object>> uses = (List<Map<String, Object>>) msgs.get(1).get("content");
        assertEquals("tool_use", uses.get(0).get("type"));
        assertEquals("a.php", ((Map<String, Object>) uses.get(0).get("input")).get("path"));
        List<Map<String, Object>> last = (List<Map<String, Object>>) msgs.get(2).get("content");
        assertEquals("tool_result", last.get(0).get("type"));
        assertEquals("call_2", last.get(1).get("tool_use_id"));
        assertEquals("Q2", last.get(2).get("text"));
    }

    @Test
    void ollamaConversionDropsIds() {
        List<Map<String, Object>> o = OllamaClient.toOllama(conversation());
        assertFalse(o.get(3).containsKey("tool_call_id"));
        assertFalse(Json.write(o.get(2)).contains("call_1"));
        assertTrue(Json.write(o.get(2)).contains("read_file"));
    }

    @Test
    void copilotPromptSendsOnlyNewMessages() {
        List<Map<String, Object>> tools = List.of(Json.obj("type", "function", "function", Json.obj("name", "read_file",
                "description", "Read a file. More text.", "parameters", Json.obj("type", "object",
                        "properties", Json.obj("path", Json.obj("type", "string"))))));
        String first = CopilotBridgeClient.prompt(conversation().subList(0, 2), tools, 0, true);
        assertTrue(first.startsWith("SYS"), first);
        assertTrue(first.contains("- read_file(path): Read a file."), first);
        assertTrue(first.contains("Developer: Q1"), first);
        String next = CopilotBridgeClient.prompt(conversation(), tools, 3, false);
        assertFalse(next.contains("SYS") || next.contains("Q1"), next);
        assertTrue(next.contains("Result of read_file:") && next.contains("R2") && next.contains("Developer: Q2"), next);
    }

    /** Serve one scripted streamed answer and remember the request body. */
    static HttpServer serve(String path, String contentType, String body, AtomicReference<String> request)
            throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext(path, ex -> {
            request.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        s.start();
        return s;
    }

    @Test
    void openAiStreamWithToolCall() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Let me look.\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"type\":\"function\","
                + "\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"pa\"}}]}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\":\\\"x\\\"}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}\n\n"
                + "data: [DONE]\n\n";
        AtomicReference<String> req = new AtomicReference<>();
        HttpServer s = serve("/v1/chat/completions", "text/event-stream", sse, req);
        try {
            OpenAiClient c = new OpenAiClient("http://127.0.0.1:" + s.getAddress().getPort() + "/v1", "k", "gpt-5.1",
                    0.3, 30);
            ChatClient.Reply r = c.chat(conversation(), List.of(), new ChatClient.Stream() { });
            assertEquals("Let me look.", r.content.toString());
            assertEquals(1, r.toolCalls.size());
            Agent.Call call = Agent.Call.from(r.toolCalls.get(0));
            assertEquals("read_file", call.name);
            assertEquals("x", call.args.get("path"));
            assertEquals("call_9", call.id);
            assertEquals(12, r.promptTokens);
            assertFalse(req.get().contains("\"temperature\""), "gpt-5 models get no temperature");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void anthropicStreamWithToolUse() throws Exception {
        String sse = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7,"
                + "\"cache_read_input_tokens\":100}}}\n\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Reading\"}}\n\n"
                + "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\","
                + "\"id\":\"toolu_1\",\"name\":\"grep\",\"input\":{}}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\"{\\\"pattern\\\":\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\","
                + "\"partial_json\":\" \\\"Route\\\"}\"}}\n\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}\n\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        AtomicReference<String> req = new AtomicReference<>();
        HttpServer s = serve("/v1/messages", "text/event-stream", sse, req);
        try {
            AnthropicClient c = new AnthropicClient("http://127.0.0.1:" + s.getAddress().getPort(), "k",
                    "claude-sonnet-5-5", 4096, 30);
            List<Map<String, Object>> tools = List.of(Json.obj("type", "function", "function", Json.obj("name", "grep",
                    "description", "Search", "parameters", Json.obj("type", "object"))));
            ChatClient.Reply r = c.chat(conversation(), tools, new ChatClient.Stream() { });
            assertEquals("Reading", r.content.toString());
            Agent.Call call = Agent.Call.from(r.toolCalls.get(0));
            assertEquals("grep", call.name);
            assertEquals("Route", call.args.get("pattern"));
            assertEquals("toolu_1", call.id);
            assertEquals(107, r.promptTokens);
            assertEquals(9, r.outputTokens);
            String body = req.get();
            assertTrue(body.contains("\"input_schema\"") && body.contains("\"cache_control\""), body);
            assertTrue(body.contains("\"system\":[{\"type\":\"text\",\"text\":\"SYS\""), body);
        } finally {
            s.stop(0);
        }
    }

    /** A provider that answers from a script, to drive the agent loop without a network. */
    static final class Scripted extends ChatClient {
        final List<Reply> replies = new ArrayList<>();
        final List<List<Map<String, Object>>> seen = new ArrayList<>();

        Scripted() {
            super("scripted", 30);
        }

        @Override
        public String endpoint() {
            return "test";
        }

        @Override
        protected Reply once(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream) {
            seen.add(new ArrayList<>(messages));
            return replies.remove(0);
        }
    }

    @Test
    void agentGivesEveryToolCallAnIdAndPlanModeIsReadOnly() throws Exception {
        Files.writeString(dir.resolve("a.txt"), "hello\n");
        Workspace ws = new Workspace(dir);
        Gate gate = new Gate(WorkMode.PLAN.effective(Gate.Mode.FULL_AUTO), c -> new Gate.Decision(true, false, ""));
        Tools tools = new Tools(ws, () -> ProjectInfo.detect(dir, ""), () -> null, Ide.NONE, gate);
        assertTrue(tools.definitions().stream().noneMatch(d -> Json.write(d).contains("\"write_file\"")));
        assertTrue(tools.definitions().stream().anyMatch(d -> Json.write(d).contains("\"plan\"")));

        Scripted s = new Scripted();
        ChatClient.Reply r1 = new ChatClient.Reply();
        r1.toolCalls.add(Json.obj("function", Json.obj("name", "read_file", "arguments", Json.obj("path", "a.txt"))));
        ChatClient.Reply r2 = new ChatClient.Reply();
        r2.content.append("Done.");
        s.replies.add(r1);
        s.replies.add(r2);
        Agent a = new Agent(s, tools, () -> Prompt.system(dir, ProjectInfo.detect(dir, ""), "test", gate.mode(),
                WorkMode.PLAN), 5);
        a.send("plan it", new Agent.Listener() { });
        List<Map<String, Object>> h = a.history();
        @SuppressWarnings("unchecked")
        Map<String, Object> tc = ((List<Map<String, Object>>) h.get(1).get("tool_calls")).get(0);
        String id = (String) tc.get("id");
        assertTrue(id.startsWith("call_"), id);
        assertEquals(id, h.get(2).get("tool_call_id"));
        assertTrue(Json.str(h.get(2), "content", "").contains("hello"));
        String sys = Json.str(s.seen.get(0).get(0), "content", "");
        assertTrue(sys.contains("# Plan mode") && !sys.contains("# Changing the project"), sys);

        // switching provider keeps the conversation
        Scripted other = new Scripted();
        ChatClient.Reply r3 = new ChatClient.Reply();
        r3.content.append("Still here.");
        other.replies.add(r3);
        a.setClient(other);
        a.send("next", new Agent.Listener() { });
        assertEquals(6, other.seen.get(0).size(), "system + 5 earlier/new messages");
    }

    @Test
    void providersBuildTheRightClient() {
        Provider.Options o = new Provider.Options();
        assertTrue(Provider.CLAUDE.client("", "k", "", o) instanceof AnthropicClient);
        assertTrue(Provider.GEMINI.client("", "k", "", o) instanceof OpenAiClient);
        assertTrue(Provider.JRBTBRAIN.client("", "", "", o) instanceof OllamaClient);
        assertTrue(Provider.COPILOT.client("", "", "", o) instanceof CopilotBridgeClient);
        assertEquals("http://127.0.0.1:11435/api/chat", Provider.JRBTBRAIN.client("", "", "", o).endpoint());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                Provider.GEMINI.client("", "k", "", o).endpoint());
        assertEquals(Provider.OLLAMA_LOCAL, Provider.byId("ollama_local", null));
        assertEquals(Gate.Mode.READ_ONLY, WorkMode.CHAT.effective(Gate.Mode.FULL_AUTO));
        assertEquals(Gate.Mode.AUTO_EDIT, WorkMode.AGENT.effective(Gate.Mode.AUTO_EDIT));
    }
}
