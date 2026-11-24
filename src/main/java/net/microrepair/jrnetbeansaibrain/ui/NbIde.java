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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import net.microrepair.jrnetbeansaibrain.core.Ide;
import org.openide.cookies.EditorCookie;
import org.openide.cookies.LineCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.text.Line;
import org.openide.text.NbDocument;

/** The NetBeans side of {@link Ide}: open documents are edited through the editor (Undo works). */
final class NbIde implements Ide {

    private static FileObject fo(Path p) {
        File f = FileUtil.normalizeFile(p.toFile());
        return FileUtil.toFileObject(f);
    }

    private static EditorCookie openEditor(Path p) {
        FileObject fo = fo(p);
        if (fo == null || !fo.isData()) {
            return null;
        }
        try {
            DataObject d = DataObject.find(fo);
            EditorCookie ec = d.getLookup().lookup(EditorCookie.class);
            return ec != null && ec.getDocument() != null ? ec : null;
        } catch (DataObjectNotFoundException ex) {
            return null;
        }
    }

    private static void onEdt(Runnable r) {
        if (EventQueue.isDispatchThread()) {
            r.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(r);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new IllegalStateException(ex.getCause());
        }
    }

    @Override
    public String openText(Path file) {
        EditorCookie ec = openEditor(file);
        if (ec == null) {
            return null;
        }
        StyledDocument d = ec.getDocument();
        if (d == null) {
            return null;
        }
        AtomicReference<String> t = new AtomicReference<>();
        d.render(() -> {
            try {
                t.set(d.getText(0, d.getLength()));
            } catch (BadLocationException ex) {
                t.set(null);
            }
        });
        return t.get();
    }

    @Override
    public boolean replaceOpenText(Path file, String text) {
        EditorCookie ec = openEditor(file);
        if (ec == null) {
            return false;
        }
        AtomicBoolean ok = new AtomicBoolean(false);
        onEdt(() -> {
            StyledDocument d = ec.getDocument();
            if (d == null) {
                return;
            }
            try {
                String old = d.getText(0, d.getLength());
                int pre = 0;
                int max = Math.min(old.length(), text.length());
                while (pre < max && old.charAt(pre) == text.charAt(pre)) {
                    pre++;
                }
                int suf = 0;
                while (suf < max - pre && old.charAt(old.length() - 1 - suf) == text.charAt(text.length() - 1 - suf)) {
                    suf++;
                }
                final int p = pre;
                final int removeLen = old.length() - pre - suf;
                final String insert = text.substring(pre, text.length() - suf);
                BadLocationException[] err = {null};
                NbDocument.runAtomicAsUser(d, () -> {
                    try {
                        if (removeLen > 0) {
                            d.remove(p, removeLen);
                        }
                        if (!insert.isEmpty()) {
                            d.insertString(p, insert, null);
                        }
                    } catch (BadLocationException ex) {
                        err[0] = ex;
                    }
                });
                if (err[0] == null) {
                    ec.saveDocument();
                    ok.set(true);
                }
            } catch (BadLocationException | IOException ex) {
                ok.set(false);
            }
        });
        return ok.get();
    }

    @Override
    public void refresh(Path fileOrFolder) {
        Path p = fileOrFolder;
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        if (p != null) {
            FileUtil.refreshFor(FileUtil.normalizeFile(p.toFile()));
        }
    }

    @Override
    public void open(Path file, int line) {
        refresh(file);
        FileObject fo = fo(file);
        if (fo == null) {
            return;
        }
        EventQueue.invokeLater(() -> {
            try {
                DataObject d = DataObject.find(fo);
                LineCookie lc = d.getLookup().lookup(LineCookie.class);
                if (lc != null) {
                    int idx = Math.max(0, line - 1);
                    Line.Set set = lc.getLineSet();
                    Line l;
                    try {
                        l = set.getOriginal(idx);
                    } catch (IndexOutOfBoundsException ex) {
                        l = set.getOriginal(0);
                    }
                    l.show(Line.ShowOpenType.OPEN, Line.ShowVisibilityType.FOCUS);
                    return;
                }
                EditorCookie ec = d.getLookup().lookup(EditorCookie.class);
                if (ec != null) {
                    ec.open();
                }
            } catch (DataObjectNotFoundException | RuntimeException ex) {
                // nothing to show
            }
        });
    }
}
