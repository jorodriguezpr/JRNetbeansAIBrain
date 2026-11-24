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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs the real agent against the AI provider on a real project. Skipped unless both are given:
 * {@code mvn test -Djrbrain.keyfile=<json with apiKey> -Djrbrain.project=<folder>}
 * (optional: -Djrbrain.base=..., -Djrbrain.model=..., -Djrbrain.question=...).
 */
class LiveAgentTest {

    @Test
    void agentAnswersFromTheRealFiles() throws Exception {
        String keyFile = System.getProperty("jrbrain.keyfile", "");
        String project = System.getProperty("jrbrain.project", "");
        Assumptions.assumeTrue(!keyFile.isEmpty() && !project.isEmpty(), "live test not requested");
        Map<String, Object> cred = Json.parseObjectOrNull(Files.readString(Path.of(keyFile), StandardCharsets.UTF_8));
        String key = Json.str(cred, "apiKey", "");
        String base = System.getProperty("jrbrain.base", "https://ollama.com");
        String model = System.getProperty("jrbrain.model", "glm-5.3-flash");
        // jrbrain.api=openai talks to the same server through its OpenAI-compatible /v1 API
        ChatClient client = "openai".equals(System.getProperty("jrbrain.api"))
                ? new OpenAiClient(base + "/v1", key, model, 0.3, 180)
                : new OllamaClient(base, key, model, true, 0.3, 180);
        System.out.println("== client: " + client.getClass().getSimpleName() + " " + client.endpoint());
        Workspace ws = new Workspace(Path.of(project));
        ProjectInfo pi = ProjectInfo.detect(ws.root(), "");
        System.out.println("== project: " + pi.headline());
        WorkMode work = WorkMode.parse(System.getProperty("jrbrain.work"), WorkMode.CHAT);
        Gate gate = new Gate(work.effective(Gate.Mode.ASK), c -> new Gate.Decision(false, false, "test: no changes"));
        Tools tools = new Tools(ws, () -> pi, () -> null, Ide.NONE, gate);
        Agent agent = new Agent(client, tools, () -> Prompt.system(ws.root(), pi, "Windows", gate.mode(), work), 25);
        System.out.println("== mode: " + work);
        String q = System.getProperty("jrbrain.question",
                "Which controllers does this project have, and which routes point to each one? Give the file paths.");
        StringBuilder answer = new StringBuilder();
        int[] tc = {0};
        Agent.Stats st = agent.send(q, new Agent.Listener() {
            @Override
            public void content(String t) {
                answer.append(t);
            }

            @Override
            public void toolStart(String name, String label) {
                tc[0]++;
                System.out.println("   tool: " + label);
            }

            @Override
            public void toolEnd(String name, boolean ok, String result) {
                if (!ok) {
                    System.out.println("     -> " + result);
                }
            }

            @Override
            public void notice(String text) {
                System.out.println("   note: " + text);
            }
        });
        System.out.println("== " + st.steps + " steps, " + st.toolCalls + " tool calls, " + st.promptTokens + " in / "
                + st.outputTokens + " out, " + st.millis + " ms");
        System.out.println("== answer:\n" + answer);
        assertTrue(st.toolCalls > 0, "the agent should look at the files");
        assertFalse(answer.toString().isBlank(), "the agent should answer");

        String follow = System.getProperty("jrbrain.question2", "");
        if (!follow.isEmpty()) {
            answer.setLength(0);
            if ("openai".equals(System.getProperty("jrbrain.switch"))) {
                agent.setClient(new OpenAiClient(base + "/v1", key, model, 0.3, 180));
                System.out.println("== switched to OpenAiClient for the follow-up");
            }
            Agent.Stats st2 = agent.send(follow, new Agent.Listener() {
                @Override
                public void content(String t) {
                    answer.append(t);
                }

                @Override
                public void toolStart(String name, String label) {
                    System.out.println("   tool: " + label);
                }
            });
            System.out.println("== follow-up: " + st2.steps + " steps, " + st2.toolCalls + " tool calls, " + st2.millis
                    + " ms\n" + answer);
        }
    }
}
