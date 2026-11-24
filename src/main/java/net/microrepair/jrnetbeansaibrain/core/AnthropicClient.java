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
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the Anthropic Messages API ({@code POST <base>/v1/messages}, server-sent events)
 * with tool use, for Claude models. The system prompt, the tool list and the newest message are
 * marked for prompt caching, so the long agent loop re-sends the same prefix at a lower price.
 */
public final class AnthropicClient extends ChatClient {

    public static final String API_VERSION = "2023-06-01";

    private final String base;
    private final String endpoint;
    private final String apiKey;
    private final int maxTokens;

    public AnthropicClient(String baseUrl, String apiKey, String model, int maxTokens, int idleTimeoutSeconds) {
        super(model, idleTimeoutSeconds);
        String b = trimBase(baseUrl, "https://api.anthropic.com");
        if (b.endsWith("/v1/messages")) {
            b = b.substring(0, b.length() - "/v1/messages".length());
        } else if (b.endsWith("/v1")) {
            b = b.substring(0, b.length() - 3);
        }
        this.base = b;
        this.endpoint = b + "/v1/messages";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.maxTokens = Math.max(1024, maxTokens);
    }

    @Override
    public String endpoint() {
        return endpoint;
    }

    private HttpRequest.Builder auth(HttpRequest.Builder rb) {
        rb.header("anthropic-version", API_VERSION);
        if (!apiKey.isEmpty()) {
            rb.header("x-api-key", apiKey);
        }
        return rb;
    }

    @Override
    public List<String> listModels() throws IOException {
        Map<String, Object> m = getJson(auth(request(base + "/v1/models?limit=100")));
        List<String> out = new ArrayList<>();
        Object list = m.get("data");
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (o instanceof Map && ((Map<?, ?>) o).get("id") != null) {
                    out.add(((Map<?, ?>) o).get("id").toString());
                }
            }
        }
        return out;
    }

    /**
     * Common format to Anthropic: the system prompt apart, assistant tool calls as tool_use blocks,
     * tool results as tool_result blocks inside one user message, consecutive user turns merged.
     */
    static Map<String, Object> toAnthropic(List<Map<String, Object>> messages) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            String role = Json.str(m, "role", "user");
            String content = Json.str(m, "content", "");
            if ("system".equals(role)) {
                system.append(system.length() > 0 ? "\n\n" : "").append(content);
                continue;
            }
            List<Map<String, Object>> blocks = new ArrayList<>();
            String apiRole;
            if ("tool".equals(role)) {
                apiRole = "user";
                blocks.add(Json.obj("type", "tool_result", "tool_use_id", Json.str(m, "tool_call_id", "call_0"),
                        "content", content.isEmpty() ? "(no output)" : content));
            } else if ("assistant".equals(role)) {
                apiRole = "assistant";
                if (!content.isBlank()) {
                    blocks.add(Json.obj("type", "text", "text", content));
                }
                Object tc = m.get("tool_calls");
                if (tc instanceof List) {
                    for (Object c : (List<?>) tc) {
                        if (!(c instanceof Map)) {
                            continue;
                        }
                        Map<?, ?> call = (Map<?, ?>) c;
                        Object fo = call.get("function");
                        Map<?, ?> f = fo instanceof Map ? (Map<?, ?>) fo : call;
                        Object args = f.get("arguments");
                        Object input = args instanceof Map ? args
                                : args instanceof String ? Json.parseObjectOrNull((String) args) : null;
                        blocks.add(Json.obj("type", "tool_use",
                                "id", call.get("id") == null ? "call_0" : call.get("id").toString(),
                                "name", String.valueOf(f.get("name")),
                                "input", input == null ? Json.obj() : input));
                    }
                }
                if (blocks.isEmpty()) {
                    blocks.add(Json.obj("type", "text", "text", "(no answer)"));
                }
            } else {
                apiRole = "user";
                blocks.add(Json.obj("type", "text", "text", content.isEmpty() ? "(empty)" : content));
            }
            Map<String, Object> prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev != null && apiRole.equals(prev.get("role"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pb = (List<Map<String, Object>>) prev.get("content");
                // tool_result blocks must come first in a user message
                if ("user".equals(apiRole) && "tool_result".equals(blocks.get(0).get("type"))) {
                    int at = 0;
                    while (at < pb.size() && "tool_result".equals(pb.get(at).get("type"))) {
                        at++;
                    }
                    pb.addAll(at, blocks);
                } else {
                    pb.addAll(blocks);
                }
            } else {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", apiRole);
                msg.put("content", blocks);
                out.add(msg);
            }
        }
        if (!out.isEmpty() && !"user".equals(out.get(0).get("role"))) {
            out.add(0, Json.obj("role", "user", "content", List.of(Json.obj("type", "text", "text", "(start)"))));
        }
        return Json.obj("system", system.toString(), "messages", out);
    }

    @Override
    protected Reply once(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException {
        Map<String, Object> conv = toAnthropic(messages);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) conv.get("messages");
        Map<String, Object> cache = Json.obj("type", "ephemeral");
        if (!msgs.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lb = (List<Map<String, Object>>) msgs.get(msgs.size() - 1).get("content");
            if (!lb.isEmpty()) {
                lb.get(lb.size() - 1).put("cache_control", cache);
            }
        }
        Map<String, Object> body = Json.obj("model", model, "max_tokens", maxTokens, "stream", Boolean.TRUE,
                "messages", msgs);
        String sys = (String) conv.get("system");
        if (!sys.isBlank()) {
            body.put("system", List.of(Json.obj("type", "text", "text", sys, "cache_control", cache)));
        }
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> t : tools) {
                Object fo = t.get("function");
                Map<?, ?> f = fo instanceof Map ? (Map<?, ?>) fo : t;
                list.add(Json.obj("name", String.valueOf(f.get("name")),
                        "description", String.valueOf(f.get("description")),
                        "input_schema", f.get("parameters") == null ? Json.obj("type", "object") : f.get("parameters")));
            }
            list.get(list.size() - 1).put("cache_control", cache);
            body.put("tools", list);
        }
        HttpRequest.Builder rb = auth(request(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)));
        HttpResponse<InputStream> resp = send(rb.build());
        Reply r = new Reply();
        Map<Integer, Map<String, Object>> uses = new LinkedHashMap<>();
        Map<Integer, StringBuilder> inputs = new LinkedHashMap<>();
        readLines(resp.body(), true, line -> {
            String t = line.trim();
            if (!t.startsWith("data:")) {
                return false;
            }
            Map<String, Object> ev = Json.parseObjectOrNull(t.substring(5).trim());
            if (ev == null) {
                return false;
            }
            String type = Json.str(ev, "type", "");
            switch (type) {
                case "error": {
                    Object e = ev.get("error");
                    String et = e instanceof Map ? String.valueOf(((Map<?, ?>) e).get("type")) : "";
                    String em = e instanceof Map ? String.valueOf(((Map<?, ?>) e).get("message")) : String.valueOf(e);
                    throw new ApiException("overloaded_error".equals(et) ? 529 : 500, "Claude: " + em);
                }
                case "message_start": {
                    Object mo = ev.get("message");
                    if (mo instanceof Map && ((Map<?, ?>) mo).get("usage") instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> u = (Map<String, Object>) ((Map<?, ?>) mo).get("usage");
                        r.promptTokens = Json.num(u, "input_tokens", 0) + Json.num(u, "cache_read_input_tokens", 0)
                                + Json.num(u, "cache_creation_input_tokens", 0);
                    }
                    return false;
                }
                case "content_block_start": {
                    int idx = Json.num(ev, "index", 0);
                    Object cb = ev.get("content_block");
                    if (cb instanceof Map && "tool_use".equals(((Map<?, ?>) cb).get("type"))) {
                        Map<?, ?> b = (Map<?, ?>) cb;
                        uses.put(idx, Json.obj("id", String.valueOf(b.get("id")), "name", String.valueOf(b.get("name"))));
                        inputs.put(idx, new StringBuilder());
                    }
                    return false;
                }
                case "content_block_delta": {
                    int idx = Json.num(ev, "index", 0);
                    Object dObj = ev.get("delta");
                    if (!(dObj instanceof Map)) {
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> d = (Map<String, Object>) dObj;
                    String dt = Json.str(d, "type", "");
                    if ("text_delta".equals(dt)) {
                        String s = Json.str(d, "text", "");
                        r.content.append(s);
                        stream.content(s);
                    } else if ("thinking_delta".equals(dt)) {
                        String s = Json.str(d, "thinking", "");
                        r.thinking.append(s);
                        stream.thinking(s);
                    } else if ("input_json_delta".equals(dt) && inputs.containsKey(idx)) {
                        inputs.get(idx).append(Json.str(d, "partial_json", ""));
                    }
                    return false;
                }
                case "message_delta": {
                    Object dObj = ev.get("delta");
                    if (dObj instanceof Map && "max_tokens".equals(((Map<?, ?>) dObj).get("stop_reason"))) {
                        r.doneReason = "length";
                    }
                    Object u = ev.get("usage");
                    if (u instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> um = (Map<String, Object>) u;
                        r.outputTokens = Json.num(um, "output_tokens", r.outputTokens);
                    }
                    return false;
                }
                case "message_stop":
                    return true;
                default:
                    return false;
            }
        });
        for (Map.Entry<Integer, Map<String, Object>> e : uses.entrySet()) {
            String args = inputs.get(e.getKey()).toString().trim();
            Map<String, Object> call = Json.obj("id", e.getValue().get("id"),
                    "function", Json.obj("name", e.getValue().get("name"), "arguments", args.isEmpty() ? "{}" : args));
            r.toolCalls.add(call);
        }
        return r;
    }
}
