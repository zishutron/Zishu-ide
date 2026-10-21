package com.zishu.ide.core.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Iterator;

/** Small I/O helpers that only use APIs available on Android API 21+. */
public final class IoUtil {
    public static final Charset UTF8 = Charset.forName("UTF-8");

    private IoUtil() {
    }

    public static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    /** Reads a stream fully; fails instead of growing past maxBytes. */
    public static byte[] readAll(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Data is larger than the allowed " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static byte[] readFile(File f, long maxBytes) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in, maxBytes);
        } finally {
            closeQuietly(in);
        }
    }

    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    /** Writes via a temp file in the same directory and renames it over the target. */
    public static void writeAtomic(File target, byte[] data) throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create directory " + dir);
        }
        File tmp = new File(dir, "." + target.getName() + ".tmp" + System.nanoTime());
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            closeQuietly(out);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        closeQuietly(out);
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Could not replace " + target.getName());
        }
    }

    public static void writeUtf8Atomic(File target, String text) throws IOException {
        writeAtomic(target, text.getBytes(UTF8));
    }

    public static String readUtf8(File f, long maxBytes) throws IOException {
        return new String(readFile(f, maxBytes), UTF8);
    }

    public static boolean isSymlink(File file) throws IOException {
        File parent = file.getParentFile();
        File candidate = parent == null ? file : new File(parent.getCanonicalFile(), file.getName());
        return !candidate.getCanonicalFile().equals(candidate.getAbsoluteFile());
    }

    /** Deletes a file or directory tree. Symbolic links are removed, never followed. */
    public static void deleteRecursively(File f) throws IOException {
        if (!f.exists() && !isSymlink(f)) {
            return;
        }
        if (f.isDirectory() && !isSymlink(f)) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        if (!f.delete() && (f.exists() || isSymlink(f))) {
            throw new IOException("Could not delete " + f.getName());
        }
    }

    public static String join(String sep, Iterable<String> parts) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = parts.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) {
                sb.append(sep);
            }
        }
        return sb.toString();
    }

    /** Human readable size, e.g. "12.4 MB". */
    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024.0;
            i++;
        }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i]);
    }
}
