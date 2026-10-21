package com.zishu.ide.core.validation;

import com.zishu.ide.core.editor.Diagnostic;
import com.zishu.ide.core.editor.SyntaxChecker;
import com.zishu.ide.core.project.FileManager;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectLocator;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static checks on a project directory before it is built. Everything reported here comes from
 * reading the project's files; nothing is guessed about whether the compiler will accept the code.
 */
public final class ProjectValidator {
    private static final Pattern AGP_PLUGIN = Pattern.compile(
            "com\\.android\\.(?:application|library)['\"]?\\s*(?:\\)\\s*)?version\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern AGP_CLASSPATH = Pattern.compile(
            "com\\.android\\.tools\\.build:gradle:([0-9][^'\"\\s]*)");
    private static final Pattern NAMESPACE = Pattern.compile("namespace\\s*=?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern APP_ID = Pattern.compile("applicationId\\s*=?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern COMPILE_SDK = Pattern.compile("compileSdk(?:Version)?\\s*=?\\s*(\\d+)");
    private static final Pattern GRADLE_DIST = Pattern.compile("gradle-([0-9][0-9.]*)-(?:bin|all)\\.zip");
    private static final Pattern KOTLIN_PLUGIN = Pattern.compile(
            "org\\.jetbrains\\.kotlin\\.android['\"]?\\s*(?:\\)\\s*)?version\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern JAVA_LEVEL = Pattern.compile("VERSION_(\\d+(?:_\\d+)?)");

    private ProjectValidator() {
    }

    public static ValidationReport validate(ProjectInfo info) {
        ValidationReport r = new ValidationReport();
        File dir = info.dir;
        if (!dir.isDirectory()) {
            r.add(Issue.error("no-project", "The project folder no longer exists.", "Remove it from the project list."));
            return r;
        }
        FileManager fm;
        try {
            fm = new FileManager(dir);
        } catch (IOException e) {
            r.add(Issue.error("io", "Cannot read the project: " + e.getMessage(), "Check the storage and try again."));
            return r;
        }

        if (!ProjectLocator.looksLikeGradleProject(dir)) {
            r.add(Issue.error("no-gradle", "The project has no build.gradle or settings.gradle file.",
                    "Add a Gradle build configuration, or create a new project from a template."));
            return r;
        }
        if (!new File(dir, "settings.gradle").isFile() && !new File(dir, "settings.gradle.kts").isFile()) {
            r.add(Issue.error("no-settings", "The project has no settings.gradle file.",
                    "Add a settings.gradle that includes the app module, for example: include ':app'."));
        }

        File appDir = new File(dir, "app");
        File manifest = new File(appDir, "src/main/AndroidManifest.xml");
        File appGradle = firstExisting(new File(appDir, "build.gradle"), new File(appDir, "build.gradle.kts"));
        if (!appDir.isDirectory()) {
            r.add(Issue.warning("no-app-module",
                    "There is no 'app' folder. Zishu IDE's checks assume the main module is called 'app'.",
                    "Manifest and module checks are skipped; the build itself may still work."));
            return r;
        }
        if (appGradle == null) {
            r.add(Issue.error("no-app-build", "The app module has no build.gradle file.",
                    "Add app/build.gradle with the com.android.application plugin."));
        }
        if (!manifest.isFile()) {
            r.add(Issue.error("no-manifest", "app/src/main/AndroidManifest.xml is missing.",
                    "Add an AndroidManifest.xml that declares your launcher activity.", "app/src/main/AndroidManifest.xml", 0));
        } else {
            checkXmlFile(fm, "app/src/main/AndroidManifest.xml", r);
            checkLauncher(manifest, r);
        }

        String gradleText = appGradle == null ? "" : readQuiet(appGradle);
        String rootText = readQuiet(firstExisting(new File(dir, "build.gradle"), new File(dir, "build.gradle.kts")));
        String settingsText = readQuiet(firstExisting(new File(dir, "settings.gradle"), new File(dir, "settings.gradle.kts")));

        String agp = first(AGP_PLUGIN, rootText);
        if (agp == null) {
            agp = first(AGP_PLUGIN, settingsText);
        }
        if (agp == null) {
            agp = first(AGP_CLASSPATH, rootText);
        }
        String gradle = null;
        File wrapper = new File(dir, "gradle/wrapper/gradle-wrapper.properties");
        if (wrapper.isFile()) {
            gradle = first(GRADLE_DIST, readQuiet(wrapper));
        }
        int compileSdk = 0;
        String cs = first(COMPILE_SDK, gradleText);
        if (cs != null) {
            compileSdk = Integer.parseInt(cs);
        }
        GradleCompat.check(agp, gradle, compileSdk, r);

        String ns = first(NAMESPACE, gradleText);
        String id = first(APP_ID, gradleText);
        if (id != null) {
            String problem = ConfigValidator.checkApplicationId(id);
            if (problem != null) {
                r.add(Issue.error("application-id", "applicationId \"" + id + "\": " + problem,
                        "Fix applicationId in app/build.gradle.", "app/build.gradle", 0));
            }
        } else if (appGradle != null) {
            r.add(Issue.warning("no-application-id", "No applicationId was found in app/build.gradle.",
                    "Add applicationId to defaultConfig; Gradle will otherwise fall back to the namespace."));
        }
        if (ns == null && agp != null && GradleCompat.compareVersions(agp, "8.0.0") >= 0
                && manifest.isFile() && !readQuiet(manifest).contains("package=")) {
            r.add(Issue.error("no-namespace",
                    "Android Gradle Plugin " + agp + " requires a namespace in app/build.gradle.",
                    "Add: android { namespace 'your.package.name' }", "app/build.gradle", 0));
        }

        boolean kotlinFiles = hasFile(new File(appDir, "src"), ".kt");
        boolean kotlinPlugin = gradleText.contains("kotlin") || rootText.contains("kotlin");
        if (kotlinFiles && !kotlinPlugin) {
            r.add(Issue.error("kotlin-plugin-missing",
                    "The project contains Kotlin (.kt) files but no Kotlin Gradle plugin is applied.",
                    "Add id 'org.jetbrains.kotlin.android' to the plugins blocks."));
        }
        String kv = first(KOTLIN_PLUGIN, rootText);
        if (kv != null && agp != null && GradleCompat.compareVersions(agp, "8.0.0") >= 0
                && GradleCompat.compareVersions(kv, "1.8.0") < 0) {
            r.add(Issue.warning("kotlin-old",
                    "Kotlin plugin " + kv + " is old for Android Gradle Plugin " + agp + ".",
                    "Use Kotlin 1.8.0 or newer."));
        }

        Matcher jm = JAVA_LEVEL.matcher(gradleText);
        while (jm.find()) {
            String lvl = jm.group(1).replace("1_", "");
            int level;
            try {
                level = Integer.parseInt(lvl);
            } catch (NumberFormatException e) {
                continue;
            }
            if (level > 17) {
                r.add(Issue.warning("java-level-high",
                        "Java language level " + level + " is higher than the level Zishu IDE has validated "
                                + "(17). It may fail to compile.", "Use Java 17 or lower in compileOptions."));
            }
        }

        checkResources(fm, r);
        checkWebAssets(info, fm, r);
        return r;
    }

    private static void checkLauncher(File manifest, ValidationReport r) {
        String text = readQuiet(manifest);
        if (!text.contains("android.intent.action.MAIN") || !text.contains("android.intent.category.LAUNCHER")) {
            r.add(Issue.warning("no-launcher", "The manifest declares no launcher activity (MAIN/LAUNCHER).",
                    "Without one the installed app has no icon to open it.", "app/src/main/AndroidManifest.xml", 0));
        }
    }

    private static void checkXmlFile(FileManager fm, String path, ValidationReport r) {
        try {
            FileManager.TextFile tf = fm.readText(path);
            Diagnostic d = SyntaxChecker.firstXmlError(tf.text);
            if (d != null) {
                r.add(Issue.error("xml-invalid", "Invalid XML: " + d.message,
                        "Fix the XML syntax error at the given line.", path, d.line));
            }
        } catch (IOException e) {
            r.add(Issue.error("io", "Cannot read " + path + ": " + e.getMessage(), "Check the file."));
        }
    }

    private static void checkResources(FileManager fm, ValidationReport r) {
        try {
            for (FileManager.FileNode top : fm.list("app/src/main/res")) {
                if (!top.directory) {
                    continue;
                }
                for (FileManager.FileNode f : fm.list(top.path)) {
                    if (!f.directory && f.name.toLowerCase(java.util.Locale.US).endsWith(".xml")) {
                        checkXmlFile(fm, f.path, r);
                    }
                }
            }
        } catch (IOException ignored) {
            // no res folder: nothing to check
        }
    }

    private static void checkWebAssets(ProjectInfo info, FileManager fm, ValidationReport r) {
        boolean web = info.type != null && info.type.name().equals("WEBVIEW_LOCAL");
        if (!web) {
            return;
        }
        try {
            if (!fm.exists("app/src/main/assets/www/index.html")) {
                r.add(Issue.error("web-entry", "The WebView project has no app/src/main/assets/www/index.html.",
                        "Create index.html in assets/www; the app loads it at start-up.",
                        "app/src/main/assets/www/index.html", 0));
            }
        } catch (IOException e) {
            r.add(Issue.error("io", e.getMessage(), "Check the project folder."));
        }
    }

    private static boolean hasFile(File dir, String suffix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return false;
        }
        for (File c : children) {
            if (c.isDirectory() ? hasFile(c, suffix) : c.getName().endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static File firstExisting(File a, File b) {
        return a.isFile() ? a : (b.isFile() ? b : null);
    }

    private static String readQuiet(File f) {
        if (f == null || !f.isFile()) {
            return "";
        }
        try {
            return IoUtil.readUtf8(f, 1024 * 1024);
        } catch (IOException e) {
            return "";
        }
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
