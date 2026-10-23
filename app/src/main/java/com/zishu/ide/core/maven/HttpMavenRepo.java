package com.zishu.ide.core.maven;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** A Maven repository over HTTPS. Downloads are verified against the repository's .sha1 file when it has one. */
public final class HttpMavenRepo implements MavenRepo {
    public static final String GOOGLE = "https://dl.google.com/dl/android/maven2/";
    public static final String CENTRAL = "https://repo1.maven.org/maven2/";
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    private final String name;
    private final String base;

    public HttpMavenRepo(String name, String baseUrl) {
        this.name = name;
        this.base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        if (!this.base.startsWith("https://")) {
            throw new IllegalArgumentException("Only https:// repositories are allowed");
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean download(String path, File dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "ZishuIDE");
            int code = c.getResponseCode();
            if (code == 404 || code == 410) {
                return false;
            }
            if (code != 200) {
                throw new IOException(name + " answered HTTP " + code + " for " + path);
            }
            if (!c.getURL().getProtocol().equalsIgnoreCase("https")) {
                throw new IOException("Refusing a redirect to an unencrypted address");
            }
            MessageDigest sha1;
            try {
                sha1 = MessageDigest.getInstance("SHA-1");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("SHA-1 is not available");
            }
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[32 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException("A download is larger than the allowed " + (MAX_BYTES / (1024 * 1024)) + " MB");
                    }
                    sha1.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // finished
                }
                out.close();
            }
            String expected = fetchSmall(path + ".sha1");
            if (expected != null) {
                String got = hex(sha1.digest());
                String want = expected.trim().split("\\s+")[0].toLowerCase(java.util.Locale.US);
                if (want.length() == 40 && !want.equals(got)) {
                    dest.delete();
                    throw new IOException("The download of " + path + " is corrupt (checksum mismatch). Try again.");
                }
            }
            return true;
        } finally {
            c.disconnect();
        }
    }

    private String fetchSmall(String path) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
            try {
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "ZishuIDE");
                if (c.getResponseCode() != 200) {
                    return null;
                }
                InputStream in = c.getInputStream();
                try {
                    byte[] b = new byte[256];
                    int n = in.read(b);
                    return n <= 0 ? null : new String(b, 0, n, "UTF-8");
                } finally {
                    in.close();
                }
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }
}
