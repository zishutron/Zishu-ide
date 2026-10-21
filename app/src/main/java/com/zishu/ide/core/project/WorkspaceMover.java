package com.zishu.ide.core.project;

import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves every project from one workspace folder to another (for example from app-private storage
 * to a visible folder on the phone). Each project is copied completely and checked before the
 * original is deleted, so a failure never loses a project. Symbolic links are not followed.
 */
public final class WorkspaceMover {
    private WorkspaceMover() {
    }

    public static final class Result {
        public final int moved;
        public final List<String> failed;

        Result(int moved, List<String> failed) {
            this.moved = moved;
            this.failed = failed;
        }
    }

    public static Result moveAll(File from, File to) throws IOException {
        if (!from.isDirectory()) {
            return new Result(0, new ArrayList<String>());
        }
        String a = from.getCanonicalPath() + File.separator;
        if (!to.isDirectory() && !to.mkdirs()) {
            throw new IOException("Cannot create " + to);
        }
        String b = to.getCanonicalPath() + File.separator;
        if (a.equals(b)) {
            return new Result(0, new ArrayList<String>());
        }
        if (a.startsWith(b) || b.startsWith(a)) {
            throw new IOException("The two project folders are inside each other.");
        }
        int moved = 0;
        List<String> failed = new ArrayList<String>();
        File[] children = from.listFiles();
        if (children == null) {
            return new Result(0, failed);
        }
        for (File src : children) {
            if (!src.isDirectory() || IoUtil.isSymlink(src) || ProjectInfo.read(src) == null) {
                continue;
            }
            File dst = uniqueDir(to, src.getName());
            try {
                if (!dst.mkdirs()) {
                    throw new IOException("Cannot create " + dst);
                }
                long[] copied = new long[2];
                copyTree(src, dst, copied);
                long[] check = new long[2];
                countTree(src, check);
                long[] got = new long[2];
                countTree(dst, got);
                if (check[0] != got[0] || check[1] != got[1]) {
                    throw new IOException("The copy of " + src.getName() + " does not match the original.");
                }
                IoUtil.deleteRecursively(src);
                moved++;
            } catch (IOException e) {
                failed.add(src.getName() + ": " + e.getMessage());
                try {
                    IoUtil.deleteRecursively(dst); // remove the partial copy; the original is untouched
                } catch (IOException ignored) {
                    // leave it; the original is still complete
                }
            }
        }
        return new Result(moved, failed);
    }

    private static File uniqueDir(File parent, String name) {
        File f = new File(parent, name);
        int n = 2;
        while (f.exists()) {
            f = new File(parent, name + "-" + n++);
        }
        return f;
    }

    private static void copyTree(File src, File dst, long[] counter) throws IOException {
        File[] kids = src.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (IoUtil.isSymlink(k)) {
                continue;
            }
            File t = new File(dst, k.getName());
            if (k.isDirectory()) {
                if (!t.isDirectory() && !t.mkdirs()) {
                    throw new IOException("Cannot create " + t);
                }
                copyTree(k, t, counter);
            } else {
                FileInputStream in = new FileInputStream(k);
                FileOutputStream out = new FileOutputStream(t);
                try {
                    IoUtil.copy(in, out);
                } finally {
                    IoUtil.closeQuietly(in);
                    IoUtil.closeQuietly(out);
                }
            }
        }
    }

    /** counter[0] = files, counter[1] = bytes (symlinks ignored). */
    private static void countTree(File dir, long[] counter) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (IoUtil.isSymlink(k)) {
                continue;
            }
            if (k.isDirectory()) {
                countTree(k, counter);
            } else {
                counter[0]++;
                counter[1] += k.length();
            }
        }
    }
}
