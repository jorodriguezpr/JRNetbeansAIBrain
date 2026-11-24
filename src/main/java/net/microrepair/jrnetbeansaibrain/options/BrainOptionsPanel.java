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

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import net.microrepair.jrnetbeansaibrain.Settings;
import net.microrepair.jrnetbeansaibrain.ui.ProvidersPanel;

/** Tools &gt; Options &gt; Advanced &gt; AI Brain: the AI providers and the general limits. */
final class BrainOptionsPanel extends JPanel {

    private final ProvidersPanel providers = new ProvidersPanel();
    private final JCheckBox think = new JCheckBox("Let the model think before it answers (Ollama; better results, a little slower)");
    private final JCheckBox showThinking = new JCheckBox("Show the model's thinking in the chat");
    private final JSpinner maxSteps = new JSpinner(new SpinnerNumberModel(40, 3, 200, 1));
    private final JSpinner temperature = new JSpinner(new SpinnerNumberModel(0.3, 0.0, 1.5, 0.1));
    private final JSpinner maxTokens = new JSpinner(new SpinnerNumberModel(16000, 1024, 128000, 1024));

    BrainOptionsPanel() {
        super(new BorderLayout(0, 10));
        add(providers, BorderLayout.CENTER);
        JPanel general = new JPanel(new GridBagLayout());
        general.setBorder(BorderFactory.createTitledBorder("General"));
        int y = 0;
        y = full(general, y, think);
        y = full(general, y, showThinking);
        y = row(general, y, "Max tool steps per message:", maxSteps);
        y = row(general, y, "Temperature (lower = more exact):", temperature);
        row(general, y, "Max answer tokens (Claude):", maxTokens);
        add(general, BorderLayout.SOUTH);
    }

    private static int row(JPanel p, int y, String label, JComponent c) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = y;
        l.anchor = GridBagConstraints.WEST;
        l.insets = new Insets(3, 4, 2, 8);
        p.add(new JLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = y;
        f.weightx = 1;
        f.anchor = GridBagConstraints.WEST;
        f.insets = new Insets(3, 0, 2, 4);
        p.add(c, f);
        return y + 1;
    }

    private static int full(JPanel p, int y, JComponent c) {
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 0;
        f.gridy = y;
        f.gridwidth = 2;
        f.anchor = GridBagConstraints.WEST;
        f.insets = new Insets(3, 4, 2, 4);
        p.add(c, f);
        return y + 1;
    }

    void load() {
        providers.load();
        think.setSelected(Settings.think());
        showThinking.setSelected(Settings.showThinking());
        maxSteps.setValue(Settings.maxSteps());
        temperature.setValue(Settings.temperature());
        maxTokens.setValue(Settings.maxOutputTokens());
    }

    void store() {
        providers.store();
        Preferences p = Settings.prefs();
        p.putBoolean("think", think.isSelected());
        p.putBoolean("showThinking", showThinking.isSelected());
        p.putInt("maxSteps", (Integer) maxSteps.getValue());
        p.putDouble("temperature", ((Number) temperature.getValue()).doubleValue());
        p.putInt("maxOutputTokens", (Integer) maxTokens.getValue());
    }
}
