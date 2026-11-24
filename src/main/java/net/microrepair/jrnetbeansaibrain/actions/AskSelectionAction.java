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
package net.microrepair.jrnetbeansaibrain.actions;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import net.microrepair.jrnetbeansaibrain.ui.ChatTopComponent;
import net.microrepair.jrnetbeansaibrain.ui.EditorBridge;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;

/** Editor popup: open the AI Brain chat with the selected code attached to the next question. */
@ActionID(category = "Edit", id = "net.microrepair.jrnetbeansaibrain.AskSelection")
@ActionRegistration(displayName = "Ask AI Brain about the Selection")
@ActionReferences({
    @ActionReference(path = "Editors/Popup", position = 4010),
    @ActionReference(path = "Menu/Tools/JRAIBrain", position = 310)
})
public final class AskSelectionAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        EditorBridge.State s = EditorBridge.state();
        ChatTopComponent c = ChatTopComponent.openChat();
        if (s != null) {
            c.askAbout(s.selection(), s.file());
        }
    }
}
