package com.zishu.ide.core.util;

import java.io.ByteArrayOutputStream;

/** Standard Base64 (RFC 4648) that works on every Android API level and on a plain JVM. */
public final class Base64Util {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64Util() {
    }

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(ALPHABET[(v >> 18) & 63]).append(ALPHABET[(v >> 12) & 63])
                    .append(ALPHABET[(v >> 6) & 63]).append(ALPHABET[v & 63]);
            i += 3;
        }
        int rem = data.length - i;
        if (rem == 1) {
            int v = (data[i] & 0xFF) << 16;
            sb.append(ALPHABET[(v >> 18) & 63]).append(ALPHABET[(v >> 12) & 63]).append("==");
        } else if (rem == 2) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(ALPHABET[(v >> 18) & 63]).append(ALPHABET[(v >> 12) & 63])
                    .append(ALPHABET[(v >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    /** Decodes standard Base64, ignoring whitespace (GitHub wraps its payloads at 60 columns). */
    public static byte[] decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' || Character.isWhitespace(c)) {
                continue;
            }
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+') {
                v = 62;
            } else if (c == '/') {
                v = 63;
            } else {
                throw new IllegalArgumentException("Invalid Base64 character at " + i);
            }
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
