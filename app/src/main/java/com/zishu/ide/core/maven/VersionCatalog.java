package com.zishu.ide.core.maven;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The small part of gradle/libs.versions.toml needed to turn libs.some.alias into a coordinate. */
public final class VersionCatalog {
    private final Map<String, Coordinate> libraries = new LinkedHashMap<String, Coordinate>();
    private final Map<String, String> bundlesUnsupported = new HashMap<String, String>();

    public static VersionCatalog parse(String toml) {
        VersionCatalog c = new VersionCatalog();
        Map<String, String> versions = new HashMap<String, String>();
        String section = "";
        for (String raw : toml.split("\r?\n")) {
            String line = stripComment(raw).trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().replace("\"", "");
            String val = line.substring(eq + 1).trim();
            if ("versions".equals(section)) {
                String s = quoted(val);
                if (s != null) {
                    versions.put(key, s);
                }
            } else if ("libraries".equals(section)) {
                c.addLibrary(key, val, versions);
            } else if ("bundles".equals(section)) {
                c.bundlesUnsupported.put(key, val);
            }
        }
        return c;
    }

    private void addLibrary(String alias, String val, Map<String, String> versions) {
        String s = quoted(val);
        if (s != null) {
            Coordinate co = Coordinate.parse(s);
            if (co != null) {
                libraries.put(normalise(alias), co);
            }
            return;
        }
        if (!val.startsWith("{")) {
            return;
        }
        String module = field(val, "module");
        String group = field(val, "group");
        String name = field(val, "name");
        String version = field(val, "version");
        Matcher ref = Pattern.compile("version\\.ref\\s*=\\s*[\"']([^\"']+)[\"']").matcher(val);
        if (ref.find()) {
            version = versions.get(ref.group(1));
        } else if (version == null) {
            Matcher vm = Pattern.compile("version\\s*=\\s*\\{[^}]*?(?:require|strictly|prefer)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(val);
            if (vm.find()) {
                version = vm.group(1);
            }
        }
        String g;
        String a;
        if (module != null && module.contains(":")) {
            g = module.substring(0, module.indexOf(':'));
            a = module.substring(module.indexOf(':') + 1);
        } else if (group != null && name != null) {
            g = group;
            a = name;
        } else {
            return;
        }
        libraries.put(normalise(alias), new Coordinate(g, a, version));
    }

    private static String field(String obj, String name) {
        Matcher m = Pattern.compile("(?<![\\w.])" + name + "\\s*=\\s*[\"']([^\"']+)[\"']").matcher(obj);
        return m.find() ? m.group(1) : null;
    }

    private static String quoted(String v) {
        Matcher m = Pattern.compile("^[\"']([^\"']*)[\"']$").matcher(v.trim());
        return m.matches() ? m.group(1) : null;
    }

    private static String stripComment(String s) {
        boolean inQ = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inQ) {
                if (ch == q) {
                    inQ = false;
                }
            } else if (ch == '"' || ch == '\'') {
                inQ = true;
                q = ch;
            } else if (ch == '#') {
                return s.substring(0, i);
            }
        }
        return s;
    }

    /** "androidx-core-ktx" and "androidx.core.ktx" and "androidx_core_ktx" are the same accessor. */
    static String normalise(String alias) {
        return alias.replace('-', '.').replace('_', '.');
    }

    /** @param accessor the part after "libs.", for example androidx.core.ktx */
    public Coordinate library(String accessor) {
        return libraries.get(normalise(accessor));
    }

    public boolean hasBundles() {
        return !bundlesUnsupported.isEmpty();
    }
}
