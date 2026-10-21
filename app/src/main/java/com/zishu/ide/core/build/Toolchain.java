package com.zishu.ide.core.build;

import com.zishu.ide.core.model.Defaults;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.validation.GradleCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects a build toolchain that is installed on this machine (JDK, Android SDK, Gradle) by
 * reading the filesystem. It reports facts about what is there; it does not download or install.
 */
public final class Toolchain {

    public enum State { INSTALLED, MISSING, INCOMPATIBLE }

    public static final class Component {
        public final String name;
        public final State state;
        public final String version;
        public final String detail;
        public final String action;

        Component(String name, State state, String version, String detail, String action) {
            this.name = name;
            this.state = state;
            this.version = version;
            this.detail = detail;
            this.action = action;
        }
    }

    public final File javaHome;
    public final File androidHome;
    public final File gradleHome;

    public Toolchain(File javaHome, File androidHome, File gradleHome) {
        this.javaHome = javaHome;
        this.androidHome = androidHome;
        this.gradleHome = gradleHome;
    }

    /** Locations from the usual environment variables; any may be null. */
    public static Toolchain fromEnvironment() {
        return new Toolchain(env("JAVA_HOME"), firstNonNull(env("ANDROID_HOME"), env("ANDROID_SDK_ROOT")), env("GRADLE_HOME"));
    }

    private static File env(String name) {
        String v = System.getenv(name);
        return v == null || v.length() == 0 ? null : new File(v);
    }

    private static File firstNonNull(File a, File b) {
        return a != null ? a : b;
    }

    public File javaExecutable() {
        return javaHome == null ? null : new File(javaHome, "bin/java");
    }

    public File gradleExecutable() {
        return gradleHome == null ? null : new File(gradleHome, "bin/gradle");
    }

    /** Major version of the JDK at javaHome (from its "release" file), or 0 if unknown. */
    public int jdkMajor() {
        if (javaHome == null) {
            return 0;
        }
        File rel = new File(javaHome, "release");
        if (!rel.isFile()) {
            return 0;
        }
        try {
            Matcher m = Pattern.compile("JAVA_VERSION=\"?(\\d+)(?:\\.(\\d+))?").matcher(IoUtil.readUtf8(rel, 64 * 1024));
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                if (major == 1 && m.group(2) != null) {
                    return Integer.parseInt(m.group(2)); // "1.8.0" means Java 8
                }
                return major;
            }
        } catch (IOException ignored) {
            // unreadable: unknown
        }
        return 0;
    }

    private String jdkVersionText() {
        File rel = javaHome == null ? null : new File(javaHome, "release");
        if (rel != null && rel.isFile()) {
            try {
                Matcher m = Pattern.compile("JAVA_VERSION=\"?([^\"\\n]+)").matcher(IoUtil.readUtf8(rel, 64 * 1024));
                if (m.find()) {
                    return m.group(1);
                }
            } catch (IOException ignored) {
                // unreadable
            }
        }
        return null;
    }

    /** Installed platform API levels (platforms/android-NN), ascending. */
    public List<Integer> platforms() {
        List<Integer> out = new ArrayList<Integer>();
        File[] dirs = androidHome == null ? null : new File(androidHome, "platforms").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                Matcher m = Pattern.compile("android-(\\d+)").matcher(d.getName());
                if (m.matches() && new File(d, "android.jar").isFile()) {
                    out.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    public List<String> buildTools() {
        List<String> out = new ArrayList<String>();
        File[] dirs = androidHome == null ? null : new File(androidHome, "build-tools").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && Pattern.matches("\\d+\\.\\d+\\.\\d+.*", d.getName())) {
                    out.add(d.getName());
                }
            }
        }
        Collections.sort(out, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return GradleCompat.compareVersions(a, b);
            }
        });
        return out;
    }

    /** Gradle version from its lib/gradle-core-X.jar file name, or null. */
    public String gradleVersion() {
        File[] libs = gradleHome == null ? null : new File(gradleHome, "lib").listFiles();
        if (libs != null) {
            for (File f : libs) {
                Matcher m = Pattern.compile("gradle-core-(\\d+(?:\\.\\d+)*)\\.jar").matcher(f.getName());
                if (m.matches()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /** Checks every component against what a project with these settings needs. */
    public List<Component> check(int compileSdk, String agpVersion, String gradleRequired) {
        List<Component> out = new ArrayList<Component>();
        int minJdk = Math.max(GradleCompat.minJdkForAgp(agpVersion), 8);

        // JDK
        if (javaHome == null || !javaExecutable().isFile()) {
            out.add(new Component("Java Development Kit", State.MISSING, null,
                    "No JDK found (JAVA_HOME is not set or has no bin/java).",
                    "Install JDK " + Defaults.JDK_VERSION + " and point JAVA_HOME at it."));
        } else {
            int major = jdkMajor();
            String v = jdkVersionText();
            if (major == 0) {
                out.add(new Component("Java Development Kit", State.INSTALLED, v, "Version could not be determined.", null));
            } else if (major < minJdk) {
                out.add(new Component("Java Development Kit", State.INCOMPATIBLE, v,
                        "Android Gradle Plugin " + agpVersion + " needs JDK " + minJdk + " or newer.",
                        "Install JDK " + Defaults.JDK_VERSION + "."));
            } else {
                out.add(new Component("Java Development Kit", State.INSTALLED, v, null, null));
            }
        }

        // SDK
        if (androidHome == null || !androidHome.isDirectory()) {
            out.add(new Component("Android SDK", State.MISSING, null, "No Android SDK found (ANDROID_HOME is not set).",
                    "Install the Android SDK command-line tools and set ANDROID_HOME."));
            out.add(new Component("SDK Platform " + compileSdk, State.MISSING, null, "Needs the Android SDK.",
                    "Install platforms;android-" + compileSdk + "."));
            out.add(new Component("Build Tools " + Defaults.BUILD_TOOLS_VERSION, State.MISSING, null, "Needs the Android SDK.",
                    "Install build-tools;" + Defaults.BUILD_TOOLS_VERSION + "."));
        } else {
            out.add(new Component("Android SDK", State.INSTALLED, null, androidHome.getPath(), null));
            if (platforms().contains(compileSdk)) {
                out.add(new Component("SDK Platform " + compileSdk, State.INSTALLED, String.valueOf(compileSdk), null, null));
            } else {
                out.add(new Component("SDK Platform " + compileSdk, State.MISSING, null,
                        "Android SDK Platform " + compileSdk + " is not installed.",
                        "Install Android SDK Platform " + compileSdk + " or select an available compileSdk."));
            }
            List<String> bt = buildTools();
            boolean haveBt = false;
            for (String b : bt) {
                if (GradleCompat.compareVersions(b, Defaults.BUILD_TOOLS_VERSION) >= 0) {
                    haveBt = true;
                }
            }
            if (haveBt) {
                out.add(new Component("Build Tools " + Defaults.BUILD_TOOLS_VERSION + "+", State.INSTALLED,
                        bt.get(bt.size() - 1), null, null));
            } else {
                out.add(new Component("Build Tools " + Defaults.BUILD_TOOLS_VERSION + "+", State.MISSING, null,
                        bt.isEmpty() ? "No build tools are installed." : "Installed: " + bt + ".",
                        "Install build-tools;" + Defaults.BUILD_TOOLS_VERSION + " or newer."));
            }
            out.add(new Component("Platform Tools", new File(androidHome, "platform-tools").isDirectory()
                    ? State.INSTALLED : State.MISSING, null, "Optional for building; needed for adb.", null));
        }

        // Gradle
        String gv = gradleVersion();
        if (gradleHome == null || gv == null) {
            out.add(new Component("Gradle", State.MISSING, null, "No Gradle distribution found (GRADLE_HOME).",
                    "Install Gradle " + gradleRequired + " or use a project with a Gradle wrapper."));
        } else if (gradleRequired != null && GradleCompat.compareVersions(gv, gradleRequired) < 0) {
            out.add(new Component("Gradle", State.INCOMPATIBLE, gv,
                    "Gradle " + gv + " is older than the " + gradleRequired + " that AGP " + agpVersion + " needs.",
                    "Install Gradle " + gradleRequired + " or newer."));
        } else {
            out.add(new Component("Gradle", State.INSTALLED, gv, null, null));
        }
        return out;
    }
}
