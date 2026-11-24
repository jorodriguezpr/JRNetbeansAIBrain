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
package net.microrepair.jrnetbeansaibrain.options;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import javax.swing.JComponent;
import org.netbeans.spi.options.OptionsPanelController;
import org.openide.util.HelpCtx;
import org.openide.util.Lookup;

/** Tools > Options > Advanced > AI Brain. */
@OptionsPanelController.SubRegistration(
        location = "Advanced",
        id = "JRAIBrain",
        displayName = "AI Brain",
        keywords = "ai brain ollama claude openai gemini copilot jrbtbrain provider model api key glm",
        keywordsCategory = "Advanced/JRAIBrain")
public final class BrainOptionsPanelController extends OptionsPanelController {

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private BrainOptionsPanel panel;

    private BrainOptionsPanel panel() {
        if (panel == null) {
            panel = new BrainOptionsPanel();
        }
        return panel;
    }

    @Override
    public void update() {
        panel().load();
    }

    @Override
    public void applyChanges() {
        panel().store();
    }

    @Override
    public void cancel() {
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean isChanged() {
        return true;
    }

    @Override
    public JComponent getComponent(Lookup masterLookup) {
        return panel();
    }

    @Override
    public HelpCtx getHelpCtx() {
        return HelpCtx.DEFAULT_HELP;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }
}
