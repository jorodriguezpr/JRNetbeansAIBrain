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

import java.nio.file.Path;

/**
 * What the tools need from the IDE. The NetBeans side edits files that are open in the editor
 * through the editor (so Undo works and unsaved typing is not lost) and refreshes the others.
 * {@link #NONE} is used in tests and outside NetBeans.
 */
public interface Ide {

    /** Text of the file when it is open in the editor (may include unsaved typing), else null. */
    String openText(Path file);

    /**
     * Replace the text of an open file through the editor as one undo step, then save it.
     * Returns false when the file is not open (the caller then writes the file on disk).
     */
    boolean replaceOpenText(Path file, String text);

    /** The file or folder changed on disk; let the IDE see it. */
    void refresh(Path fileOrFolder);

    /** Show the file in the editor at the line (1-based; 0 = keep position). */
    void open(Path file, int line);

    Ide NONE = new Ide() {
        public String openText(Path file) {
            return null;
        }

        public boolean replaceOpenText(Path file, String text) {
            return false;
        }

        public void refresh(Path fileOrFolder) {
        }

        public void open(Path file, int line) {
        }
    };
}
