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

/**
 * What the AI does with a message, chosen explicitly by the developer:
 * Chat answers (reads the project, changes nothing), Plan explores and writes a plan to approve,
 * Agent does the work (changes files and runs commands, within the permission mode).
 */
public enum WorkMode {
    CHAT("Chat", "Answers questions; reads the project, changes nothing"),
    PLAN("Plan", "Explores and writes a step-by-step plan for you to approve; changes nothing"),
    AGENT("Agent", "Does the work: creates and edits files and runs commands (per the permission list)");

    public final String label;
    public final String tip;

    WorkMode(String label, String tip) {
        this.label = label;
        this.tip = tip;
    }

    /** The permission mode the tools really get: read only unless this is Agent mode. */
    public Gate.Mode effective(Gate.Mode permission) {
        return this == AGENT ? (permission == null || permission == Gate.Mode.READ_ONLY ? Gate.Mode.ASK : permission)
                : Gate.Mode.READ_ONLY;
    }

    @Override
    public String toString() {
        return label;
    }

    public static WorkMode parse(String s, WorkMode def) {
        try {
            return s == null ? def : WorkMode.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }
}
