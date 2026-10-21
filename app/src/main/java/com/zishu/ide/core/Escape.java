package com.zishu.ide.core.template;

/** Escaping for the different languages that user input is written into. */
public final class Escape {
    private Escape() {
    }

    /** Text/attribute content in XML or HTML. */
    public static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Value of a &lt;string&gt; element in res/values/strings.xml. */
    public static String androidString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '\'': sb.append("\\'"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\\': sb.append("\\\\"); break;
                default:
                    if (i == 0 && (c == '@' || c == '?')) {
                        sb.append('\\');
                    }
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Contents of a double-quoted Java string literal (not Kotlin: '$' would need escaping there). */
    public static String javaString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
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
        return sb.toString();
    }

    /** Contents of a single-quoted Groovy string. */
    public static String groovyString(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** #RRGGBB darkened to 80% brightness, used for colorPrimaryDark. */
    public static String darken(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return String.format(java.util.Locale.US, "#%02X%02X%02X", (int) (r * 0.8), (int) (g * 0.8), (int) (b * 0.8));
    }
}
