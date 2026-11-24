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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Detects what kind of project a folder is (Laravel, plain PHP, WordPress, Symfony, Node, Maven...)
 * and its main tools, from marker files only. Never reads secret values: from {@code .env} only the
 * database driver name is taken.
 */
public final class ProjectInfo {

    public String type = "Unknown";
    public String framework = "";
    public String frameworkVersion = "";
    public String php = "";
    public String netbeansType = "";
    public final List<String> features = new ArrayList<>();
    public final Map<String, Integer> counts = new LinkedHashMap<>();
    public String dbDriver = "";
    public boolean vendorInstalled;
    public boolean nodeModulesInstalled;
    public String memory = "";
    public String memoryFile = "";

    public boolean isLaravel() {
        return "Laravel".equals(framework);
    }

    /** Detect from the project folder. {@code nbType} is the NetBeans project type, if known. */
    public static ProjectInfo detect(Path root, String nbType) {
        ProjectInfo pi = new ProjectInfo();
        pi.netbeansType = nbType == null ? "" : nbType;
        Map<String, Object> composer = readJson(root.resolve("composer.json"));
        Map<String, Object> pkg = readJson(root.resolve("package.json"));
        Map<String, Object> require = merged(composer, "require", "require-dev");
        Map<String, Object> npm = merged(pkg, "dependencies", "devDependencies");

        if (require.containsKey("laravel/framework") || Files.isRegularFile(root.resolve("artisan"))) {
            pi.type = "PHP";
            pi.framework = "Laravel";
            pi.frameworkVersion = lockedVersion(root, "laravel/framework",
                    Json.str(require, "laravel/framework", ""));
        } else if (require.containsKey("symfony/framework-bundle")) {
            pi.type = "PHP";
            pi.framework = "Symfony";
            pi.frameworkVersion = lockedVersion(root, "symfony/framework-bundle",
                    Json.str(require, "symfony/framework-bundle", ""));
        } else if (Files.isRegularFile(root.resolve("wp-config.php"))
                || Files.isRegularFile(root.resolve("wp-config-sample.php"))) {
            pi.type = "PHP";
            pi.framework = "WordPress";
        } else if (composer != null || hasFile(root, ".php")) {
            pi.type = "PHP";
        } else if (Files.isRegularFile(root.resolve("pom.xml"))) {
            pi.type = "Java (Maven)";
        } else if (Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            pi.type = "Java (Gradle)";
        } else if (pkg != null) {
            pi.type = "JavaScript/Node";
        } else if (Files.isRegularFile(root.resolve("pyproject.toml"))
                || Files.isRegularFile(root.resolve("requirements.txt"))) {
            pi.type = "Python";
        }
        pi.php = Json.str(require, "php", "");

        String[][] composerFeatures = {
            {"livewire/livewire", "Livewire"}, {"inertiajs/inertia-laravel", "Inertia"},
            {"laravel/breeze", "Breeze"}, {"laravel/jetstream", "Jetstream"}, {"laravel/sanctum", "Sanctum"},
            {"laravel/fortify", "Fortify"}, {"laravel/passport", "Passport"}, {"filament/filament", "Filament"},
            {"spatie/laravel-permission", "spatie/laravel-permission"}, {"pestphp/pest", "Pest"},
            {"phpunit/phpunit", "PHPUnit"}, {"laravel/pint", "Pint"}, {"laravel/sail", "Sail"},
            {"barryvdh/laravel-debugbar", "Debugbar"}, {"doctrine/orm", "Doctrine"}, {"twig/twig", "Twig"}};
        for (String[] f : composerFeatures) {
            if (require.containsKey(f[0])) {
                pi.features.add(f[1]);
            }
        }
        String[][] npmFeatures = {
            {"vite", "Vite"}, {"laravel-mix", "Laravel Mix"}, {"tailwindcss", "Tailwind CSS"},
            {"bootstrap", "Bootstrap"}, {"alpinejs", "Alpine.js"}, {"vue", "Vue"}, {"react", "React"},
            {"jquery", "jQuery"}, {"axios", "axios"}, {"typescript", "TypeScript"}, {"sass", "Sass"},
            {"livewire", "Livewire JS"}};
        for (String[] f : npmFeatures) {
            if (npm.containsKey(f[0])) {
                pi.features.add(f[1] + " " + Json.str(npm, f[0], "").replaceAll("[\\^~]", ""));
            }
        }

        if (pi.isLaravel()) {
            pi.counts.put("Blade views", countFiles(root.resolve("resources/views"), ".blade.php"));
            pi.counts.put("controllers", countFiles(root.resolve("app/Http/Controllers"), ".php"));
            pi.counts.put("models", countFiles(root.resolve("app/Models"), ".php"));
            pi.counts.put("migrations", countFiles(root.resolve("database/migrations"), ".php"));
            pi.counts.put("route files", countFiles(root.resolve("routes"), ".php"));
            pi.counts.put("JS files", countFiles(root.resolve("resources/js"), ".js")
                    + countFiles(root.resolve("resources/js"), ".ts"));
            pi.counts.put("tests", countFiles(root.resolve("tests"), ".php"));
            pi.dbDriver = envValue(root.resolve(".env"), "DB_CONNECTION");
            if (pi.dbDriver.isEmpty()) {
                pi.dbDriver = envValue(root.resolve(".env.example"), "DB_CONNECTION");
            }
        }
        pi.vendorInstalled = Files.isDirectory(root.resolve("vendor"));
        pi.nodeModulesInstalled = Files.isDirectory(root.resolve("node_modules"));

        for (String name : new String[] {"JRBRAIN.md", "AGENTS.md", "CLAUDE.md"}) {
            Path m = root.resolve(name);
            if (Files.isRegularFile(m)) {
                try {
                    String t = Files.readString(m, StandardCharsets.UTF_8);
                    pi.memory = t.length() > 12000 ? t.substring(0, 12000) + "\n[... cut]" : t;
                    pi.memoryFile = name;
                } catch (IOException ignore) {
                    // unreadable memory file: carry on without it
                }
                break;
            }
        }
        return pi;
    }

    /** Short description for the chat header, e.g. "Laravel 11.31.0 · PHP ^8.2 · Vite, Tailwind". */
    public String headline() {
        StringBuilder b = new StringBuilder();
        b.append(framework.isEmpty() ? type : framework);
        if (!frameworkVersion.isEmpty()) {
            b.append(' ').append(frameworkVersion);
        }
        if (!php.isEmpty() && type.equals("PHP")) {
            b.append(" · PHP ").append(php);
        }
        if (!features.isEmpty()) {
            b.append(" · ");
            List<String> shortNames = new ArrayList<>();
            for (String f : features) {
                shortNames.add(f.replaceAll(" [0-9.x*|<>= -]+$", ""));
                if (shortNames.size() == 5) {
                    break;
                }
            }
            b.append(String.join(", ", shortNames));
        }
        return b.toString();
    }

    /** Full description for the model. */
    public String describe(Path root) {
        StringBuilder b = new StringBuilder();
        b.append("Project folder: ").append(root).append('\n');
        b.append("Type: ").append(type);
        if (!framework.isEmpty()) {
            b.append(", framework ").append(framework);
            if (!frameworkVersion.isEmpty()) {
                b.append(' ').append(frameworkVersion);
            }
        }
        b.append('\n');
        if (!netbeansType.isEmpty()) {
            b.append("NetBeans project type: ").append(netbeansType).append('\n');
        }
        if (!php.isEmpty()) {
            b.append("PHP required: ").append(php).append('\n');
        }
        if (!features.isEmpty()) {
            b.append("Packages: ").append(String.join(", ", features)).append('\n');
        }
        if (!counts.isEmpty()) {
            List<String> c = new ArrayList<>();
            counts.forEach((k, v) -> c.add(v + " " + k));
            b.append("Files: ").append(String.join(", ", c)).append('\n');
        }
        if (!dbDriver.isEmpty()) {
            b.append("Database driver: ").append(dbDriver).append('\n');
        }
        b.append("composer vendor/ installed: ").append(vendorInstalled ? "yes" : "no")
                .append(", node_modules/ installed: ").append(nodeModulesInstalled ? "yes" : "no").append('\n');
        return b.toString();
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(Path p) {
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            Object o = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merged(Map<String, Object> json, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (json == null) {
            return m;
        }
        for (String k : keys) {
            Object o = json.get(k);
            if (o instanceof Map) {
                m.putAll((Map<String, Object>) o);
            }
        }
        return m;
    }

    /** Exact version from composer.lock when present, else the constraint from composer.json. */
    @SuppressWarnings("unchecked")
    private static String lockedVersion(Path root, String pkg, String constraint) {
        Map<String, Object> lock = readJson(root.resolve("composer.lock"));
        if (lock != null && lock.get("packages") instanceof List) {
            for (Object o : (List<Object>) lock.get("packages")) {
                if (o instanceof Map && pkg.equals(((Map<String, Object>) o).get("name"))) {
                    return Json.str((Map<String, Object>) o, "version", constraint).replaceFirst("^v", "");
                }
            }
        }
        return constraint;
    }

    private static boolean hasFile(Path root, String ext) {
        try (Stream<Path> s = Files.list(root)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(ext));
        } catch (IOException ex) {
            return false;
        }
    }

    private static int countFiles(Path dir, String ext) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir, 8)) {
            return (int) s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(ext)).count();
        } catch (IOException | RuntimeException ex) {
            return 0;
        }
    }

    /** One value from a dotenv file (only used for non-secret keys such as DB_CONNECTION). */
    static String envValue(Path env, String key) {
        if (!Files.isRegularFile(env)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith(key + "=")) {
                    String v = t.substring(key.length() + 1).trim();
                    if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                            || v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    return v.matches("[A-Za-z0-9_.-]{1,40}") ? v : "";
                }
            }
        } catch (IOException | RuntimeException ex) {
            return "";
        }
        return "";
    }
}
