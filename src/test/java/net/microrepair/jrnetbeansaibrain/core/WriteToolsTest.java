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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteToolsTest {

    @TempDir
    Path dir;
    Tools tools;
    final List<Gate.Change> asked = new ArrayList<>();
    Gate.Decision answer = new Gate.Decision(true, false, "");

    @BeforeEach
    void setUp() throws Exception {
        Workspace ws = new Workspace(dir);
        Gate gate = new Gate(Gate.Mode.ASK, c -> {
            asked.add(c);
            return answer;
        });
        tools = new Tools(ws, () -> ProjectInfo.detect(ws.root(), ""), () -> null, Ide.NONE, gate);
    }

    String run(String tool, Object... kv) throws Exception {
        return tools.get(tool).run(Json.obj(kv));
    }

    @Test
    void createThenEditWithExactText() throws Exception {
        String r = run("write_file", "path", "app/Models/Post.php", "content", "<?php\nclass Post\n{\n    public $a = 1;\n}\n");
        assertTrue(r.startsWith("Created app/Models/Post.php"), r);
        assertEquals(Gate.Kind.CREATE, asked.get(0).kind);
        r = run("edit_file", "path", "app/Models/Post.php", "old_text", "    public $a = 1;", "new_text", "    public $a = 2;");
        assertTrue(r.startsWith("Edited app/Models/Post.php at line 4 (+1 -1 lines)"), r);
        assertTrue(Files.readString(dir.resolve("app/Models/Post.php")).contains("$a = 2;"));
    }

    @Test
    void editRequiresReadAndDetectsOutsideChanges() throws Exception {
        Files.writeString(dir.resolve("a.php"), "<?php\necho 1;\n");
        assertThrows(Workspace.ToolError.class, () -> run("edit_file", "path", "a.php", "old_text", "echo 1;", "new_text", "echo 2;"));
        run("read_file", "path", "a.php");
        Files.writeString(dir.resolve("a.php"), "<?php\necho 5;\n");
        Workspace.ToolError e = assertThrows(Workspace.ToolError.class,
                () -> run("edit_file", "path", "a.php", "old_text", "echo 5;", "new_text", "echo 2;"));
        assertTrue(e.getMessage().contains("changed since you read it"));
    }

    @Test
    void editForgivesLineNumbersIndentAndKeepsCrlf() throws Exception {
        Files.writeString(dir.resolve("b.php"), "<?php\r\nfunction f() {\r\n        return 1;\r\n}\r\n");
        run("read_file", "path", "b.php");
        String r = run("edit_file", "path", "b.php", "old_text", "3|         return 1;", "new_text", "3|         return 2;");
        assertTrue(r.startsWith("Edited"), r);
        run("read_file", "path", "b.php");
        r = run("edit_file", "path", "b.php", "old_text", "return 2;\n}", "new_text", "return 3;\n}");
        assertTrue(r.startsWith("Edited"), r);
        assertEquals("<?php\r\nfunction f() {\r\n        return 3;\r\n}\r\n",
                Files.readString(dir.resolve("b.php"), StandardCharsets.UTF_8));
    }

    @Test
    void ambiguousEditIsRefused() throws Exception {
        Files.writeString(dir.resolve("c.txt"), "x\ny\nx\n");
        run("read_file", "path", "c.txt");
        Workspace.ToolError e = assertThrows(Workspace.ToolError.class,
                () -> run("edit_file", "path", "c.txt", "old_text", "x", "new_text", "z"));
        assertTrue(e.getMessage().contains("appears 2 times (lines 1, 3)"), e.getMessage());
        assertTrue(run("edit_file", "path", "c.txt", "old_text", "x", "new_text", "z", "replace_all", true).contains("2 places"));
    }

    @Test
    void rejectedChangeIsNotWrittenAndCarriesTheNote() throws Exception {
        answer = new Gate.Decision(false, false, "use a Form Request instead");
        String r = run("write_file", "path", "x.php", "content", "<?php\n");
        assertTrue(r.contains("rejected") && r.contains("use a Form Request instead"), r);
        assertFalse(Files.exists(dir.resolve("x.php")));
    }

    @Test
    void readOnlyModeHidesWriteTools() {
        tools.gate().setMode(Gate.Mode.READ_ONLY);
        String defs = Json.write(tools.definitions());
        assertFalse(defs.contains("write_file") || defs.contains("run_command"));
        tools.gate().setMode(Gate.Mode.ASK);
        assertTrue(Json.write(tools.definitions()).contains("edit_file"));
    }

    @Test
    void commandsDangerousAndServers() throws Exception {
        assertTrue(Gate.isDangerous("git reset --hard HEAD"));
        assertTrue(Gate.isDangerous("php artisan migrate:fresh --seed"));
        assertTrue(Gate.isDangerous("rm -rf storage"));
        assertFalse(Gate.isDangerous("php artisan make:model Post -mcr"));
        assertThrows(Workspace.ToolError.class, () -> run("run_command", "command", "php artisan serve"));
        assertThrows(Workspace.ToolError.class, () -> run("run_command", "command", "npm run dev"));
        String r = run("run_command", "command", "echo hello");
        assertTrue(r.startsWith("Exit code 0") && r.contains("hello"), r);
        Gate full = new Gate(Gate.Mode.FULL_AUTO, c -> new Gate.Decision(false, false, "no"));
        assertTrue(full.check(new Gate.Change(Gate.Kind.COMMAND, ".", "", "", "npm install", "", false)).allowed);
        assertFalse(full.check(new Gate.Change(Gate.Kind.COMMAND, ".", "", "", "git push --force", "", true)).allowed);
    }

    @Test
    void bladeAndJsonChecks() {
        assertEquals("", WriteTools.bladeCheck("@if($a) x @else y @endif {{ $b }} @foreach($l as $i) @endforeach"));
        assertTrue(WriteTools.bladeCheck("@if($a) <p>{{ $x }}</p>").contains("1 @if but 0 @endif"));
        assertTrue(WriteTools.bladeCheck("@section('content') x").contains("@section"));
        assertEquals("", WriteTools.bladeCheck("@section('title', 'Home') {{-- @if --}}"));
    }

    @Test
    void diffCounts() {
        Diff d = Diff.of("a\nb\nc\n", "a\nB\nc\nd\n");
        assertEquals(2, d.added);
        assertEquals(1, d.removed);
        assertTrue(d.unified(1, 100).contains("- b\n+ B\n"));
    }
}
