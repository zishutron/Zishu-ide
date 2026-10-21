package com.zishu.ide.core.ondevice;

import com.zishu.ide.core.util.IoUtil;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds the unsigned APK: every entry of the aapt2 output plus the DEX files. Uncompressed entries
 * (resources.arsc) are aligned to 4 bytes, which Android 11+ requires for apps targeting API 30+.
 * Signing must happen after this step because signing keeps the alignment but packaging would break it.
 */
public final class ApkPackager {
    private static final int ALIGNMENT = 4;
    private static final long FIXED_TIME = 1230768000000L; // 2009-01-01, keeps output stable

    private ApkPackager() {
    }

    private static final class Counting extends OutputStream {
        private final OutputStream out;
        long count;

        Counting(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /**
     * @param extraFiles entry name to file, added compressed; an entry with the same name in the base APK is replaced
     */
    public static void assemble(File baseApk, Map<String, File> extraFiles, File out) throws IOException {
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        ZipFile base = new ZipFile(baseApk);
        Counting counting = new Counting(new BufferedOutputStream(new FileOutputStream(out), 64 * 1024));
        ZipOutputStream zos = new ZipOutputStream(counting);
        try {
            Enumeration<? extends ZipEntry> entries = base.entries();
            while (entries.hasMoreElements()) {
                ZipEntry in = entries.nextElement();
                if (in.isDirectory() || extraFiles.containsKey(in.getName())) {
                    continue;
                }
                byte[] data = readEntry(base, in);
                if (in.getMethod() == ZipEntry.STORED) {
                    writeStored(zos, counting, in.getName(), data);
                } else {
                    writeDeflated(zos, in.getName(), data);
                }
            }
            for (Map.Entry<String, File> e : new LinkedHashMap<String, File>(extraFiles).entrySet()) {
                writeDeflated(zos, e.getKey(), IoUtil.readFile(e.getValue(), 256L * 1024 * 1024));
            }
            zos.finish();
        } finally {
            IoUtil.closeQuietly(zos);
            try {
                base.close();
            } catch (IOException ignored) {
                // read-only archive
            }
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry e) throws IOException {
        InputStream in = zip.getInputStream(e);
        try {
            return IoUtil.readAll(in, 512L * 1024 * 1024);
        } finally {
            IoUtil.closeQuietly(in);
        }
    }

    private static void writeDeflated(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        e.setTime(FIXED_TIME);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    private static void writeStored(ZipOutputStream zos, Counting counting, String name, byte[] data) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(crc.getValue());
        e.setTime(FIXED_TIME);
        int nameLen = name.getBytes(IoUtil.UTF8).length;
        long dataStart = counting.count + 30 + nameLen;
        int pad = (int) ((ALIGNMENT - (dataStart % ALIGNMENT)) % ALIGNMENT);
        if (pad > 0) {
            e.setExtra(new byte[pad]);
        }
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }
}
