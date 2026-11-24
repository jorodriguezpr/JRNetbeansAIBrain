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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;

/**
 * The project folder the agent works in. Every path a tool receives goes through
 * {@link #resolve(String)}, which refuses anything outside the folder (also through links).
 */
public final class Workspace {

    /** Folders that tools skip when walking (dependencies, caches, build output). */
    public static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "vendor", ".idea", ".vscode", ".venv", "venv",
            "__pycache__", "target", "dist", "build", ".gradle", ".next", ".nuxt", ".cache");

    /** Paths (relative, with /) that are skipped as well. */
    public static final Set<String> SKIPPED_PATHS = Set.of(
            "storage/framework", "storage/logs", "storage/debugbar", "bootstrap/cache", "public/build",
            "public/hot", "nbproject/private");

    private final Path root;

    public Workspace(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a folder: " + root);
        }
        this.root = root.toRealPath();
    }

    public Path root() {
        return root;
    }

    /** Resolve a tool path ("" or "." = the project folder) and make sure it stays inside. */
    public Path resolve(String path) throws ToolError {
        String p = path == null ? "" : path.trim();
        if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
            p = p.substring(1, p.length() - 1);
        }
        p = p.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.isEmpty() || p.equals(".") || p.equals("/")) {
            return root;
        }
        for (String seg : p.split("/")) {
            if (!seg.isEmpty() && !seg.equals(seg.strip())) {
                throw new ToolError("The path '" + path + "' has a space at the start or end of a folder or file "
                        + "name ('" + seg + "'). Remove the space.");
            }
            if (seg.matches(".*[<>\"|?*\\x00-\\x1f].*")) {
                throw new ToolError("The path '" + path + "' contains a character that is not allowed in file names.");
            }
        }
        Path r;
        try {
            Path given = Paths.get(p);
            r = given.isAbsolute() ? given.normalize() : root.resolve(given).normalize();
        } catch (InvalidPathException ex) {
            throw new ToolError("Invalid path '" + path + "'.");
        }
        Path check = r;
        try {
            if (Files.exists(r, LinkOption.NOFOLLOW_LINKS)) {
                check = r.toRealPath();
            }
        } catch (IOException ex) {
            check = r;
        }
        if (!check.startsWith(root)) {
            throw new ToolError("'" + path + "' is outside the project folder " + root
                    + ". Use paths relative to the project folder.");
        }
        return r;
    }

    /** Project-relative path with forward slashes ("." for the folder itself). */
    public String rel(Path p) {
        Path a = p.toAbsolutePath().normalize();
        if (a.equals(root)) {
            return ".";
        }
        if (!a.startsWith(root)) {
            return a.toString();
        }
        return root.relativize(a).toString().replace('\\', '/');
    }

    /** True for folders that walking tools skip. */
    public boolean skipped(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (SKIPPED_DIRS.contains(name)) {
            return true;
        }
        return SKIPPED_PATHS.contains(rel(dir));
    }

    /** Files whose content must never be sent to the AI as-is. */
    public static boolean isSecretFile(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.equals(".env") || (n.startsWith(".env.") && !n.equals(".env.example") && !n.equals(".env.dist")
                && !n.equals(".env.sample") && !n.equals(".env.testing.example"))) {
            return true;
        }
        return n.endsWith(".pem") || n.endsWith(".key") || n.endsWith(".p12") || n.endsWith(".pfx")
                || n.equals("id_rsa") || n.equals("id_ed25519") || n.equals("auth.json")
                || n.equals(".npmrc") || n.equals(".pgpass") || n.equals("credentials.json");
    }

    /** A tool failure that is reported back to the model (not a crash). */
    public static final class ToolError extends Exception {
        public ToolError(String message) {
            super(message);
        }
    }
}
