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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a change (file edit, delete, command) may happen: by the permission mode, by
 * the developer's earlier "accept all" choices in this chat, or by asking the developer.
 * Dangerous commands are always asked, in every mode.
 */
public final class Gate {

    /** Permission modes, from safest to most automatic. */
    public enum Mode {
        READ_ONLY("Read only (plan, no changes)"),
        ASK("Ask before every change"),
        AUTO_EDIT("Accept file edits, ask for commands"),
        FULL_AUTO("Accept edits and commands (dangerous ones still ask)");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        public static Mode parse(String s, Mode def) {
            try {
                return s == null ? def : Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return def;
            }
        }
    }

    public enum Kind { EDIT, CREATE, DELETE, COMMAND }

    /** A change waiting for a decision. */
    public static final class Change {
        public final Kind kind;
        public final String path;
        public final String before;
        public final String after;
        public final String command;
        public final String why;
        public final boolean dangerous;

        public Change(Kind kind, String path, String before, String after, String command, String why,
                boolean dangerous) {
            this.kind = kind;
            this.path = path;
            this.before = before;
            this.after = after;
            this.command = command;
            this.why = why == null ? "" : why;
            this.dangerous = dangerous;
        }

        public String title() {
            switch (kind) {
                case CREATE: return "Create " + path;
                case DELETE: return "Delete " + path;
                case COMMAND: return "Run: " + command;
                default: return "Edit " + path;
            }
        }
    }

    /** The developer's answer. */
    public static final class Decision {
        public final boolean allowed;
        /** Accept all changes of this kind for the rest of this chat. */
        public final boolean always;
        public final String note;

        public Decision(boolean allowed, boolean always, String note) {
            this.allowed = allowed;
            this.always = always;
            this.note = note == null ? "" : note.trim();
        }
    }

    /** Asks the developer (the NetBeans side shows a dialog). Called on the agent's thread. */
    public interface Approver {
        Decision ask(Change c);
    }

    private static final Pattern DANGEROUS = Pattern.compile(String.join("|",
            "\\brm\\s+-[a-z]*[rf]", "\\brmdir\\b", "\\brd\\s+/s", "\\bdel\\b", "\\berase\\b", "\\bformat\\b",
            "remove-item", "\\bgit\\s+(reset\\s+--hard|clean\\s+-[a-z]*f|push\\b|checkout\\s+--|restore\\b|rebase\\b|branch\\s+-D)",
            "migrate:(fresh|reset|refresh|rollback)", "db:wipe", "\\bshutdown\\b", "\\breg\\s+(delete|add)",
            "\\bcurl\\b.*\\|\\s*(sh|bash|powershell|iex)", "\\biwr\\b.*\\|\\s*iex", "\\bsc\\s+(delete|stop)",
            "\\btaskkill\\b", "\\bnet\\s+(user|stop)", "--force\\b", "\\bmkfs", "\\bdd\\s+if=", "\\bchmod\\s+-R",
            "\\bsetx\\b", "\\bnpm\\s+publish\\b", "\\bcomposer\\s+global\\b", "\\bnpm\\s+(i|install)\\s+-g\\b"),
            Pattern.CASE_INSENSITIVE);

    public static boolean isDangerous(String command) {
        return command != null && DANGEROUS.matcher(command).find();
    }

    private volatile Mode mode;
    private final Approver approver;
    private volatile boolean alwaysEdits;
    private volatile boolean alwaysCommands;

    public Gate(Mode mode, Approver approver) {
        this.mode = mode;
        this.approver = approver;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode m) {
        mode = m;
    }

    public boolean readOnly() {
        return mode == Mode.READ_ONLY;
    }

    public Decision check(Change c) {
        if (mode == Mode.READ_ONLY) {
            return new Decision(false, false, "Read-only mode is on: describe the change instead of making it.");
        }
        boolean isCommand = c.kind == Kind.COMMAND;
        boolean auto;
        if (c.dangerous || c.kind == Kind.DELETE && mode != Mode.FULL_AUTO) {
            auto = false;
        } else if (isCommand) {
            auto = mode == Mode.FULL_AUTO || alwaysCommands;
        } else {
            auto = mode == Mode.AUTO_EDIT || mode == Mode.FULL_AUTO || alwaysEdits;
        }
        if (auto) {
            return new Decision(true, false, "");
        }
        if (approver == null) {
            return new Decision(false, false, "No one is there to approve this change.");
        }
        Decision d = approver.ask(c);
        if (d.allowed && d.always && !c.dangerous) {
            if (isCommand) {
                alwaysCommands = true;
            } else {
                alwaysEdits = true;
            }
        }
        return d;
    }
}
