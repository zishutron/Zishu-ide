package com.zishu.ide.core.ondevice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R classes of libraries, the way Gradle makes them: each library gets an R class that holds only the resources it declares
 * (from the R.txt inside its AAR), with the final ids that aapt2 gave them in the app. aapt2's --extra-packages instead
 * copies the whole resource table into one R class per library, which for 40+ libraries means tens of thousands of fields
 * to compile and dex in every build.
 */
public final class LibraryR {
    /** One entry of an R.txt: an int, or an int[] (styleable arrays). */
    public static final class Sym {
        public final boolean array;
        public final String value;

        Sym(boolean array, String value) {
            this.array = array;
            this.value = value;
        }
    }

    /** Symbols by resource type (attr, string, styleable ...) and name, in file order. */
    public static final class Table {
        public final Map<String, Map<String, Sym>> types = new LinkedHashMap<String, Map<String, Sym>>();

        public Sym get(String type, String name) {
            Map<String, Sym> m = types.get(type);
            return m == null ? null : m.get(name);
        }

        void put(String type, String name, Sym s) {
            Map<String, Sym> m = types.get(type);
            if (m == null) {
                m = new LinkedHashMap<String, Sym>();
                types.put(type, m);
            }
            if (!m.containsKey(name)) {
                m.put(name, s);
            }
        }

        public int size() {
            int n = 0;
            for (Map<String, Sym> m : types.values()) {
                n += m.size();
            }
            return n;
        }
    }

    private LibraryR() {
    }

    /** Parses R.txt lines: {@code int attr name 0x7f030000}, {@code int[] styleable X { 0x7f01, 0x7f02 }}, {@code int styleable X_a 3}. */
    public static Table parse(String text) {
        Table t = new Table();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.length() == 0) {
                continue;
            }
            String[] p = line.split("\\s+", 4);
            if (p.length < 4) {
                continue;
            }
            boolean array = "int[]".equals(p[0]);
            if (!array && !"int".equals(p[0])) {
                continue;
            }
            String value = p[3].trim();
            if (array) {
                int open = value.indexOf('{');
                int close = value.lastIndexOf('}');
                if (open < 0 || close < open) {
                    continue;
                }
                value = value.substring(open + 1, close).trim();
            }
            t.put(p[1], p[2], new Sym(array, value));
        }
        return t;
    }

    /** Adds the symbols of {@code other} that this table does not have yet (two AARs can share a package). */
    public static void merge(Table into, Table other) {
        for (Map.Entry<String, Map<String, Sym>> e : other.types.entrySet()) {
            for (Map.Entry<String, Sym> s : e.getValue().entrySet()) {
                into.put(e.getKey(), s.getKey(), s.getValue());
            }
        }
    }

    /**
     * Java source of {@code pkg}.R for the symbols of {@code library}, with the ids of {@code finalIds}. A symbol the final table
     * does not know keeps the library's own value. Styleable arrays and their index entries both come from the final table when it lists them, so they agree.
     */
    public static String generate(String pkg, Table library, Table finalIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("public final class R {\n    private R() {\n    }\n");
        for (Map.Entry<String, Map<String, Sym>> type : library.types.entrySet()) {
            sb.append("\n    public static final class ").append(type.getKey()).append(" {\n");
            sb.append("        private ").append(type.getKey()).append("() {\n        }\n");
            for (Map.Entry<String, Sym> e : type.getValue().entrySet()) {
                Sym own = e.getValue();
                Sym f = finalIds.get(type.getKey(), e.getKey());
                Sym use = f != null && f.array == own.array ? f : own;
                if (use.array) {
                    sb.append("        public static final int[] ").append(e.getKey()).append(" = { ").append(use.value).append(" };\n");
                } else {
                    sb.append("        public static final int ").append(e.getKey()).append(" = ").append(use.value).append(";\n");
                }
            }
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Names of the resource types in a table, for log lines and tests. */
    public static List<String> typeNames(Table t) {
        return new ArrayList<String>(t.types.keySet());
    }
}
