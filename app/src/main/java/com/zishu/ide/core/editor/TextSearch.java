package com.zishu.ide.core.editor;

import com.zishu.ide.core.project.FileManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Find / replace in one text and search across the files of a project. */
public final class TextSearch {

    public static final class Options {
        public boolean caseSensitive;
        public boolean wholeWord;
        public boolean regex;

        public Options() {
        }

        public Options(boolean caseSensitive, boolean wholeWord, boolean regex) {
            this.caseSensitive = caseSensitive;
            this.wholeWord = wholeWord;
            this.regex = regex;
        }
    }

    public static final class Match {
        public final int start;
        public final int end;

        public Match(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    public static final class FileMatch {
        public final String path;
        public final int line;
        public final String lineText;

        public FileMatch(String path, int line, String lineText) {
            this.path = path;
            this.line = line;
            this.lineText = lineText;
        }
    }

    private TextSearch() {
    }

    /** @throws PatternSyntaxException for a bad regular expression */
    public static Pattern compile(String query, Options o) {
        String p = o.regex ? query : Pattern.quote(query);
        if (o.wholeWord) {
            p = "(?<![\\w])(?:" + p + ")(?![\\w])";
        }
        int flags = o.caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        if (o.regex) {
            flags |= Pattern.MULTILINE;
        }
        return Pattern.compile(p, flags);
    }

    public static List<Match> findAll(CharSequence text, String query, Options o, int limit) {
        List<Match> out = new ArrayList<Match>();
        if (query == null || query.length() == 0) {
            return out;
        }
        Matcher m = compile(query, o).matcher(text);
        while (m.find() && out.size() < limit) {
            if (m.end() == m.start()) {
                if (m.end() >= text.length()) {
                    break;
                }
                continue; // skip empty regex matches instead of looping
            }
            out.add(new Match(m.start(), m.end()));
        }
        return out;
    }

    /** Index into matches of the first match starting at or after offset, wrapping around; -1 if empty. */
    public static int nextIndex(List<Match> matches, int offset) {
        if (matches.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).start >= offset) {
                return i;
            }
        }
        return 0;
    }

    public static int previousIndex(List<Match> matches, int offset) {
        if (matches.isEmpty()) {
            return -1;
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            if (matches.get(i).end <= offset) {
                return i;
            }
        }
        return matches.size() - 1;
    }

    /** Replaces every match. With regex on, $1-style group references in the replacement are honoured. */
    public static String replaceAll(String text, String query, String replacement, Options o) {
        if (query == null || query.length() == 0) {
            return text;
        }
        Matcher m = compile(query, o).matcher(text);
        String rep = o.regex ? replacement : Matcher.quoteReplacement(replacement);
        return m.replaceAll(rep);
    }

    /** The replacement text for one match (resolves group references when regex is on). */
    public static String replacementFor(String text, Match match, String query, String replacement, Options o) {
        if (!o.regex) {
            return replacement;
        }
        Matcher m = compile(query, o).matcher(text);
        if (m.find(match.start) && m.start() == match.start && m.end() == match.end) {
            StringBuffer sb = new StringBuffer();
            m.appendReplacement(sb, replacement);
            String full = sb.toString();
            return full.substring(match.start);
        }
        return replacement;
    }

    /**
     * Searches text files below the project root. Skips build output, binary and large files.
     * Stops after maxResults.
     */
    public static List<FileMatch> searchProject(FileManager fm, String query, Options o, int maxResults)
            throws IOException {
        List<FileMatch> out = new ArrayList<FileMatch>();
        if (query == null || query.length() == 0) {
            return out;
        }
        Pattern p = compile(query, o);
        searchDir(fm, "", p, out, maxResults, 0);
        return out;
    }

    private static void searchDir(FileManager fm, String dir, Pattern p, List<FileMatch> out, int max, int depth)
            throws IOException {
        if (depth > 20 || out.size() >= max) {
            return;
        }
        for (FileManager.FileNode n : fm.list(dir)) {
            if (out.size() >= max) {
                return;
            }
            if (n.directory) {
                String name = n.name;
                if (name.equals("build") || name.equals(".gradle") || name.equals(".git") || name.equals(".idea")) {
                    continue;
                }
                searchDir(fm, n.path, p, out, max, depth + 1);
            } else if (n.size <= 512 * 1024) {
                FileManager.TextFile tf;
                try {
                    tf = fm.readText(n.path);
                } catch (FileManager.NotTextException e) {
                    continue;
                }
                String[] lines = tf.text.split("\n", -1);
                for (int i = 0; i < lines.length && out.size() < max; i++) {
                    if (p.matcher(lines[i]).find()) {
                        String shown = lines[i].trim();
                        if (shown.length() > 160) {
                            shown = shown.substring(0, 157) + "...";
                        }
                        out.add(new FileMatch(n.path, i + 1, shown));
                    }
                }
            }
        }
    }
}
