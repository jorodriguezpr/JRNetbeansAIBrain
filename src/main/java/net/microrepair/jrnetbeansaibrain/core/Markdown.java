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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown to the simple HTML that Swing's HTML renderer understands (headings, lists, bold,
 * italic, inline code, links, fenced code blocks). Code blocks are collected so the chat window
 * can offer "Insert at cursor" and "Copy" links for them.
 */
public final class Markdown {

    private static final Pattern FENCE = Pattern.compile("^\\s*(```+|~~~+)\\s*([A-Za-z0-9_+#.-]*).*$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    /** A project file reference such as app/Models/Post.php or routes/web.php:12 (linked to open it). */
    private static final String FILE = "[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\.(?:php|js|mjs|ts|jsx|tsx|vue|css|scss|"
            + "json|md|xml|yml|yaml|env|example|html|txt|sql|ini|neon|lock|sh|cmd|ps1|java|py)(?::\\d+(?:-\\d+)?)?";
    private static final Pattern FILE_REF = Pattern.compile(FILE);
    private static final Pattern BARE_REF = Pattern.compile("(^|[\\s(])(" + "[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+"
            + "\\.(?:php|js|ts|vue|css|json|blade\\.php)(?::\\d+)?)(?=[\\s),.;:]|$)");

    private Markdown() {
    }

    /** A fenced block: language and text. */
    public static final class Block {
        public final String lang;
        public final String code;

        public Block(String lang, String code) {
            this.lang = lang;
            this.code = code;
        }
    }

    /**
     * Render markdown. Code blocks are appended to {@code blocks}; their links use the index in that list
     * ({@code code-insert:N}, {@code code-copy:N}). An unclosed fence (still streaming) is shown as code.
     */
    public static String toHtml(String md, List<Block> blocks) {
        StringBuilder out = new StringBuilder();
        String[] lines = md.replace("\r\n", "\n").split("\n", -1);
        StringBuilder para = new StringBuilder();
        String list = null;
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            Matcher fm = FENCE.matcher(line);
            if (fm.matches()) {
                flushPara(out, para);
                list = closeList(out, list);
                String fence = fm.group(1);
                String lang = fm.group(2);
                StringBuilder code = new StringBuilder();
                int j = k + 1;
                boolean closed = false;
                for (; j < lines.length; j++) {
                    if (lines[j].trim().startsWith(fence.substring(0, 3)) && lines[j].trim().replace(fence.charAt(0), ' ')
                            .isBlank()) {
                        closed = true;
                        break;
                    }
                    if (code.length() > 0) {
                        code.append('\n');
                    }
                    code.append(lines[j]);
                }
                k = j;
                int idx = blocks.size();
                blocks.add(new Block(lang, code.toString()));
                out.append("<table class='code' width='100%' cellspacing='0' cellpadding='6'><tr><td class='codehead'>")
                        .append(lang.isEmpty() ? "code" : esc(lang));
                if (closed) {
                    out.append(" &nbsp; <a href='code-insert:").append(idx).append("'>Insert at cursor</a>")
                            .append(" &nbsp; <a href='code-copy:").append(idx).append("'>Copy</a>");
                }
                out.append("</td></tr><tr><td class='codebody'><pre>").append(esc(code.toString()))
                        .append("</pre></td></tr></table>");
                continue;
            }
            Matcher hm = HEADING.matcher(line);
            Matcher nm = NUMBERED.matcher(line);
            Matcher bm = BULLET.matcher(line);
            if (hm.matches()) {
                flushPara(out, para);
                list = closeList(out, list);
                int level = Math.min(4, hm.group(1).length() + 1);
                out.append("<h").append(level).append('>').append(inline(hm.group(2))).append("</h").append(level)
                        .append('>');
            } else if (bm.matches() && !line.trim().matches("^[-*_]{3,}$")) {
                flushPara(out, para);
                if (!"ul".equals(list)) {
                    list = closeList(out, list);
                    out.append("<ul>");
                    list = "ul";
                }
                out.append("<li>").append(inline(bm.group(1))).append("</li>");
            } else if (nm.matches()) {
                flushPara(out, para);
                if (!"ol".equals(list)) {
                    list = closeList(out, list);
                    out.append("<ol>");
                    list = "ol";
                }
                out.append("<li>").append(inline(nm.group(2))).append("</li>");
            } else if (line.trim().matches("^[-*_]{3,}$")) {
                flushPara(out, para);
                list = closeList(out, list);
                out.append("<hr>");
            } else if (line.isBlank()) {
                flushPara(out, para);
                list = closeList(out, list);
            } else {
                if (list != null && line.startsWith("  ")) {
                    out.append("<div class='cont'>").append(inline(line.trim())).append("</div>");
                    continue;
                }
                list = closeList(out, list);
                if (para.length() > 0) {
                    para.append("<br>");
                }
                para.append(inline(line));
            }
        }
        flushPara(out, para);
        closeList(out, list);
        return out.toString();
    }

    private static void flushPara(StringBuilder out, StringBuilder para) {
        if (para.length() > 0) {
            out.append("<p>").append(para).append("</p>");
            para.setLength(0);
        }
    }

    private static String closeList(StringBuilder out, String list) {
        if (list != null) {
            out.append("</").append(list).append('>');
        }
        return null;
    }

    /** Inline markdown: `code`, **bold**, *italic*, [text](url). */
    static String inline(String s) {
        StringBuilder b = new StringBuilder();
        String[] parts = s.split("`", -1);
        for (int k = 0; k < parts.length; k++) {
            if (k % 2 == 1 && k < parts.length - 1) {
                String code = parts[k];
                if (FILE_REF.matcher(code).matches()) {
                    b.append("<a href='jrbrain:open:").append(esc(code)).append("'><code>").append(esc(code))
                            .append("</code></a>");
                } else {
                    b.append("<code>").append(esc(code)).append("</code>");
                }
            } else {
                String t = esc(k % 2 == 1 ? "`" + parts[k] : parts[k]);
                t = t.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
                t = t.replaceAll("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])", "<i>$1</i>");
                t = t.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)\\s]+|jrbrain:[a-z0-9:]+)\\)", "<a href='$2'>$1</a>");
                t = BARE_REF.matcher(t).replaceAll("$1<a href='jrbrain:open:$2'>$2</a>");
                b.append(t);
            }
        }
        return b.toString();
    }

    public static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '&': b.append("&amp;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
}
