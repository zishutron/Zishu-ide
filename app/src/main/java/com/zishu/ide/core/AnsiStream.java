package com.zishu.ide.core.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * Incremental parser for terminal output. Colours and text styles are tracked across chunks, so a colour set
 * in one line applies until it is reset. Sequences that a plain text view cannot show (cursor movement, titles)
 * are removed instead of printed as garbage.
 */
public final class AnsiStream {
    /** "No colour set": use the theme's default text colour. */
    public static final int DEFAULT = Integer.MIN_VALUE;

    public enum Kind {TEXT, CARRIAGE_RETURN, BACKSPACE, CLEAR}

    public static final class Span {
        public final Kind kind;
        public final String text;
        /** DEFAULT, 0-15 for the 16 basic colours, or an opaque ARGB value for 256-colour / true-colour. */
        public final int fg;
        public final boolean bold;
        public final boolean underline;

        Span(Kind kind, String text, int fg, boolean bold, boolean underline) {
            this.kind = kind;
            this.text = text;
            this.fg = fg;
            this.bold = bold;
            this.underline = underline;
        }
    }

    private int fg = DEFAULT;
    private boolean bold;
    private boolean underline;
    private boolean inverse;
    private String pending = "";

    public List<Span> feed(String chunk) {
        String s = pending + chunk;
        pending = "";
        List<Span> out = new ArrayList<Span>();
        StringBuilder text = new StringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == 0x1B) {
                if (i + 1 >= n) {
                    pending = s.substring(i);
                    break;
                }
                char d = s.charAt(i + 1);
                if (d == '[') {
                    int j = i + 2;
                    while (j < n && !(s.charAt(j) >= '@' && s.charAt(j) <= '~')) {
                        j++;
                    }
                    if (j >= n) {
                        pending = s.substring(i);
                        break;
                    }
                    String args = s.substring(i + 2, j);
                    char cmd = s.charAt(j);
                    if (cmd == 'm') {
                        flush(out, text);
                        sgr(args);
                    } else if (cmd == 'J' && (args.equals("2") || args.equals("3"))) {
                        flush(out, text);
                        out.add(new Span(Kind.CLEAR, "", fg, bold, underline));
                    } else if (cmd == 'K' && (args.isEmpty() || args.equals("0") || args.equals("2"))) {
                        // erase line: output after a carriage return replaces the line anyway
                    }
                    i = j + 1;
                } else if (d == ']') {
                    int j = i + 2;
                    int end = -1;
                    while (j < n) {
                        char x = s.charAt(j);
                        if (x == 0x07) {
                            end = j + 1;
                            break;
                        }
                        if (x == 0x1B && j + 1 < n && s.charAt(j + 1) == '\\') {
                            end = j + 2;
                            break;
                        }
                        j++;
                    }
                    if (end < 0) {
                        pending = s.substring(i);
                        break;
                    }
                    i = end;
                } else if (d == '(' || d == ')') {
                    if (i + 2 >= n) {
                        pending = s.substring(i);
                        break;
                    }
                    i += 3;
                } else if (d == 'c') {
                    flush(out, text);
                    out.add(new Span(Kind.CLEAR, "", fg, bold, underline));
                    resetAttrs();
                    i += 2;
                } else {
                    i += 2;
                }
            } else if (c == '\r') {
                if (i + 1 >= n) {
                    pending = "\r";
                    break;
                }
                if (s.charAt(i + 1) == '\n') {
                    i++; // CRLF is a plain newline
                } else {
                    flush(out, text);
                    out.add(new Span(Kind.CARRIAGE_RETURN, "", fg, bold, underline));
                    i++;
                }
            } else if (c == '\b') {
                flush(out, text);
                out.add(new Span(Kind.BACKSPACE, "", fg, bold, underline));
                i++;
            } else if (c == 0x07 || (c < 0x20 && c != '\n' && c != '\t')) {
                i++; // bell and other control characters are not shown
            } else {
                text.append(c);
                i++;
            }
        }
        flush(out, text);
        return out;
    }

    private void flush(List<Span> out, StringBuilder text) {
        if (text.length() > 0) {
            out.add(new Span(Kind.TEXT, text.toString(), inverse ? 7 : fg, bold, underline));
            text.setLength(0);
        }
    }

    private void resetAttrs() {
        fg = DEFAULT;
        bold = false;
        underline = false;
        inverse = false;
    }

    private void sgr(String args) {
        if (args.isEmpty()) {
            resetAttrs();
            return;
        }
        String[] parts = args.split(";", -1);
        for (int i = 0; i < parts.length; i++) {
            int v = parse(parts[i]);
            if (v == 0) {
                resetAttrs();
            } else if (v == 1) {
                bold = true;
            } else if (v == 4) {
                underline = true;
            } else if (v == 7) {
                inverse = true;
            } else if (v == 22) {
                bold = false;
            } else if (v == 24) {
                underline = false;
            } else if (v == 27) {
                inverse = false;
            } else if (v >= 30 && v <= 37) {
                fg = v - 30;
            } else if (v == 39) {
                fg = DEFAULT;
            } else if (v >= 90 && v <= 97) {
                fg = v - 90 + 8;
            } else if (v == 38) {
                int mode = i + 1 < parts.length ? parse(parts[i + 1]) : -1;
                if (mode == 5 && i + 2 < parts.length) {
                    fg = color256(parse(parts[i + 2]));
                    i += 2;
                } else if (mode == 2 && i + 4 < parts.length) {
                    int r = clamp(parse(parts[i + 2]));
                    int g = clamp(parse(parts[i + 3]));
                    int b = clamp(parse(parts[i + 4]));
                    fg = 0xFF000000 | (r << 16) | (g << 8) | b;
                    i += 4;
                }
            } else if (v == 48) {
                int mode = i + 1 < parts.length ? parse(parts[i + 1]) : -1;
                i += mode == 5 ? 2 : mode == 2 ? 4 : 0; // background colours are not drawn
            }
        }
    }

    private static int parse(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    static int color256(int idx) {
        if (idx < 0) {
            return DEFAULT;
        }
        if (idx < 16) {
            return idx;
        }
        if (idx < 232) {
            int c = idx - 16;
            int[] steps = {0, 95, 135, 175, 215, 255};
            return 0xFF000000 | (steps[c / 36] << 16) | (steps[(c % 36) / 6] << 8) | steps[c % 6];
        }
        int gray = Math.min(255, 8 + (idx - 232) * 10);
        return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
    }
}
