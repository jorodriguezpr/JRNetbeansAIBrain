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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import net.microrepair.jrnetbeansaibrain.core.Diff;
import net.microrepair.jrnetbeansaibrain.core.Gate;
import net.microrepair.jrnetbeansaibrain.core.Markdown;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;

/** "Accept / Accept all / Reject" dialog with a coloured diff (or the command line). */
final class ApprovalDialog {

    private ApprovalDialog() {
    }

    /** Ask on the Swing thread and wait for the answer (called from the agent's thread). */
    static Gate.Decision ask(Gate.Change c) {
        AtomicReference<Gate.Decision> ref = new AtomicReference<>(new Gate.Decision(false, false, ""));
        Runnable r = () -> ref.set(show(c));
        if (EventQueue.isDispatchThread()) {
            r.run();
        } else {
            try {
                EventQueue.invokeAndWait(r);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException ex) {
                return new Gate.Decision(false, false, "");
            }
        }
        return ref.get();
    }

    private static Gate.Decision show(Gate.Change c) {
        Color bg = UIManager.getColor("TextArea.background");
        boolean dark = bg != null && (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        String head;
        String body;
        switch (c.kind) {
            case COMMAND:
                head = "The AI wants to run this command in the project folder:";
                body = "<pre style='font-size:13pt'>" + Markdown.esc(c.command) + "</pre>"
                        + (c.dangerous ? "<p style='color:" + (dark ? "#ff8a80" : "#b00020") + "'><b>Careful:</b> this "
                        + "command can delete or overwrite data, or change things outside this project.</p>" : "");
                break;
            case DELETE:
                head = "The AI wants to DELETE " + c.path + ":";
                body = Diff.of(c.before, "").html(100000, dark);
                break;
            case CREATE:
                head = "The AI wants to create " + c.path + ":";
                body = Diff.of("", c.after).html(100000, dark);
                break;
            default: {
                Diff d = Diff.of(c.before, c.after);
                head = c.before.isEmpty() && c.after.isEmpty() ? "The AI wants to: " + c.path
                        : "The AI wants to change " + c.path + " (+" + d.added + " -" + d.removed + " lines):";
                body = c.before.isEmpty() && c.after.isEmpty() ? "<p>" + Markdown.esc(c.path) + "</p>" : d.html(3, dark);
                if (c.dangerous) {
                    body = "<p style='color:" + (dark ? "#ff8a80" : "#b00020") + "'><b>This is a secret/settings file "
                            + "(for example .env).</b></p>" + body;
                }
                break;
            }
        }
        JLabel title = new JLabel(head);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        p.add(title, BorderLayout.NORTH);
        JEditorPane view = new JEditorPane("text/html", "<html><body style='font-family:Monospaced'>" + body
                + "</body></html>");
        view.setEditable(false);
        view.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(view);
        sp.setPreferredSize(new Dimension(900, c.kind == Gate.Kind.COMMAND ? 160 : 520));
        p.add(sp, BorderLayout.CENTER);
        JPanel notePanel = new JPanel(new BorderLayout(6, 0));
        notePanel.add(new JLabel("Note for the AI (optional, e.g. what to do instead):"), BorderLayout.NORTH);
        JTextField note = new JTextField();
        notePanel.add(note, BorderLayout.CENTER);
        p.add(notePanel, BorderLayout.SOUTH);

        JButton accept = new JButton("Accept");
        JButton all = new JButton(c.kind == Gate.Kind.COMMAND ? "Allow all commands in this chat"
                : "Accept all edits in this chat");
        JButton reject = new JButton("Reject");
        Object[] options = c.dangerous || c.kind == Gate.Kind.DELETE ? new Object[] {accept, reject}
                : new Object[] {accept, all, reject};
        DialogDescriptor dd = new DialogDescriptor(p, "AI Brain - " + c.title(), true, options, accept,
                DialogDescriptor.DEFAULT_ALIGN, null, null);
        dd.setClosingOptions(options);
        Object r = DialogDisplayer.getDefault().notify(dd);
        if (r == accept) {
            return new Gate.Decision(true, false, note.getText());
        }
        if (r == all) {
            return new Gate.Decision(true, true, note.getText());
        }
        return new Gate.Decision(false, false, note.getText());
    }
}
