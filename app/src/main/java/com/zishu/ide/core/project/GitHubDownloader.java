package com.zishu.ide.core.project;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads a repository ZIP from GitHub over HTTPS. The optional token is sent only to api.github.com, never
 * along a redirect. Public repositories need no token.
 */
public final class GitHubDownloader {
    public interface Progress {
        void bytes(long done);
    }

    public static final long MAX_BYTES = 300L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private GitHubDownloader() {
    }

    public static void download(GitHubRepo repo, String tokenOrNull, File target, Progress progress,
                                java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        String url = repo.zipballUrl();
        boolean first = true;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = new URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol())) {
                throw new IOException("Refusing a non-https redirect");
            }
            if (!GitHubRepo.trustedRedirectHost(u.getHost())) {
                throw new IOException("Refusing a redirect to " + u.getHost());
            }
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "ZishuIDE");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (first && tokenOrNull != null && tokenOrNull.length() > 0 && u.getHost().equals("api.github.com")) {
                    c.setRequestProperty("Authorization", "Bearer " + tokenOrNull);
                }
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) {
                        throw new IOException("GitHub sent a redirect without a location");
                    }
                    url = new URL(u, loc).toString();
                    first = false;
                    continue;
                }
                if (code == 404) {
                    throw new IOException("Repository not found. If it is private, use  zishu clone <repo> --auth  "
                            + "(needs the cloud token to have read access to it).");
                }
                if (code == 401 || code == 403) {
                    throw new IOException("GitHub refused the request (HTTP " + code + "). Unauthenticated requests are "
                            + "rate-limited; wait a while or use --auth.");
                }
                if (code != 200) {
                    throw new IOException("GitHub answered HTTP " + code);
                }
                long len = c.getContentLengthLong();
                if (len > MAX_BYTES) {
                    throw new IOException("The repository ZIP is " + (len / (1024 * 1024)) + " MB, over the "
                            + (MAX_BYTES / (1024 * 1024)) + " MB limit.");
                }
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(target);
                try {
                    byte[] buf = new byte[32 * 1024];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel.get()) {
                            throw new IOException("Cancelled");
                        }
                        done += n;
                        if (done > MAX_BYTES) {
                            throw new IOException("The repository ZIP is over the " + (MAX_BYTES / (1024 * 1024)) + " MB limit.");
                        }
                        out.write(buf, 0, n);
                        if (progress != null) {
                            progress.bytes(done);
                        }
                    }
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // closing a finished stream
                    }
                    out.close();
                }
                return;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }
}
