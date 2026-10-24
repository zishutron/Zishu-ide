package com.zishu.ide.core.maven;

/**
 * Writes download progress as text lines: a "Downloading x" line for every library and, for big files, percent, size,
 * speed and time left every two seconds. Used by builds and by the first-run setup in the terminal.
 */
public final class DownloadReporter implements DownloadProgress, MavenCache.Listener {
    /** Where the lines go. */
    public interface Sink {
        void line(String text);
    }

    private static final long SHOW_FROM = 512L * 1024;
    private final Sink sink;
    private String current;
    private DownloadMeter meter;
    private boolean finished;

    private DownloadReporter(Sink sink) {
        this.sink = sink;
    }

    /** Connects a reporter to the cache; every download from now on is reported to {@code sink}. */
    public static void attach(MavenCache cache, Sink sink) {
        DownloadReporter r = new DownloadReporter(sink);
        cache.setListener(r);
        cache.setProgress(r);
    }

    @Override
    public void downloading(String path) {
        sink.line("Downloading " + path.substring(path.lastIndexOf('/') + 1));
    }

    @Override
    public void bytes(String path, long done, long total) {
        long now = System.currentTimeMillis();
        if (!path.equals(current)) {
            current = path;
            finished = false;
            meter = new DownloadMeter(path.substring(path.lastIndexOf('/') + 1), now, 2000);
        }
        if (finished) {
            return;
        }
        long size = total > 0 ? total : done;
        if (size < SHOW_FROM) {
            return;
        }
        if (total > 0 && done >= total) {
            finished = true;
            sink.line(meter.finish(now, done));
            return;
        }
        String l = meter.update(now, done, total);
        if (l != null) {
            sink.line(l);
        }
    }
}
