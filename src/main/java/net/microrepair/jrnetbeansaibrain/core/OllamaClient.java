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
 * Client for the Ollama chat API ({@code POST <base>/api/chat}, streamed NDJSON) with native
 * tool calling. Works with Ollama Cloud ({@code https://ollama.com} + API key), a local Ollama,
 * and the jrbtbrain Bluetooth/USB bridge (which speaks the same API).
 */
public final class OllamaClient extends ChatClient {

    private final String endpoint;
    private final String base;
    private final String apiKey;
    private final double temperature;
    private volatile boolean think;

    public OllamaClient(String baseUrl, String apiKey, String model, boolean think, double temperature,
            int idleTimeoutSeconds) {
        super(model, idleTimeoutSeconds);
        this.endpoint = endpointFor(baseUrl);
        this.base = endpoint.substring(0, endpoint.length() - "/api/chat".length());
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.think = think;
        this.temperature = temperature;
    }

    /** {@code https://ollama.com}, {@code .../api} or {@code .../api/chat} all become {@code .../api/chat}. */
    public static String endpointFor(String baseUrl) {
        String b = trimBase(baseUrl, "https://ollama.com");
        if (b.endsWith("/api/chat")) {
            return b;
        }
        if (b.endsWith("/api")) {
            return b + "/chat";
        }
        if (b.endsWith("/v1")) {
            b = b.substring(0, b.length() - 3);
        }
        return b + "/api/chat";
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
        Map<String, Object> m = getJson(auth(request(base + "/api/tags")));
        List<String> out = new ArrayList<>();
        Object list = m.get("models");
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (o instanceof Map) {
                    Object name = ((Map<?, ?>) o).get("name");
                    if (name == null) {
                        name = ((Map<?, ?>) o).get("model");
                    }
                    if (name != null && !name.toString().isEmpty()) {
                        out.add(name.toString());
                    }
                }
            }
        }
        return out;
    }

    /** The common format minus the fields older Ollama versions do not know. */
    static List<Map<String, Object>> toOllama(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages.size());
        for (Map<String, Object> m : messages) {
            Map<String, Object> c = new LinkedHashMap<>(m);
            c.remove("tool_call_id");
            Object tc = c.get("tool_calls");
            if (tc instanceof List) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (Object o : (List<?>) tc) {
                    if (o instanceof Map) {
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("function", ((Map<?, ?>) o).get("function"));
                        calls.add(one);
                    }
                }
                c.put("tool_calls", calls);
            }
            out.add(c);
        }
        return out;
    }

    @Override
    protected Reply once(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException {
        try {
            return request(messages, tools, stream);
        } catch (ApiException ex) {
            // Local models without thinking refuse "think": ask again without it.
            String m = String.valueOf(ex.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (think && ex.status == 400 && m.contains("think")) {
                think = false;
                return request(messages, tools, stream);
            }
            throw ex;
        }
    }

    private Reply request(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException {
        Map<String, Object> body = Json.obj(
                "model", model,
                "messages", toOllama(messages),
                "stream", Boolean.TRUE,
                "think", think,
                "options", Json.obj("temperature", temperature));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        HttpRequest.Builder rb = auth(request(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)));
        HttpResponse<InputStream> resp = send(rb.build());
        Reply r = new Reply();
        readLines(resp.body(), true, line -> {
            if (line.isBlank()) {
                return false;
            }
            Map<String, Object> chunk = Json.parseObjectOrNull(line);
            if (chunk == null) {
                return false;
            }
            if (chunk.get("error") != null) {
                throw new ApiException(500, "Ollama: " + chunk.get("error"));
            }
            Object mo = chunk.get("message");
            if (mo instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) mo;
                String th = Json.str(m, "thinking", "");
                if (!th.isEmpty()) {
                    r.thinking.append(th);
                    stream.thinking(th);
                }
                String c = Json.str(m, "content", "");
                if (!c.isEmpty()) {
                    r.content.append(c);
                    stream.content(c);
                }
                Object tc = m.get("tool_calls");
                if (tc instanceof List) {
                    for (Object o : (List<?>) tc) {
                        if (o instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> call = (Map<String, Object>) o;
                            r.toolCalls.add(call);
                        }
                    }
                }
            }
            if (Json.bool(chunk, "done", false)) {
                r.promptTokens = Json.num(chunk, "prompt_eval_count", 0);
                r.outputTokens = Json.num(chunk, "eval_count", 0);
                r.doneReason = Json.str(chunk, "done_reason", "");
                return true;
            }
            return false;
        });
        return r;
    }
}
