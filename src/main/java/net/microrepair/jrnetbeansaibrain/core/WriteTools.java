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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.microrepair.jrnetbeansaibrain.core.Workspace.ToolError;

/**
 * Tools that change the project: write_file, edit_file, create_folder, move_file, delete_file,
 * run_command, plus open_in_editor and plan. Every change goes through the {@link Gate}
 * (permission mode / developer approval). Edits must be based on what the agent has read:
 * a file that was not read, or that changed since it was read, is refused with a clear message.
 * After each change to a PHP, JSON or Blade file a quick syntax check runs and its result goes
 * back to the model.
 */
final class WriteTools {

    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+\\| ?");
    private static final Pattern SERVER = Pattern.compile(
            "artisan\\s+(serve|queue:work|queue:listen|schedule:work|pail|reverb:start|horizon|octane:start)\\b"
            + "|\\bnpm\\s+(run\\s+)?(dev|start|watch)\\b|\\byarn\\s+(dev|start)\\b|\\bpnpm\\s+(dev|start)\\b"
            + "|\\bvite(?!\\s+build)(\\s|$)|\\bphp\\s+-S\\b|\\bnodemon\\b|--watch\\b|\\btail\\s+-f\\b",
            Pattern.CASE_INSENSITIVE);

    private final Tools t;
    private Boolean phpWorks;

    WriteTools(Tools t) {
        this.t = t;
    }

    List<Tools.Tool> all() {
        List<Tools.Tool> l = new ArrayList<>();
        l.add(new WriteFile());
        l.add(new EditFile());
        l.add(new CreateFolder());
        l.add(new MoveFile());
        l.add(new DeleteFile());
        l.add(new RunCommand());
        l.add(new OpenInEditor());
        l.add(new Plan());
        return l;
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> schema(Object[][] p, String... req) {
        return Tools.schema(p, req);
    }

    private String denied(Gate.Decision d) {
        if (t.gate != null && t.gate.readOnly()) {
            return "Not done: read-only mode is on. Describe the change to the developer instead.";
        }
        return "Not done: the developer rejected this change." + (d.note.isEmpty() ? ""
                : " Their note: " + d.note) + " Do not try the same change again; follow the note or ask.";
    }

    private Gate.Decision ask(Gate.Change c) {
        if (t.gate == null) {
            return new Gate.Decision(true, false, "");
        }
        return t.gate.check(c);
    }

    /** Remove "  12| " prefixes that the model copied from read_file output (only if every line has one). */
    static String stripLineNumbers(String s) {
        String[] lines = s.split("\n", -1);
        int numbered = 0;
        int nonEmpty = 0;
        for (String l : lines) {
            if (l.isEmpty()) {
                continue;
            }
            nonEmpty++;
            if (NUMBERED.matcher(l).find()) {
                numbered++;
            }
        }
        if (nonEmpty == 0 || numbered != nonEmpty) {
            return s;
        }
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            if (k > 0) {
                b.append('\n');
            }
            b.append(NUMBERED.matcher(lines[k]).replaceFirst(""));
        }
        return b.toString();
    }

    /** Check that the agent's view of the file is current. */
    private void requireRead(Path p, String current) throws ToolError {
        Integer h = t.known.get(p);
        if (h == null) {
            throw new ToolError("Read " + t.ws.rel(p) + " with read_file before changing it, so the change is based "
                    + "on its real content.");
        }
        if (h != Tools.hash(current)) {
            throw new ToolError(t.ws.rel(p) + " changed since you read it (the developer or a command edited it). "
                    + "Read it again, then make the change.");
        }
    }

    /** Write the new text (LF line endings) keeping the file's line endings and BOM; through the editor if open. */
    private void apply(Path p, String text, String oldRaw) throws IOException {
        String before = oldRaw == null ? "" : oldRaw.replace("\r\n", "\n");
        if (before.startsWith("﻿")) {
            before = before.substring(1);
        }
        t.lastDiff = Diff.of(before, text).unified(3, 600);
        if (!t.ide.replaceOpenText(p, text)) {
            boolean crlf = oldRaw != null ? oldRaw.contains("\r\n") : false;
            boolean bom = oldRaw != null && oldRaw.startsWith("﻿");
            String out = crlf ? text.replace("\r\n", "\n").replace("\n", "\r\n") : text;
            if (bom && !out.startsWith("﻿")) {
                out = "﻿" + out;
            }
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, out, StandardCharsets.UTF_8);
            t.ide.refresh(p);
        }
        t.known.put(p, Tools.hash(text));
    }

    /** Quick check after a change: php -l, JSON parse, Blade directive balance. Returns "" or a note. */
    String check(Path p, String text) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".blade.php")) {
            return bladeCheck(text);
        }
        if (n.endsWith(".php")) {
            return phpLint(p);
        }
        if (n.endsWith(".json")) {
            try {
                Json.parse(text);
                return "";
            } catch (IllegalArgumentException ex) {
                return "\nJSON check: the file is not valid JSON (" + ex.getMessage() + "). Fix it.";
            }
        }
        return "";
    }

    private String phpLint(Path p) {
        if (Boolean.FALSE.equals(phpWorks)) {
            return "";
        }
        try {
            CommandRunner r = new CommandRunner();
            CommandRunner.Result res = r.run("php -l \"" + p.toAbsolutePath() + "\"", t.ws.root(), 30, null);
            String out = res.output.toString().trim();
            if (res.exit == 9009 || res.exit == 127 || out.contains("is not recognized") || out.contains("not found")
                    && !out.contains("Parse error")) {
                phpWorks = false;
                return "";
            }
            phpWorks = true;
            if (res.exit == 0) {
                return "\nphp -l: no syntax errors.";
            }
            return "\nphp -l found a syntax error. Fix it now:\n" + out.replace(t.ws.root().toString(), "");
        } catch (IOException ex) {
            phpWorks = false;
            return "";
        }
    }

    private static final String[][] BLADE_PAIRS = {
        {"if", "endif"}, {"foreach", "endforeach"}, {"forelse", "endforelse"}, {"for", "endfor"},
        {"while", "endwhile"}, {"auth", "endauth"}, {"guest", "endguest"}, {"isset", "endisset"},
        {"empty", "endempty"}, {"unless", "endunless"}, {"switch", "endswitch"}, {"push", "endpush"},
        {"once", "endonce"}, {"error", "enderror"}, {"can", "endcan"}, {"production", "endproduction"}};

    static String bladeCheck(String text) {
        String code = text.replaceAll("(?s)\\{\\{--.*?--\\}\\}", "");
        List<String> problems = new ArrayList<>();
        for (String[] pair : BLADE_PAIRS) {
            int open = count(code, "@" + pair[0] + "(?![A-Za-z])");
            if (pair[0].equals("empty")) {
                open = count(code, "@empty\\s*\\(");
            }
            int close = count(code, "@" + pair[1] + "(?![A-Za-z])");
            if (open != close) {
                problems.add(open + " @" + pair[0] + " but " + close + " @" + pair[1]);
            }
        }
        int sectionsBlock = count(code, "@section\\s*\\(\\s*['\"][^'\"]+['\"]\\s*\\)");
        int sectionEnds = count(code, "@(endsection|show|stop|overwrite)(?![A-Za-z])");
        if (sectionsBlock != sectionEnds) {
            problems.add(sectionsBlock + " block @section but " + sectionEnds + " @endsection/@show");
        }
        int php = count(code, "@php(?![A-Za-z(])");
        int endphp = count(code, "@endphp(?![A-Za-z])");
        if (php != endphp) {
            problems.add(php + " @php but " + endphp + " @endphp");
        }
        int open = count(code, "\\{\\{(?!--)");
        int close = count(code, "(?<!--)\\}\\}");
        if (open != close) {
            problems.add(open + " {{ but " + close + " }}");
        }
        return problems.isEmpty() ? "" : "\nBlade check: " + String.join("; ", problems) + ". Check the directives.";
    }

    private static int count(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static String stats(String before, String after) {
        Diff d = Diff.of(before, after);
        return "+" + d.added + " -" + d.removed + " lines";
    }

    // ---------------------------------------------------------------- write_file

    final class WriteFile implements Tools.Tool {
        public String name() {
            return "write_file";
        }

        public boolean writes() {
            return true;
        }

        public String description() {
            return "Create a new file, or replace a whole file, with the given content. Missing folders are created. "
                    + "For a small change to an existing file use edit_file. An existing file must be read first.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"path", "string", "File path relative to the project folder."},
                {"content", "string", "The complete new content of the file."}}, "path", "content");
        }

        public String label(Map<String, Object> a) {
            return (Files.exists(safe(a)) ? "Write " : "Create ") + Json.str(a, "path", "");
        }

        private Path safe(Map<String, Object> a) {
            try {
                return t.ws.resolve(Json.str(a, "path", ""));
            } catch (ToolError ex) {
                return t.ws.root().resolve("\u0000invalid");
            }
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            Path p = t.ws.resolve(Tools.need(a, "path"));
            Object co = a.get("content");
            if (co == null) {
                throw new ToolError("Missing required argument 'content'.");
            }
            String content = stripLineNumbers(co.toString().replace("\r\n", "\n"));
            if (Files.isDirectory(p)) {
                throw new ToolError("'" + t.ws.rel(p) + "' is a folder.");
            }
            boolean exists = Files.exists(p);
            String raw = null;
            String before = "";
            if (exists) {
                before = t.text(p);
                raw = Files.readString(p, StandardCharsets.UTF_8);
                requireRead(p, before);
                if (before.replace("\r\n", "\n").equals(content)) {
                    return "No change: the file already has exactly this content.";
                }
            }
            boolean secret = Workspace.isSecretFile(p);
            Gate.Decision d = ask(new Gate.Change(exists ? Gate.Kind.EDIT : Gate.Kind.CREATE, t.ws.rel(p),
                    before.replace("\r\n", "\n"), content, null, "", secret));
            if (!d.allowed) {
                return denied(d);
            }
            apply(p, content, raw);
            int lines = Diff.split(content).length;
            return (exists ? "Replaced " + t.ws.rel(p) + " (" + stats(before, content) + ")"
                    : "Created " + t.ws.rel(p) + " (" + lines + " lines)") + check(p, content);
        }
    }

    // ---------------------------------------------------------------- edit_file

    final class EditFile implements Tools.Tool {
        public String name() {
            return "edit_file";
        }

        public boolean writes() {
            return true;
        }

        public String description() {
            return "Change part of an existing file: replace old_text (copied exactly from the file, without the line "
                    + "numbers that read_file shows) with new_text. old_text must appear exactly once; include enough "
                    + "surrounding lines to make it unique, or set replace_all. Read the file first.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"path", "string", "File path relative to the project folder."},
                {"old_text", "string", "The exact text to replace (several lines are fine)."},
                {"new_text", "string", "The text to put in its place (may be empty to delete)."},
                {"replace_all", "boolean", "Replace every occurrence instead of exactly one (default false)."}},
                    "path", "old_text", "new_text");
        }

        public String label(Map<String, Object> a) {
            return "Edit " + Json.str(a, "path", "");
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            Path p = t.ws.resolve(Tools.need(a, "path"));
            if (!Files.isRegularFile(p)) {
                throw new ToolError("'" + Json.str(a, "path", "") + "' does not exist. Use write_file to create it.");
            }
            Object oo = a.get("old_text");
            Object no = a.get("new_text");
            if (oo == null || oo.toString().isEmpty()) {
                throw new ToolError("old_text is empty. To replace the whole file use write_file.");
            }
            if (no == null) {
                throw new ToolError("Missing required argument 'new_text' (use \"\" to delete the text).");
            }
            boolean all = Json.bool(a, "replace_all", false);
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            String text = t.text(p).replace("\r\n", "\n");
            requireRead(p, text);
            String oldT = oo.toString().replace("\r\n", "\n");
            String newT = no.toString().replace("\r\n", "\n");

            int[] where = find(text, oldT);
            if (where.length == 0) {
                String o2 = stripLineNumbers(oldT);
                if (!o2.equals(oldT)) {
                    oldT = o2;
                    newT = stripLineNumbers(newT);
                    where = find(text, oldT);
                }
            }
            if (where.length == 0) {
                String region = looseMatch(text, oldT);
                if (region != null) {
                    oldT = region;
                    where = find(text, oldT);
                }
            }
            if (where.length == 0) {
                throw new ToolError("old_text was not found in " + t.ws.rel(p) + ". " + hint(text, oldT)
                        + " Copy the text exactly from read_file output (without the line numbers), or read the file "
                        + "again.");
            }
            if (where.length > 1 && !all) {
                List<String> at = new ArrayList<>();
                for (int k = 0; k < Math.min(8, where.length); k++) {
                    at.add(String.valueOf(lineOf(text, where[k])));
                }
                throw new ToolError("old_text appears " + where.length + " times (lines " + String.join(", ", at)
                        + "). Include more surrounding lines so it is unique, or set replace_all to true.");
            }
            String after = all ? text.replace(oldT, newT)
                    : text.substring(0, where[0]) + newT + text.substring(where[0] + oldT.length());
            if (after.equals(text)) {
                return "No change: new_text is the same as old_text.";
            }
            Gate.Decision d = ask(new Gate.Change(Gate.Kind.EDIT, t.ws.rel(p), text, after, null, "",
                    Workspace.isSecretFile(p)));
            if (!d.allowed) {
                return denied(d);
            }
            apply(p, after, raw);
            return "Edited " + t.ws.rel(p) + " at line " + lineOf(text, where[0]) + " (" + stats(text, after) + ")"
                    + (where.length > 1 ? ", " + where.length + " places" : "") + check(p, after);
        }

        private int[] find(String text, String s) {
            List<Integer> at = new ArrayList<>();
            int i = text.indexOf(s);
            while (i >= 0) {
                at.add(i);
                i = text.indexOf(s, i + Math.max(1, s.length()));
            }
            return at.stream().mapToInt(Integer::intValue).toArray();
        }

        /** Match ignoring trailing spaces and indentation differences; returns the file's real text or null. */
        private String looseMatch(String text, String old) {
            String[] want = old.split("\n", -1);
            int first = 0;
            int last = want.length - 1;
            while (first <= last && want[first].isBlank()) {
                first++;
            }
            while (last >= first && want[last].isBlank()) {
                last--;
            }
            if (first > last) {
                return null;
            }
            String[] have = text.split("\n", -1);
            int n = last - first + 1;
            String found = null;
            for (int s = 0; s + n <= have.length; s++) {
                boolean ok = true;
                for (int k = 0; k < n && ok; k++) {
                    ok = have[s + k].trim().equals(want[first + k].trim());
                }
                if (ok) {
                    if (found != null) {
                        return null;
                    }
                    StringBuilder b = new StringBuilder();
                    for (int k = 0; k < n; k++) {
                        if (k > 0) {
                            b.append('\n');
                        }
                        b.append(have[s + k]);
                    }
                    found = b.toString();
                }
            }
            return found;
        }

        private String hint(String text, String old) {
            String firstLine = "";
            for (String l : old.split("\n")) {
                if (!l.isBlank()) {
                    firstLine = l.trim();
                    break;
                }
            }
            if (firstLine.length() < 4) {
                return "";
            }
            String[] have = text.split("\n", -1);
            List<String> hits = new ArrayList<>();
            for (int k = 0; k < have.length && hits.size() < 3; k++) {
                if (have[k].trim().equals(firstLine) || have[k].contains(firstLine)) {
                    hits.add(String.valueOf(k + 1));
                }
            }
            return hits.isEmpty() ? "Its first line does not appear in the file either."
                    : "Its first line appears at line " + String.join(", ", hits) + ", but the lines after it differ.";
        }
    }

    static int lineOf(String text, int offset) {
        int n = 1;
        for (int k = 0; k < offset && k < text.length(); k++) {
            if (text.charAt(k) == '\n') {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- create_folder

    final class CreateFolder implements Tools.Tool {
        public String name() {
            return "create_folder";
        }

        public boolean writes() {
            return true;
        }

        public String description() {
            return "Create a folder (and missing parent folders). write_file creates folders by itself.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {{"path", "string", "Folder path relative to the project folder."}}, "path");
        }

        public String label(Map<String, Object> a) {
            return "Create folder " + Json.str(a, "path", "");
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            Path p = t.ws.resolve(Tools.need(a, "path"));
            if (t.gate != null && t.gate.readOnly()) {
                return denied(new Gate.Decision(false, false, ""));
            }
            if (Files.isDirectory(p)) {
                return "The folder " + t.ws.rel(p) + " already exists.";
            }
            if (Files.exists(p)) {
                throw new ToolError("'" + t.ws.rel(p) + "' is a file.");
            }
            Files.createDirectories(p);
            t.ide.refresh(p.getParent() == null ? p : p.getParent());
            return "Created folder " + t.ws.rel(p);
        }
    }

    // ---------------------------------------------------------------- move_file

    final class MoveFile implements Tools.Tool {
        public String name() {
            return "move_file";
        }

        public boolean writes() {
            return true;
        }

        public String description() {
            return "Move or rename a file or folder inside the project. The target must not exist.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"from", "string", "Current path relative to the project folder."},
                {"to", "string", "New path relative to the project folder."}}, "from", "to");
        }

        public String label(Map<String, Object> a) {
            return "Move " + Json.str(a, "from", "") + " to " + Json.str(a, "to", "");
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            Path from = t.ws.resolve(Tools.need(a, "from"));
            Path to = t.ws.resolve(Tools.need(a, "to"));
            if (!Files.exists(from)) {
                throw new ToolError("'" + Json.str(a, "from", "") + "' does not exist.");
            }
            if (Files.exists(to)) {
                throw new ToolError("'" + Json.str(a, "to", "") + "' already exists.");
            }
            if (from.equals(t.ws.root())) {
                throw new ToolError("The project folder itself cannot be moved.");
            }
            Gate.Decision d = ask(new Gate.Change(Gate.Kind.EDIT, t.ws.rel(from) + " -> " + t.ws.rel(to), "", "",
                    null, "", false));
            if (!d.allowed) {
                return denied(d);
            }
            if (to.getParent() != null) {
                Files.createDirectories(to.getParent());
            }
            Files.move(from, to);
            Integer h = t.known.remove(from);
            if (h != null) {
                t.known.put(to, h);
            }
            t.ide.refresh(from.getParent() == null ? t.ws.root() : from.getParent());
            t.ide.refresh(to.getParent() == null ? t.ws.root() : to.getParent());
            return "Moved " + t.ws.rel(from) + " to " + t.ws.rel(to);
        }
    }

    // ---------------------------------------------------------------- delete_file

    final class DeleteFile implements Tools.Tool {
        public String name() {
            return "delete_file";
        }

        public boolean writes() {
            return true;
        }

        public String description() {
            return "Delete a file, or an empty folder. The developer is always asked (except in full-auto mode).";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {{"path", "string", "Path relative to the project folder."}}, "path");
        }

        public String label(Map<String, Object> a) {
            return "Delete " + Json.str(a, "path", "");
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            Path p = t.ws.resolve(Tools.need(a, "path"));
            if (p.equals(t.ws.root())) {
                throw new ToolError("The project folder itself cannot be deleted.");
            }
            if (!Files.exists(p)) {
                return "'" + t.ws.rel(p) + "' does not exist (nothing to delete).";
            }
            String before = "";
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    if (s.findAny().isPresent()) {
                        throw new ToolError("The folder is not empty. Delete its files first, one by one.");
                    }
                }
            } else {
                try {
                    before = Tools.readText(p);
                } catch (ToolError ex) {
                    before = "(binary file)";
                }
            }
            Gate.Decision d = ask(new Gate.Change(Gate.Kind.DELETE, t.ws.rel(p), before, "", null, "", false));
            if (!d.allowed) {
                return denied(d);
            }
            Files.delete(p);
            t.known.remove(p);
            t.ide.refresh(p.getParent() == null ? t.ws.root() : p.getParent());
            return "Deleted " + t.ws.rel(p);
        }
    }

    // ---------------------------------------------------------------- run_command

    final class RunCommand implements Tools.Tool {
        public String name() {
            return "run_command";
        }

        public boolean writes() {
            return true;
        }

        public String description() {
            String shell = CommandRunner.WINDOWS ? "Windows cmd.exe" : "sh";
            return "Run a command in the project folder (" + shell + "), e.g. php artisan make:model Post -mcr, "
                    + "php artisan migrate, composer require ..., npm install, npm run build, php artisan test. "
                    + "There is no input: use non-interactive flags. Servers and watchers (artisan serve, npm run "
                    + "dev) are not allowed; tell the developer to start them.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"command", "string", "The command line."},
                {"timeout_seconds", "integer", "Stop after this many seconds (default 300, max 1800)."}}, "command");
        }

        public String label(Map<String, Object> a) {
            return "Run " + Json.str(a, "command", "");
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            String cmd = Tools.need(a, "command").trim();
            if (cmd.contains("\n")) {
                throw new ToolError("Send one command line (join steps with && if needed).");
            }
            if (SERVER.matcher(cmd).find()) {
                throw new ToolError("This starts a server or watcher that never ends, so it cannot run here. Tell the "
                        + "developer to run it in a terminal (for example: php artisan serve, npm run dev). For "
                        + "assets use npm run build.");
            }
            int timeout = Math.max(10, Math.min(1800, Json.num(a, "timeout_seconds", 300)));
            Gate.Decision d = ask(new Gate.Change(Gate.Kind.COMMAND, ".", "", "", cmd, "", Gate.isDangerous(cmd)));
            if (!d.allowed) {
                return denied(d);
            }
            t.runner.reset();
            CommandRunner.Result r = t.runner.run(cmd, t.ws.root(), timeout, line -> {
                java.util.function.Consumer<String> sink = t.progress;
                if (sink != null) {
                    sink.accept(line);
                }
            });
            t.ide.refresh(t.ws.root());
            String out = r.output.toString().replace(t.ws.root().toString() + java.io.File.separator, "");
            if (out.length() > 16000) {
                out = out.substring(0, 3000) + "\n[... " + (out.length() - 15000) + " characters cut ...]\n"
                        + out.substring(out.length() - 12000);
            }
            String head = r.stopped ? "Stopped by the developer." : r.timedOut ? "Timed out after " + timeout
                    + " s and was stopped." : "Exit code " + r.exit + (r.exit == 0 ? "" : " (the command failed)");
            return head + String.format(Locale.ROOT, " · %.1f s", r.millis / 1000.0) + "\n"
                    + (out.isBlank() ? "(no output)" : out);
        }
    }

    // ---------------------------------------------------------------- open_in_editor

    final class OpenInEditor implements Tools.Tool {
        public String name() {
            return "open_in_editor";
        }

        public String description() {
            return "Open a file in the NetBeans editor for the developer, at a line. Use it to show the main file you "
                    + "created or changed.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"path", "string", "File path relative to the project folder."},
                {"line", "integer", "Line to show (default 1)."}}, "path");
        }

        public String label(Map<String, Object> a) {
            return "Open " + Json.str(a, "path", "") + " in the editor";
        }

        public String run(Map<String, Object> a) throws ToolError {
            Path p = t.ws.resolve(Tools.need(a, "path"));
            if (!Files.isRegularFile(p)) {
                throw new ToolError("'" + Json.str(a, "path", "") + "' does not exist.");
            }
            t.ide.open(p, Math.max(1, Json.num(a, "line", 1)));
            return "Opened " + t.ws.rel(p) + " in the editor.";
        }
    }

    // ---------------------------------------------------------------- plan

    final class Plan implements Tools.Tool {
        public String name() {
            return "plan";
        }

        public String description() {
            return "Write or update your step-by-step plan for a bigger task, as a checklist: one step per line, "
                    + "starting with [ ] (to do), [~] (doing) or [x] (done). The developer sees it. Update it as you go.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {{"steps", "string", "The checklist, one step per line."}}, "steps");
        }

        public String label(Map<String, Object> a) {
            return "Update the plan";
        }

        public String run(Map<String, Object> a) throws ToolError {
            String s = Tools.need(a, "steps").trim();
            java.util.function.Consumer<String> sink = t.planSink;
            if (sink != null) {
                sink.accept(s);
            }
            long done = s.lines().filter(l -> l.trim().startsWith("[x]") || l.trim().startsWith("- [x]")).count();
            return "Plan saved (" + done + " of " + s.lines().filter(l -> !l.isBlank()).count() + " steps done).";
        }
    }
}
