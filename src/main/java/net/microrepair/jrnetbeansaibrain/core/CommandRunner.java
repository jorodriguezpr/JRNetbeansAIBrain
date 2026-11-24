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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs a shell command in a folder (cmd.exe on Windows, sh elsewhere) with no input, a timeout,
 * live output lines, and a stop that kills the whole process tree.
 */
public final class CommandRunner {

    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    /** Result of one command. */
    public static final class Result {
        public int exit = -1;
        public boolean timedOut;
        public boolean stopped;
        public final StringBuilder output = new StringBuilder();
        public long millis;
    }

    private volatile Process current;
    private volatile boolean stopped;

    /** Kill the running command (and its children). */
    public void stop() {
        stopped = true;
        Process p = current;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    public void reset() {
        stopped = false;
    }

    public Result run(String command, Path dir, int timeoutSeconds, Consumer<String> lines) throws IOException {
        List<String> cmd = new ArrayList<>();
        if (WINDOWS) {
            cmd.add("cmd.exe");
            cmd.add("/d");
            cmd.add("/s");
            cmd.add("/c");
            cmd.add(command);
        } else {
            cmd.add("/bin/sh");
            cmd.add("-c");
            cmd.add(command);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("COMPOSER_NO_INTERACTION", "1");
        env.put("NO_COLOR", "1");
        env.put("FORCE_COLOR", "0");
        env.put("npm_config_yes", "true");
        env.put("npm_config_fund", "false");
        env.put("npm_config_audit", "false");
        env.put("CI", "1");
        if (WINDOWS) {
            // Windows' own find/sort/where must win over Git's Unix tools that may be earlier on the PATH
            String sys = System.getenv().getOrDefault("SystemRoot", "C:\\Windows") + "\\System32";
            String key = env.containsKey("Path") ? "Path" : "PATH";
            env.put(key, sys + ";" + env.getOrDefault(key, ""));
        }
        Result r = new Result();
        long start = System.currentTimeMillis();
        Process p = pb.start();
        current = p;
        p.getOutputStream().close();
        String enc = System.getProperty(WINDOWS ? "sun.stdout.encoding" : "native.encoding");
        Charset cs;
        try {
            cs = enc == null ? Charset.defaultCharset() : Charset.forName(enc);
        } catch (RuntimeException ex) {
            cs = Charset.defaultCharset();
        }
        final Charset fcs = cs;
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), fcs))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String clean = line.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
                    synchronized (r.output) {
                        if (r.output.length() < 400_000) {
                            r.output.append(clean).append('\n');
                        }
                    }
                    if (lines != null) {
                        lines.accept(clean);
                    }
                }
            } catch (IOException ignore) {
                // process ended or was killed
            }
        }, "JRNetbeansAIBrain-command");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean done = p.waitFor(Math.max(5, timeoutSeconds), TimeUnit.SECONDS);
            if (!done) {
                r.timedOut = true;
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            reader.join(3000);
            r.exit = p.isAlive() ? -1 : p.exitValue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            stop();
        } finally {
            current = null;
        }
        r.stopped = stopped;
        r.millis = System.currentTimeMillis() - start;
        return r;
    }
}
