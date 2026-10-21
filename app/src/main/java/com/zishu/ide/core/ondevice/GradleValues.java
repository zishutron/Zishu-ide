package com.zishu.ide.core.ondevice;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the handful of values the on-device build needs from a module's build.gradle / build.gradle.kts.
 * It is a text reader, not a Gradle interpreter: anything computed or conditional is reported as missing
 * rather than guessed.
 */
public final class GradleValues {
    public String namespace;
    public String applicationId;
    public int compileSdk;
    public int minSdk;
    public int targetSdk;
    public int versionCode;
    public String versionName;
    public boolean usesKotlin;
    public boolean usesFileTreeJars;
    /** Dependency declarations the on-device engine cannot resolve (it has no Maven access). */
    public final List<String> unsupportedDependencies = new ArrayList<String>();

    private static final Pattern DEP = Pattern.compile(
            "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|annotationProcessor|kapt|ksp|debugImplementation)\\b(.*)$");

    public static GradleValues parse(String text) {
        String t = stripComments(text);
        GradleValues v = new GradleValues();
        v.namespace = str(t, "namespace");
        v.applicationId = str(t, "applicationId");
        v.compileSdk = num(t, "compileSdk", "compileSdkVersion");
        v.minSdk = num(t, "minSdk", "minSdkVersion");
        v.targetSdk = num(t, "targetSdk", "targetSdkVersion");
        v.versionCode = num(t, "versionCode");
        v.versionName = str(t, "versionName");
        v.usesKotlin = Pattern.compile("kotlin-android|org\\.jetbrains\\.kotlin|kotlin\\(").matcher(t).find();
        Matcher m = DEP.matcher(t);
        while (m.find()) {
            String rest = m.group(2).trim();
            String line = (m.group(1) + " " + rest).trim();
            if (rest.contains("fileTree")) {
                v.usesFileTreeJars = true;
            } else {
                v.unsupportedDependencies.add(line);
            }
        }
        return v;
    }

    private static String stripComments(String s) {
        String noBlock = s.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)(?<![:\"'])//.*$", "");
    }

    private static String str(String t, String key) {
        Matcher m = Pattern.compile("(?m)\\b" + key + "\\s*(?:=|\\s)\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(t);
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    private static int num(String t, String... keys) {
        for (String key : keys) {
            Matcher m = Pattern.compile("(?m)\\b" + key + "\\s*(?:=|\\s)\\s*(\\d+)\\b").matcher(t);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }
}
