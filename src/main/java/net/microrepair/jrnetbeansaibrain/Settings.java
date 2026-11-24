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
package net.microrepair.jrnetbeansaibrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.prefs.Preferences;
import net.microrepair.jrnetbeansaibrain.core.ChatClient;
import net.microrepair.jrnetbeansaibrain.core.Json;
import net.microrepair.jrnetbeansaibrain.core.Provider;
import org.netbeans.api.keyring.Keyring;
import org.openide.util.NbPreferences;

/**
 * Settings (Tools &gt; AI Brain &gt; AI Providers, and Tools &gt; Options &gt; Advanced &gt; AI Brain),
 * kept in the NetBeans user folder. Each provider has its own address, model and key source.
 * API keys are never stored in these settings: they come from the NetBeans keyring, from a JSON
 * credentials file the developer already has, or from an environment variable.
 */
public final class Settings {

    public static final String DEFAULT_BASE_URL = Provider.OLLAMA_CLOUD.defaultBaseUrl;
    public static final String DEFAULT_MODEL = Provider.OLLAMA_CLOUD.defaultModel;
    /** Keyring entry of the Ollama Cloud key (0.1/0.2 name, kept so the saved key still works). */
    static final String KEYRING_ID = "net.microrepair.jrnetbeansaibrain.apiKey";

    public static final String KEY_FILE = "file";
    public static final String KEY_KEYRING = "keyring";
    public static final String KEY_ENV = "env";
    public static final String KEY_NONE = "none";

    private Settings() {
    }

    public static Preferences prefs() {
        return NbPreferences.forModule(Settings.class);
    }

    // ---------------------------------------------------------------- providers

    /** The provider the chat uses. */
    public static Provider provider() {
        return Provider.byId(prefs().get("provider", ""), Provider.OLLAMA_CLOUD);
    }

    public static void setProvider(Provider p) {
        prefs().put("provider", p.id());
    }

    private static String key(Provider p, String name) {
        return "p." + p.id() + "." + name;
    }

    /** Ollama Cloud falls back to the 0.1/0.2 settings (baseUrl, model, keySource, keyFile). */
    private static String get(Provider p, String name, String def) {
        String v = prefs().get(key(p, name), null);
        if (v == null && p == Provider.OLLAMA_CLOUD) {
            v = prefs().get(name, null);
        }
        return v == null ? def : v;
    }

    public static void put(Provider p, String name, String value) {
        prefs().put(key(p, name), value == null ? "" : value.trim());
    }

    public static String baseUrl(Provider p) {
        String v = get(p, "baseUrl", p.defaultBaseUrl).trim();
        return v.isEmpty() ? p.defaultBaseUrl : v;
    }

    public static String model(Provider p) {
        String v = get(p, "model", p.defaultModel).trim();
        return v.isEmpty() ? p.defaultModel : v;
    }

    /** file | keyring | env | none. */
    public static String keySource(Provider p) {
        return get(p, "keySource", p.needsKey ? KEY_KEYRING : KEY_NONE);
    }

    /** JSON file with an "apiKey" (or "api_key", "key") field, or a text file with only the key. */
    public static String keyFile(Provider p) {
        return get(p, "keyFile", "");
    }

    /** Model of the active provider. */
    public static String model() {
        return model(provider());
    }

    /** "Ollama Cloud · glm-5.3-flash" */
    public static String describe(Provider p) {
        return p.label + (p.api == Provider.Api.COPILOT ? "" : " · " + model(p));
    }

    // ---------------------------------------------------------------- general

    public static boolean think() {
        return prefs().getBoolean("think", true);
    }

    public static double temperature() {
        return prefs().getDouble("temperature", 0.3);
    }

    public static int maxSteps() {
        return Math.max(3, Math.min(200, prefs().getInt("maxSteps", 40)));
    }

    public static int idleTimeoutSeconds() {
        return Math.max(30, prefs().getInt("idleTimeoutSeconds", 180));
    }

    public static int maxOutputTokens() {
        return Math.max(1024, Math.min(128_000, prefs().getInt("maxOutputTokens", 16000)));
    }

    public static boolean showThinking() {
        return prefs().getBoolean("showThinking", true);
    }

    public static Provider.Options options() {
        Provider.Options o = new Provider.Options();
        o.think = think();
        o.temperature = temperature();
        o.idleTimeoutSeconds = idleTimeoutSeconds();
        o.maxTokens = maxOutputTokens();
        return o;
    }

    // ---------------------------------------------------------------- keys

    static String keyringId(Provider p) {
        return p == Provider.OLLAMA_CLOUD ? KEYRING_ID : KEYRING_ID + "." + p.id();
    }

    /** The API key of a provider from its saved source, or "" (never logged or shown). */
    public static String apiKey(Provider p) throws IOException {
        return apiKey(p, keySource(p), null, keyFile(p));
    }

    /**
     * The key from the given source.
     *
     * @param typed a key typed in the form and not saved yet (keyring source), or null
     */
    public static String apiKey(Provider p, String source, char[] typed, String file) throws IOException {
        switch (source) {
            case KEY_NONE:
                return "";
            case KEY_ENV: {
                if (p.envVar.isEmpty()) {
                    throw new IOException(p.label + " has no standard environment variable; choose another key source.");
                }
                String v = System.getenv(p.envVar);
                if (v == null || v.isBlank()) {
                    throw new IOException("The environment variable " + p.envVar + " is not set. Set it and restart "
                            + "NetBeans, or choose another key source in Tools > AI Brain > AI Providers.");
                }
                return v.trim();
            }
            case KEY_FILE:
                return keyFromFile(file);
            default: {
                if (typed != null && typed.length > 0) {
                    return new String(typed).trim();
                }
                char[] k = Keyring.read(keyringId(p));
                if (k == null || k.length == 0) {
                    throw new IOException("No " + p.label + " API key is saved yet. Open Tools > AI Brain > AI Providers "
                            + "and paste it (or point it at your credentials file).");
                }
                return new String(k).trim();
            }
        }
    }

    public static void saveKeyToKeyring(Provider p, char[] key) {
        if (key == null || key.length == 0) {
            Keyring.delete(keyringId(p));
        } else {
            Keyring.save(keyringId(p), key, "JR NetBeans AI Brain - " + p.label + " API key");
        }
    }

    public static boolean keyringHasKey(Provider p) {
        char[] k = Keyring.read(keyringId(p));
        return k != null && k.length > 0;
    }

    /** Read the key from a credentials file (JSON with apiKey/api_key/key, or a one-line text file). */
    public static String keyFromFile(String file) throws IOException {
        if (file == null || file.isBlank()) {
            throw new IOException("No credentials file is set. Choose one in Tools > AI Brain > AI Providers.");
        }
        Path p = Path.of(file.trim());
        if (!Files.isRegularFile(p)) {
            throw new IOException("The credentials file does not exist: " + p);
        }
        String text = Files.readString(p, StandardCharsets.UTF_8).trim();
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        Map<String, Object> m = Json.parseObjectOrNull(text);
        if (m != null) {
            for (String k : new String[] {"apiKey", "api_key", "key", "OLLAMA_API_KEY", "ANTHROPIC_API_KEY",
                "OPENAI_API_KEY", "GEMINI_API_KEY", "token"}) {
                String v = Json.str(m, k, "").trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
            throw new IOException("The credentials file has no apiKey field: " + p);
        }
        if (!text.isEmpty() && !text.contains("\n") && !text.contains(" ")) {
            return text;
        }
        throw new IOException("The credentials file is not JSON with an apiKey field: " + p);
    }

    // ---------------------------------------------------------------- clients

    /** A client for the active provider. */
    public static ChatClient client() throws IOException {
        return client(provider());
    }

    public static ChatClient client(Provider p) throws IOException {
        return p.client(baseUrl(p), apiKey(p), model(p), options());
    }
}
