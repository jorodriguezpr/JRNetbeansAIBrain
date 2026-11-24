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
package net.microrepair.jrnetbeansaibrain.core;

import java.util.ArrayList;
import java.util.List;

/** Line diff (common prefix/suffix + LCS on the middle) with unified-diff and HTML output. */
public final class Diff {

    /** One line of the diff: ' ' same, '-' removed, '+' added. */
    public static final class Line {
        public final char op;
        public final String text;
        public final int oldNo;
        public final int newNo;

        Line(char op, String text, int oldNo, int newNo) {
            this.op = op;
            this.text = text;
            this.oldNo = oldNo;
            this.newNo = newNo;
        }
    }

    public final List<Line> lines = new ArrayList<>();
    public int added;
    public int removed;

    private Diff() {
    }

    public static String[] split(String s) {
        if (s == null || s.isEmpty()) {
            return new String[0];
        }
        String t = s.replace("\r\n", "\n");
        if (t.endsWith("\n")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.split("\n", -1);
    }

    public static Diff of(String before, String after) {
        String[] a = split(before);
        String[] b = split(after);
        Diff d = new Diff();
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) {
            pre++;
        }
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) {
            suf++;
        }
        for (int k = 0; k < pre; k++) {
            d.lines.add(new Line(' ', a[k], k + 1, k + 1));
        }
        int n = a.length - pre - suf;
        int m = b.length - pre - suf;
        if ((long) n * m > 4_000_000L) {
            for (int k = 0; k < n; k++) {
                d.lines.add(new Line('-', a[pre + k], pre + k + 1, -1));
            }
            for (int k = 0; k < m; k++) {
                d.lines.add(new Line('+', b[pre + k], -1, pre + k + 1));
            }
        } else {
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    lcs[i][j] = a[pre + i].equals(b[pre + j]) ? lcs[i + 1][j + 1] + 1
                            : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && a[pre + i].equals(b[pre + j])) {
                    d.lines.add(new Line(' ', a[pre + i], pre + i + 1, pre + j + 1));
                    i++;
                    j++;
                } else if (i < n && (j >= m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                    d.lines.add(new Line('-', a[pre + i], pre + i + 1, -1));
                    i++;
                } else {
                    d.lines.add(new Line('+', b[pre + j], -1, pre + j + 1));
                    j++;
                }
            }
        }
        for (int k = 0; k < suf; k++) {
            int ai = a.length - suf + k;
            int bi = b.length - suf + k;
            d.lines.add(new Line(' ', a[ai], ai + 1, bi + 1));
        }
        for (Line l : d.lines) {
            if (l.op == '+') {
                d.added++;
            } else if (l.op == '-') {
                d.removed++;
            }
        }
        return d;
    }

    /** Indexes of the lines to show: every change plus {@code context} lines around it. */
    private boolean[] visible(int context) {
        boolean[] v = new boolean[lines.size()];
        for (int k = 0; k < lines.size(); k++) {
            if (lines.get(k).op != ' ') {
                for (int j = Math.max(0, k - context); j <= Math.min(lines.size() - 1, k + context); j++) {
                    v[j] = true;
                }
            }
        }
        return v;
    }

    /** Unified diff text with {@code context} lines around each change (max {@code maxLines} lines). */
    public String unified(int context, int maxLines) {
        boolean[] v = visible(context);
        StringBuilder b = new StringBuilder();
        int shown = 0;
        boolean gap = false;
        for (int k = 0; k < lines.size(); k++) {
            if (!v[k]) {
                gap = true;
                continue;
            }
            if (gap && b.length() > 0) {
                b.append("...\n");
            }
            gap = false;
            Line l = lines.get(k);
            b.append(l.op).append(' ').append(l.text).append('\n');
            if (++shown >= maxLines) {
                b.append("[... diff cut]\n");
                break;
            }
        }
        return b.toString();
    }

    /** HTML table for the approval dialog. */
    public String html(int context, boolean dark) {
        boolean[] v = visible(context);
        String addBg = dark ? "#1f3d26" : "#e6ffec";
        String delBg = dark ? "#4a2227" : "#ffebe9";
        String num = dark ? "#8b949e" : "#6e7781";
        StringBuilder b = new StringBuilder("<table cellspacing='0' cellpadding='1' width='100%'>");
        boolean gap = false;
        int shown = 0;
        for (int k = 0; k < lines.size(); k++) {
            if (!v[k]) {
                gap = true;
                continue;
            }
            if (gap) {
                b.append("<tr><td colspan='3' style='color:").append(num).append("'>&nbsp;...</td></tr>");
            }
            gap = false;
            Line l = lines.get(k);
            String bg = l.op == '+' ? addBg : l.op == '-' ? delBg : null;
            b.append("<tr").append(bg == null ? "" : " bgcolor='" + bg + "'").append("><td style='color:").append(num)
                    .append("'><pre>").append(l.oldNo > 0 ? l.oldNo : "").append("</pre></td><td style='color:")
                    .append(num).append("'><pre>").append(l.newNo > 0 ? l.newNo : "").append("</pre></td><td><pre>")
                    .append(l.op == ' ' ? "  " : l.op + " ").append(Markdown.esc(l.text)).append("</pre></td></tr>");
            if (++shown > 3000) {
                b.append("<tr><td colspan='3'>[... diff cut]</td></tr>");
                break;
            }
        }
        return b.append("</table>").toString();
    }
}
