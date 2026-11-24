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

import java.awt.EventQueue;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import net.microrepair.jrnetbeansaibrain.core.Tools;
import org.netbeans.api.editor.EditorRegistry;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.text.NbDocument;

/** Reads the editor state and inserts code at the cursor, always on the Swing thread. */
public final class EditorBridge {

    private EditorBridge() {
    }

    /** Snapshot of the editor. */
    public static final class State implements Tools.EditorState {
        String file;
        File absFile;
        int line;
        int column;
        String selection = "";
        String mime = "";

        public String file() {
            return file;
        }

        public File absFile() {
            return absFile;
        }

        public int line() {
            return line;
        }

        public int column() {
            return column;
        }

        public String selection() {
            return selection;
        }

        public String mime() {
            return mime;
        }
    }

    /** The last focused editor, or null. Safe to call from any thread. */
    public static State state() {
        if (EventQueue.isDispatchThread()) {
            return stateNow();
        }
        AtomicReference<State> ref = new AtomicReference<>();
        try {
            EventQueue.invokeAndWait(() -> ref.set(stateNow()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ex) {
            return null;
        }
        return ref.get();
    }

    private static State stateNow() {
        JTextComponent c = EditorRegistry.lastFocusedComponent();
        if (c == null) {
            return null;
        }
        Document d = c.getDocument();
        FileObject fo = fileOf(d);
        if (fo == null) {
            return null;
        }
        State s = new State();
        s.absFile = FileUtil.toFile(fo);
        s.file = s.absFile != null ? s.absFile.getPath() : fo.getPath();
        s.mime = fo.getMIMEType();
        int dot = c.getCaretPosition();
        Element root = d.getDefaultRootElement();
        int li = root.getElementIndex(dot);
        s.line = li + 1;
        s.column = dot - root.getElement(li).getStartOffset() + 1;
        String sel = c.getSelectedText();
        s.selection = sel == null ? "" : sel;
        return s;
    }

    static FileObject fileOf(Document d) {
        Object o = d.getProperty(Document.StreamDescriptionProperty);
        if (o instanceof DataObject) {
            return ((DataObject) o).getPrimaryFile();
        }
        if (o instanceof FileObject) {
            return (FileObject) o;
        }
        return null;
    }

    /**
     * Insert code at the cursor of the last focused editor (replacing the selection), as one undo
     * step, indented to the cursor's line. Must be called on the Swing thread. Returns an error
     * message or null when done.
     */
    public static String insert(String code) {
        JTextComponent c = EditorRegistry.lastFocusedComponent();
        if (c == null || !(c.getDocument() instanceof StyledDocument)) {
            return "Click in a file in the editor first, at the place where the code should go.";
        }
        if (!c.isEditable()) {
            return "The file in the editor is read-only.";
        }
        StyledDocument d = (StyledDocument) c.getDocument();
        int start = Math.min(c.getSelectionStart(), c.getSelectionEnd());
        int end = Math.max(c.getSelectionStart(), c.getSelectionEnd());
        String[] err = {null};
        try {
            Element root = d.getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(start));
            String before = d.getText(line.getStartOffset(), start - line.getStartOffset());
            String text = indentTo(code, before);
            NbDocument.runAtomicAsUser(d, () -> {
                try {
                    if (end > start) {
                        d.remove(start, end - start);
                    }
                    d.insertString(start, text, null);
                } catch (BadLocationException ex) {
                    err[0] = "Could not insert: " + ex.getMessage();
                }
            });
            if (err[0] == null) {
                c.setCaretPosition(Math.min(d.getLength(), start + text.length()));
                c.requestFocusInWindow();
            }
        } catch (BadLocationException ex) {
            return "Could not insert: " + ex.getMessage();
        }
        return err[0];
    }

    /**
     * Re-indent a block for the cursor position: remove the block's common indentation, then indent
     * every line after the first with the cursor line's leading whitespace (when the cursor sits in it).
     */
    public static String indentTo(String code, String lineBeforeCursor) {
        String[] lines = code.replace("\r\n", "\n").split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (String l : lines) {
            if (l.isBlank()) {
                continue;
            }
            int n = 0;
            while (n < l.length() && (l.charAt(n) == ' ' || l.charAt(n) == '\t')) {
                n++;
            }
            common = Math.min(common, n);
        }
        if (common == Integer.MAX_VALUE) {
            common = 0;
        }
        String indent = lineBeforeCursor.isBlank() ? lineBeforeCursor : "";
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            String l = lines[k].isBlank() ? "" : lines[k].substring(Math.min(common, lines[k].length()));
            if (k > 0) {
                b.append('\n');
                if (!l.isEmpty()) {
                    b.append(indent);
                }
            }
            b.append(l);
        }
        return b.toString();
    }
}
