package com.zishu.ide.core.terminal;

import java.util.ArrayList;
import java.util.List;

/** Splits a typed line into words the way a shell does for simple cases: quotes and backslash escapes. */
public final class CommandLine {
    private CommandLine() {
    }

    /** @return the words, or null when a quote is left open */
    public static List<String> tokenize(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < line.length()
                        && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                    cur.append(line.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < line.length()) {
                cur.append(line.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
            } else {
                cur.append(c);
                inWord = true;
            }
        }
        if (quote != 0) {
            return null;
        }
        if (inWord) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Quotes a path for safe use in a POSIX shell command. */
    public static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
