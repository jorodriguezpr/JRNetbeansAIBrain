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
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Creates a new project before the agent builds it: runs the official installer for the chosen
 * template (composer create-project, Breeze, npm install), writes the NetBeans PHP project files,
 * optionally makes a first git commit (so every AI change can be undone), and builds the first
 * message for the agent from the developer's description.
 */
public final class Scaffold {

    /** Project templates. */
    public enum Template {
        LARAVEL("Laravel (Blade + JavaScript + Vite)"),
        LARAVEL_BREEZE("Laravel with login and registration (Breeze, Blade)"),
        PHP("Plain PHP (no framework)"),
        EMPTY("Empty folder (the AI chooses everything)");

        public final String label;

        Template(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What to create. */
    public static final class Spec {
        public Path parent;
        public String name;
        public Template template = Template.LARAVEL;
        public String description = "";
        public boolean git = true;

        public Path folder() {
            return parent.resolve(name);
        }
    }

    /** Progress events. */
    public interface Listener {
        void step(String label);

        void line(String text);
    }

    private final CommandRunner runner = new CommandRunner();

    public void stop() {
        runner.stop();
    }

    /** Check the spec before anything is created; returns an error message or null. */
    public static String validate(Spec s) {
        if (s.parent == null || !Files.isDirectory(s.parent)) {
            return "The parent folder does not exist.";
        }
        if (s.name == null || !s.name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            return "The project name may use letters, digits, '.', '_' and '-' (and must start with a letter or digit).";
        }
        Path f = s.folder();
        if (Files.exists(f)) {
            try (Stream<Path> st = Files.list(f)) {
                if (st.findAny().isPresent()) {
                    return "The folder " + f + " already exists and is not empty.";
                }
            } catch (IOException ex) {
                return "Cannot read " + f + ": " + ex.getMessage();
            }
        }
        if (s.description == null || s.description.trim().length() < 10) {
            return "Describe the application you want (a sentence or more).";
        }
        return null;
    }

    /** Create the project. Throws with a readable message when a step fails. */
    public void create(Spec s, Listener l) throws IOException {
        Path f = s.folder();
        runner.reset();
        switch (s.template) {
            case LARAVEL:
            case LARAVEL_BREEZE:
                if (Files.isDirectory(f)) {
                    Files.delete(f); // empty (validated): composer wants to create it
                }
                run(l, "Installing Laravel (composer create-project)",
                        "composer create-project laravel/laravel \"" + s.name + "\" --prefer-dist --no-interaction",
                        s.parent, 900);
                if (s.template == Template.LARAVEL_BREEZE) {
                    run(l, "Adding Laravel Breeze", "composer require laravel/breeze --dev --no-interaction", f, 600);
                    run(l, "Installing Breeze (Blade)", "php artisan breeze:install blade --no-interaction", f, 900);
                    run(l, "Creating the database tables", "php artisan migrate --no-interaction --force", f, 300);
                } else {
                    run(l, "Installing the JavaScript packages (npm install)", "npm install", f, 900);
                }
                break;
            default:
                Files.createDirectories(f);
                if (s.template == Template.PHP) {
                    Files.createDirectories(f.resolve("public"));
                }
                break;
        }
        if (s.template != Template.EMPTY) {
            writeNetBeansProject(f, s.name, "public");
        }
        if (s.git) {
            ensureGitignore(f);
            try {
                run(l, "Saving a first git commit (so every AI change can be undone)",
                        "git init -q && git add -A && git commit -q -m \"Project skeleton (" + s.template.name().toLowerCase()
                        + ")\"", f, 120);
            } catch (IOException ex) {
                l.line("git: " + ex.getMessage() + " (continuing without the first commit)");
            }
        }
    }

    static boolean isLaravel(Template t) {
        return t == Template.LARAVEL || t == Template.LARAVEL_BREEZE;
    }

    private void run(Listener l, String label, String cmd, Path dir, int timeout) throws IOException {
        l.step(label);
        l.line("> " + cmd);
        CommandRunner.Result r = runner.run(cmd, dir, timeout, l::line);
        if (r.stopped) {
            throw new IOException("Stopped.");
        }
        if (r.timedOut) {
            throw new IOException(label + ": timed out after " + timeout + " s.");
        }
        if (r.exit != 0) {
            String out = r.output.toString().trim();
            String tail = out.length() > 1500 ? out.substring(out.length() - 1500) : out;
            throw new IOException(label + " failed (exit code " + r.exit + "):\n" + tail);
        }
    }

    /** NetBeans PHP project files (nbproject/project.xml and project.properties). */
    public static void writeNetBeansProject(Path f, String name, String webRoot) throws IOException {
        Path nb = f.resolve("nbproject");
        if (Files.isRegularFile(nb.resolve("project.xml"))) {
            return;
        }
        Files.createDirectories(nb);
        String xmlName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        Files.writeString(nb.resolve("project.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://www.netbeans.org/ns/project/1\">\n"
                + "    <type>org.netbeans.modules.php.project</type>\n"
                + "    <configuration>\n"
                + "        <data xmlns=\"http://www.netbeans.org/ns/php-project/1\">\n"
                + "            <name>" + xmlName + "</name>\n"
                + "        </data>\n"
                + "    </configuration>\n"
                + "</project>\n", StandardCharsets.UTF_8);
        Files.writeString(nb.resolve("project.properties"), "include.path=${php.global.include.path}\n"
                + "php.version=" + phpVersionConstant() + "\n"
                + "source.encoding=UTF-8\n"
                + "src.dir=.\n"
                + "tags.asp=false\n"
                + "tags.short=false\n"
                + "web.root=" + webRoot + "\n", StandardCharsets.UTF_8);
    }

    private static String phpVersionConstant() {
        try {
            CommandRunner.Result r = new CommandRunner().run("php -r \"echo PHP_MAJOR_VERSION.PHP_MINOR_VERSION;\"",
                    Path.of(System.getProperty("user.home")), 20, null);
            String v = r.output.toString().trim();
            if (r.exit == 0 && v.matches("\\d{2}")) {
                return "PHP_" + v;
            }
        } catch (IOException ignore) {
            // fall through
        }
        return "PHP_83";
    }

    private static void ensureGitignore(Path f) throws IOException {
        Path gi = f.resolve(".gitignore");
        String text = Files.isRegularFile(gi) ? Files.readString(gi, StandardCharsets.UTF_8) : "";
        List<String> add = new ArrayList<>();
        for (String must : new String[] {"/nbproject/private/", "/vendor", "/node_modules", ".env"}) {
            if (!text.contains(must)) {
                add.add(must);
            }
        }
        if (!add.isEmpty()) {
            String sep = text.isEmpty() || text.endsWith("\n") ? "" : "\n";
            Files.writeString(gi, text + sep + String.join("\n", add) + "\n", StandardCharsets.UTF_8);
        }
    }

    /** The first message for the agent. */
    public static String kickoff(Spec s, ProjectInfo pi) {
        StringBuilder b = new StringBuilder();
        b.append("Build this new application in the project folder:\n\n").append(s.description.trim()).append("\n\n");
        switch (s.template) {
            case LARAVEL:
            case LARAVEL_BREEZE:
                b.append("The Laravel ").append(pi.frameworkVersion.isEmpty() ? "" : pi.frameworkVersion + " ")
                        .append("skeleton is already installed and working: composer packages, .env with APP_KEY, "
                        + "the SQLite database (database/database.sqlite) with the default tables, and the npm "
                        + "packages.");
                if (s.template == Template.LARAVEL_BREEZE) {
                    b.append(" Laravel Breeze (Blade) is installed: login, registration, profile and the layouts in "
                            + "resources/views/layouts (app.blade.php for logged-in pages, guest.blade.php) already "
                            + "exist. Put the new pages behind the auth middleware and use <x-app-layout>.");
                }
                b.append("\n\nWork like this:\n"
                        + "1. Look at the structure (list_dir, routes/web.php, resources/views, resources/js, "
                        + "vite.config.js) so you build on what is there.\n"
                        + "2. Call plan with a checklist of everything to build.\n"
                        + "3. Build it the Laravel way: migrations, models (with $fillable, relationships, casts), "
                        + "factories/seeders for demo data, Form Requests, resource controllers, named routes, a Blade "
                        + "layout with navigation and flash messages, Blade pages and components, JavaScript in "
                        + "resources/js imported from app.js, and styles (Tailwind when it is installed, else "
                        + "resources/css/app.css). Use php artisan make:* generators, then edit the generated files.\n"
                        + "4. Replace the default welcome page with the application's start page.\n"
                        + "5. Run php artisan migrate --no-interaction (and db:seed if you made seeders), npm run build, "
                        + "php artisan route:list, and php artisan test. Fix every error you see.\n"
                        + "6. Finish with a short summary: what you built (main files), and how to start it: "
                        + "php artisan serve, then open http://127.0.0.1:8000.\n");
                break;
            case PHP:
                b.append("This is a plain PHP project (no framework); the public/ folder is the web root. Use "
                        + "Composer only if it really helps (PSR-4 autoload in src/). Keep config and code outside "
                        + "public/, use PDO with prepared statements (SQLite in a data/ folder unless the description "
                        + "says otherwise), escape output with htmlspecialchars, and put JavaScript and CSS in "
                        + "public/assets. Call plan first, then build everything, run php -l on the files, and finish "
                        + "with how to start it: php -S 127.0.0.1:8000 -t public.\n");
                break;
            default:
                b.append("The folder is empty. Choose a stack that fits the description. For a Laravel app, install "
                        + "it first with run_command: composer create-project laravel/laravel . --prefer-dist "
                        + "--no-interaction (works because the folder is empty), then npm install. Call plan first, "
                        + "then build everything, check it, and finish with how to run it.\n");
                break;
        }
        b.append("\nWork until the whole application is built; do not stop after the plan.");
        return b.toString();
    }
}
