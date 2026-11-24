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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base of every AI provider client. The conversation is always kept in one format (the Ollama
 * one: roles system/user/assistant/tool, assistant {@code tool_calls} with an {@code id} and
 * {@code function.name/arguments}, tool results with {@code tool_call_id} and {@code tool_name});
 * each provider converts it to its own API. Shared here: retries (network errors, 429, 5xx),
 * stop, and the stall watchdog for streamed answers.
 */
public abstract class ChatClient {

    public static final String VERSION = "0.3.0";

    /** Receives the answer while it streams in. All methods are called on the caller's thread. */
    public interface Stream {
        default void thinking(String text) {
        }

        default void content(String text) {
        }

        /** The request failed and is being sent again; drop any partial text shown so far. */
        default void retrying(String why, int attempt) {
        }

        /** A status line from a slow provider (the Copilot app bridge). */
        default void progress(String text) {
        }
    }

    /** One finished model turn. {@code toolCalls} are in the Ollama format (id, function.name, function.arguments). */
    public static final class Reply {
        public final StringBuilder content = new StringBuilder();
        public final StringBuilder thinking = new StringBuilder();
        public final List<Map<String, Object>> toolCalls = new ArrayList<>();
        public int promptTokens;
        public int outputTokens;
        /** "length" when the answer was cut by the output limit. */
        public String doneReason = "";
        public long millis;
    }

    /** Thrown for answers that should not be retried (bad key, unknown model, bad request). */
    public static final class ApiException extends IOException {
        public final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** Thrown when {@link #cancel()} stopped the request. */
    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("Stopped.");
        }
    }

    /** Handles one line of a streamed answer; returns true when the answer is complete. */
    protected interface LineHandler {
        boolean line(String line) throws IOException;
    }

    protected final String model;
    protected final int idleTimeoutSeconds;
    protected final HttpClient http;
    protected volatile boolean cancelled;
    private volatile InputStream current;
    private String providerName = "";

    protected ChatClient(String model, int idleTimeoutSeconds) {
        this.model = model == null ? "" : model.trim();
        this.idleTimeoutSeconds = Math.max(30, idleTimeoutSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String model() {
        return model;
    }

    /** Name shown to the developer, e.g. "Ollama Cloud". */
    public String providerName() {
        return providerName;
    }

    public ChatClient named(String name) {
        providerName = name == null ? "" : name;
        return this;
    }

    /** Where requests go (for messages). */
    public abstract String endpoint();

    /** True when the provider runs tools natively; false = tool calls are written as text (Copilot app). */
    public boolean nativeTools() {
        return true;
    }

    /** Models the provider offers (empty when it cannot list them). */
    public List<String> listModels() throws IOException {
        return List.of();
    }

    /** Send one request (no retries) and stream the answer. */
    protected abstract Reply once(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException;

    /** Stop the request that is running now (safe to call from any thread). */
    public void cancel() {
        cancelled = true;
        InputStream in = current;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignore) {
                // closing only interrupts the read
            }
        }
    }

    public void resetCancel() {
        cancelled = false;
    }

    /**
     * Send the conversation and stream the answer, retrying network errors, 429 and 5xx.
     *
     * @param messages conversation in the common format, starting with the system message
     * @param tools    tool definitions ({type:function, function:{name, description, parameters}}), or null
     */
    public Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Stream stream)
            throws IOException {
        long[] waits = {1500, 4000, 9000};
        IOException last = null;
        for (int attempt = 0; attempt <= waits.length; attempt++) {
            if (cancelled) {
                throw new CancelledException();
            }
            if (attempt > 0) {
                stream.retrying(last == null ? "" : last.getMessage(), attempt);
                sleep(waits[attempt - 1]);
            }
            try {
                long start = System.currentTimeMillis();
                Reply r = once(messages, tools, stream);
                r.millis = System.currentTimeMillis() - start;
                return r;
            } catch (ApiException ex) {
                if (ex.status != 429 && ex.status < 500) {
                    throw ex;
                }
                last = ex;
            } catch (CancelledException ex) {
                throw ex;
            } catch (IOException ex) {
                if (cancelled) {
                    throw new CancelledException();
                }
                last = ex;
            }
        }
        throw last;
    }

    // ---------------------------------------------------------------- HTTP helpers for subclasses

    /** Send a request; non-200 answers become {@link ApiException} with a readable message. */
    protected HttpResponse<InputStream> send(HttpRequest req) throws IOException {
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (java.net.http.HttpTimeoutException ex) {
            throw new IOException("No answer from " + host() + " within 60 s.", ex);
        } catch (java.net.ConnectException ex) {
            throw new IOException("Cannot connect to " + host() + " (" + ex.getMessage() + ").", ex);
        }
        int status = resp.statusCode();
        if (status / 100 != 2) {
            String err;
            try (InputStream in = resp.body()) {
                err = new String(in.readNBytes(4000), StandardCharsets.UTF_8);
            }
            throw new ApiException(status, explain(status, err));
        }
        return resp;
    }

    protected HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "JRNetbeansAIBrain/" + VERSION);
    }

    /** GET a JSON object (model lists). */
    protected Map<String, Object> getJson(HttpRequest.Builder rb) throws IOException {
        HttpResponse<InputStream> resp = send(rb.GET().build());
        try (InputStream in = resp.body()) {
            Map<String, Object> m = Json.parseObjectOrNull(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (m == null) {
                throw new IOException("The answer from " + host() + " is not JSON.");
            }
            return m;
        }
    }

    /**
     * Read a streamed answer line by line, with stop and the stall watchdog.
     *
     * @param needDone true when the stream must end with a "done" line (else the end of the stream is enough)
     */
    protected void readLines(InputStream in, boolean needDone, LineHandler h) throws IOException {
        current = in;
        final long[] lastByte = {System.currentTimeMillis()};
        Thread watchdog = new Thread(() -> {
            while (current == in) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.currentTimeMillis() - lastByte[0] > idleTimeoutSeconds * 1000L) {
                    try {
                        in.close();
                    } catch (IOException ignore) {
                        // ends the read below
                    }
                    return;
                }
            }
        }, "JRNetbeansAIBrain-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        boolean done = false;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                lastByte[0] = System.currentTimeMillis();
                if (cancelled) {
                    throw new CancelledException();
                }
                if (h.line(line)) {
                    done = true;
                    break;
                }
            }
        } catch (CancelledException | ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            if (cancelled) {
                throw new CancelledException();
            }
            if (System.currentTimeMillis() - lastByte[0] > idleTimeoutSeconds * 1000L) {
                throw new IOException("The answer stopped for " + idleTimeoutSeconds + " s.", ex);
            }
            throw ex;
        } finally {
            current = null;
        }
        if (cancelled) {
            throw new CancelledException();
        }
        if (needDone && !done) {
            throw new IOException("The connection closed before the answer finished.");
        }
    }

    protected String host() {
        try {
            String h = URI.create(endpoint()).getHost();
            return h == null ? endpoint() : h;
        } catch (RuntimeException ex) {
            return endpoint();
        }
    }

    /** Pull the message out of an error body ({"error":"..."} or {"error":{"message":"..."}}). */
    protected static String errorText(String body) {
        String msg = body == null ? "" : body;
        Map<String, Object> m = Json.parseObjectOrNull(msg);
        if (m != null && m.get("error") != null) {
            Object e = m.get("error");
            if (e instanceof Map && ((Map<?, ?>) e).get("message") != null) {
                msg = String.valueOf(((Map<?, ?>) e).get("message"));
            } else {
                msg = String.valueOf(e);
            }
        } else if (m != null && m.get("message") != null) {
            msg = String.valueOf(m.get("message"));
        }
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }

    protected String explain(int status, String body) {
        String msg = errorText(body);
        String who = providerName.isEmpty() ? "The AI provider" : providerName;
        switch (status) {
            case 401:
            case 403:
                return who + " refused the API key (HTTP " + status + "). Check Tools > AI Brain > AI Providers. " + msg;
            case 404:
                return "Model '" + model + "' or the address " + endpoint() + " was not found (HTTP 404). " + msg;
            case 429:
                return who + ": too many requests or the plan limit was reached (HTTP 429). " + msg;
            default:
                return who + " answered HTTP " + status + ". " + msg;
        }
    }

    protected void sleep(long ms) throws CancelledException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelled) {
                throw new CancelledException();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
    }

    /** Trailing slashes removed; a default when blank. */
    protected static String trimBase(String base, String def) {
        String b = base == null || base.isBlank() ? def : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }
}
