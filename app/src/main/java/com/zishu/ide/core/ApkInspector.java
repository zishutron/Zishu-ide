package com.zishu.ide.core.build;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Structural checks on a built APK: it must be a readable ZIP with a compiled manifest, at least
 * one dex file and a signature. This does NOT verify the signature cryptographically (Android's
 * installer does that) and it does not read the package name from the binary manifest; on a
 * device the package name and version come from PackageManager, which is authoritative.
 */
public final class ApkInspector {

    public static final class Info {
        public final long sizeBytes;
        public final int entryCount;
        public final int dexCount;
        public final boolean hasManifest;
        public final boolean hasResourceTable;
        public final boolean hasV1Signature;
        public final boolean hasV2OrLaterSignature;
        public final boolean hasAssetsWww;

        Info(long sizeBytes, int entryCount, int dexCount, boolean hasManifest, boolean hasResourceTable,
             boolean hasV1Signature, boolean hasV2OrLaterSignature, boolean hasAssetsWww) {
            this.sizeBytes = sizeBytes;
            this.entryCount = entryCount;
            this.dexCount = dexCount;
            this.hasManifest = hasManifest;
            this.hasResourceTable = hasResourceTable;
            this.hasV1Signature = hasV1Signature;
            this.hasV2OrLaterSignature = hasV2OrLaterSignature;
            this.hasAssetsWww = hasAssetsWww;
        }

        public boolean isSigned() {
            return hasV1Signature || hasV2OrLaterSignature;
        }
    }

    private ApkInspector() {
    }

    /** @throws IOException if the file is not a structurally valid APK; the message says why */
    public static Info inspect(File apk) throws IOException {
        if (!apk.isFile() || apk.length() == 0) {
            throw new IOException("The APK file is missing or empty.");
        }
        ZipFile zip;
        try {
            zip = new ZipFile(apk);
        } catch (ZipException e) {
            throw new IOException("The file is not a valid ZIP/APK archive: " + e.getMessage());
        }
        try {
            int entries = 0;
            int dex = 0;
            boolean manifest = false;
            boolean arsc = false;
            boolean v1 = false;
            boolean www = false;
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                entries++;
                String n = e.getName();
                if (n.equals("AndroidManifest.xml")) {
                    manifest = true;
                } else if (n.equals("resources.arsc")) {
                    arsc = true;
                } else if (n.matches("classes\\d*\\.dex")) {
                    dex++;
                } else if (n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) {
                    v1 = true;
                } else if (n.startsWith("assets/www/")) {
                    www = true;
                }
            }
            if (!manifest) {
                throw new IOException("The APK has no AndroidManifest.xml, so it cannot be installed.");
            }
            if (dex == 0) {
                throw new IOException("The APK contains no classes.dex, so it has no compiled code.");
            }
            return new Info(apk.length(), entries, dex, manifest, arsc, v1, hasSigningBlock(apk), www);
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    /** Looks for the "APK Sig Block 42" magic that precedes the ZIP central directory (v2+ signatures). */
    static boolean hasSigningBlock(File apk) throws IOException {
        RandomAccessFile f = new RandomAccessFile(apk, "r");
        try {
            long len = f.length();
            long scan = Math.min(len, 22L + 65535L);
            byte[] tail = new byte[(int) scan];
            f.seek(len - scan);
            f.readFully(tail);
            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                return false;
            }
            long cdOffset = (tail[eocd + 16] & 0xFFL) | ((tail[eocd + 17] & 0xFFL) << 8)
                    | ((tail[eocd + 18] & 0xFFL) << 16) | ((tail[eocd + 19] & 0xFFL) << 24);
            if (cdOffset < 24 || cdOffset > len) {
                return false;
            }
            byte[] magic = new byte[16];
            f.seek(cdOffset - 16);
            f.readFully(magic);
            return new String(magic, "ISO-8859-1").equals("APK Sig Block 42");
        } finally {
            f.close();
        }
    }
}
