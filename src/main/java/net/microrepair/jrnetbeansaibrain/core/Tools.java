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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import net.microrepair.jrnetbeansaibrain.core.Workspace.ToolError;

/**
 * The tools the model can call. Version 0.1 has read-only tools: they look at the project but
 * never change it. Every tool validates its arguments and answers with a clear error message the
 * model can act on, instead of failing.
 */
public final class Tools {

    /** One tool. */
    public interface Tool {
        String name();

        String description();

        /** JSON schema of the arguments (type object). */
        Map<String, Object> parameters();

        /** Short text for the chat window, e.g. "Read routes/web.php". */
        String label(Map<String, Object> args);

        /** True for tools that change the project (hidden in read-only mode). */
        default boolean writes() {
            return false;
        }

        String run(Map<String, Object> args) throws ToolError, IOException;
    }

    /** What the editor shows now (supplied by the NetBeans side; may return null). */
    public interface EditorState {
        /** Project-relative or absolute path of the file in the editor, or null. */
        String file();

        int line();

        int column();

        String selection();
    }

    public static final int MAX_RESULT_CHARS = 24000;
    private static final long MAX_TEXT_FILE = 2_000_000;

    final Workspace ws;
    final Ide ide;
    final Gate gate;
    final CommandRunner runner = new CommandRunner();
    /** Hash of each file's text as the agent last saw it (read or written). */
    final Map<Path, Integer> known = new java.util.concurrent.ConcurrentHashMap<>();
    volatile java.util.function.Consumer<String> progress;
    volatile java.util.function.Consumer<String> planSink;
    /** Unified diff of the last file change, for the chat window ({@link #takeLastDiff()}). */
    volatile String lastDiff;
    private final Supplier<ProjectInfo> info;
    private final Supplier<EditorState> editor;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Read-only tools only (no gate). */
    public Tools(Workspace ws, Supplier<ProjectInfo> info, Supplier<EditorState> editor) {
        this(ws, info, editor, Ide.NONE, null);
    }

    /** All tools; changes go through {@code gate} (null = read-only tools only). */
    public Tools(Workspace ws, Supplier<ProjectInfo> info, Supplier<EditorState> editor, Ide ide, Gate gate) {
        this.ws = ws;
        this.info = info;
        this.editor = editor;
        this.ide = ide == null ? Ide.NONE : ide;
        this.gate = gate;
        add(new ListDir());
        add(new Glob());
        add(new Grep());
        add(new ReadFile());
        add(new ProjectInfoTool());
        add(new EditorTool());
        if (gate != null) {
            for (Tool t : new WriteTools(this).all()) {
                add(t);
            }
        }
    }

    public Workspace workspace() {
        return ws;
    }

    public Gate gate() {
        return gate;
    }

    /** Where command output lines and plans go (set by the agent). */
    public void setSinks(java.util.function.Consumer<String> progress, java.util.function.Consumer<String> plan) {
        this.progress = progress;
        this.planSink = plan;
    }

    /** The diff of the last change made by a tool, once. */
    public String takeLastDiff() {
        String d = lastDiff;
        lastDiff = null;
        return d;
    }

    /** Stop a running command. */
    public void cancel() {
        runner.stop();
    }

    static int hash(String text) {
        String t = text.replace("\r\n", "\n");
        return t.hashCode() * 31 + t.length();
    }

    /** Current text of a file: the editor's (unsaved) text when it is open, else the file on disk. */
    String text(Path p) throws ToolError, IOException {
        String open = ide.openText(p);
        return open != null ? open : readText(p);
    }

    private void add(Tool t) {
        tools.put(t.name(), t);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    /** Tool definitions in the Ollama/OpenAI "function" format. */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (t.writes() && (gate == null || gate.readOnly())) {
                continue;
            }
            list.add(Json.obj("type", "function", "function",
                    Json.obj("name", t.name(), "description", t.description(), "parameters", t.parameters())));
        }
        return list;
    }

    // ---------------------------------------------------------------- schema helpers

    static Map<String, Object> schema(Object[][] props, String... required) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (Object[] d : props) {
            Map<String, Object> one = Json.obj("type", d[1], "description", d[2]);
            p.put((String) d[0], one);
        }
        return Json.obj("type", "object", "properties", p, "required", List.of(required));
    }

    static String need(Map<String, Object> a, String key) throws ToolError {
        Object v = a.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new ToolError("Missing required argument '" + key + "'.");
        }
        return v.toString();
    }

    /** Read a text file; refuses binaries and very large files with a clear message. */
    static String readText(Path p) throws ToolError, IOException {
        long size = Files.size(p);
        if (size > MAX_TEXT_FILE) {
            throw new ToolError("File is too large to read (" + size / 1024 + " KB).");
        }
        byte[] data = Files.readAllBytes(p);
        for (int k = 0; k < Math.min(data.length, 8000); k++) {
            if (data[k] == 0) {
                throw new ToolError("This is a binary file; it cannot be read as text.");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException ex) {
            return new String(data, StandardCharsets.ISO_8859_1);
        }
    }

    static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n[... output cut at " + max + " characters]";
    }

    /** Walk the project, skipping dependency/cache folders, calling {@code visit} for files. */
    private void walk(Path start, java.util.function.Predicate<Path> visit) throws IOException {
        walk(start, visit, false);
    }

    private void walk(Path start, java.util.function.Predicate<Path> visit, boolean all) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(start) && (all ? dir.getFileName() != null && dir.getFileName().toString().equals(".git")
                        : ws.skipped(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                return visit.test(file) ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ---------------------------------------------------------------- list_dir

    final class ListDir implements Tool {
        public String name() {
            return "list_dir";
        }

        public String description() {
            return "List a folder of the project as a tree (folders end with /). Dependency and cache folders "
                    + "such as vendor/ and node_modules/ are shown but not opened.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"path", "string", "Folder relative to the project, '.' for the project folder."},
                {"depth", "integer", "How many levels to show, 1 to 4 (default 2)."}});
        }

        public String label(Map<String, Object> a) {
            return "List " + Json.str(a, "path", ".");
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            Path dir = ws.resolve(Json.str(a, "path", "."));
            if (!Files.isDirectory(dir)) {
                throw new ToolError("'" + Json.str(a, "path", ".") + "' is not a folder."
                        + (Files.exists(dir) ? " Use read_file for files." : " It does not exist."));
            }
            int depth = Math.max(1, Math.min(4, Json.num(a, "depth", 2)));
            StringBuilder b = new StringBuilder(ws.rel(dir)).append("/\n");
            int[] count = {0};
            tree(dir, depth, "  ", b, count);
            if (count[0] >= 400) {
                b.append("[... stopped at 400 entries; list a sub-folder]\n");
            }
            return b.toString();
        }

        private void tree(Path dir, int depth, String indent, StringBuilder b, int[] count) throws IOException {
            List<Path> kids;
            try (Stream<Path> s = Files.list(dir)) {
                kids = new ArrayList<>(s.toList());
            }
            kids.sort((x, y) -> {
                boolean dx = Files.isDirectory(x);
                boolean dy = Files.isDirectory(y);
                if (dx != dy) {
                    return dx ? -1 : 1;
                }
                return x.getFileName().toString().compareToIgnoreCase(y.getFileName().toString());
            });
            for (Path k : kids) {
                if (count[0]++ >= 400) {
                    return;
                }
                String n = k.getFileName().toString();
                if (Files.isDirectory(k)) {
                    if (ws.skipped(k)) {
                        b.append(indent).append(n).append("/  (skipped)\n");
                    } else {
                        b.append(indent).append(n).append("/\n");
                        if (depth > 1) {
                            tree(k, depth - 1, indent + "  ", b, count);
                        }
                    }
                } else {
                    long size = 0;
                    try {
                        size = Files.size(k);
                    } catch (IOException ignore) {
                        // size stays 0
                    }
                    b.append(indent).append(n).append("  ").append(size < 1024 ? size + " B" : size / 1024 + " KB")
                            .append('\n');
                }
            }
        }
    }

    // ---------------------------------------------------------------- glob

    final class Glob implements Tool {
        public String name() {
            return "glob";
        }

        public String description() {
            return "Find files by name pattern, e.g. '**/*.blade.php', 'app/Models/*.php', '**/*Controller.php'. "
                    + "Returns project-relative paths.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"pattern", "string", "Glob pattern relative to the project folder."},
                {"path", "string", "Folder to search in (default: the project folder)."},
                {"include_dependencies", "boolean", "Also search vendor/, node_modules/ and caches (default false)."}},
                    "pattern");
        }

        public String label(Map<String, Object> a) {
            return "Find " + Json.str(a, "pattern", "");
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            String pattern = need(a, "pattern").replace('\\', '/');
            while (pattern.startsWith("./")) {
                pattern = pattern.substring(2);
            }
            Path base = ws.resolve(Json.str(a, "path", "."));
            if (!Files.isDirectory(base)) {
                throw new ToolError("'" + Json.str(a, "path", ".") + "' is not a folder.");
            }
            // "**/" means "any folders, or none": Java's glob needs at least one, so also try without them
            List<PathMatcher> ms = new ArrayList<>();
            try {
                ms.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
                if (pattern.contains("**/")) {
                    ms.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace("**/", "")));
                }
            } catch (IllegalArgumentException ex) {
                throw new ToolError("Bad glob pattern: " + ex.getMessage());
            }
            List<String> found = new ArrayList<>();
            boolean deps = Json.bool(a, "include_dependencies", false);
            walk(base, f -> {
                Path r = base.relativize(f);
                for (PathMatcher m : ms) {
                    if (m.matches(r)) {
                        found.add(ws.rel(f));
                        break;
                    }
                }
                return found.size() < 300;
            }, deps);
            if (found.isEmpty()) {
                return "No files match '" + pattern + "' (vendor/, node_modules/ and caches are not searched).";
            }
            found.sort(String::compareToIgnoreCase);
            return String.join("\n", found) + (found.size() >= 300 ? "\n[... first 300 files only]" : "")
                    + "\n(" + found.size() + " files)";
        }
    }

    // ---------------------------------------------------------------- grep

    final class Grep implements Tool {
        public String name() {
            return "grep";
        }

        public String description() {
            return "Search file contents with a regular expression (Java syntax). Returns path:line: text. "
                    + "Use 'include' to limit to file names, e.g. '*.php' or '*.blade.php'.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"pattern", "string", "Regular expression to search for."},
                {"path", "string", "Folder or file to search (default: the project folder)."},
                {"include", "string", "Only files whose name matches this glob, e.g. '*.php'."},
                {"ignore_case", "boolean", "Case-insensitive search (default false)."},
                {"context", "integer", "Lines of context before and after each match, 0 to 5 (default 0)."},
                {"include_dependencies", "boolean", "Also search vendor/, node_modules/ and caches (default false)."}},
                    "pattern");
        }

        public String label(Map<String, Object> a) {
            String inc = Json.str(a, "include", "");
            return "Search \"" + Json.str(a, "pattern", "") + "\"" + (inc.isEmpty() ? "" : " in " + inc);
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            String pat = need(a, "pattern");
            boolean ic = Json.bool(a, "ignore_case", false);
            int ctx = Math.max(0, Math.min(5, Json.num(a, "context", 0)));
            Pattern p;
            String note = "";
            try {
                p = Pattern.compile(pat, ic ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            } catch (PatternSyntaxException ex) {
                p = Pattern.compile(Pattern.quote(pat), ic ? Pattern.CASE_INSENSITIVE : 0);
                note = "(The pattern is not a valid regular expression, so it was searched as plain text.)\n";
            }
            String inc = Json.str(a, "include", "").trim();
            PathMatcher incM = null;
            if (!inc.isEmpty()) {
                try {
                    incM = FileSystems.getDefault().getPathMatcher("glob:" + inc.replace('\\', '/'));
                } catch (IllegalArgumentException ex) {
                    throw new ToolError("Bad include pattern: " + ex.getMessage());
                }
            }
            Path base = ws.resolve(Json.str(a, "path", "."));
            if (!Files.exists(base)) {
                throw new ToolError("'" + Json.str(a, "path", ".") + "' does not exist.");
            }
            StringBuilder out = new StringBuilder(note);
            int[] hits = {0};
            int[] files = {0};
            final Pattern fp = p;
            final PathMatcher fInc = incM;
            java.util.function.Predicate<Path> visit = f -> {
                if (fInc != null && !fInc.matches(f.getFileName())) {
                    return true;
                }
                if (Workspace.isSecretFile(f)) {
                    return true;
                }
                String text;
                try {
                    if (Files.size(f) > MAX_TEXT_FILE) {
                        return true;
                    }
                    text = text(f);
                } catch (ToolError | IOException ex) {
                    return true;
                }
                String[] lines = text.split("\r?\n", -1);
                boolean any = false;
                int lastPrinted = -1;
                for (int k = 0; k < lines.length; k++) {
                    if (!fp.matcher(lines[k]).find()) {
                        continue;
                    }
                    if (!any) {
                        files[0]++;
                        any = true;
                    }
                    int from = Math.max(0, k - ctx);
                    int to = Math.min(lines.length - 1, k + ctx);
                    if (ctx > 0 && lastPrinted >= 0 && from > lastPrinted + 1) {
                        out.append("--\n");
                    }
                    for (int j = Math.max(from, lastPrinted + 1); j <= to; j++) {
                        String l = lines[j].length() > 300 ? lines[j].substring(0, 300) + "..." : lines[j];
                        out.append(ws.rel(f)).append(':').append(j + 1).append(j == k ? ": " : "- ").append(l)
                                .append('\n');
                    }
                    lastPrinted = to;
                    if (++hits[0] >= 150) {
                        return false;
                    }
                }
                return true;
            };
            if (Files.isRegularFile(base)) {
                visit.test(base);
            } else {
                walk(base, visit, Json.bool(a, "include_dependencies", false));
            }
            if (hits[0] == 0) {
                return note + "No matches for '" + pat + "'" + (inc.isEmpty() ? "" : " in " + inc)
                        + " (vendor/, node_modules/, caches and .env are not searched).";
            }
            out.append(hits[0] >= 150 ? "[... stopped at 150 matches; narrow the search]\n" : "")
                    .append("(").append(hits[0]).append(" matches in ").append(files[0]).append(" files)");
            return cut(out.toString(), MAX_RESULT_CHARS);
        }
    }

    // ---------------------------------------------------------------- read_file

    final class ReadFile implements Tool {
        public String name() {
            return "read_file";
        }

        public String description() {
            return "Read a text file of the project with line numbers. Long files are read in parts: use offset "
                    + "(first line, from 1) and limit (number of lines, default 400). Secret files such as .env "
                    + "show the setting names only.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[][] {
                {"path", "string", "File path relative to the project folder."},
                {"offset", "integer", "First line to read, from 1 (default 1)."},
                {"limit", "integer", "Number of lines to read (default 400, max 1500)."}}, "path");
        }

        public String label(Map<String, Object> a) {
            int off = Json.num(a, "offset", 1);
            return "Read " + Json.str(a, "path", "") + (off > 1 ? " from line " + off : "");
        }

        public String run(Map<String, Object> a) throws ToolError, IOException {
            String given = need(a, "path");
            Path f = ws.resolve(given);
            if (Files.isDirectory(f)) {
                throw new ToolError("'" + given + "' is a folder. Use list_dir.");
            }
            if (!Files.isRegularFile(f)) {
                throw new ToolError("'" + given + "' does not exist. Use glob to find the right path.");
            }
            String text = text(f);
            known.put(f, hash(text));
            if (Workspace.isSecretFile(f)) {
                StringBuilder b = new StringBuilder("(Secret file: values are hidden.)\n");
                for (String line : text.split("\r?\n")) {
                    String t = line.trim();
                    int eq = t.indexOf('=');
                    if (t.isEmpty() || t.startsWith("#") || eq < 0) {
                        b.append(t.startsWith("#") ? t : "").append('\n');
                    } else {
                        String v = t.substring(eq + 1).trim();
                        b.append(t, 0, eq).append(v.isEmpty() ? "=" : "=<set>").append('\n');
                    }
                }
                return cut(b.toString(), MAX_RESULT_CHARS);
            }
            String[] lines = text.split("\r?\n", -1);
            int total = lines.length;
            if (total > 0 && lines[total - 1].isEmpty()) {
                total--;
            }
            int off = Math.max(1, Json.num(a, "offset", 1));
            int lim = Math.max(1, Math.min(1500, Json.num(a, "limit", 400)));
            if (total == 0) {
                return "(" + ws.rel(f) + " is empty)";
            }
            if (off > total) {
                throw new ToolError("The file has only " + total + " lines; offset " + off + " is past the end.");
            }
            int end = Math.min(total, off + lim - 1);
            StringBuilder b = new StringBuilder();
            int width = String.valueOf(end).length();
            for (int k = off; k <= end; k++) {
                String l = lines[k - 1];
                if (l.length() > 2000) {
                    l = l.substring(0, 2000) + "[... line cut]";
                }
                b.append(String.format(Locale.ROOT, "%" + width + "d| ", k)).append(l).append('\n');
                if (b.length() > MAX_RESULT_CHARS) {
                    end = k;
                    break;
                }
            }
            b.append('(').append(ws.rel(f)).append(": lines ").append(off).append('-').append(end).append(" of ")
                    .append(total).append(end < total ? "; read more with offset " + (end + 1) : "").append(')');
            return b.toString();
        }
    }

    // ---------------------------------------------------------------- project_info

    final class ProjectInfoTool implements Tool {
        public String name() {
            return "project_info";
        }

        public String description() {
            return "Describe the project: type, framework and version, PHP version, packages, file counts.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[0][]);
        }

        public String label(Map<String, Object> a) {
            return "Look at the project type";
        }

        public String run(Map<String, Object> a) {
            return info.get().describe(ws.root());
        }
    }

    // ---------------------------------------------------------------- editor_state

    final class EditorTool implements Tool {
        public String name() {
            return "editor_state";
        }

        public String description() {
            return "The file open in the NetBeans editor now, the cursor line and column, and the selected text.";
        }

        public Map<String, Object> parameters() {
            return schema(new Object[0][]);
        }

        public String label(Map<String, Object> a) {
            return "Look at the editor";
        }

        public String run(Map<String, Object> a) {
            EditorState e = editor == null ? null : editor.get();
            if (e == null || e.file() == null) {
                return "No file is open in the editor.";
            }
            String sel = e.selection() == null ? "" : e.selection();
            return "File: " + e.file() + "\nCursor: line " + e.line() + ", column " + e.column()
                    + (sel.isEmpty() ? "\nNo selection." : "\nSelected text:\n" + cut(sel, 8000));
        }
    }
}
