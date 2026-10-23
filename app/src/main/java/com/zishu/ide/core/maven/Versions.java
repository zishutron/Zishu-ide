package com.zishu.ide.core.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Version ordering close to Gradle's: numbers compare numerically, qualifiers (alpha < beta < rc < release) in order. */
public final class Versions {
    private Versions() {
    }

    public static int compare(String a, String b) {
        List<String> x = split(a);
        List<String> y = split(b);
        int n = Math.max(x.size(), y.size());
        for (int i = 0; i < n; i++) {
            String p = i < x.size() ? x.get(i) : "";
            String q = i < y.size() ? y.get(i) : "";
            int c = comparePart(p, q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    public static String max(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return compare(a, b) >= 0 ? a : b;
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '.' || c == '-' || c == '_' || c == '+') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                if (cur.length() > 0 && Character.isDigit(c) != Character.isDigit(cur.charAt(cur.length() - 1))) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static boolean numeric(String s) {
        return s.length() > 0 && Character.isDigit(s.charAt(0));
    }

    private static int rank(String q) {
        String s = q.toLowerCase(Locale.US);
        if (s.length() == 0 || s.equals("final") || s.equals("ga") || s.equals("release")) {
            return 5;
        }
        if (s.startsWith("dev")) {
            return 0;
        }
        if (s.startsWith("alpha") || s.equals("a")) {
            return 1;
        }
        if (s.startsWith("beta") || s.equals("b")) {
            return 2;
        }
        if (s.startsWith("m") || s.startsWith("milestone")) {
            return 3;
        }
        if (s.startsWith("rc") || s.startsWith("cr")) {
            return 4;
        }
        if (s.startsWith("snapshot")) {
            return 4;
        }
        if (s.startsWith("sp")) {
            return 6;
        }
        return 5;
    }

    private static int comparePart(String p, String q) {
        boolean np = numeric(p);
        boolean nq = numeric(q);
        if (np && nq) {
            java.math.BigInteger a = new java.math.BigInteger(p);
            java.math.BigInteger b = new java.math.BigInteger(q);
            return a.compareTo(b);
        }
        if (np && !nq) {
            return q.length() == 0 ? (new java.math.BigInteger(p).signum() == 0 ? 0 : 1) : 1;
        }
        if (!np && nq) {
            return p.length() == 0 ? (new java.math.BigInteger(q).signum() == 0 ? 0 : -1) : -1;
        }
        int rp = rank(p);
        int rq = rank(q);
        if (rp != rq) {
            return rp < rq ? -1 : 1;
        }
        return p.toLowerCase(Locale.US).compareTo(q.toLowerCase(Locale.US));
    }
}
