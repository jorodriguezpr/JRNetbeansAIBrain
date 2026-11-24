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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {

    @TempDir
    static Path dir;
    static Workspace ws;
    static Tools tools;

    static void write(String rel, String text) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void laravel() throws Exception {
        write("composer.json", "{\"require\":{\"php\":\"^8.2\",\"laravel/framework\":\"^11.0\"},"
                + "\"require-dev\":{\"pestphp/pest\":\"^3.0\"}}");
        write("composer.lock", "{\"packages\":[{\"name\":\"laravel/framework\",\"version\":\"v11.31.0\"}]}");
        write("package.json", "{\"devDependencies\":{\"vite\":\"^5.0\",\"tailwindcss\":\"^3.4.1\"}}");
        write("artisan", "#!/usr/bin/env php\n");
        write(".env", "APP_KEY=base64:secretsecret\nDB_CONNECTION=mysql\nDB_PASSWORD=hunter2\n");
        write("routes/web.php", "<?php\nuse App\\Http\\Controllers\\PostController;\n"
                + "Route::get('/', fn () => view('welcome'));\nRoute::resource('posts', PostController::class);\n");
        write("app/Models/Post.php", "<?php\nnamespace App\\Models;\nclass Post extends Model {}\n");
        write("resources/views/welcome.blade.php", "<x-layout>\n  <h1>{{ $title }}</h1>\n</x-layout>\n");
        write("vendor/laravel/framework/x.php", "<?php // Route::resource in vendor\n");
        ws = new Workspace(dir);
        tools = new Tools(ws, () -> ProjectInfo.detect(ws.root(), "PhpProject"), () -> null);
    }

    static String run(String tool, Object... kv) throws Exception {
        return tools.get(tool).run(Json.obj(kv));
    }

    @Test
    void jsonRoundTrip() {
        Map<String, Object> m = Json.obj("a", "x\"y\n\u0001", "n", 3, "d", 1.5, "l", List.of(true, false), "z", null);
        String s = Json.write(m);
        assertEquals("{\"a\":\"x\\\"y\\n\\u0001\",\"n\":3,\"d\":1.5,\"l\":[true,false],\"z\":null}", s);
        assertEquals(m.get("a"), ((Map<?, ?>) Json.parse(s)).get("a"));
    }

    @Test
    void detectsLaravel() {
        ProjectInfo pi = ProjectInfo.detect(ws.root(), "");
        assertEquals("Laravel", pi.framework);
        assertEquals("11.31.0", pi.frameworkVersion);
        assertEquals("^8.2", pi.php);
        assertEquals("mysql", pi.dbDriver);
        assertTrue(pi.features.contains("Pest"));
        assertTrue(pi.headline().startsWith("Laravel 11.31.0 · PHP ^8.2"), pi.headline());
        assertEquals(1, pi.counts.get("Blade views"));
        assertFalse(pi.describe(ws.root()).contains("hunter2"));
    }

    @Test
    void refusesPathsOutsideTheProject() {
        assertThrows(Workspace.ToolError.class, () -> ws.resolve("../outside.txt"));
        assertThrows(Workspace.ToolError.class, () -> ws.resolve(dir.getParent().resolve("x").toString()));
    }

    @Test
    void readFileWithLineNumbersAndHidesSecrets() throws Exception {
        String r = run("read_file", "path", "routes/web.php");
        assertTrue(r.contains("3| Route::get('/'"), r);
        assertTrue(r.contains("lines 1-4 of 4"), r);
        String env = run("read_file", "path", ".env");
        assertFalse(env.contains("hunter2") || env.contains("secretsecret"), env);
        assertTrue(env.contains("DB_PASSWORD=<set>"), env);
        String part = run("read_file", "path", "routes/web.php", "offset", 2, "limit", 1);
        assertTrue(part.contains("read more with offset 3"), part);
        assertThrows(Workspace.ToolError.class, () -> run("read_file", "path", "nope.php"));
    }

    @Test
    void grepSkipsVendorAndEnvAndAcceptsBadRegex() throws Exception {
        String r = run("grep", "pattern", "Route::resource");
        assertTrue(r.contains("routes/web.php:4:"), r);
        assertFalse(r.contains("vendor/"), r);
        assertFalse(run("grep", "pattern", "hunter2").contains("hunter2 "));
        String bad = run("grep", "pattern", "view(");
        assertTrue(bad.contains("searched as plain text") && bad.contains("routes/web.php:3:"), bad);
        String blade = run("grep", "pattern", "title", "include", "*.blade.php");
        assertTrue(blade.contains("welcome.blade.php:2:"), blade);
    }

    @Test
    void globFindsTopLevelAndNested() throws Exception {
        assertTrue(run("glob", "pattern", "**/*.blade.php").contains("resources/views/welcome.blade.php"));
        assertTrue(run("glob", "pattern", "**/composer.json").contains("composer.json"));
        assertTrue(run("glob", "pattern", "app/Models/**/*.php").contains("app/Models/Post.php"));
        assertFalse(run("glob", "pattern", "**/*.php").contains("vendor/"));
        assertTrue(run("list_dir", "path", ".").contains("vendor/  (skipped)"));
    }

    @Test
    void recognisesToolCallsWrittenAsText() {
        Agent a = new Agent(new OllamaClient("http://127.0.0.1:1", "", "m", false, 0, 30), tools, () -> "", 5);
        List<Agent.Call> c = a.textCalls("<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"artisan\"}}</tool_call>");
        assertEquals(1, c.size());
        assertEquals("artisan", c.get(0).args.get("path"));
        c = a.textCalls("```json\n{\"name\": \"grep\", \"arguments\": {\"pattern\": \"Post\",}}\n```");
        assertEquals("Post", c.get(0).args.get("pattern"));
        assertTrue(a.textCalls("Here is some code: {\"name\":\"not_a_tool\"}").isEmpty());
        assertEquals("read_file", a.fixName("read_file</think><tool_call>read_file"));
        assertEquals("grep", a.fixName(" Grep "));
        assertThrows(Workspace.ToolError.class, () -> ws.resolve("app/ Models/Task.php"));
        Agent.Call s = Agent.Call.from(Json.obj("function", Json.obj("name", "glob", "arguments", "{\"pattern\":\"*.php\"")));
        assertEquals("*.php", s.args.get("pattern"));
    }

    @Test
    void markdownCodeBlocks() {
        List<Markdown.Block> b = new ArrayList<>();
        String h = Markdown.toHtml("Use **this**:\n\nFile: app/X.php\n```php\n<?php echo 1;\n```\n- one\n- `two`", b);
        assertEquals(1, b.size());
        assertEquals("php", b.get(0).lang);
        assertEquals("<?php echo 1;", b.get(0).code);
        assertTrue(h.contains("code-insert:0") && h.contains("<b>this</b>") && h.contains("<li><code>two</code></li>"), h);
        b.clear();
        String streaming = Markdown.toHtml("```js\nlet a", b);
        assertFalse(streaming.contains("code-insert"), "no insert link while the block is still open");
    }

    @Test
    void endpoints() {
        assertEquals("https://ollama.com/api/chat", OllamaClient.endpointFor("https://ollama.com/"));
        assertEquals("http://127.0.0.1:11435/api/chat", OllamaClient.endpointFor("http://127.0.0.1:11435/v1"));
        assertEquals("http://h/api/chat", OllamaClient.endpointFor("http://h/api"));
    }

    @Test
    void compactDropsOldToolOutput() {
        Agent a = new Agent(new OllamaClient("http://127.0.0.1:1", "", "m", false, 0, 30), tools, () -> "", 5);
        String big = "x".repeat(60_000);
        for (int k = 0; k < 6; k++) {
            a.history().add(Json.obj("role", "user", "content", "q" + k));
            a.history().add(Json.obj("role", "tool", "tool_name", "read_file", "content", big));
        }
        a.compact();
        assertTrue(Json.str(a.history().get(1), "content", "").startsWith("[Old tool result removed"));
        assertEquals(big, Json.str(a.history().get(a.history().size() - 1), "content", ""));
    }
}
