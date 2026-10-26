package com.zishu.ide.core.ondevice;

import com.zishu.ide.core.maven.DownloadMeter;

import java.util.ArrayList;
import java.util.List;

/** Measures how long each build stage took, so the build log can show where the time went. */
public final class StageTimer {
    private final List<String> names = new ArrayList<String>();
    private final List<Long> millis = new ArrayList<Long>();
    private long last;
    private final long start;

    public StageTimer() {
        this(System.currentTimeMillis());
    }

    public StageTimer(long nowMillis) {
        this.start = nowMillis;
        this.last = nowMillis;
    }

    /** Ends the current stage under {@code name} (time since the previous mark). */
    public void mark(String name) {
        mark(name, System.currentTimeMillis());
    }

    public void mark(String name, long nowMillis) {
        names.add(name);
        millis.add(Math.max(0, nowMillis - last));
        last = nowMillis;
    }

    /** For example "Time: libraries 2 s, resources 5 s, Kotlin 1 min 20 s, D8 <1 s (total 1 min 28 s)". */
    public String summary() {
        StringBuilder sb = new StringBuilder("Time: ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(names.get(i)).append(' ').append(format(millis.get(i)));
        }
        sb.append(" (total ").append(format(last - start)).append(')');
        return sb.toString();
    }

    static String format(long ms) {
        if (ms < 1000) {
            return "<1 s";
        }
        return DownloadMeter.duration(Math.round(ms / 1000.0));
    }
}
