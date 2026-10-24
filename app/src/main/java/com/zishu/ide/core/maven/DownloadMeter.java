package com.zishu.ide.core.maven;

import java.util.Locale;

/**
 * Turns byte counts into readable progress lines: percent, size, speed and time left. A line is produced at most every
 * {@code intervalMs}; speed is smoothed so one slow second does not make the estimate jump.
 */
public final class DownloadMeter {
    private final String name;
    private final long startMs;
    private final long intervalMs;
    private long lastMs;
    private long lastBytes;
    private double speed = -1;

    public DownloadMeter(String name, long startMs, long intervalMs) {
        this.name = name;
        this.startMs = startMs;
        this.intervalMs = intervalMs;
        this.lastMs = startMs;
    }

    /** @return a line to show, or null when it is too soon since the last one */
    public String update(long nowMs, long done, long total) {
        if (nowMs - lastMs < intervalMs) {
            return null;
        }
        double dt = (nowMs - lastMs) / 1000.0;
        double instant = (done - lastBytes) / dt;
        speed = speed < 0 ? instant : 0.6 * speed + 0.4 * instant;
        lastMs = nowMs;
        lastBytes = done;
        StringBuilder sb = new StringBuilder("  ").append(name).append(": ");
        if (total > 0) {
            sb.append(percent(done, total)).append("% (").append(mb(done)).append(" of ").append(mb(total)).append("), ");
        } else {
            sb.append(mb(done)).append(" downloaded, ");
        }
        sb.append(rate(speed));
        if (total > 0 && speed > 1) {
            sb.append(", about ").append(duration((long) ((total - done) / speed))).append(" left");
        }
        return sb.toString();
    }

    public String finish(long nowMs, long done) {
        double secs = Math.max(0.001, (nowMs - startMs) / 1000.0);
        return "  " + name + ": done, " + mb(done) + " in " + duration((long) Math.ceil(secs)) + " (" + rate(done / secs) + " average)";
    }

    static int percent(long done, long total) {
        return (int) Math.min(100, done * 100 / total);
    }

    static String mb(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String rate(double bytesPerSec) {
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    public static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        long m = seconds / 60;
        return m + " min " + (seconds % 60) + " s";
    }
}
