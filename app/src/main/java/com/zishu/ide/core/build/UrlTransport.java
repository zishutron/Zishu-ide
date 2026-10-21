package com.zishu.ide.core.build;

import com.zishu.ide.core.util.IoUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link HttpTransport} over HttpURLConnection. HTTPS only; never follows redirects. */
public final class UrlTransport implements HttpTransport {
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public UrlTransport() {
        this(20000, 60000);
    }

    public UrlTransport(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public Response execute(String method, String url, Map<String, String> headers, byte[] body, long maxResponseBytes)
            throws IOException {
        URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol())) {
            throw new IOException("Refusing to use an unencrypted connection: only https:// is allowed");
        }
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectTimeoutMs);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream out = c.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    IoUtil.closeQuietly(out);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = new byte[0];
            if (in != null) {
                try {
                    data = IoUtil.readAll(in, maxResponseBytes);
                } finally {
                    IoUtil.closeQuietly(in);
                }
            }
            Map<String, String> rh = new HashMap<String, String>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null && !e.getValue().isEmpty()) {
                    rh.put(e.getKey().toLowerCase(java.util.Locale.US), e.getValue().get(0));
                }
            }
            return new Response(code, rh, data);
        } finally {
            c.disconnect();
        }
    }
}
