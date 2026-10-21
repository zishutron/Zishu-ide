package com.zishu.ide.core.build;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts categorised errors from raw Gradle, javac, kotlinc and aapt2 output. It only reports
 * lines that are really present in the log; it never invents a cause.
 */
public final class BuildLogAnalyzer {

    public enum Category {
        JAVA_COMPILE("Java compilation"),
        KOTLIN_COMPILE("Kotlin compilation"),
        RESOURCE("Android resources"),
        DEPENDENCY("Dependency resolution"),
        SIGNING("APK signing"),
        SDK("Android SDK"),
        TOOLCHAIN("Gradle / JDK / plugin versions"),
        CONFIGURATION("Gradle configuration"),
        OTHER("Other");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    public static final class Finding {
        public final Category category;
        public final String message;
        public final String file;
        public final int line;
        public final String action;

        Finding(Category category, String message, String file, int line, String action) {
            this.category = category;
            this.message = message;
            this.file = file;
            this.line = line;
            this.action = action;
        }
    }

    private static final Pattern JAVAC = Pattern.compile("^(?:.*?[\\s>])?((?:/|[A-Za-z]:)[^\\s:]*\\.java):(\\d+): error: (.*)$");
    private static final Pattern KOTLINC = Pattern.compile("^e: (?:file://)?(\\S+\\.kt):(\\d+):(?:\\d+)\\s*:?\\s*(.*)$");
    private static final Pattern AAPT = Pattern.compile("^(?:ERROR: )?(\\S+\\.xml):(\\d+): (?:AAPT: )?error: (.*)$");
    private static final Pattern AAPT_PLAIN = Pattern.compile("^.*(?:AAPT: error|error: resource|error: failed linking)(.*)$");

    private BuildLogAnalyzer() {
    }

    /** Strips the leading timestamp GitHub puts on every log line. */
    static String stripTimestamp(String line) {
        if (line.length() > 28 && line.charAt(4) == '-' && line.charAt(10) == 'T' && line.indexOf("Z ") > 0
                && line.indexOf("Z ") < 32) {
            return line.substring(line.indexOf("Z ") + 2);
        }
        return line;
    }

    public static List<Finding> analyze(String log) {
        List<Finding> out = new ArrayList<Finding>();
        String[] lines = log.split("\n");
        for (int i = 0; i < lines.length && out.size() < 60; i++) {
            String line = stripTimestamp(lines[i].replace("\r", "")).trim();
            if (line.length() == 0) {
                continue;
            }
            Matcher m = JAVAC.matcher(line);
            if (m.matches()) {
                out.add(new Finding(Category.JAVA_COMPILE, m.group(3), shorten(m.group(1)), parse(m.group(2)),
                        "Fix the error in the source file, then build again."));
                continue;
            }
            m = KOTLINC.matcher(line);
            if (m.matches()) {
                out.add(new Finding(Category.KOTLIN_COMPILE, m.group(3), shorten(m.group(1)), parse(m.group(2)),
                        "Fix the error in the source file, then build again."));
                continue;
            }
            m = AAPT.matcher(line);
            if (m.matches()) {
                out.add(new Finding(Category.RESOURCE, m.group(3), shorten(m.group(1)), parse(m.group(2)),
                        "Fix the resource file named here (check the XML and any @references)."));
                continue;
            }
            if (AAPT_PLAIN.matcher(line).matches()) {
                out.add(new Finding(Category.RESOURCE, line, null, 0,
                        "Check the resource files and references named in the message."));
                continue;
            }
            if (line.startsWith("> Could not resolve ") || line.startsWith("Could not resolve ")
                    || line.startsWith("> Could not find ") || line.startsWith("Could not find ")
                    || line.contains("Could not GET '")) {
                out.add(new Finding(Category.DEPENDENCY, line.replaceFirst("^> ", ""), null, 0,
                        "Check the dependency coordinates and version, and that the repository (google(), mavenCentral()) is declared."));
                continue;
            }
            if (line.contains("SDK location not found")) {
                out.add(new Finding(Category.SDK, line, null, 0,
                        "Define sdk.dir in local.properties or set ANDROID_HOME on the build machine."));
                continue;
            }
            if (line.contains("Failed to find target with hash string")
                    || line.contains("Failed to find Build Tools revision")
                    || (line.contains("platforms;android-") && line.toLowerCase(java.util.Locale.US).contains("not installed"))) {
                out.add(new Finding(Category.SDK, line, null, 0,
                        "Install the Android SDK Platform or Build Tools named in the message, or choose an installed compileSdk."));
                continue;
            }
            if (line.contains("licences have not been accepted") || line.contains("licenses have not been accepted")) {
                out.add(new Finding(Category.SDK, line, null, 0, "Accept the Android SDK licences on the build machine."));
                continue;
            }
            if (line.contains("Minimum supported Gradle version is")
                    || line.contains("requires Java 17") || line.contains("requires Java 11")
                    || line.contains("Unsupported class file major version")
                    || line.contains("Could not find com.android.tools.build:gradle")
                    || line.contains("Plugin [id: 'com.android.application'")) {
                out.add(new Finding(Category.TOOLCHAIN, line, null, 0,
                        "Select a compatible Gradle / Android Gradle Plugin / JDK combination."));
                continue;
            }
            String lower = line.toLowerCase(java.util.Locale.US);
            if ((lower.contains("keystore") || lower.contains("signing")) && (lower.contains("error") || lower.contains("failed")
                    || lower.contains("not found") || lower.contains("was tampered") || lower.contains("password"))) {
                out.add(new Finding(Category.SIGNING, line, null, 0, "Check the signing configuration and credentials."));
                continue;
            }
            if (line.startsWith("FAILURE: Build failed") || line.startsWith("* What went wrong:")) {
                // The next non-empty line explains the failure.
                for (int j = i + 1; j < lines.length && j < i + 4; j++) {
                    String next = stripTimestamp(lines[j].replace("\r", "")).trim();
                    if (next.length() > 0) {
                        if (!alreadyCovered(out, next)) {
                            out.add(new Finding(categoriseConfig(next), next.replaceFirst("^> ", ""), null, 0,
                                    "Read the full log for the cause."));
                        }
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static boolean alreadyCovered(List<Finding> found, String next) {
        String n = next.replaceFirst("^> ", "");
        for (Finding f : found) {
            if (f.message.equals(n) || n.contains(f.message) || f.message.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static Category categoriseConfig(String s) {
        if (s.contains("Execution failed for task") && s.contains("compile")) {
            return s.contains("Kotlin") ? Category.KOTLIN_COMPILE : Category.JAVA_COMPILE;
        }
        if (s.contains("A problem occurred evaluating") || s.contains("Could not compile build file")
                || s.contains("Build file '")) {
            return Category.CONFIGURATION;
        }
        return Category.OTHER;
    }

    private static String shorten(String path) {
        int i = path.indexOf("/src/");
        if (i >= 0) {
            int start = path.lastIndexOf('/', i - 1);
            return path.substring(start + 1);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A short multi-line summary of the findings, grouped by category. */
    public static String summarize(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Category c : Category.values()) {
            boolean header = false;
            int shown = 0;
            for (Finding f : findings) {
                if (f.category != c) {
                    continue;
                }
                if (!header) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(c.label).append(":\n");
                    header = true;
                }
                if (++shown > 8) {
                    sb.append("  ...\n");
                    break;
                }
                sb.append("  ");
                if (f.file != null) {
                    sb.append(f.file);
                    if (f.line > 0) {
                        sb.append(':').append(f.line);
                    }
                    sb.append(": ");
                }
                sb.append(f.message).append('\n');
            }
        }
        return sb.toString();
    }
}
