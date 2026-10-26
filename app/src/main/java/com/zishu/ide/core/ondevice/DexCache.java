package com.zishu.ide.core.ondevice;

import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Where the dex version of each library jar is kept between builds. A library does not change, so it is converted once
 * (D8 intermediate output) and later builds only merge the stored dex files with the app's own classes.
 * The key covers the jar (path, size, modification time), the minSdk, the platform jar and the dexer version.
 */
public final class DexCache {
    /** Bump when the bundled D8 or the way libraries are converted changes: old entries are then ignored. */
    public static final String ID = "d8-8.7.18-intermediate-1";

    private DexCache() {
    }

    public static String key(File jar, int minSdk, String platform) {
        return ID + "|" + jar.getAbsolutePath() + "|" + jar.length() + "|" + jar.lastModified() + "|" + minSdk + "|" + platform;
    }

    public static File dirFor(File root, File jar, int minSdk, String platform) {
        String hex;
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key(jar, minSdk, platform).getBytes(IoUtil.UTF8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append(String.format(java.util.Locale.US, "%02x", d[i] & 0xff));
            }
            hex = sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return new File(root, jar.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "-" + hex);
    }

    public static boolean isComplete(File dir) {
        return new File(dir, "READY").isFile();
    }

    public static void markComplete(File dir) throws IOException {
        IoUtil.writeUtf8Atomic(new File(dir, "READY"), ID);
    }

    /** The stored dex files in order: classes.dex, classes2.dex, classes3.dex ... */
    public static List<File> dexFiles(File dir) {
        File[] all = dir.listFiles();
        List<File> out = new ArrayList<File>();
        if (all != null) {
            for (File f : all) {
                if (f.getName().startsWith("classes") && f.getName().endsWith(".dex")) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Integer.compare(number(a), number(b));
            }
        });
        return out;
    }

    private static int number(File f) {
        String n = f.getName().substring("classes".length(), f.getName().length() - ".dex".length());
        if (n.length() == 0) {
            return 1;
        }
        try {
            return Integer.parseInt(n);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** True when the jar has at least one .class entry (a jar with only resources has nothing to convert). */
    public static boolean hasClasses(File jar) throws IOException {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.endsWith(".class") && !n.equals("module-info.class") && !n.startsWith("META-INF/versions/")) {
                    return true;
                }
            }
            return false;
        } finally {
            zf.close();
        }
    }

}
