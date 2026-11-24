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
import java.util.Locale;
import java.util.Map;

/**
 * Client for the OpenAI Chat Completions API ({@code POST <base>/chat/completions}, server-sent
 * events) with function calling. Used for OpenAI, Google Gemini (its OpenAI-compatible endpoint)
 * and any other compatible server (LM Studio, OpenRouter, Groq, vLLM...).
 */
public final class OpenAiClient extends ChatClient {

    private final String base;
    private final String endpoint;
    private final String apiKey;
    private final double temperature;
    private volatile boolean sendTemperature;
    private volatile boolean sendStreamOptions = true;

    public OpenAiClient(String baseUrl, String apiKey, String model, double temperature, int idleTimeoutSeconds) {
        super(model, idleTimeoutSeconds);
        String b = trimBase(baseUrl, "https://api.openai.com/v1");
        if (b.endsWith("/chat/completions")) {
            b = b.substring(0, b.length() - "/chat/completions".length());
        }
        this.base = b;
        this.endpoint = b + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.temperature = temperature;
        // OpenAI reasoning models (o1, o3, o4, gpt-5...) accept only the default temperature.
        String m = this.model.toLowerCase(Locale.ROOT);
        this.sendTemperature = !(m.matches("^o\\d.*") || m.startsWith("gpt-5"));
    }

    @Override
    public String endpoint() {
        return endpoint;
    }

    private HttpRequest.Builder auth(HttpRequest.Builder rb) {
        if (!apiKey.isEmpty()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        return rb;
    }

    @Override
    public List<String> listModels() throws IOException {
        Map<String, Object> m = getJson(auth(request(base + "/models")));
        List<String> out = new ArrayList<>();
        Object list = m.get("data");
        if (!(list instanceof List)) {
            list = m.get("models");
        }
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (o instanceof Map) {
                    Object id = ((Map<?, ?>) o).get("id");
                    if (id == null) {
                        id = ((Map<?, ?>) o).get("name");
                    }
                    if (id != null) {
                        String s = id.toString();
                        out.add(s.startsWith("models/") ? s.substring(7) : s);
                    }
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Common format to OpenAI messages (tool call ids, arguments as JSON text, tool results by id). */
    static List<Map<String, Object>> toOpenAi(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (Map<String, Object> m : messages) {
            String role = Json.str(m, "role", "user");
            String content = Json.str(m, "content", "");
            if ("tool".equals(role)) {
                out.add(Json.obj("role", "tool", "tool_call_id", Json.str(m, "tool_call_id", "call_0"),
                        "content", content));
                continue;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("role", role);
            Object tc = m.get("tool_calls");
            if ("assistant".equals(role) && tc instanceof List && !((List<?>) tc).isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (Object c : (List<?>) tc) {
                    if (!(c instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> call = (Map<?, ?>) c;
                    Object fo = call.get("function");
                    Map<?, ?> f = fo instanceof Map ? (Map<?, ?>) fo : call;
                    Object args = f.get("arguments");
                    String argText = args instanceof String ? (String) args : Json.write(args == null ? Json.obj() : args);
                    calls.add(Json.obj("id", call.get("id") == null ? "call_0" : call.get("id").toString(),
                            "type", "function",
                            "function", Json.obj("name", String.valueOf(f.get("name")), "arguments", argText)));
                }
                o.put("content", content.isEmpty() ? null : content);
                o.put("tool_calls", calls);
            } else {
                o.put("content", content);
            }
            out.add(o);
        }
        return out;
    }

    @Override
    protected Reply once(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException {
        for (int k = 0; ; k++) {
            try {
                return request(messages, tools, stream);
            } catch (ApiException ex) {
                // Some servers refuse optional fields: drop the one named in the error and ask again.
                String m = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
                if (ex.status == 400 && k < 2 && sendStreamOptions && m.contains("stream_options")) {
                    sendStreamOptions = false;
                } else if (ex.status == 400 && k < 2 && sendTemperature && m.contains("temperature")) {
                    sendTemperature = false;
                } else {
                    throw ex;
                }
            }
        }
    }

    /** One tool call being assembled from streamed pieces. */
    private static final class Part {
        String id = "";
        String name = "";
        final StringBuilder args = new StringBuilder();
    }

    private Reply request(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException {
        Map<String, Object> body = Json.obj("model", model, "messages", toOpenAi(messages), "stream", Boolean.TRUE);
        if (sendStreamOptions) {
            body.put("stream_options", Json.obj("include_usage", Boolean.TRUE));
        }
        if (sendTemperature) {
            body.put("temperature", temperature);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        HttpRequest.Builder rb = auth(request(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)));
        HttpResponse<InputStream> resp = send(rb.build());
        Reply r = new Reply();
        Map<Integer, Part> parts = new LinkedHashMap<>();
        Part[] lastPart = {null};
        readLines(resp.body(), false, line -> {
            String t = line.trim();
            if (!t.startsWith("data:")) {
                return false;
            }
            String data = t.substring(5).trim();
            if ("[DONE]".equals(data)) {
                return true;
            }
            Map<String, Object> chunk = Json.parseObjectOrNull(data);
            if (chunk == null) {
                return false;
            }
            if (chunk.get("error") != null) {
                throw new ApiException(500, (providerName().isEmpty() ? "Provider" : providerName()) + ": "
                        + errorText(data));
            }
            Object usage = chunk.get("usage");
            if (usage instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> u = (Map<String, Object>) usage;
                r.promptTokens = Json.num(u, "prompt_tokens", r.promptTokens);
                r.outputTokens = Json.num(u, "completion_tokens", r.outputTokens);
            }
            Object ch = chunk.get("choices");
            if (!(ch instanceof List) || ((List<?>) ch).isEmpty() || !(((List<?>) ch).get(0) instanceof Map)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> choice = (Map<String, Object>) ((List<?>) ch).get(0);
            String finish = Json.str(choice, "finish_reason", "");
            if ("length".equals(finish)) {
                r.doneReason = "length";
            }
            Object dObj = choice.get("delta");
            if (!(dObj instanceof Map)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) dObj;
            String think = Json.str(d, "reasoning_content", "");
            if (think.isEmpty()) {
                think = Json.str(d, "reasoning", "");
            }
            if (!think.isEmpty()) {
                r.thinking.append(think);
                stream.thinking(think);
            }
            String c = Json.str(d, "content", "");
            if (!c.isEmpty()) {
                r.content.append(c);
                stream.content(c);
            }
            Object tcs = d.get("tool_calls");
            if (tcs instanceof List) {
                for (Object o : (List<?>) tcs) {
                    if (!(o instanceof Map)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tc = (Map<String, Object>) o;
                    String id = Json.str(tc, "id", "");
                    Part p;
                    if (tc.get("index") instanceof Number) {
                        p = parts.computeIfAbsent(((Number) tc.get("index")).intValue(), x -> new Part());
                    } else if (lastPart[0] == null || !id.isEmpty() && !id.equals(lastPart[0].id)) {
                        p = new Part();
                        parts.put(1000 + parts.size(), p);
                    } else {
                        p = lastPart[0];
                    }
                    lastPart[0] = p;
                    if (!id.isEmpty()) {
                        p.id = id;
                    }
                    Object fo = tc.get("function");
                    if (fo instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> f = (Map<String, Object>) fo;
                        String n = Json.str(f, "name", "");
                        if (!n.isEmpty()) {
                            p.name = p.name.isEmpty() || !n.startsWith(p.name) ? p.name + n : n;
                        }
                        Object a = f.get("arguments");
                        if (a instanceof String) {
                            p.args.append((String) a);
                        } else if (a != null) {
                            p.args.append(Json.write(a));
                        }
                    }
                }
            }
            return false;
        });
        for (Part p : parts.values()) {
            if (p.name.isEmpty()) {
                continue;
            }
            Map<String, Object> call = Json.obj("function", Json.obj("name", p.name, "arguments", p.args.toString()));
            if (!p.id.isEmpty()) {
                call.put("id", p.id);
            }
            r.toolCalls.add(call);
        }
        return r;
    }
}
