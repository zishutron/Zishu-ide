package com.zishu.ide.core.project;

import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZIP import/export with protection against path traversal ("zip slip"), absolute paths,
 * duplicate entries and decompression bombs. Sizes are counted while inflating; the sizes
 * declared in the archive headers are never trusted.
 */
public final class SafeZip {

    /** Thrown for archives that are malicious or malformed. */
    public static final class ZipSecurityException extends IOException {
        public ZipSecurityException(String message) {
            super(message);
        }
    }

    public static final class Limits {
        public int maxEntries = 20000;
        public long maxTotalBytes = 500L * 1024 * 1024;
        public long maxEntryBytes = 200L * 1024 * 1024;
    }

    public static final class Result {
        public final int files;
        public final int directories;
        public final long bytes;

        Result(int files, int directories, long bytes) {
            this.files = files;
            this.directories = directories;
            this.bytes = bytes;
        }
    }

    private SafeZip() {
    }

    /**
     * Normalises an entry name to a safe relative path with '/' separators.
     *
     * @return the safe path (no trailing slash), or null for entries that should be skipped silently
     * @throws ZipSecurityException when the name is unsafe
     */
    public static String sanitizeEntryName(String raw) throws ZipSecurityException {
        if (raw == null || raw.length() == 0) {
            throw new ZipSecurityException("Archive contains an entry with an empty name");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new ZipSecurityException("Archive contains an entry name with a NUL character");
        }
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0)))) {
            throw new ZipSecurityException("Archive contains an absolute path: " + abbreviate(raw));
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20) {
                throw new ZipSecurityException("Archive contains a control character in an entry name");
            }
        }
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.length() == 0) {
            throw new ZipSecurityException("Archive contains an entry with an empty name");
        }
        List<String> kept = new ArrayList<String>();
        for (String seg : name.split("/", -1)) {
            if (seg.equals("..")) {
                throw new ZipSecurityException("Archive entry escapes the target directory: " + abbreviate(raw));
            }
            if (seg.length() == 0 || seg.equals(".")) {
                continue;
            }
            kept.add(seg);
        }
        if (kept.isEmpty()) {
            return null;
        }
        if (kept.get(0).equals("__MACOSX") || kept.get(kept.size() - 1).equals(".DS_Store")) {
            return null;
        }
        return IoUtil.join("/", kept);
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    /**
     * Extracts into destDir, which must not exist yet or must be empty (files are never overwritten).
     * On any failure everything this call created is removed again.
     */
    public static Result extract(InputStream in, File destDir, Limits limits) throws IOException {
        boolean created = false;
        if (destDir.exists()) {
            String[] existing = destDir.list();
            if (!destDir.isDirectory() || (existing != null && existing.length > 0)) {
                throw new FileManager.FileExistsException("Target directory already exists and is not empty: " + destDir.getName());
            }
        } else {
            if (!destDir.mkdirs()) {
                throw new IOException("Cannot create directory " + destDir);
            }
            created = true;
        }
        try {
            return doExtract(in, destDir, limits, true);
        } catch (IOException e) {
            cleanup(destDir, created);
            throw e;
        } catch (RuntimeException e) {
            cleanup(destDir, created);
            throw e;
        }
    }

    /** Reads the whole archive and applies every check without writing anything. */
    public static Result validate(InputStream in, Limits limits) throws IOException {
        return doExtract(in, null, limits, false);
    }

    private static void cleanup(File destDir, boolean created) {
        try {
            if (created) {
                IoUtil.deleteRecursively(destDir);
            } else {
                File[] children = destDir.listFiles();
                if (children != null) {
                    for (File c : children) {
                        IoUtil.deleteRecursively(c);
                    }
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static Result doExtract(InputStream in, File destDir, Limits limits, boolean write) throws IOException {
        String destCanonical = write ? destDir.getCanonicalPath() : null;
        ZipInputStream zin = new ZipInputStream(in);
        Set<String> seen = new HashSet<String>();
        int entries = 0;
        int files = 0;
        int dirs = 0;
        long total = 0;
        byte[] buf = new byte[16384];
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            if (++entries > limits.maxEntries) {
                throw new ZipSecurityException("Archive has more than " + limits.maxEntries + " entries");
            }
            String name = sanitizeEntryName(e.getName());
            if (name == null) {
                zin.closeEntry();
                continue;
            }
            if (!seen.add(name)) {
                throw new ZipSecurityException("Archive contains the same path twice: " + abbreviate(name));
            }
            File target = null;
            if (write) {
                target = new File(destDir, name);
                String canonical = target.getCanonicalPath();
                if (!canonical.equals(destCanonical) && !canonical.startsWith(destCanonical + File.separator)) {
                    throw new ZipSecurityException("Archive entry escapes the target directory: " + abbreviate(name));
                }
            }
            if (e.isDirectory()) {
                dirs++;
                if (write && !target.isDirectory() && !target.mkdirs()) {
                    throw new IOException("Cannot create directory " + name);
                }
                zin.closeEntry();
                continue;
            }
            files++;
            OutputStream out = null;
            try {
                if (write) {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Cannot create directory for " + name);
                    }
                    if (target.exists()) {
                        throw new ZipSecurityException("Archive entry conflicts with another entry: " + abbreviate(name));
                    }
                    out = new FileOutputStream(target);
                }
                long entryBytes = 0;
                int n;
                while ((n = zin.read(buf)) != -1) {
                    entryBytes += n;
                    total += n;
                    if (entryBytes > limits.maxEntryBytes) {
                        throw new ZipSecurityException("An archive entry is larger than " + IoUtil.formatSize(limits.maxEntryBytes));
                    }
                    if (total > limits.maxTotalBytes) {
                        throw new ZipSecurityException("Archive expands to more than " + IoUtil.formatSize(limits.maxTotalBytes));
                    }
                    if (out != null) {
                        out.write(buf, 0, n);
                    }
                }
            } finally {
                IoUtil.closeQuietly(out);
            }
            zin.closeEntry();
        }
        if (entries == 0) {
            throw new IOException("The file is not a ZIP archive or it is empty");
        }
        return new Result(files, dirs, total);
    }

    /** Directory names never packed into exports or cloud builds. */
    public static final Set<String> DEFAULT_EXCLUDES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("build", ".gradle", ".git", ".idea", ".zishu", ".cxx")));

    /**
     * Zips a directory. Entries are sorted so the output is reproducible; symbolic links are
     * skipped; local.properties is excluded because it may hold machine specific paths.
     */
    public static long zipDirectory(File srcDir, OutputStream out, Set<String> excludeDirNames, long maxBytes)
            throws IOException {
        ZipOutputStream zout = new ZipOutputStream(out);
        long[] total = {0};
        try {
            addDir(zout, srcDir, "", excludeDirNames, maxBytes, total);
            zout.finish();
        } finally {
            zout.flush();
        }
        return total[0];
    }

    private static void addDir(ZipOutputStream zout, File dir, String prefix, Set<String> excludes,
                               long maxBytes, long[] total) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children);
        for (File f : children) {
            String name = f.getName();
            if (IoUtil.isSymlink(f)) {
                continue;
            }
            if (f.isDirectory()) {
                if (excludes != null && excludes.contains(name)) {
                    continue;
                }
                zout.putNextEntry(new ZipEntry(prefix + name + "/"));
                zout.closeEntry();
                addDir(zout, f, prefix + name + "/", excludes, maxBytes, total);
            } else if (f.isFile()) {
                if (prefix.length() == 0 && name.equals("local.properties")) {
                    continue;
                }
                total[0] += f.length();
                if (total[0] > maxBytes) {
                    throw new IOException("The project is larger than the allowed " + IoUtil.formatSize(maxBytes));
                }
                zout.putNextEntry(new ZipEntry(prefix + name));
                FileInputStream in = new FileInputStream(f);
                try {
                    IoUtil.copy(in, zout);
                } finally {
                    IoUtil.closeQuietly(in);
                }
                zout.closeEntry();
            }
        }
    }
}
