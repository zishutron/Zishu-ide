package com.zishu.ide.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small strict JSON parser/encoder. It exists so the core has no dependency on
 * org.json (which is not present in plain JVM unit tests) and so the editor can report
 * the exact line and column of a syntax error.
 *
 * Objects become LinkedHashMap, arrays ArrayList, integers Long, other numbers Double.
 */
public final class MiniJson {
    private static final int MAX_DEPTH = 200;

    public static final class JsonException extends Exception {
        public final int position;
        public final int line;
        public final int column;

        JsonException(String message, int position, int line, int column) {
            super(message + " (line " + line + ", column " + column + ")");
            this.position = position;
            this.line = line;
            this.column = column;
        }
    }

    private final String s;
    private int pos;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) throws JsonException {
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object v = p.readValue(0);
        p.skipWs();
        if (p.pos < p.s.length()) {
            throw p.error("Unexpected data after the end of the JSON value");
        }
        return v;
    }

    private JsonException error(String msg) {
        int line = 1;
        int col = 1;
        int end = Math.min(pos, s.length());
        for (int i = 0; i < end; i++) {
            if (s.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new JsonException(msg, pos, line, col);
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object readValue(int depth) throws JsonException {
        if (depth > MAX_DEPTH) {
            throw error("JSON is nested too deeply");
        }
        if (pos >= s.length()) {
            throw error("Unexpected end of JSON");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return readObject(depth);
            case '[':
                return readArray(depth);
            case '"':
                return readString();
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw error("Unexpected character '" + c + "'");
        }
    }

    private void expectWord(String w) throws JsonException {
        if (!s.startsWith(w, pos)) {
            throw error("Invalid literal, expected '" + w + "'");
        }
        pos += w.length();
    }

    private Map<String, Object> readObject(int depth) throws JsonException {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++;
        skipWs();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw error("Expected a string key");
            }
            String key = readString();
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != ':') {
                throw error("Expected ':' after key");
            }
            pos++;
            skipWs();
            map.put(key, readValue(depth + 1));
            skipWs();
            if (pos >= s.length()) {
                throw error("Unterminated object");
            }
            char c = s.charAt(pos++);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                pos--;
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray(int depth) throws JsonException {
        List<Object> list = new ArrayList<Object>();
        pos++;
        skipWs();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readValue(depth + 1));
            skipWs();
            if (pos >= s.length()) {
                throw error("Unterminated array");
            }
            char c = s.charAt(pos++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                pos--;
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String readString() throws JsonException {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("Unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                pos--;
                throw error("Control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw error("Unterminated escape");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) {
                        throw error("Incomplete unicode escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException nfe) {
                        throw error("Invalid unicode escape");
                    }
                    pos += 4;
                    break;
                default:
                    pos--;
                    throw error("Invalid escape '\\" + e + "'");
            }
        }
    }

    private Object readNumber() throws JsonException {
        int start = pos;
        if (s.charAt(pos) == '-') {
            pos++;
        }
        if (pos >= s.length()) {
            throw error("Invalid number");
        }
        if (s.charAt(pos) == '0') {
            pos++;
        } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
        } else {
            throw error("Invalid number");
        }
        boolean integer = true;
        if (pos < s.length() && s.charAt(pos) == '.') {
            integer = false;
            pos++;
            int d = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == d) {
                throw error("Digits expected after decimal point");
            }
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            integer = false;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            int d = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == d) {
                throw error("Digits expected in exponent");
            }
        }
        String t = s.substring(start, pos);
        if (integer) {
            try {
                return Long.valueOf(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // falls through to double
            }
        }
        return Double.valueOf(Double.parseDouble(t));
    }

    // ---- encoding ----

    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder();
        encode(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void encode(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote((String) v, sb);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            sb.append(v.toString());
        } else if (v instanceof Number) {
            sb.append(((Number) v).doubleValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(e.getKey(), sb);
                sb.append(':');
                encode(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                encode(o, sb);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Cannot encode " + v.getClass().getName());
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---- typed access helpers ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        return o instanceof String ? (String) o : null;
    }

    public static long num(Map<String, Object> m, String key, long dflt) {
        Object o = m == null ? null : m.get(key);
        return o instanceof Number ? ((Number) o).longValue() : dflt;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean dflt) {
        Object o = m == null ? null : m.get(key);
        return o instanceof Boolean ? ((Boolean) o).booleanValue() : dflt;
    }
}
