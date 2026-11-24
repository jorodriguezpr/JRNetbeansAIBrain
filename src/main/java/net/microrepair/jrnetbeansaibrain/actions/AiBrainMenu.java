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
import net.microrepair.jrnetbeansaibrain.core.WorkMode;
import net.microrepair.jrnetbeansaibrain.ui.ChatTopComponent;
import net.microrepair.jrnetbeansaibrain.ui.ProvidersPanel;
import org.netbeans.api.options.OptionsDisplayer;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;

/** The Tools &gt; AI Brain menu (the folder name comes from layer.xml). */
public final class AiBrainMenu {

    private AiBrainMenu() {
    }

    @ActionID(category = "Tools", id = "net.microrepair.jrnetbeansaibrain.menu.Open")
    @ActionRegistration(displayName = "Open AI Brain Chat")
    @ActionReference(path = "Menu/Tools/JRAIBrain", position = 100)
    public static final class Open implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            ChatTopComponent.openChat();
        }
    }

    @ActionID(category = "Tools", id = "net.microrepair.jrnetbeansaibrain.menu.Chat")
    @ActionRegistration(displayName = "Chat Mode (answers, no changes)")
    @ActionReference(path = "Menu/Tools/JRAIBrain", position = 200, separatorBefore = 150)
    public static final class Chat implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            ChatTopComponent.openChat().setWorkMode(WorkMode.CHAT);
        }
    }

    @ActionID(category = "Tools", id = "net.microrepair.jrnetbeansaibrain.menu.Plan")
    @ActionRegistration(displayName = "Plan Mode (plan first, then approve)")
    @ActionReference(path = "Menu/Tools/JRAIBrain", position = 210)
    public static final class Plan implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            ChatTopComponent.openChat().setWorkMode(WorkMode.PLAN);
        }
    }

    @ActionID(category = "Tools", id = "net.microrepair.jrnetbeansaibrain.menu.Agent")
    @ActionRegistration(displayName = "Agent Mode (does the work)")
    @ActionReference(path = "Menu/Tools/JRAIBrain", position = 220)
    public static final class Agent implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            ChatTopComponent.openChat().setWorkMode(WorkMode.AGENT);
        }
    }

    @ActionID(category = "Tools", id = "net.microrepair.jrnetbeansaibrain.menu.NewProject")
    @ActionRegistration(displayName = "New Project with AI...")
    @ActionReference(path = "Menu/Tools/JRAIBrain", position = 300, separatorBefore = 250)
    public static final class NewProject implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            ChatTopComponent.openChat().startNewProject();
        }
    }

    @ActionID(category = "Tools", id = "net.microrepair.jrnetbeansaibrain.menu.Providers")
    @ActionRegistration(displayName = "AI Providers...")
    @ActionReference(path = "Menu/Tools/JRAIBrain", position = 400, separatorBefore = 350)
    public static final class Providers implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            if (ProvidersPanel.showDialog()) {
                org.openide.windows.TopComponent tc = org.openide.windows.WindowManager.getDefault()
                        .findTopComponent("JRAIBrainChat");
                if (tc instanceof ChatTopComponent && tc.isOpened()) {
                    ((ChatTopComponent) tc).providersChanged();
                }
            }
        }
    }

    @ActionID(category = "Tools", id = "net.microrepair.jrnetbeansaibrain.menu.Settings")
    @ActionRegistration(displayName = "Settings...")
    @ActionReference(path = "Menu/Tools/JRAIBrain", position = 410)
    public static final class SettingsAction implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            OptionsDisplayer.getDefault().open("Advanced/JRAIBrain");
        }
    }
}
