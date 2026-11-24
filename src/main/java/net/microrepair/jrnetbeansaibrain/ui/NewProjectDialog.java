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
package net.microrepair.jrnetbeansaibrain.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import net.microrepair.jrnetbeansaibrain.Settings;
import net.microrepair.jrnetbeansaibrain.core.Scaffold;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;

/** "New project with AI Brain": folder, name, template and the description of the application. */
final class NewProjectDialog {

    /** The spec plus whether the AI may work without asking. */
    static final class Result {
        Scaffold.Spec spec;
        boolean auto;
    }

    private NewProjectDialog() {
    }

    static Result show(Path suggestedParent) {
        JPanel p = new JPanel(new GridBagLayout());
        JTextField parent = new JTextField(Settings.prefs().get("lastParent",
                suggestedParent != null ? suggestedParent.toString() : System.getProperty("user.home")), 36);
        JButton browse = new JButton("Browse...");
        JTextField name = new JTextField("my-app", 20);
        JComboBox<Scaffold.Template> template = new JComboBox<>(Scaffold.Template.values());
        JTextArea desc = new JTextArea(9, 60);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.setText(Settings.prefs().get("lastDescription", ""));
        JCheckBox auto = new JCheckBox("Let the AI create files and run commands without asking (dangerous commands "
                + "still ask)", true);
        JCheckBox git = new JCheckBox("Create a git repository with a first commit (lets you undo the AI's changes)",
                true);

        JPanel parentRow = new JPanel(new BorderLayout(4, 0));
        parentRow.add(parent, BorderLayout.CENTER);
        parentRow.add(browse, BorderLayout.EAST);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(new File(parent.getText()));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(p) == JFileChooser.APPROVE_OPTION) {
                parent.setText(fc.getSelectedFile().getPath());
            }
        });
        int y = 0;
        y = row(p, y, "Create in folder:", parentRow);
        y = row(p, y, "Project name:", name);
        y = row(p, y, "Start from:", template);
        JLabel dl = new JLabel("<html>Describe the application: what it is for, its pages, the data it keeps, who uses it, "
                + "anything special (login, search, charts, JavaScript behaviour...).</html>");
        dl.setPreferredSize(new Dimension(560, 36));
        y = full(p, y, dl);
        JScrollPane sp = new JScrollPane(desc);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = y++;
        g.gridwidth = 2;
        g.fill = GridBagConstraints.BOTH;
        g.weightx = 1;
        g.weighty = 1;
        g.insets = new Insets(2, 4, 6, 4);
        p.add(sp, g);
        y = full(p, y, auto);
        full(p, y, git);

        while (true) {
            DialogDescriptor dd = new DialogDescriptor(p, "New Project with AI Brain");
            Object r = DialogDisplayer.getDefault().notify(dd);
            if (r != DialogDescriptor.OK_OPTION) {
                return null;
            }
            Scaffold.Spec s = new Scaffold.Spec();
            try {
                s.parent = Path.of(parent.getText().trim());
            } catch (RuntimeException ex) {
                s.parent = null;
            }
            s.name = name.getText().trim();
            s.template = (Scaffold.Template) template.getSelectedItem();
            s.description = desc.getText().trim();
            s.git = git.isSelected();
            String err = Scaffold.validate(s);
            if (err != null) {
                DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(err, NotifyDescriptor.WARNING_MESSAGE));
                continue;
            }
            Settings.prefs().put("lastParent", s.parent.toString());
            Settings.prefs().put("lastDescription", s.description);
            Result res = new Result();
            res.spec = s;
            res.auto = auto.isSelected();
            return res;
        }
    }

    private static int row(JPanel p, int y, String label, JComponent c) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = y;
        l.anchor = GridBagConstraints.WEST;
        l.insets = new Insets(4, 4, 2, 8);
        p.add(new JLabel(label), l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = y;
        f.weightx = 1;
        f.fill = c instanceof JComboBox ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        f.anchor = GridBagConstraints.WEST;
        f.insets = new Insets(4, 0, 2, 4);
        p.add(c, f);
        return y + 1;
    }

    private static int full(JPanel p, int y, JComponent c) {
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 0;
        f.gridy = y;
        f.gridwidth = 2;
        f.anchor = GridBagConstraints.WEST;
        f.insets = new Insets(4, 4, 2, 4);
        p.add(c, f);
        return y + 1;
    }
}
