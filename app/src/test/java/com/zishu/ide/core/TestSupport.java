package com.zishu.ide.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Helpers shared by the unit tests. */
public final class TestSupport {
    private TestSupport() {
    }

    public static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            z.putNextEntry(new ZipEntry(e.getKey()));
            z.write(e.getValue());
            z.closeEntry();
        }
        z.close();
        return bos.toByteArray();
    }

    public static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    /**
     * A ZIP with the structure of a signed APK (manifest, dex, resource table, v1 signature file).
     * It is a synthetic stand-in: it is NOT installable and only exercises the structural checks.
     */
    public static byte[] syntheticApk(boolean signed) throws IOException {
        java.util.LinkedHashMap<String, byte[]> m = new java.util.LinkedHashMap<String, byte[]>();
        m.put("AndroidManifest.xml", new byte[]{3, 0, 8, 0});
        m.put("classes.dex", utf8("dex\n035\0"));
        m.put("resources.arsc", new byte[]{2, 0, 12, 0});
        if (signed) {
            m.put("META-INF/CERT.SF", utf8("Signature-Version: 1.0\n"));
            m.put("META-INF/CERT.RSA", new byte[]{1, 2, 3});
        }
        return zip(m);
    }
}
