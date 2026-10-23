package com.zishu.ide.core.maven;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads dependency declarations from a module's build.gradle / build.gradle.kts as text. Anything it cannot
 * resolve safely (project modules, annotation processors, computed versions) is reported in {@link #unsupported}
 * instead of being guessed.
 */
public final class GradleDeps {
    public static final class Dep {
        public final String configuration;
        public final Coordinate coordinate;
        public final List<String> excludes = new ArrayList<String>();

        Dep(String configuration, Coordinate coordinate) {
            this.configuration = configuration;
            this.coordinate = coordinate;
        }

        public boolean compile() {
            return !"runtimeOnly".equals(configuration);
        }

        public boolean runtime() {
            return !"compileOnly".equals(configuration);
        }
    }

    public final List<Dep> deps = new ArrayList<Dep>();
    public final List<Coordinate> boms = new ArrayList<Coordinate>();
    /** Paths from files('libs/x.jar'). */
    public final List<String> localJars = new ArrayList<String>();
    public boolean fileTree;
    public final List<String> unsupported = new ArrayList<String>();

    private static final Pattern START = Pattern.compile(
            "(?m)^[ \\t]*(implementation|api|compileOnly|runtimeOnly|debugImplementation|debugApi|annotationProcessor|kapt|ksp)\\b[ \\t]*(\\(?)(.*)$");
    private static final Pattern STRING = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern MAP = Pattern.compile(
            "group\\s*[:=]\\s*[\"']([^\"']+)[\"']\\s*,\\s*name\\s*[:=]\\s*[\"']([^\"']+)[\"']\\s*(?:,\\s*version\\s*[:=]\\s*[\"']([^\"']+)[\"'])?");
    private static final Pattern CATALOG = Pattern.compile("\\blibs\\.([A-Za-z0-9_.]+)");
    private static final Pattern VAR = Pattern.compile(
            "(?m)^[ \\t]*(?:def|val|final\\s+String|String|ext\\.|ext\\s*\\{\\s*|set\\(\\s*)?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*[\"']([^\"'\\n]+)[\"']");

    public static GradleDeps parse(String gradleText, VersionCatalog catalog) {
        String text = stripComments(gradleText);
        GradleDeps out = new GradleDeps();
        Map<String, String> vars = new HashMap<String, String>();
        Matcher vm = VAR.matcher(text);
        while (vm.find()) {
            vars.put(vm.group(1), vm.group(2));
        }
        Matcher m = START.matcher(text);
        while (m.find()) {
            String config = m.group(1);
            String rest = m.group(3).trim();
            String block = "";
            int brace = m.group(3).indexOf('{');
            if (brace >= 0) {
                block = readBlock(text, m.start(3) + brace + 1);
            }
            String line = config + " " + rest;
            if ("annotationProcessor".equals(config) || "kapt".equals(config) || "ksp".equals(config)) {
                out.unsupported.add(line + "  (annotation processors do not run in the on-phone build yet)");
                continue;
            }
            if (rest.startsWith("project(") || rest.contains("project(\":") || rest.contains("project(':")) {
                out.unsupported.add(line + "  (module dependencies are not supported in the on-phone build yet)");
                continue;
            }
            if (rest.contains("fileTree")) {
                out.fileTree = true;
                continue;
            }
            if (rest.startsWith("files(") || rest.startsWith("files (")) {
                Matcher fm = STRING.matcher(rest);
                while (fm.find()) {
                    out.localJars.add(fm.group(1));
                }
                continue;
            }
            boolean platform = rest.contains("platform(") || rest.contains("enforcedPlatform(");
            String cfg = "debugImplementation".equals(config) ? "implementation" : "debugApi".equals(config) ? "api" : config;
            Coordinate co = null;
            Matcher mm = MAP.matcher(rest);
            if (mm.find()) {
                co = new Coordinate(mm.group(1), mm.group(2), mm.group(3));
            } else {
                Matcher cm = CATALOG.matcher(rest);
                if (cm.find()) {
                    co = catalog == null ? null : catalog.library(cm.group(1));
                    if (co == null) {
                        out.unsupported.add(line + "  (libs." + cm.group(1) + " is not in gradle/libs.versions.toml)");
                        continue;
                    }
                } else {
                    Matcher sm = STRING.matcher(rest);
                    if (sm.find()) {
                        String lit = substitute(sm.group(1), vars);
                        if (lit.contains("$")) {
                            out.unsupported.add(line + "  (the version is computed; write a fixed version)");
                            continue;
                        }
                        co = Coordinate.parse(lit);
                    }
                }
            }
            if (co == null) {
                out.unsupported.add(line + "  (not understood)");
                continue;
            }
            if (platform) {
                out.boms.add(co);
                continue;
            }
            Dep d = new Dep(cfg, co);
            Matcher ex = Pattern.compile("exclude\\s*\\(?\\s*(?:group\\s*[:=]\\s*[\"']([^\"']+)[\"'])?\\s*,?\\s*(?:module\\s*[:=]\\s*[\"']([^\"']+)[\"'])?")
                    .matcher(block);
            while (ex.find()) {
                if (ex.group(1) != null || ex.group(2) != null) {
                    d.excludes.add((ex.group(1) == null ? "*" : ex.group(1)) + ":" + (ex.group(2) == null ? "*" : ex.group(2)));
                }
            }
            out.deps.add(d);
        }
        return out;
    }

    private static String substitute(String s, Map<String, String> vars) {
        String cur = s;
        for (int i = 0; i < 5 && cur.contains("$"); i++) {
            Matcher m = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)").matcher(cur);
            StringBuffer sb = new StringBuffer();
            boolean any = false;
            while (m.find()) {
                String name = m.group(1) != null ? m.group(1) : m.group(2);
                String val = vars.get(name);
                if (val == null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(val));
                    any = true;
                }
            }
            m.appendTail(sb);
            cur = sb.toString();
            if (!any) {
                break;
            }
        }
        return cur;
    }

    private static String readBlock(String text, int from) {
        int depth = 1;
        int i = from;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return text.substring(from, Math.max(from, i - 1));
    }

    private static String stripComments(String s) {
        String noBlock = s.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)(?<![:\"'])//.*$", "");
    }
}
