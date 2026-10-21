package com.zishu.ide.core.validation;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compatibility knowledge for Android Gradle Plugin (AGP), Gradle, JDK and compileSdk.
 *
 * The AGP 7.x/8.x rows follow Android's published "minimum Gradle version" table; AGP 8.7 was
 * verified against its release notes (Gradle 8.9, JDK 17, API 35). Versions that are not in the
 * table are reported as "not validated" rather than guessed.
 */
public final class GradleCompat {
    private static final Map<String, String> MIN_GRADLE = new HashMap<String, String>();
    private static final Pattern VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    static {
        MIN_GRADLE.put("7.0", "7.0");
        MIN_GRADLE.put("7.1", "7.2");
        MIN_GRADLE.put("7.2", "7.3.3");
        MIN_GRADLE.put("7.3", "7.4");
        MIN_GRADLE.put("7.4", "7.5");
        MIN_GRADLE.put("8.0", "8.0");
        MIN_GRADLE.put("8.1", "8.0");
        MIN_GRADLE.put("8.2", "8.2");
        MIN_GRADLE.put("8.3", "8.4");
        MIN_GRADLE.put("8.4", "8.6");
        MIN_GRADLE.put("8.5", "8.7");
        MIN_GRADLE.put("8.6", "8.7");
        MIN_GRADLE.put("8.7", "8.9");
        MIN_GRADLE.put("8.8", "8.10.2");
        MIN_GRADLE.put("8.9", "8.11.1");
        MIN_GRADLE.put("8.10", "8.11.1");
        MIN_GRADLE.put("8.11", "8.13");
        MIN_GRADLE.put("8.12", "8.13");
        MIN_GRADLE.put("8.13", "8.13");
    }

    private GradleCompat() {
    }

    private static String majorMinor(String version) {
        Matcher m = VERSION.matcher(version == null ? "" : version.trim());
        if (!m.find()) {
            return null;
        }
        return m.group(1) + "." + (m.group(2) == null ? "0" : m.group(2));
    }

    /** Minimum Gradle version for an AGP version, or null if the AGP version is not in the table. */
    public static String minGradleForAgp(String agp) {
        String mm = majorMinor(agp);
        return mm == null ? null : MIN_GRADLE.get(mm);
    }

    /** Minimum JDK for an AGP version: 8.x needs 17, 7.x needs 11, older needs 8. 0 if unknown. */
    public static int minJdkForAgp(String agp) {
        Matcher m = VERSION.matcher(agp == null ? "" : agp.trim());
        if (!m.find()) {
            return 0;
        }
        int major = Integer.parseInt(m.group(1));
        if (major >= 8) {
            return 17;
        }
        if (major == 7) {
            return 11;
        }
        return 8;
    }

    /** Smallest AGP that officially supports compileSdk; null when the table has no rule. */
    public static String minAgpForCompileSdk(int compileSdk) {
        if (compileSdk >= 35) {
            return "8.6.0";
        }
        if (compileSdk == 34) {
            return "8.1.1";
        }
        if (compileSdk == 33) {
            return "7.4.0";
        }
        return null;
    }

    /** Compares dotted versions numerically; non-numeric suffixes such as "-rc01" are ignored. */
    public static int compareVersions(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return x[i] < y[i] ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] parts(String v) {
        int[] out = new int[3];
        Matcher m = VERSION.matcher(v == null ? "" : v.trim());
        if (m.find()) {
            for (int i = 1; i <= 3; i++) {
                out[i - 1] = m.group(i) == null ? 0 : Integer.parseInt(m.group(i));
            }
        }
        return out;
    }

    /**
     * Checks the AGP / Gradle / compileSdk combination. gradle and agp may be null when they
     * could not be determined; that is reported as information, not as an error.
     */
    public static void check(String agp, String gradle, int compileSdk, ValidationReport report) {
        if (agp == null) {
            report.add(Issue.info("agp-unknown",
                    "The Android Gradle Plugin version could not be determined from the build files, "
                            + "so Gradle compatibility was not checked."));
            return;
        }
        String minGradle = minGradleForAgp(agp);
        if (minGradle == null) {
            report.add(Issue.warning("agp-not-validated",
                    "Android Gradle Plugin " + agp + " is not in Zishu IDE's compatibility table.",
                    "Check https://developer.android.com/build/releases/gradle-plugin for the Gradle "
                            + "and JDK versions this plugin needs."));
        } else if (gradle != null && compareVersions(gradle, minGradle) < 0) {
            report.add(Issue.error("gradle-agp-incompatible",
                    "The selected Gradle (" + gradle + ") and Android Gradle Plugin (" + agp
                            + ") versions are incompatible. AGP " + agp + " needs Gradle " + minGradle + " or newer.",
                    "Select a compatible version pair, for example Gradle " + minGradle + " with AGP " + agp + "."));
        }
        if (compileSdk > 0) {
            String minAgp = minAgpForCompileSdk(compileSdk);
            if (minAgp != null && compareVersions(agp, minAgp) < 0) {
                report.add(Issue.warning("agp-compilesdk",
                        "Android Gradle Plugin " + agp + " was not released with support for compileSdk "
                                + compileSdk + " (needs AGP " + minAgp + " or newer).",
                        "Upgrade the plugin to " + minAgp + " or newer, or select a lower compileSdk."));
            }
        }
    }
}
