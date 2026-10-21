package com.zishu.ide.core.editor;

/** A syntax problem found by {@link SyntaxChecker}. Line and column are 1-based. */
public final class Diagnostic {
    public enum Severity { ERROR, WARNING }

    public final Severity severity;
    public final int line;
    public final int column;
    public final String message;

    public Diagnostic(Severity severity, int line, int column, String message) {
        this.severity = severity;
        this.line = line;
        this.column = column;
        this.message = message;
    }

    @Override
    public String toString() {
        return line + ":" + column + " " + severity.name().toLowerCase(java.util.Locale.US) + ": " + message;
    }
}
