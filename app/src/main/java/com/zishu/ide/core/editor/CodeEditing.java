package com.zishu.ide.core.editor;

/** Pure text-editing helpers: auto indent, bracket matching, line navigation. */
public final class CodeEditing {
    private CodeEditing() {
    }

    /**
     * The text to insert when Enter is pressed at caret: a newline plus the current line's indent,
     * one level deeper after an opening bracket (and, between "{" and "}", an extra blank line is
     * produced by the caller using {@link #newlineWithIndent}'s returned caret offset).
     */
    public static final class Enter {
        public final String insert;
        /** Offset of the caret from the start of {@code insert}. */
        public final int caretOffset;

        Enter(String insert, int caretOffset) {
            this.insert = insert;
            this.caretOffset = caretOffset;
        }
    }

    public static Enter newlineWithIndent(CharSequence text, int caret, int indentSize, boolean useTabs) {
        int lineStart = caret;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int i = lineStart;
        while (i < caret && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        String indent = text.subSequence(lineStart, i).toString();

        int before = caret - 1;
        while (before >= lineStart && (text.charAt(before) == ' ' || text.charAt(before) == '\t')) {
            before--;
        }
        char prev = before >= lineStart ? text.charAt(before) : 0;
        int after = caret;
        while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\t')) {
            after++;
        }
        char next = after < text.length() ? text.charAt(after) : 0;

        String unit = unit(indentSize, useTabs);
        boolean opens = prev == '{' || prev == '(' || prev == '[';
        boolean closesNext = (prev == '{' && next == '}') || (prev == '(' && next == ')') || (prev == '[' && next == ']');
        if (closesNext) {
            String first = "\n" + indent + unit;
            return new Enter(first + "\n" + indent, first.length());
        }
        String ins = "\n" + indent + (opens ? unit : "");
        return new Enter(ins, ins.length());
    }

    /**
     * Indentation for a newline that has ALREADY been inserted at nlPos (as an editor's text watcher
     * sees it). Returns the text to insert directly after the newline and where the caret should
     * end up relative to that position.
     */
    public static Enter indentAfterNewline(CharSequence text, int nlPos, int indentSize, boolean useTabs) {
        int lineStart = nlPos;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        int i = lineStart;
        while (i < nlPos && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        String indent = text.subSequence(lineStart, i).toString();
        int before = nlPos - 1;
        while (before >= lineStart && (text.charAt(before) == ' ' || text.charAt(before) == '\t')) {
            before--;
        }
        char prev = before >= lineStart ? text.charAt(before) : 0;
        int after = nlPos + 1;
        while (after < text.length() && (text.charAt(after) == ' ' || text.charAt(after) == '\t')) {
            after++;
        }
        char next = after < text.length() ? text.charAt(after) : 0;
        String unit = unit(indentSize, useTabs);
        boolean opens = prev == '{' || prev == '(' || prev == '[';
        boolean closesNext = (prev == '{' && next == '}') || (prev == '(' && next == ')') || (prev == '[' && next == ']');
        if (closesNext) {
            String inner = indent + unit;
            return new Enter(inner + "\n" + indent, inner.length());
        }
        String ins = indent + (opens ? unit : "");
        return new Enter(ins, ins.length());
    }

    public static String unit(int indentSize, boolean useTabs) {
        if (useTabs) {
            return "\t";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indentSize; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Offset of the bracket matching the one at (or just before) caret, or -1. Brackets inside
     * strings and comments, as given by skip, are ignored. skip may be null.
     */
    public static int matchingBracket(CharSequence text, int caret, boolean[] skip) {
        int pos = -1;
        if (caret < text.length() && isBracket(text.charAt(caret)) && !(skip != null && skip[caret])) {
            pos = caret;
        } else if (caret > 0 && isBracket(text.charAt(caret - 1)) && !(skip != null && skip[caret - 1])) {
            pos = caret - 1;
        }
        if (pos < 0) {
            return -1;
        }
        char c = text.charAt(pos);
        char partner = partnerOf(c);
        boolean forward = c == '(' || c == '[' || c == '{';
        int depth = 0;
        int step = forward ? 1 : -1;
        for (int i = pos; i >= 0 && i < text.length(); i += step) {
            if (skip != null && skip[i]) {
                continue;
            }
            char d = text.charAt(i);
            if (d == c) {
                depth++;
            } else if (d == partner) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Marks string and comment characters, so bracket matching can ignore them. */
    public static boolean[] skipMask(FileLanguage lang, CharSequence text) {
        boolean[] skip = new boolean[text.length()];
        for (Highlighter.Token t : Highlighter.highlight(lang, text)) {
            if (t.type == TokenType.STRING || t.type == TokenType.COMMENT) {
                for (int i = t.start; i < t.end && i < skip.length; i++) {
                    skip[i] = true;
                }
            }
        }
        return skip;
    }

    private static boolean isBracket(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}';
    }

    private static char partnerOf(char c) {
        switch (c) {
            case '(': return ')';
            case ')': return '(';
            case '[': return ']';
            case ']': return '[';
            case '{': return '}';
            default: return '{';
        }
    }

    /** Offset of the start of a 1-based line, clamped to the text; line beyond the end gives the end. */
    public static int offsetOfLine(CharSequence text, int line) {
        if (line <= 1) {
            return 0;
        }
        int current = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                current++;
                if (current == line) {
                    return i + 1;
                }
            }
        }
        return text.length();
    }

    public static int lineCount(CharSequence text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    public static int lineOfOffset(CharSequence text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Removes one indent level (up to {@code indentSize} spaces or one tab) from the start of a line. */
    public static int outdentAmount(CharSequence text, int lineStart, int indentSize) {
        if (lineStart < text.length() && text.charAt(lineStart) == '\t') {
            return 1;
        }
        int n = 0;
        while (n < indentSize && lineStart + n < text.length() && text.charAt(lineStart + n) == ' ') {
            n++;
        }
        return n;
    }
}
