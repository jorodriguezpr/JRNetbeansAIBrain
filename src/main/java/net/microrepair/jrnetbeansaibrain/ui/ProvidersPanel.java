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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import net.microrepair.jrnetbeansaibrain.Settings;
import net.microrepair.jrnetbeansaibrain.core.ChatClient;
import net.microrepair.jrnetbeansaibrain.core.CopilotBridgeClient;
import net.microrepair.jrnetbeansaibrain.core.Json;
import net.microrepair.jrnetbeansaibrain.core.Markdown;
import net.microrepair.jrnetbeansaibrain.core.Provider;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;
import org.openide.util.RequestProcessor;

/**
 * The AI provider form: a list of providers (Ollama Cloud, Ollama local, jrbtbrain bridge,
 * Claude, OpenAI, Gemini, any OpenAI-compatible server, the Copilot app bridge), and for the
 * selected one its address, model (with "Load models"), where its API key comes from, a
 * connection test, and "Use in the chat". Nothing is saved until {@link #store()}.
 */
public final class ProvidersPanel extends JPanel {

    private static final String[] SOURCES = {Settings.KEY_KEYRING, Settings.KEY_FILE, Settings.KEY_ENV, Settings.KEY_NONE};
    private static final RequestProcessor RP = new RequestProcessor("JRNetbeansAIBrain-providers", 2);

    /** Unsaved values of one provider. */
    private static final class Draft {
        String baseUrl;
        String model;
        String keySource;
        String keyFile;
        char[] typedKey = new char[0];
        final Set<String> models = new LinkedHashSet<>();
    }

    private final Map<Provider, Draft> drafts = new EnumMap<>(Provider.class);
    private Provider active = Provider.OLLAMA_CLOUD;
    private Provider shown;

    private final JList<Provider> list = new JList<>(Provider.values());
    private final JLabel title = new JLabel(" ");
    private final JLabel help = new JLabel(" ");
    private final JLabel addressLabel = new JLabel("Address:");
    private final JTextField baseUrl = new JTextField(32);
    private final JButton browseExe = new JButton("Browse...");
    private final JComboBox<String> model = new JComboBox<>();
    private final JButton loadModels = new JButton("Load models");
    private final JComboBox<String> source = new JComboBox<>();
    private final JPasswordField key = new JPasswordField(32);
    private final JLabel keyState = new JLabel(" ");
    private final JTextField keyFile = new JTextField(32);
    private final JButton browse = new JButton("Browse...");
    private final JButton use = new JButton("Use in the chat");
    private final JButton test = new JButton("Test connection");
    private final JLabel result = new JLabel(" ");
    private boolean loading;

    public ProvidersPanel() {
        super(new BorderLayout(10, 0));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, focus);
                Provider p = (Provider) v;
                c.setText((p == active ? "● " : "    ") + p.label);
                c.setFont(c.getFont().deriveFont(p == active ? Font.BOLD : Font.PLAIN));
                c.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 8));
                return c;
            }
        });
        JScrollPane ls = new JScrollPane(list);
        ls.setPreferredSize(new Dimension(210, 260));
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(new JLabel("AI providers (● = used by the chat):"), BorderLayout.NORTH);
        left.add(ls, BorderLayout.CENTER);
        add(left, BorderLayout.WEST);

        JPanel form = new JPanel(new GridBagLayout());
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
        int y = 0;
        y = full(form, y, title);
        y = full(form, y, help);
        JPanel addr = new JPanel(new BorderLayout(4, 0));
        addr.add(baseUrl, BorderLayout.CENTER);
        addr.add(browseExe, BorderLayout.EAST);
        y = row(form, y, addressLabel, addr);
        model.setEditable(true);
        JPanel mp = new JPanel(new BorderLayout(4, 0));
        mp.add(model, BorderLayout.CENTER);
        mp.add(loadModels, BorderLayout.EAST);
        y = row(form, y, new JLabel("Model:"), mp);
        y = row(form, y, new JLabel("API key from:"), source);
        y = row(form, y, new JLabel("API key:"), key);
        y = row(form, y, new JLabel(""), keyState);
        JPanel fp = new JPanel(new BorderLayout(4, 0));
        fp.add(keyFile, BorderLayout.CENTER);
        fp.add(browse, BorderLayout.EAST);
        y = row(form, y, new JLabel("Credentials file:"), fp);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(use);
        buttons.add(javax.swing.Box.createHorizontalStrut(8));
        buttons.add(test);
        y = row(form, y, new JLabel(""), buttons);
        y = full(form, y, result);
        GridBagConstraints fill = new GridBagConstraints();
        fill.gridy = y;
        fill.weighty = 1;
        form.add(new JLabel(), fill);
        add(form, BorderLayout.CENTER);

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                show(list.getSelectedValue());
            }
        });
        source.addActionListener(e -> {
            if (!loading) {
                sourceChanged();
            }
        });
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(keyFile.getText().isBlank() ? null : new File(keyFile.getText()));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                keyFile.setText(fc.getSelectedFile().getPath());
            }
        });
        browseExe.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(baseUrl.getText().isBlank() ? null : new File(baseUrl.getText()));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                baseUrl.setText(fc.getSelectedFile().getPath());
            }
        });
        use.addActionListener(e -> {
            keep();
            active = shown;
            list.repaint();
            use.setEnabled(false);
            result.setText("The chat will use " + shown.label + " (after OK / Apply).");
        });
        loadModels.addActionListener(e -> loadModels());
        test.addActionListener(e -> test());
    }

    private static int row(JPanel form, int y, JComponent label, JComponent c) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = y;
        l.anchor = GridBagConstraints.WEST;
        l.insets = new Insets(4, 0, 2, 8);
        form.add(label, l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = y;
        f.weightx = 1;
        f.fill = c instanceof JComboBox ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        f.anchor = GridBagConstraints.WEST;
        f.insets = new Insets(4, 0, 2, 4);
        form.add(c, f);
        return y + 1;
    }

    private static int full(JPanel form, int y, JComponent c) {
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 0;
        f.gridy = y;
        f.gridwidth = 2;
        f.weightx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.anchor = GridBagConstraints.WEST;
        f.insets = new Insets(2, 0, 4, 4);
        form.add(c, f);
        return y + 1;
    }

    private static String sourceName(Provider p, String s) {
        switch (s) {
            case Settings.KEY_FILE:
                return "Credentials file (JSON with apiKey)";
            case Settings.KEY_ENV:
                return p.envVar.isEmpty() ? "Environment variable (none for this provider)"
                        : "Environment variable " + p.envVar;
            case Settings.KEY_NONE:
                return "No key";
            default:
                return "Saved in NetBeans (keyring)";
        }
    }

    /** Load the saved settings of every provider. */
    public void load() {
        drafts.clear();
        for (Provider p : Provider.values()) {
            Draft d = new Draft();
            d.baseUrl = Settings.baseUrl(p);
            d.model = Settings.model(p);
            d.keySource = Settings.keySource(p);
            d.keyFile = Settings.keyFile(p);
            d.models.addAll(p.suggestedModels);
            drafts.put(p, d);
        }
        active = Settings.provider();
        shown = null;
        list.setSelectedValue(active, true);
        if (shown == null) {
            show(active);
        }
        result.setText(" ");
    }

    /** Save every provider's settings, the typed keys (to the keyring) and the active provider. */
    public void store() {
        keep();
        for (Map.Entry<Provider, Draft> e : drafts.entrySet()) {
            Provider p = e.getKey();
            Draft d = e.getValue();
            Settings.put(p, "baseUrl", d.baseUrl);
            Settings.put(p, "model", d.model);
            Settings.put(p, "keySource", d.keySource);
            Settings.put(p, "keyFile", d.keyFile);
            if (d.typedKey.length > 0 && Settings.KEY_KEYRING.equals(d.keySource)) {
                Settings.saveKeyToKeyring(p, d.typedKey);
            }
            Arrays.fill(d.typedKey, '\0');
            d.typedKey = new char[0];
        }
        Settings.setProvider(active);
        key.setText("");
        if (shown != null) {
            sourceChanged();
        }
    }

    /** Copy the form into the shown provider's draft. */
    private void keep() {
        if (shown == null) {
            return;
        }
        Draft d = drafts.get(shown);
        d.baseUrl = baseUrl.getText().trim();
        Object m = model.getEditor().getItem();
        d.model = m == null ? "" : m.toString().trim();
        int si = source.getSelectedIndex();
        d.keySource = si < 0 ? d.keySource : SOURCES[si];
        d.keyFile = keyFile.getText().trim();
        char[] k = key.getPassword();
        if (k.length > 0) {
            Arrays.fill(d.typedKey, '\0');
            d.typedKey = k;
        }
    }

    private void show(Provider p) {
        keep();
        loading = true;
        try {
            shown = p;
            Draft d = drafts.get(p);
            boolean copilot = p.api == Provider.Api.COPILOT;
            title.setText(p.label);
            help.setText("<html><div style='width:420px'>" + Markdown.esc(p.help) + "</div></html>");
            addressLabel.setText(copilot ? "Copilot app:" : "Address:");
            baseUrl.setText(d.baseUrl);
            browseExe.setVisible(copilot);
            DefaultComboBoxModel<String> mm = new DefaultComboBoxModel<>();
            for (String s : d.models) {
                mm.addElement(s);
            }
            if (!d.model.isEmpty() && mm.getIndexOf(d.model) < 0) {
                mm.insertElementAt(d.model, 0);
            }
            model.setModel(mm);
            model.getEditor().setItem(d.model);
            model.setEnabled(!copilot);
            loadModels.setEnabled(!copilot);
            DefaultComboBoxModel<String> sm = new DefaultComboBoxModel<>();
            int sel = 0;
            for (int i = 0; i < SOURCES.length; i++) {
                sm.addElement(sourceName(p, SOURCES[i]));
                if (SOURCES[i].equals(d.keySource)) {
                    sel = i;
                }
            }
            source.setModel(sm);
            source.setSelectedIndex(sel);
            source.setEnabled(!copilot);
            key.setText(d.typedKey.length > 0 ? new String(d.typedKey) : "");
            keyFile.setText(d.keyFile);
            use.setEnabled(p != active);
            result.setText(" ");
        } finally {
            loading = false;
        }
        sourceChanged();
    }

    private String sourceId() {
        int i = source.getSelectedIndex();
        return i < 0 ? Settings.KEY_NONE : SOURCES[i];
    }

    private void sourceChanged() {
        Provider p = shown;
        if (p == null) {
            return;
        }
        String s = sourceId();
        boolean copilot = p.api == Provider.Api.COPILOT;
        key.setEnabled(!copilot && Settings.KEY_KEYRING.equals(s));
        keyFile.setEnabled(!copilot && Settings.KEY_FILE.equals(s));
        browse.setEnabled(!copilot && Settings.KEY_FILE.equals(s));
        String text;
        if (copilot) {
            text = "No key: the Copilot app uses your Microsoft account.";
        } else if (Settings.KEY_KEYRING.equals(s)) {
            text = Settings.keyringHasKey(p) ? "A key is saved. Leave the box empty to keep it."
                    : "No key saved yet. Paste your " + p.label + " API key.";
        } else if (Settings.KEY_ENV.equals(s)) {
            String v = p.envVar.isEmpty() ? null : System.getenv(p.envVar);
            text = p.envVar.isEmpty() ? "Choose another source." : v == null || v.isBlank()
                    ? p.envVar + " is not set for NetBeans." : p.envVar + " is set.";
        } else if (Settings.KEY_FILE.equals(s)) {
            text = "Only the key is read from the file, each time; nothing is copied.";
        } else {
            text = p.needsKey ? "This provider normally needs a key." : "Fine for a local server or a bridge.";
        }
        keyState.setText(text);
    }

    /** A client built from the values in the form (saved or not). */
    private ChatClient formClient(Provider p) throws IOException {
        keep();
        Draft d = drafts.get(p);
        String k = p.api == Provider.Api.COPILOT ? ""
                : Settings.apiKey(p, d.keySource, d.typedKey, d.keyFile);
        Provider.Options o = Settings.options();
        o.think = false;
        o.idleTimeoutSeconds = 60;
        o.maxTokens = 1024;
        return p.client(d.baseUrl, k, d.model, o);
    }

    private void loadModels() {
        Provider p = shown;
        loadModels.setEnabled(false);
        result.setText("Loading the models of " + p.label + " ...");
        RP.post(() -> {
            String msg;
            List<String> got = new ArrayList<>();
            try {
                got = formClient(p).listModels();
                msg = got.isEmpty() ? "The provider listed no models; type the model name."
                        : got.size() + " models loaded; open the Model list.";
            } catch (IOException | RuntimeException ex) {
                msg = "Could not load the models: " + ex.getMessage();
            }
            List<String> fin = got;
            String fm = msg;
            SwingUtilities.invokeLater(() -> {
                if (shown == p && !fin.isEmpty()) {
                    keep();
                    Draft d = drafts.get(p);
                    d.models.clear();
                    d.models.addAll(fin);
                    String cur = d.model;
                    DefaultComboBoxModel<String> mm = new DefaultComboBoxModel<>();
                    for (String s : d.models) {
                        mm.addElement(s);
                    }
                    model.setModel(mm);
                    model.getEditor().setItem(cur);
                }
                result.setText("<html>" + Markdown.esc(fm) + "</html>");
                loadModels.setEnabled(shown != null && shown.api != Provider.Api.COPILOT);
            });
        });
    }

    /** Send a tiny request with the values in the form. */
    private void test() {
        Provider p = shown;
        test.setEnabled(false);
        result.setText("Testing " + p.label + " ...");
        RP.post(() -> {
            String msg;
            try {
                ChatClient c = formClient(p);
                if (c instanceof CopilotBridgeClient) {
                    msg = ((CopilotBridgeClient) c).status();
                } else {
                    ChatClient.Reply r = c.chat(List.of(Json.obj("role", "user", "content",
                            "Reply with the single word OK.")), null, new ChatClient.Stream() { });
                    String answer = r.content.toString().trim().replaceAll("\\s+", " ");
                    if (answer.isEmpty() && r.thinking.length() > 0) {
                        answer = "(thought only)";
                    }
                    msg = "Connected: " + c.model() + " answered \"" + (answer.length() > 40
                            ? answer.substring(0, 40) + "..." : answer) + "\" in " + r.millis + " ms.";
                }
            } catch (IOException | RuntimeException ex) {
                msg = "Failed: " + ex.getMessage();
            }
            String fin = msg;
            SwingUtilities.invokeLater(() -> {
                result.setText("<html><div style='width:420px'>" + Markdown.esc(fin) + "</div></html>");
                test.setEnabled(true);
            });
        });
    }

    /** Tools &gt; AI Brain &gt; AI Providers...: the form in a dialog; OK saves. True when saved. */
    public static boolean showDialog() {
        ProvidersPanel panel = new ProvidersPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.load();
        DialogDescriptor dd = new DialogDescriptor(panel, "AI Brain - AI Providers");
        Object r = DialogDisplayer.getDefault().notify(dd);
        if (r == DialogDescriptor.OK_OPTION) {
            panel.store();
            return true;
        }
        return false;
    }
}
