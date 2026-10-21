package com.zishu.ide.core.build;

/** Collects log lines for a build, forwards them to a listener and removes secrets. */
public final class BuildLog {
    private static final int MAX_CHARS = 2_000_000;

    private final StringBuilder text = new StringBuilder();
    private final BuildListener listener;
    private final String[] secrets;

    public BuildLog(BuildListener listener, String... secrets) {
        this.listener = listener;
        this.secrets = secrets;
    }

    public synchronized void line(String s) {
        String clean = redact(s);
        if (text.length() < MAX_CHARS) {
            text.append(clean).append('\n');
        }
        listener.onLog(clean);
    }

    /** Appends a block of text (already multi-line) line by line. */
    public synchronized void block(String s) {
        for (String l : s.split("\n", -1)) {
            line(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
        }
    }

    public synchronized String text() {
        return text.toString();
    }

    public String redact(String s) {
        String out = s;
        for (String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                out = out.replace(secret, "***");
            }
        }
        // GitHub tokens that were not registered as secrets.
        return out.replaceAll("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\\b", "***")
                .replaceAll("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b", "***");
    }
}
