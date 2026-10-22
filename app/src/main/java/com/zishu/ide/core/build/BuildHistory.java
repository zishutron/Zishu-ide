package com.zishu.ide.core.build;

import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.util.MiniJson;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-project record of past builds, kept in {project}/.zishu/builds.json with each build's full
 * log in {project}/.zishu/builds/{id}/build.log, so failed builds can be diagnosed later.
 */
public final class BuildHistory {
    public static final int MAX_ENTRIES = 30;

    public static final class Entry {
        public final String id;
        public final long time;
        public final boolean success;
        public final boolean cancelled;
        public final String engine;
        public final String variant;
        public final long durationMs;
        /** APK path relative to the project directory, or null. */
        public final String apkPath;
        public final long apkSize;
        public final String title;
        public final String runUrl;
        public final String signing;

        public Entry(String id, long time, boolean success, boolean cancelled, String engine, String variant,
                     long durationMs, String apkPath, long apkSize, String title, String runUrl, String signing) {
            this.id = id;
            this.time = time;
            this.success = success;
            this.cancelled = cancelled;
            this.engine = engine;
            this.variant = variant;
            this.durationMs = durationMs;
            this.apkPath = apkPath;
            this.apkSize = apkSize;
            this.title = title;
            this.runUrl = runUrl;
            this.signing = signing;
        }
    }

    private final File projectDir;

    public BuildHistory(File projectDir) {
        this.projectDir = projectDir;
    }

    private File indexFile() {
        return new File(new File(projectDir, ".zishu"), "builds.json");
    }

    public File buildDir(String id) {
        return new File(new File(new File(projectDir, ".zishu"), "builds"), id);
    }

    public File logFile(String id) {
        return new File(buildDir(id), "build.log");
    }

    /** Where a new build with this id should write its APK. */
    public File apkDir(String id) {
        return buildDir(id);
    }

    /** Folder in the project that file managers show (the .zishu folder is hidden). */
    public static final String VISIBLE_APK_DIR = "apk";

    public File visibleApkDir() {
        return new File(projectDir, VISIBLE_APK_DIR);
    }

    /**
     * Copies a built APK into the project's visible {@code apk/} folder (same file name; a newer build of the
     * same app replaces the older copy) and returns the copy.
     */
    public File publishApk(File apk) throws IOException {
        File dir = visibleApkDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File target = new File(dir, apk.getName());
        java.io.InputStream in = new java.io.FileInputStream(apk);
        try {
            IoUtil.writeAtomic(target, IoUtil.readAll(in, 1024L * 1024 * 1024));
        } finally {
            IoUtil.closeQuietly(in);
        }
        return target;
    }

    /** The visible copy of a built APK, or the original when no copy exists. */
    public File preferVisible(File apk) {
        File v = new File(visibleApkDir(), apk.getName());
        return v.isFile() ? v : apk;
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<Entry>();
        File f = indexFile();
        if (!f.isFile()) {
            return out;
        }
        try {
            List<Object> arr = MiniJson.asList(MiniJson.parse(IoUtil.readUtf8(f, 4 * 1024 * 1024)));
            if (arr != null) {
                for (Object o : arr) {
                    Map<String, Object> m = MiniJson.asMap(o);
                    if (m != null && MiniJson.str(m, "id") != null) {
                        out.add(new Entry(MiniJson.str(m, "id"), MiniJson.num(m, "time", 0),
                                MiniJson.bool(m, "success", false), MiniJson.bool(m, "cancelled", false),
                                MiniJson.str(m, "engine"), MiniJson.str(m, "variant"), MiniJson.num(m, "durationMs", 0),
                                MiniJson.str(m, "apkPath"), MiniJson.num(m, "apkSize", 0), MiniJson.str(m, "title"),
                                MiniJson.str(m, "runUrl"), MiniJson.str(m, "signing")));
                    }
                }
            }
        } catch (IOException ignored) {
            // unreadable history is treated as empty
        } catch (MiniJson.JsonException ignored) {
            // corrupt history is treated as empty
        }
        return out;
    }

    /** Stores the result and its log. Returns the entry. Older entries beyond the limit are deleted. */
    public Entry record(String id, BuildResult r) throws IOException {
        File dir = buildDir(id);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        IoUtil.writeUtf8Atomic(logFile(id), r.log == null ? "" : r.log);
        String apkRel = null;
        long size = 0;
        if (r.apk != null) {
            apkRel = relative(r.apk);
            size = r.apk.length();
        }
        Entry e = new Entry(id, System.currentTimeMillis(), r.success, r.cancelled, r.engineId, r.variant,
                r.durationMs, apkRel, size, r.errorTitle, r.runUrl, r.signing);
        List<Entry> all = list();
        all.add(0, e);
        while (all.size() > MAX_ENTRIES) {
            Entry old = all.remove(all.size() - 1);
            IoUtil.deleteRecursively(buildDir(old.id));
        }
        write(all);
        return e;
    }

    public void delete(String id) throws IOException {
        List<Entry> all = list();
        List<Entry> keep = new ArrayList<Entry>();
        for (Entry e : all) {
            if (!e.id.equals(id)) {
                keep.add(e);
            }
        }
        IoUtil.deleteRecursively(buildDir(id));
        write(keep);
    }

    public String readLog(String id) {
        try {
            return IoUtil.readUtf8(logFile(id), 8 * 1024 * 1024);
        } catch (IOException e) {
            return "";
        }
    }

    public File apkFile(Entry e) {
        if (e.apkPath == null) {
            return null;
        }
        File f = new File(projectDir, e.apkPath);
        return f.isFile() ? f : null;
    }

    private String relative(File f) throws IOException {
        String root = projectDir.getCanonicalPath() + File.separator;
        String p = f.getCanonicalPath();
        if (!p.startsWith(root)) {
            throw new IOException("APK is outside the project folder");
        }
        return p.substring(root.length()).replace(File.separatorChar, '/');
    }

    private void write(List<Entry> entries) throws IOException {
        List<Object> arr = new ArrayList<Object>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", e.id);
            m.put("time", Long.valueOf(e.time));
            m.put("success", Boolean.valueOf(e.success));
            m.put("cancelled", Boolean.valueOf(e.cancelled));
            m.put("engine", e.engine);
            m.put("variant", e.variant);
            m.put("durationMs", Long.valueOf(e.durationMs));
            m.put("apkPath", e.apkPath);
            m.put("apkSize", Long.valueOf(e.apkSize));
            m.put("title", e.title);
            m.put("runUrl", e.runUrl);
            m.put("signing", e.signing);
            arr.add(m);
        }
        IoUtil.writeUtf8Atomic(indexFile(), MiniJson.encode(arr));
    }
}
