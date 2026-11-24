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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Creates a whole project from a description with the real AI, in full-auto mode. Skipped unless asked:
 * {@code mvn test -Dtest=LiveCreateTest -Djrbrain.keyfile=<json> -Djrbrain.parent=<folder> -Djrbrain.name=<name>}
 * (optional -Djrbrain.template=LARAVEL|LARAVEL_BREEZE|PHP|EMPTY, -Djrbrain.description=...).
 */
class LiveCreateTest {

    @Test
    void createsAProjectFromADescription() throws Exception {
        String keyFile = System.getProperty("jrbrain.keyfile", "");
        String parent = System.getProperty("jrbrain.parent", "");
        Assumptions.assumeTrue(!keyFile.isEmpty() && !parent.isEmpty(), "live test not requested");
        Map<String, Object> cred = Json.parseObjectOrNull(Files.readString(Path.of(keyFile), StandardCharsets.UTF_8));
        Scaffold.Spec spec = new Scaffold.Spec();
        spec.parent = Path.of(parent);
        spec.name = System.getProperty("jrbrain.name", "jrbrain-test-app");
        spec.template = Scaffold.Template.valueOf(System.getProperty("jrbrain.template", "LARAVEL"));
        spec.description = System.getProperty("jrbrain.description",
                "A task list app. Tasks have a title, an optional description, an optional due date and a done flag. "
                + "The home page lists the tasks with a filter (all / open / done) and shows overdue tasks in red. "
                + "Create, edit and delete tasks with validation. A checkbox marks a task done without reloading the "
                + "page (JavaScript fetch to a PATCH route that returns JSON). Seed 8 demo tasks.");
        String err = Scaffold.validate(spec);
        Assumptions.assumeTrue(err == null, err);
        long t0 = System.currentTimeMillis();
        new Scaffold().create(spec, new Scaffold.Listener() {
            @Override
            public void step(String label) {
                System.out.println("== scaffold: " + label);
            }

            @Override
            public void line(String text) {
            }
        });
        System.out.println("== scaffold done in " + (System.currentTimeMillis() - t0) / 1000 + " s");

        Workspace ws = new Workspace(spec.folder());
        ProjectInfo pi = ProjectInfo.detect(ws.root(), "");
        Gate gate = new Gate(Gate.Mode.FULL_AUTO, c -> {
            System.out.println("   ASKED (dangerous, denied in the test): " + c.title());
            return new Gate.Decision(false, false, "Not allowed in this automatic test.");
        });
        Tools tools = new Tools(ws, () -> ProjectInfo.detect(ws.root(), ""), () -> null, Ide.NONE, gate);
        OllamaClient client = new OllamaClient(System.getProperty("jrbrain.base", "https://ollama.com"),
                Json.str(cred, "apiKey", ""), System.getProperty("jrbrain.model", "glm-5.3-flash"), true, 0.3, 240);
        Agent agent = new Agent(client, tools,
                () -> Prompt.system(ws.root(), ProjectInfo.detect(ws.root(), ""), "Windows", gate.mode()), 150);
        StringBuilder answer = new StringBuilder();
        Agent.Stats st = agent.send(Scaffold.kickoff(spec, pi), new Agent.Listener() {
            @Override
            public void stepStart(int step) {
                answer.setLength(0);
            }

            @Override
            public void content(String t) {
                answer.append(t);
            }

            @Override
            public void toolStart(String name, String label) {
                System.out.println("   " + label.replace('\n', ' '));
            }

            @Override
            public void toolEnd(String name, boolean ok, String result) {
                String first = result.split("\n", 2)[0];
                if (!ok || name.equals("run_command") || first.contains("check") || result.contains("syntax error")
                        || result.contains("Blade check")) {
                    String tail = result.length() > 400 ? result.substring(0, 400) : result;
                    System.out.println("     -> " + (ok ? "" : "ERROR ") + tail.replace("\n", "\n        "));
                }
            }

            @Override
            public void notice(String text) {
                System.out.println("   note: " + text);
            }

            @Override
            public void plan(String steps) {
                System.out.println("   PLAN:\n      " + steps.replace("\n", "\n      "));
            }
        });
        System.out.println("== agent: " + st.steps + " steps, " + st.toolCalls + " tool calls, " + st.promptTokens
                + " in / " + st.outputTokens + " out, " + st.millis / 1000 + " s" + (st.stepLimit ? " (STEP LIMIT)" : ""));
        System.out.println("== final answer:\n" + answer);
        assertTrue(st.toolCalls > 5);
    }
}
