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
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import net.microrepair.jrnetbeansaibrain.Settings;
import net.microrepair.jrnetbeansaibrain.core.Agent;
import net.microrepair.jrnetbeansaibrain.core.Gate;
import net.microrepair.jrnetbeansaibrain.core.Markdown;
import net.microrepair.jrnetbeansaibrain.core.Scaffold;
import org.netbeans.api.project.ProjectManager;
import net.microrepair.jrnetbeansaibrain.core.ChatClient;
import net.microrepair.jrnetbeansaibrain.core.Provider;
import net.microrepair.jrnetbeansaibrain.core.WorkMode;
import net.microrepair.jrnetbeansaibrain.core.ProjectInfo;
import net.microrepair.jrnetbeansaibrain.core.Prompt;
import net.microrepair.jrnetbeansaibrain.core.Tools;
import net.microrepair.jrnetbeansaibrain.core.Workspace;
import org.netbeans.api.options.OptionsDisplayer;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.HtmlBrowser;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.RequestProcessor;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/** The chat window: conversation, tool steps, and the question box. */
@TopComponent.Description(
        preferredID = "JRAIBrainChat",
        persistenceType = TopComponent.PERSISTENCE_ALWAYS)
@TopComponent.Registration(mode = "properties", openAtStartup = false)
@ActionID(category = "Window", id = "net.microrepair.jrnetbeansaibrain.OpenChat")
@ActionReferences({
    @ActionReference(path = "Menu/Window", position = 333),
    @ActionReference(path = "Shortcuts", name = "DOS-B")
})
@TopComponent.OpenActionRegistration(displayName = "AI Brain", preferredID = "JRAIBrainChat")
public final class ChatTopComponent extends TopComponent {

    private static final RequestProcessor RP = new RequestProcessor("JRNetbeansAIBrain", 1, true);

    /** One item in the conversation view. */
    private static final class Entry {
        enum Kind { USER, ASSISTANT, TOOL, NOTICE, ERROR, PLAN, ACTION }

        final Kind kind;
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        String label = "";
        Boolean ok;
        String detail = "";
        String live = "";
        final StringBuilder output = new StringBuilder();

        Entry(Kind kind, String text) {
            this.kind = kind;
            this.text.append(text);
        }
    }

    /** A project the chat can work in. */
    private static final class Choice {
        final String name;
        final Path root;
        final String nbType;

        Choice(String name, Path root, String nbType) {
            this.name = name;
            this.root = root;
            this.nbType = nbType;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Markdown.Block> blocks = new ArrayList<>();
    private final DefaultComboBoxModel<Choice> projectModel = new DefaultComboBoxModel<>();
    private final JComboBox<Choice> projectBox = new JComboBox<>(projectModel);
    private final JLabel headline = new JLabel(" ");
    private final JEditorPane view = new JEditorPane();
    private final JScrollPane viewScroll = new JScrollPane(view);
    private final JTextArea input = new JTextArea(3, 30);
    private final JButton sendButton = new JButton("Send");
    private final JLabel status = new JLabel(" ");
    private final JComboBox<WorkMode> workBox = new JComboBox<>(WorkMode.values());
    private final JComboBox<Gate.Mode> modeBox = new JComboBox<>(
            new Gate.Mode[] {Gate.Mode.ASK, Gate.Mode.AUTO_EDIT, Gate.Mode.FULL_AUTO});
    private final DefaultComboBoxModel<Object> providerModel = new DefaultComboBoxModel<>();
    private final JComboBox<Object> providerBox = new JComboBox<>(providerModel);
    private static final String SETUP_PROVIDERS = "Set up AI providers...";
    private boolean ignoreProviderEvents;
    private volatile WorkMode work = WorkMode.AGENT;
    private final JButton newProjectButton = new JButton("New project...");
    private final Timer repaintTimer;
    private Gate gate;
    private volatile Scaffold scaffold;

    private boolean dirty;
    private boolean busy;
    private boolean ignoreProjectEvents;
    private Agent agent;
    private Choice current;
    private ProjectInfo info;
    private String pendingSelection = "";
    private String pendingSelectionFile = "";

    public ChatTopComponent() {
        setName("AI Brain");
        setToolTipText("JR NetBeans AI Brain - chat with the AI about this project");
        setLayout(new BorderLayout());

        JPanel top = new JPanel(new BorderLayout(6, 2));
        top.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(new JLabel("Project:"), BorderLayout.WEST);
        row.add(projectBox, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        JButton newChat = new JButton("New chat");
        newChat.setToolTipText("Forget this conversation and start again");
        newChat.addActionListener(e -> newChat());
        JButton settings = new JButton("Settings");
        settings.addActionListener(e -> OptionsDisplayer.getDefault().open("Advanced/JRAIBrain"));
        buttons.add(newChat);
        buttons.add(settings);
        row.add(buttons, BorderLayout.EAST);
        top.add(row, BorderLayout.NORTH);
        JPanel row2 = new JPanel(new BorderLayout(6, 0));
        // Explicit mode: Chat / Plan / Agent; the permission list applies to Agent mode.
        String oldMode = Settings.prefs().get("mode", "ASK");
        work = WorkMode.parse(Settings.prefs().get("workMode", null),
                "READ_ONLY".equals(oldMode) ? WorkMode.CHAT : WorkMode.AGENT);
        workBox.setSelectedItem(work);
        workBox.setToolTipText("<html><b>Chat</b>: " + WorkMode.CHAT.tip + "<br><b>Plan</b>: " + WorkMode.PLAN.tip
                + "<br><b>Agent</b>: " + WorkMode.AGENT.tip + "</html>");
        workBox.addActionListener(e -> {
            WorkMode w = (WorkMode) workBox.getSelectedItem();
            if (w != null) {
                work = w;
                Settings.prefs().put("workMode", w.name());
                applyMode();
            }
        });
        Gate.Mode perm = Gate.Mode.parse(oldMode, Gate.Mode.ASK);
        modeBox.setSelectedItem(perm == Gate.Mode.READ_ONLY ? Gate.Mode.ASK : perm);
        modeBox.setToolTipText("Agent mode: what the AI may do without asking you (dangerous commands always ask)");
        modeBox.addActionListener(e -> {
            Gate.Mode m = (Gate.Mode) modeBox.getSelectedItem();
            if (m != null) {
                Settings.prefs().put("mode", m.name());
                applyMode();
            }
        });
        JPanel modeWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        modeWrap.add(new JLabel("Mode:"));
        modeWrap.add(workBox);
        modeWrap.add(modeBox);
        row2.add(modeWrap, BorderLayout.CENTER);
        newProjectButton.setToolTipText("Create a whole new project from a description");
        newProjectButton.addActionListener(e -> newProject());
        row2.add(newProjectButton, BorderLayout.EAST);
        JPanel row3 = new JPanel(new BorderLayout(6, 0));
        row3.add(new JLabel("AI:"), BorderLayout.WEST);
        providerBox.setToolTipText("The AI provider and model for this chat (the conversation continues when you switch)");
        refreshProviders();
        providerBox.addActionListener(e -> {
            if (!ignoreProviderEvents) {
                providerChosen();
            }
        });
        row3.add(providerBox, BorderLayout.CENTER);
        JPanel rows = new JPanel(new BorderLayout(0, 2));
        rows.add(row2, BorderLayout.NORTH);
        rows.add(row3, BorderLayout.SOUTH);
        applyMode();
        JPanel mid = new JPanel(new BorderLayout(0, 2));
        mid.add(rows, BorderLayout.NORTH);
        headline.setFont(headline.getFont().deriveFont(Font.PLAIN, headline.getFont().getSize2D() - 1f));
        mid.add(headline, BorderLayout.SOUTH);
        top.add(mid, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        view.setEditable(false);
        HTMLEditorKit kit = new HTMLEditorKit();
        kit.setStyleSheet(styles());
        view.setEditorKit(kit);
        view.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                link(e.getDescription(), e.getURL());
            }
        });
        viewScroll.setBorder(BorderFactory.createEmptyBorder());
        add(viewScroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setToolTipText("Enter sends, Shift+Enter makes a new line");
        input.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown() && !e.isControlDown()) {
                    e.consume();
                    if (!busy) {
                        send();
                    }
                }
            }
        });
        JScrollPane inScroll = new JScrollPane(input);
        inScroll.setPreferredSize(new Dimension(200, 70));
        bottom.add(inScroll, BorderLayout.CENTER);
        sendButton.addActionListener(e -> {
            if (busy) {
                stop();
            } else {
                send();
            }
        });
        bottom.add(sendButton, BorderLayout.EAST);
        status.setFont(status.getFont().deriveFont(status.getFont().getSize2D() - 1f));
        bottom.add(status, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        projectBox.addActionListener(e -> {
            if (ignoreProjectEvents) {
                return;
            }
            Object o = projectBox.getSelectedItem();
            if (o instanceof Choice && o != current) {
                if (((Choice) o).root == null) {
                    chooseFolder();
                } else {
                    switchTo((Choice) o);
                }
            }
        });
        OpenProjects.getDefault().addPropertyChangeListener(e -> {
            if (OpenProjects.PROPERTY_OPEN_PROJECTS.equals(e.getPropertyName())) {
                SwingUtilities.invokeLater(this::refreshProjects);
            }
        });

        repaintTimer = new Timer(120, e -> {
            if (dirty) {
                dirty = false;
                render();
            }
        });
        repaintTimer.start();
        render();
    }

    /** Open (or focus) the chat window. */
    public static ChatTopComponent openChat() {
        TopComponent tc = WindowManager.getDefault().findTopComponent("JRAIBrainChat");
        ChatTopComponent c = tc instanceof ChatTopComponent ? (ChatTopComponent) tc : new ChatTopComponent();
        c.open();
        c.requestActive();
        return c;
    }

    /** Switch between Chat, Plan and Agent mode (also used by the Tools menu). */
    public void setWorkMode(WorkMode w) {
        workBox.setSelectedItem(w);
    }

    /** Tools &gt; AI Brain &gt; New Project with AI... */
    public void startNewProject() {
        newProject();
    }

    /** Tools &gt; AI Brain &gt; AI Providers...: after the dialog, use the (maybe new) provider. */
    public void providersChanged() {
        refreshProviders();
        useProvider();
    }

    private void applyMode() {
        WorkMode w = work;
        modeBox.setEnabled(w == WorkMode.AGENT);
        Gate g = gate;
        if (g != null) {
            g.setMode(w.effective((Gate.Mode) modeBox.getSelectedItem()));
        }
        status.setText(w.label + " mode: " + w.tip + ".");
    }

    private void refreshProviders() {
        ignoreProviderEvents = true;
        try {
            providerModel.removeAllElements();
            for (Provider p : Provider.values()) {
                providerModel.addElement(new ProviderChoice(p));
            }
            providerModel.addElement(SETUP_PROVIDERS);
            Provider act = Settings.provider();
            for (int k = 0; k < providerModel.getSize(); k++) {
                Object o = providerModel.getElementAt(k);
                if (o instanceof ProviderChoice && ((ProviderChoice) o).p == act) {
                    providerBox.setSelectedIndex(k);
                }
            }
        } finally {
            ignoreProviderEvents = false;
        }
    }

    /** A provider in the "AI:" list, shown with its model. */
    private static final class ProviderChoice {
        final Provider p;

        ProviderChoice(Provider p) {
            this.p = p;
        }

        @Override
        public String toString() {
            return Settings.describe(p);
        }
    }

    private void providerChosen() {
        Object o = providerBox.getSelectedItem();
        if (SETUP_PROVIDERS.equals(o)) {
            ProvidersPanel.showDialog();
            refreshProviders();
            useProvider();
            return;
        }
        if (o instanceof ProviderChoice) {
            Settings.setProvider(((ProviderChoice) o).p);
            useProvider();
        }
    }

    /** Point the running conversation at the active provider (the history is kept). */
    private void useProvider() {
        Agent a = agent;
        if (a != null && !busy) {
            try {
                a.setClient(Settings.client());
                addNotice("Now using " + Settings.describe(Settings.provider()) + "; the conversation continues.");
            } catch (IOException ex) {
                addError(ex.getMessage() + " [AI providers](jrbrain:providers)");
            }
        } else if (a != null) {
            addNotice("The new provider is used from your next message.");
            pendingProvider = true;
        }
        if (info != null) {
            headline.setText(info.headline() + (info.memoryFile.isEmpty() ? "" : " · " + info.memoryFile)
                    + " · " + Settings.describe(Settings.provider()));
        }
    }

    private boolean pendingProvider;

    /** Called by "Ask AI Brain about the selection": attach the selection to the next question. */
    public void askAbout(String selection, String file) {
        pendingSelection = selection == null ? "" : selection;
        pendingSelectionFile = file == null ? "" : file;
        if (!pendingSelection.isEmpty()) {
            addNotice("The selected code (" + pendingSelection.split("\n", -1).length
                    + " lines) will be sent with your next question.");
        }
        input.requestFocusInWindow();
    }

    @Override
    protected void componentOpened() {
        refreshProjects();
    }

    @Override
    protected void componentActivated() {
        if (current == null) {
            refreshProjects();
        }
        input.requestFocusInWindow();
    }

    // ---------------------------------------------------------------- projects

    private void refreshProjects() {
        ignoreProjectEvents = true;
        try {
            Choice keep = current;
            projectModel.removeAllElements();
            Choice preferred = null;
            Path editorRoot = editorProjectRoot();
            for (Project p : OpenProjects.getDefault().getOpenProjects()) {
                File f = FileUtil.toFile(p.getProjectDirectory());
                if (f == null) {
                    continue;
                }
                Choice c = new Choice(ProjectUtils.getInformation(p).getDisplayName(), f.toPath(), nbType(p));
                projectModel.addElement(c);
                if (keep != null && c.root.equals(keep.root)) {
                    preferred = c;
                }
                if (preferred == null && editorRoot != null && c.root.equals(editorRoot)) {
                    preferred = c;
                }
            }
            if (keep != null && preferred == null && keep.root != null) {
                projectModel.addElement(keep);
                preferred = keep;
            }
            if (preferred == null) {
                Project main = OpenProjects.getDefault().getMainProject();
                for (int k = 0; k < projectModel.getSize() && main != null; k++) {
                    File f = FileUtil.toFile(main.getProjectDirectory());
                    if (f != null && projectModel.getElementAt(k).root.equals(f.toPath())) {
                        preferred = projectModel.getElementAt(k);
                    }
                }
            }
            if (preferred == null && projectModel.getSize() > 0) {
                preferred = projectModel.getElementAt(0);
            }
            projectModel.addElement(new Choice("Other folder...", null, ""));
            if (preferred != null) {
                projectBox.setSelectedItem(preferred);
                if (keep == null || !preferred.root.equals(keep.root)) {
                    switchTo(preferred);
                }
            } else {
                projectBox.setSelectedIndex(-1);
                headline.setText("Open a project in NetBeans, or choose \"Other folder...\".");
            }
        } finally {
            ignoreProjectEvents = false;
        }
    }

    private static String nbType(Project p) {
        String n = p.getClass().getSimpleName();
        return n.isEmpty() ? p.getClass().getName() : n;
    }

    private static Path editorProjectRoot() {
        EditorBridge.State s = EditorBridge.state();
        if (s == null || s.absFile() == null) {
            return null;
        }
        FileObject fo = FileUtil.toFileObject(s.absFile());
        Project p = fo == null ? null : FileOwnerQuery.getOwner(fo);
        File f = p == null ? null : FileUtil.toFile(p.getProjectDirectory());
        return f == null ? null : f.toPath();
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser(current != null && current.root != null ? current.root.toFile() : null);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder for AI Brain to work in");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path p = fc.getSelectedFile().toPath();
            Choice c = new Choice(p.getFileName() == null ? p.toString() : p.getFileName().toString(), p, "");
            ignoreProjectEvents = true;
            projectModel.insertElementAt(c, Math.max(0, projectModel.getSize() - 1));
            projectBox.setSelectedItem(c);
            ignoreProjectEvents = false;
            switchTo(c);
        } else {
            ignoreProjectEvents = true;
            projectBox.setSelectedItem(current);
            ignoreProjectEvents = false;
        }
    }

    private void switchTo(Choice c) {
        if (busy) {
            stop();
        }
        current = c;
        agent = null;
        info = null;
        entries.clear();
        headline.setText("Looking at " + c.root + " ...");
        RP.post(() -> {
            ProjectInfo pi = ProjectInfo.detect(c.root, c.nbType);
            SwingUtilities.invokeLater(() -> {
                if (current == c) {
                    info = pi;
                    headline.setText(pi.headline() + (pi.memoryFile.isEmpty() ? "" : " · " + pi.memoryFile)
                            + " · " + Settings.describe(Settings.provider()));
                    dirty = true;
                }
            });
        });
        dirty = true;
    }

    // ---------------------------------------------------------------- chat

    private void newChat() {
        if (busy) {
            stop();
        }
        agent = null;
        entries.clear();
        status.setText(" ");
        if (current != null) {
            switchTo(current);
        }
        dirty = true;
        input.requestFocusInWindow();
    }

    private void send() {
        String q = input.getText().trim();
        if (q.isEmpty()) {
            return;
        }
        if (current == null || current.root == null) {
            addError("Choose a project first (the list at the top).");
            return;
        }
        Agent a;
        try {
            a = agent();
        } catch (IOException ex) {
            addError(ex.getMessage() + " [AI providers](jrbrain:providers)");
            return;
        }
        input.setText("");
        StringBuilder msg = new StringBuilder(q);
        EditorBridge.State es = EditorBridge.state();
        Path root = current.root;
        if (!pendingSelection.isEmpty()) {
            msg.append("\n\nSelected code").append(pendingSelectionFile.isEmpty() ? "" : " from "
                    + relTo(root, pendingSelectionFile)).append(":\n```\n").append(pendingSelection).append("\n```");
        }
        if (es != null && es.file() != null && Path.of(es.file()).toAbsolutePath().startsWith(root)) {
            msg.append("\n\n(Editor: ").append(relTo(root, es.file())).append(", cursor at line ").append(es.line())
                    .append(es.selection().isEmpty() ? "" : ", " + es.selection().length() + " characters selected")
                    .append(")");
        }
        pendingSelection = "";
        pendingSelectionFile = "";
        entries.removeIf(e -> e.kind == Entry.Kind.ACTION);
        entries.add(new Entry(Entry.Kind.USER, q));
        a.setMaxSteps(Settings.maxSteps());
        setBusy(true);
        status.setText("Asking " + who(a) + " (" + work.label + " mode) ...");
        dirty = true;
        final Choice runFor = current;
        final WorkMode w = work;
        RP.post(() -> run(a, msg.toString(), runFor, w));
    }

    /** Start a turn with a message written by the plugin (shown short, sent in full). */
    private void startTurn(String shown, String message, int maxSteps) {
        Agent a;
        try {
            a = agent();
        } catch (IOException ex) {
            addError(ex.getMessage() + " [AI providers](jrbrain:providers)");
            return;
        }
        a.setMaxSteps(maxSteps);
        entries.add(new Entry(Entry.Kind.NOTICE, shown));
        setBusy(true);
        status.setText("Asking " + who(a) + " ...");
        dirty = true;
        final Choice runFor = current;
        final WorkMode w = work;
        RP.post(() -> run(a, message, runFor, w));
    }

    private static String who(Agent a) {
        ChatClient c = a.client();
        String n = c.providerName();
        return n.isEmpty() || n.equals(c.model()) ? c.model() : c.model().equals("Copilot app") ? n : n + " " + c.model();
    }

    /** Plan mode answered: carry the plan out in Agent mode. */
    private void executePlan() {
        if (busy || current == null) {
            return;
        }
        entries.removeIf(e -> e.kind == Entry.Kind.ACTION);
        setWorkMode(WorkMode.AGENT);
        startTurn("Carry out the plan (Agent mode).", "The plan above is approved. Now do the work: carry it out step "
                + "by step with the tools, keep the plan checklist up to date, then verify (tests or a quick command) "
                + "and give a short summary of what you changed.", Math.max(Settings.maxSteps(), 80));
    }

    private static String relTo(Path root, String file) {
        try {
            Path p = Path.of(file).toAbsolutePath();
            return p.startsWith(root) ? root.relativize(p).toString().replace('\\', '/') : file;
        } catch (RuntimeException ex) {
            return file;
        }
    }

    private Agent agent() throws IOException {
        if (agent != null) {
            if (pendingProvider) {
                agent.setClient(Settings.client());
                pendingProvider = false;
            }
            return agent;
        }
        pendingProvider = false;
        Workspace ws = new Workspace(current.root);
        ChatClient client = Settings.client();
        final Choice c = current;
        java.util.function.Supplier<ProjectInfo> pi = () -> {
            ProjectInfo i = info;
            return i != null ? i : ProjectInfo.detect(c.root, c.nbType);
        };
        Gate g = new Gate(work.effective((Gate.Mode) modeBox.getSelectedItem()), ApprovalDialog::ask);
        gate = g;
        Tools tools = new Tools(ws, pi, () -> {
            EditorBridge.State s = EditorBridge.state();
            if (s != null && s.file() != null) {
                s.file = relTo(ws.root(), s.file());
            }
            return s;
        }, new NbIde(), g);
        String os = System.getProperty("os.name") + " " + System.getProperty("os.version");
        agent = new Agent(client, tools, () -> Prompt.system(ws.root(), pi.get(), os, g.mode(), work),
                Settings.maxSteps());
        return agent;
    }

    private void run(Agent a, String msg, Choice runFor, WorkMode mode) {
        final Entry[] cur = {null};
        final long[] thinkStart = {0};
        Agent.Listener l = new Agent.Listener() {
            @Override
            public void stepStart(int step) {
                ui(() -> {
                    cur[0] = null;
                    status.setText("Step " + step + " · " + who(a) + " is working (" + mode.label + " mode) ...");
                });
            }

            private Entry assistant() {
                if (cur[0] == null) {
                    cur[0] = new Entry(Entry.Kind.ASSISTANT, "");
                    entries.add(cur[0]);
                }
                return cur[0];
            }

            @Override
            public void thinking(String t) {
                ui(() -> {
                    if (thinkStart[0] == 0) {
                        thinkStart[0] = System.currentTimeMillis();
                    }
                    assistant().thinking.append(t);
                    dirty = true;
                });
            }

            @Override
            public void content(String t) {
                ui(() -> {
                    assistant().text.append(t);
                    dirty = true;
                });
            }

            @Override
            public void retrying(String why, int attempt) {
                ui(() -> {
                    if (cur[0] != null) {
                        entries.remove(cur[0]);
                        cur[0] = null;
                    }
                    addNotice("Connection problem (" + why + "). Trying again (" + attempt + "/3) ...");
                });
            }

            @Override
            public void toolStart(String name, String label) {
                ui(() -> {
                    cur[0] = null;
                    Entry e = new Entry(Entry.Kind.TOOL, "");
                    e.label = label;
                    entries.add(e);
                    dirty = true;
                });
            }

            @Override
            public void toolEnd(String name, boolean ok, String result) {
                String diff = a.tools().takeLastDiff();
                ui(() -> {
                    for (int k = entries.size() - 1; k >= 0; k--) {
                        Entry e = entries.get(k);
                        if (e.kind == Entry.Kind.TOOL && e.ok == null) {
                            e.ok = ok;
                            e.detail = diff == null ? result : result + "\n\n" + diff;
                            e.live = "";
                            break;
                        }
                    }
                    dirty = true;
                });
            }

            @Override
            public void toolProgress(String line) {
                ui(() -> {
                    for (int k = entries.size() - 1; k >= 0; k--) {
                        Entry e = entries.get(k);
                        if (e.kind == Entry.Kind.TOOL && e.ok == null) {
                            e.live = line;
                            dirty = true;
                            break;
                        }
                    }
                });
            }

            @Override
            public void plan(String steps) {
                ui(() -> {
                    cur[0] = null;
                    Entry pe = null;
                    for (int k = entries.size() - 1; k >= 0; k--) {
                        if (entries.get(k).kind == Entry.Kind.USER) {
                            break;
                        }
                        if (entries.get(k).kind == Entry.Kind.PLAN) {
                            pe = entries.get(k);
                            break;
                        }
                    }
                    if (pe != null) {
                        entries.remove(pe);
                    }
                    entries.add(new Entry(Entry.Kind.PLAN, steps));
                    dirty = true;
                });
            }

            @Override
            public void notice(String text) {
                ui(() -> addNotice(text));
            }

            @Override
            public void progress(String text) {
                ui(() -> status.setText(text));
            }
        };
        try {
            Agent.Stats st = a.send(msg, l);
            if (mode == WorkMode.PLAN) {
                ui(() -> {
                    if (current == runFor) {
                        entries.add(new Entry(Entry.Kind.ACTION, "execplan"));
                        dirty = true;
                    }
                });
            }
            ui(() -> status.setText(String.format(Locale.ROOT, "%s · %d step%s · %d tool call%s · %s in / %s out · %.1f s%s",
                    who(a), st.steps, st.steps == 1 ? "" : "s", st.toolCalls, st.toolCalls == 1 ? "" : "s",
                    k(st.promptTokens), k(st.outputTokens), st.millis / 1000.0, st.stepLimit ? " · step limit" : "")));
        } catch (ChatClient.CancelledException ex) {
            ui(() -> {
                addNotice("Stopped.");
                status.setText("Stopped.");
            });
        } catch (IOException ex) {
            ui(() -> {
                addError(ex.getMessage() + (ex instanceof ChatClient.ApiException
                        && ((ChatClient.ApiException) ex).status / 100 == 4 ? " [AI providers](jrbrain:providers)" : ""));
                status.setText("Error.");
            });
        } catch (RuntimeException ex) {
            ui(() -> {
                addError("Internal error: " + ex);
                status.setText("Error.");
            });
        } finally {
            ui(() -> {
                if (current == runFor) {
                    setBusy(false);
                }
            });
        }
    }

    private static String k(int n) {
        return n >= 1000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
    }

    private void stop() {
        Agent a = agent;
        if (a != null) {
            a.cancel();
        }
        Scaffold sc = scaffold;
        if (sc != null) {
            sc.stop();
        }
        setBusy(false);
    }

    private void setBusy(boolean b) {
        busy = b;
        sendButton.setText(b ? "Stop" : "Send");
        projectBox.setEnabled(!b);
        newProjectButton.setEnabled(!b);
    }

    // ---------------------------------------------------------------- new project

    private void newProject() {
        if (busy) {
            return;
        }
        Path suggested = current != null && current.root != null ? current.root.getParent() : null;
        NewProjectDialog.Result r = NewProjectDialog.show(suggested);
        if (r == null) {
            return;
        }
        final Scaffold.Spec spec = r.spec;
        final Path folder = spec.folder();
        entries.clear();
        agent = null;
        current = null;
        headline.setText("Creating " + folder + " ...");
        entries.add(new Entry(Entry.Kind.USER, "New project \"" + spec.name + "\" (" + spec.template.label + "):\n"
                + spec.description));
        dirty = true;
        setBusy(true);
        Scaffold sc = new Scaffold();
        scaffold = sc;
        RP.post(() -> {
            try {
                sc.create(spec, new Scaffold.Listener() {
                    @Override
                    public void step(String label) {
                        ui(() -> {
                            for (Entry e : entries) {
                                if (e.kind == Entry.Kind.TOOL && e.ok == null) {
                                    e.ok = true;
                                    e.live = "";
                                }
                            }
                            Entry e = new Entry(Entry.Kind.TOOL, "");
                            e.label = label;
                            entries.add(e);
                            status.setText(label + " ...");
                            dirty = true;
                        });
                    }

                    @Override
                    public void line(String text) {
                        ui(() -> {
                            for (int k = entries.size() - 1; k >= 0; k--) {
                                Entry e = entries.get(k);
                                if (e.kind == Entry.Kind.TOOL) {
                                    e.live = text;
                                    if (e.output.length() < 200_000) {
                                        e.output.append(text).append('\n');
                                    }
                                    e.detail = e.output.toString();
                                    dirty = true;
                                    break;
                                }
                            }
                        });
                    }
                });
            } catch (IOException ex) {
                ui(() -> {
                    for (Entry e : entries) {
                        if (e.kind == Entry.Kind.TOOL && e.ok == null) {
                            e.ok = false;
                            e.live = "";
                        }
                    }
                    addError("Creating the project failed: " + ex.getMessage());
                    status.setText("Error.");
                    scaffold = null;
                    setBusy(false);
                });
                return;
            }
            Project p = null;
            try {
                FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(folder.toFile()));
                if (fo != null) {
                    fo.refresh();
                    p = ProjectManager.getDefault().findProject(fo);
                }
                if (p != null) {
                    OpenProjects.getDefault().open(new Project[] {p}, false, true);
                }
            } catch (IOException | IllegalArgumentException ex) {
                p = null;
            }
            final String nbType = p == null ? "" : nbType(p);
            ui(() -> {
                scaffold = null;
                for (Entry e : entries) {
                    if (e.kind == Entry.Kind.TOOL && e.ok == null) {
                        e.ok = true;
                        e.live = "";
                    }
                }
                Choice c = new Choice(spec.name, folder, nbType);
                ignoreProjectEvents = true;
                projectModel.insertElementAt(c, 0);
                projectBox.setSelectedItem(c);
                ignoreProjectEvents = false;
                current = c;
                info = ProjectInfo.detect(folder, nbType);
                headline.setText(info.headline() + " · " + Settings.describe(Settings.provider()));
                setWorkMode(WorkMode.AGENT);
                modeBox.setSelectedItem(r.auto ? Gate.Mode.FULL_AUTO : Gate.Mode.ASK);
                addNotice("The skeleton is ready" + (spec.git ? " and saved in git" : "") + ". The AI now builds the "
                        + "application" + (r.auto ? " (it works without asking; dangerous commands still ask)" : "")
                        + ".");
                setBusy(false);
                startTurn("Build the application described above.", Scaffold.kickoff(spec, info),
                        Math.max(Settings.maxSteps(), 150));
            });
        });
    }

    private static void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }

    private void addNotice(String t) {
        entries.add(new Entry(Entry.Kind.NOTICE, t));
        dirty = true;
    }

    private void addError(String t) {
        entries.add(new Entry(Entry.Kind.ERROR, t));
        dirty = true;
    }

    // ---------------------------------------------------------------- view

    private void render() {
        JScrollBar bar = viewScroll.getVerticalScrollBar();
        boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
        blocks.clear();
        StringBuilder h = new StringBuilder("<html><body>");
        if (entries.isEmpty()) {
            h.append("<div class='welcome'><h2>JR NetBeans AI Brain</h2><p>Ask about this project: how something "
                    + "works, where a feature lives, what is wrong, or how to build something new. The AI reads the "
                    + "project's files itself before it answers.</p><p class='dim'>Examples:<br>"
                    + "&nbsp;&middot; Explain how the routes and controllers of this project fit together<br>"
                    + "&nbsp;&middot; Where is the login handled, and how is it validated?<br>"
                    + "&nbsp;&middot; Write a Blade page with a form to create a Post, matching the existing layout"
                    + "<br>&nbsp;&middot; Add a categories table and let posts have a category, with the form and the "
                    + "list filter</p><p class='dim'><b>Mode</b>: <b>Chat</b> answers and changes nothing; "
                    + "<b>Plan</b> explores and writes a plan you approve with one click; <b>Agent</b> does the "
                    + "work. In Agent mode the second list decides what it may do without asking: ask before every "
                    + "change (you see a diff and Accept or Reject), accept edits, or accept edits and commands.</p>"
                    + "<p class='dim'><b>AI</b>: Ollama Cloud, local Ollama, the jrbtbrain bridge, Claude, OpenAI, "
                    + "Gemini, any OpenAI-compatible server, or the Copilot app; switch at any time (Tools &gt; AI "
                    + "Brain &gt; AI Providers). <b>New project...</b> creates a whole application from your "
                    + "description.</p><p class='dim'>Enter sends · Shift+Enter new line · Ctrl+Alt+Shift+B opens "
                    + "this window · click a file name to open it.</p></div>");
        }
        for (int k = 0; k < entries.size(); k++) {
            Entry e = entries.get(k);
            switch (e.kind) {
                case USER:
                    h.append("<div class='user'>").append(Markdown.esc(e.text.toString()).replace("\n", "<br>"))
                            .append("</div>");
                    break;
                case ASSISTANT:
                    if (e.thinking.length() > 0 && Settings.showThinking()) {
                        String t = e.thinking.toString().trim();
                        if (e.text.length() == 0) {
                            String tail = t.length() > 500 ? "..." + t.substring(t.length() - 500) : t;
                            h.append("<div class='think'>Thinking: ").append(Markdown.esc(tail)).append("</div>");
                        } else {
                            h.append("<div class='think'>Thought (").append(t.length()).append(" characters)</div>");
                        }
                    }
                    if (e.text.length() > 0) {
                        h.append("<div class='ai'>").append(Markdown.toHtml(e.text.toString(), blocks)).append("</div>");
                    }
                    break;
                case TOOL: {
                    String mark = e.ok == null ? "&#9675;" : e.ok ? "&#10003;" : "&#10007;";
                    String cls = e.ok == null ? "tool" : e.ok ? "tool" : "toolerr";
                    h.append("<div class='").append(cls).append("'>").append(mark).append(' ')
                            .append(Markdown.esc(e.label));
                    if (e.ok != null) {
                        h.append(" <span class='dim'>").append(Markdown.esc(summary(e))).append("</span>")
                                .append(" &nbsp;<a href='jrbrain:tool:").append(k).append("'>details</a>");
                    } else if (!e.live.isBlank()) {
                        String lv = e.live.length() > 160 ? e.live.substring(0, 160) + "..." : e.live;
                        h.append("<br><span class='dim'>&nbsp;&nbsp;&nbsp;").append(Markdown.esc(lv)).append("</span>");
                    }
                    h.append("</div>");
                    break;
                }
                case PLAN: {
                    h.append("<div class='plan'><b>Plan</b>");
                    for (String line : e.text.toString().split("\n")) {
                        String t = line.trim().replaceFirst("^[-*]\\s*", "");
                        if (t.isEmpty()) {
                            continue;
                        }
                        String mark = "&#9744;";
                        String cls = "";
                        if (t.startsWith("[x]") || t.startsWith("[X]")) {
                            mark = "&#9745;";
                            cls = " class='dim'";
                            t = t.substring(3).trim();
                        } else if (t.startsWith("[~]")) {
                            mark = "&#9654;";
                            t = t.substring(3).trim();
                        } else if (t.startsWith("[ ]")) {
                            t = t.substring(3).trim();
                        }
                        h.append("<br><span").append(cls).append('>').append(mark).append(' ')
                                .append(Markdown.esc(t)).append("</span>");
                    }
                    h.append("</div>");
                    break;
                }
                case NOTICE:
                    h.append("<div class='notice'>").append(Markdown.esc(e.text.toString())).append("</div>");
                    break;
                case ERROR:
                    h.append("<div class='error'>").append(Markdown.toHtml(e.text.toString(), blocks)).append("</div>");
                    break;
                case ACTION:
                    h.append("<div class='action'><a href='jrbrain:execplan'>&#9654; Carry out this plan in Agent mode</a>"
                            + " <span class='dim'>&nbsp;or write what to change in the plan</span></div>");
                    break;
                default:
                    break;
            }
        }
        h.append("</body></html>");
        view.setText(h.toString());
        if (atBottom || busy) {
            SwingUtilities.invokeLater(() -> view.setCaretPosition(view.getDocument().getLength()));
        }
    }

    private static String summary(Entry e) {
        String d = e.detail == null ? "" : e.detail.trim();
        if (!Boolean.TRUE.equals(e.ok)) {
            String first = d.split("\n", 2)[0];
            return first.length() > 140 ? first.substring(0, 140) + "..." : first;
        }
        String last = d.isEmpty() ? "" : d.substring(d.lastIndexOf('\n') + 1);
        if (last.startsWith("(") && last.endsWith(")") && last.length() < 120) {
            return last;
        }
        int lines = d.isEmpty() ? 0 : d.split("\n", -1).length;
        return lines + " line" + (lines == 1 ? "" : "s");
    }

    private void link(String desc, URL url) {
        if (desc == null) {
            return;
        }
        if (desc.startsWith("code-insert:") || desc.startsWith("code-copy:")) {
            int idx;
            try {
                idx = Integer.parseInt(desc.substring(desc.indexOf(':') + 1));
            } catch (NumberFormatException ex) {
                return;
            }
            if (idx < 0 || idx >= blocks.size()) {
                return;
            }
            String code = blocks.get(idx).code;
            if (desc.startsWith("code-copy:")) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
                status.setText("Code copied to the clipboard.");
            } else {
                String err = EditorBridge.insert(code);
                status.setText(err == null ? "Code inserted at the cursor (Ctrl+Z undoes it)." : err);
            }
        } else if (desc.startsWith("jrbrain:open:")) {
            if (current == null || current.root == null) {
                return;
            }
            String ref = desc.substring("jrbrain:open:".length());
            int line = 1;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?):(\\d+)(?:-\\d+)?$").matcher(ref);
            if (m.matches()) {
                ref = m.group(1);
                line = Integer.parseInt(m.group(2));
            }
            try {
                Path p = new Workspace(current.root).resolve(ref);
                if (java.nio.file.Files.isRegularFile(p)) {
                    new NbIde().open(p, line);
                } else {
                    status.setText("Not found in the project: " + ref);
                }
            } catch (IOException | Workspace.ToolError ex) {
                status.setText(ex.getMessage());
            }
        } else if (desc.equals("jrbrain:execplan")) {
            executePlan();
        } else if (desc.equals("jrbrain:providers")) {
            ProvidersPanel.showDialog();
            providersChanged();
        } else if (desc.equals("jrbrain:options")) {
            OptionsDisplayer.getDefault().open("Advanced/JRAIBrain");
        } else if (desc.startsWith("jrbrain:tool:")) {
            try {
                Entry e = entries.get(Integer.parseInt(desc.substring("jrbrain:tool:".length())));
                JTextArea ta = new JTextArea(e.detail, 24, 100);
                ta.setEditable(false);
                ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, ta.getFont().getSize()));
                ta.setCaretPosition(0);
                DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(new JScrollPane(ta),
                        NotifyDescriptor.PLAIN_MESSAGE));
            } catch (RuntimeException ignore) {
                // entry gone (new chat)
            }
        } else if (url != null && (desc.startsWith("http://") || desc.startsWith("https://"))) {
            HtmlBrowser.URLDisplayer.getDefault().showURL(url);
        }
    }

    private static StyleSheet styles() {
        Color bg = color("TextArea.background", Color.WHITE);
        Color fg = color("TextArea.foreground", Color.BLACK);
        boolean dark = (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
        Font f = UIManager.getFont("Label.font");
        int size = f == null ? 12 : f.getSize();
        String family = f == null ? "SansSerif" : f.getFamily();
        String dim = hex(mix(fg, bg, 0.45));
        String codeBg = hex(dark ? mix(bg, Color.WHITE, 0.06) : mix(bg, Color.BLACK, 0.04));
        String headBg = hex(dark ? mix(bg, Color.WHITE, 0.12) : mix(bg, Color.BLACK, 0.09));
        String userBg = hex(dark ? mix(bg, new Color(70, 120, 200), 0.25) : mix(bg, new Color(70, 120, 200), 0.12));
        String link = dark ? "#7fb2ff" : "#1a5fb4";
        String err = dark ? "#ff8a80" : "#b00020";
        StyleSheet s = new StyleSheet();
        s.addRule("body { font-family: '" + family + "'; font-size: " + size + "pt; color: " + hex(fg)
                + "; background-color: " + hex(bg) + "; margin: 6px; }");
        s.addRule("a { color: " + link + "; }");
        s.addRule("p { margin-top: 3px; margin-bottom: 5px; }");
        s.addRule("h2 { font-size: " + (size + 4) + "pt; margin-top: 4px; margin-bottom: 4px; }");
        s.addRule("h3 { font-size: " + (size + 2) + "pt; margin-top: 6px; margin-bottom: 3px; }");
        s.addRule("h4 { font-size: " + (size + 1) + "pt; margin-top: 5px; margin-bottom: 2px; }");
        s.addRule("ul, ol { margin-top: 2px; margin-bottom: 4px; margin-left: 18px; }");
        s.addRule("code { font-family: Monospaced; background-color: " + codeBg + "; }");
        s.addRule("pre { font-family: Monospaced; font-size: " + size + "pt; margin: 0px; }");
        s.addRule("table.code { margin-top: 4px; margin-bottom: 6px; border: 1px solid " + headBg + "; }");
        s.addRule("td.codehead { background-color: " + headBg + "; font-size: " + (size - 1) + "pt; color: " + dim + "; }");
        s.addRule("td.codebody { background-color: " + codeBg + "; }");
        s.addRule(".user { background-color: " + userBg + "; padding: 6px; margin-top: 10px; margin-bottom: 4px; }");
        s.addRule(".ai { margin-top: 2px; margin-bottom: 4px; }");
        s.addRule(".think { color: " + dim + "; font-size: " + (size - 1) + "pt; font-style: italic; margin-top: 2px; }");
        s.addRule(".tool { color: " + dim + "; font-size: " + (size - 1) + "pt; margin-top: 1px; margin-left: 4px; }");
        s.addRule(".toolerr { color: " + err + "; font-size: " + (size - 1) + "pt; margin-top: 1px; margin-left: 4px; }");
        s.addRule(".notice { color: " + dim + "; font-size: " + (size - 1) + "pt; margin-top: 3px; }");
        s.addRule(".error { color: " + err + "; margin-top: 4px; }");
        s.addRule(".dim { color: " + dim + "; }");
        s.addRule(".cont { margin-left: 22px; }");
        s.addRule(".plan { border: 1px solid " + headBg + "; padding: 5px; margin-top: 5px; margin-bottom: 5px; }");
        s.addRule(".welcome { margin: 8px; }");
        s.addRule(".action { border: 1px solid " + headBg + "; padding: 6px; margin-top: 4px; margin-bottom: 6px; }");
        return s;
    }

    private static Color color(String key, Color def) {
        Color c = UIManager.getColor(key);
        return c == null ? def : c;
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() * (1 - t) + b.getRed() * t), (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) (a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
