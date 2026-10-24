package com.zishu.ide.core.ondevice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Splits the class files of a big jar into groups of roughly equal compressed size, so each group can be converted
 * to dex on its own (the dexer needs far less memory per run). Classes are grouped in name order, which keeps a package
 * together. Non-class entries are not part of any group.
 */
public final class JarSplitter {
    private JarSplitter() {
    }

    /** Names of the .class entries, in groups of at most about {@code maxCompressedBytes}. A small jar is one group. */
    public static List<List<String>> plan(File jar, long maxCompressedBytes) throws IOException {
        ZipFile z = new ZipFile(jar);
        try {
            List<ZipEntry> classes = new ArrayList<ZipEntry>();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/versions/")) {
                    classes.add(e);
                }
            }
            Collections.sort(classes, new java.util.Comparator<ZipEntry>() {
                @Override
                public int compare(ZipEntry a, ZipEntry b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            List<List<String>> groups = new ArrayList<List<String>>();
            List<String> cur = new ArrayList<String>();
            long size = 0;
            for (ZipEntry e : classes) {
                long c = e.getCompressedSize() >= 0 ? e.getCompressedSize() : Math.max(0, e.getSize() / 2);
                if (!cur.isEmpty() && size + c > maxCompressedBytes) {
                    groups.add(cur);
                    cur = new ArrayList<String>();
                    size = 0;
                }
                cur.add(e.getName());
                size += c;
            }
            if (!cur.isEmpty()) {
                groups.add(cur);
            }
            return groups;
        } finally {
            z.close();
        }
    }

    /** Writes the named entries of {@code jar} into a new jar. */
    public static void write(File jar, List<String> names, File out) throws IOException {
        ZipFile z = new ZipFile(jar);
        try {
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
            try {
                byte[] buf = new byte[64 * 1024];
                for (String n : names) {
                    ZipEntry e = z.getEntry(n);
                    if (e == null) {
                        throw new IOException(n + " is missing from " + jar.getName());
                    }
                    zos.putNextEntry(new ZipEntry(n));
                    InputStream in = z.getInputStream(e);
                    try {
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            zos.write(buf, 0, r);
                        }
                    } finally {
                        in.close();
                    }
                    zos.closeEntry();
                }
            } finally {
                zos.close();
            }
        } finally {
            z.close();
        }
    }
}
