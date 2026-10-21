package com.zishu.ide.core.build;

import java.io.IOException;
import java.util.Map;

/** Minimal HTTP abstraction so the GitHub client can be tested without a network. */
public interface HttpTransport {

    final class Response {
        public final int code;
        /** Header names are lower-cased. */
        public final Map<String, String> headers;
        public final byte[] body;

        public Response(int code, Map<String, String> headers, byte[] body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.US));
        }

        public String text() {
            return new String(body, com.zishu.ide.core.util.IoUtil.UTF8);
        }
    }

    /**
     * Performs one request. Redirects are NOT followed (the caller decides, so credentials are never
     * sent to another host). Throws IOException for transport failures; HTTP error statuses are
     * returned as responses.
     */
    Response execute(String method, String url, Map<String, String> headers, byte[] body, long maxResponseBytes)
            throws IOException;
}
