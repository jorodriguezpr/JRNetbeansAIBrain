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

import java.util.List;

/** The AI providers the plugin can use, with their defaults and how to talk to them. */
public enum Provider {

    OLLAMA_CLOUD("Ollama Cloud", Api.OLLAMA, "https://ollama.com", "glm-5.3-flash", "OLLAMA_API_KEY", true,
            "Key: ollama.com > Settings > Keys. Native tools, thinking.",
            List.of("glm-5.3-flash", "gpt-oss:120b", "qwen3-coder:480b", "deepseek-v3.1:671b", "kimi-k2:1t")),
    OLLAMA_LOCAL("Ollama (local)", Api.OLLAMA, "http://127.0.0.1:11434", "qwen2.5-coder:7b", "", false,
            "Ollama running on this PC or the LAN. Pick a model that supports tools (qwen2.5-coder, llama3.1, "
            + "qwen3...).",
            List.of("qwen2.5-coder:7b", "qwen2.5:7b", "qwen3:8b", "llama3.1:8b")),
    JRBTBRAIN("jrbtbrain bridge", Api.OLLAMA, "http://127.0.0.1:11435", "glm-5.3-flash", "OLLAMA_API_KEY", false,
            "Ollama Cloud through the phone (Bluetooth or USB) with the jrbtbrain bridge. Start the bridge first; "
            + "the phone app adds the key.",
            List.of("glm-5.3-flash", "gpt-oss:120b", "qwen3-coder:480b")),
    CLAUDE("Claude (Anthropic)", Api.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5-5", "ANTHROPIC_API_KEY",
            true, "Key: console.anthropic.com > API keys. Prompt caching is on.",
            List.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-fable-5-2")),
    OPENAI("OpenAI", Api.OPENAI, "https://api.openai.com/v1", "gpt-5.1", "OPENAI_API_KEY", true,
            "Key: platform.openai.com > API keys. Use \"Load models\" to see the models of your account.",
            List.of("gpt-5.1", "gpt-5.1-mini", "gpt-5", "gpt-4.1")),
    GEMINI("Google Gemini", Api.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash",
            "GEMINI_API_KEY", true, "Key: aistudio.google.com > Get API key. Uses Gemini's OpenAI-compatible API.",
            List.of("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-pro-preview")),
    OPENAI_COMPATIBLE("OpenAI-compatible (custom)", Api.OPENAI, "http://127.0.0.1:1234/v1", "", "", false,
            "LM Studio, OpenRouter, Groq, vLLM, LiteLLM... any server with /v1/chat/completions.",
            List.of()),
    COPILOT("Copilot app bridge", Api.COPILOT, CopilotBridgeClient.DEFAULT_EXE, "Copilot app", "", false,
            "Experimental, Windows only: drives the Microsoft Copilot app (no API, no key). Slow; tools are run "
            + "from JSON blocks in its answers. The app is restarted with accessibility on when needed.",
            List.of("Copilot app"));

    /** How the provider is called. */
    public enum Api { OLLAMA, OPENAI, ANTHROPIC, COPILOT }

    public final String label;
    public final Api api;
    public final String defaultBaseUrl;
    public final String defaultModel;
    /** Environment variable that may hold the key ("" = none). */
    public final String envVar;
    /** A key is normally needed. */
    public final boolean needsKey;
    public final String help;
    public final List<String> suggestedModels;

    Provider(String label, Api api, String baseUrl, String model, String envVar, boolean needsKey, String help,
            List<String> models) {
        this.label = label;
        this.api = api;
        this.defaultBaseUrl = baseUrl;
        this.defaultModel = model;
        this.envVar = envVar;
        this.needsKey = needsKey;
        this.help = help;
        this.suggestedModels = models;
    }

    /** Preference id, e.g. "ollama_cloud". */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Provider byId(String id, Provider def) {
        if (id != null) {
            for (Provider p : values()) {
                if (p.id().equalsIgnoreCase(id.trim()) || p.name().equalsIgnoreCase(id.trim())) {
                    return p;
                }
            }
        }
        return def;
    }

    @Override
    public String toString() {
        return label;
    }

    /** Values a client needs beyond the address, key and model. */
    public static final class Options {
        public boolean think = true;
        public double temperature = 0.3;
        public int idleTimeoutSeconds = 180;
        public int maxTokens = 16000;
    }

    /** A client for this provider. */
    public ChatClient client(String baseUrl, String apiKey, String model, Options o) {
        String b = baseUrl == null || baseUrl.isBlank() ? defaultBaseUrl : baseUrl.trim();
        String m = model == null || model.isBlank() ? defaultModel : model.trim();
        ChatClient c;
        switch (api) {
            case ANTHROPIC:
                c = new AnthropicClient(b, apiKey, m, o.maxTokens, o.idleTimeoutSeconds);
                break;
            case OPENAI:
                c = new OpenAiClient(b, apiKey, m, o.temperature, o.idleTimeoutSeconds);
                break;
            case COPILOT:
                c = new CopilotBridgeClient(b, Math.max(180, o.idleTimeoutSeconds));
                break;
            default:
                c = new OllamaClient(b, apiKey, m, o.think, o.temperature, o.idleTimeoutSeconds);
                break;
        }
        return c.named(label);
    }
}
