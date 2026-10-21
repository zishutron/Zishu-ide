package com.zishu.ide.core.validation;

/** One validation finding. Every ERROR carries an action telling the user what to do. */
public final class Issue {
    public enum Severity { ERROR, WARNING, INFO }

    public final Severity severity;
    public final String code;
    public final String message;
    public final String action;
    /** Project-relative path the issue refers to, or null. */
    public final String path;
    /** 1-based line, or 0 when unknown. */
    public final int line;

    public Issue(Severity severity, String code, String message, String action, String path, int line) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.action = action;
        this.path = path;
        this.line = line;
    }

    public static Issue error(String code, String message, String action) {
        return new Issue(Severity.ERROR, code, message, action, null, 0);
    }

    public static Issue error(String code, String message, String action, String path, int line) {
        return new Issue(Severity.ERROR, code, message, action, path, line);
    }

    public static Issue warning(String code, String message, String action) {
        return new Issue(Severity.WARNING, code, message, action, null, 0);
    }

    public static Issue warning(String code, String message, String action, String path, int line) {
        return new Issue(Severity.WARNING, code, message, action, path, line);
    }

    public static Issue info(String code, String message) {
        return new Issue(Severity.INFO, code, message, null, null, 0);
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity.name()).append(":\n").append(message);
        if (path != null) {
            sb.append("\n(").append(path);
            if (line > 0) {
                sb.append(':').append(line);
            }
            sb.append(')');
        }
        if (action != null && action.length() > 0) {
            sb.append("\n\nACTION:\n").append(action);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return format();
    }
}
