package com.zishu.ide.core.ondevice;

import com.zishu.ide.core.build.BuildLog;
import com.zishu.ide.core.maven.Coordinate;
import com.zishu.ide.core.maven.MavenDependencies;
import com.zishu.ide.core.maven.Resolver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Kotlin on the phone. The compiler is the official kotlin-compiler-embeddable, downloaded once from Maven Central; the
 * Android side converts it to dex and runs it. Two versions are known: 2.0.21 (the K2 compiler, the default) and 1.9.24
 * (the K1 compiler, used for projects that ask for a Kotlin 1.x plugin).
 */
public final class KotlinSupport {
    public static final String DEFAULT_VERSION = "2.0.21";
    public static final String LEGACY_VERSION = "1.9.24";
    /** The version used when a project does not ask for a Kotlin 1.x compiler. */
    public static final String VERSION = DEFAULT_VERSION;
    public static final String COMPILER = "org.jetbrains.kotlin:kotlin-compiler-embeddable:" + VERSION;
    /** The compiler reads its XML configuration through StAX (javax.xml.stream), which Android does not have. */
    public static final String STAX_API = "javax.xml.stream:stax-api:1.0-2";
    /**
     * The compiler needs these at run time but its POM does not bring them in (the Gradle module metadata does):
     * kotlin-reflect and the coroutines library (its progress/cancellation code uses kotlinx.coroutines.Job).
     */
    public static final String REFLECT = "org.jetbrains.kotlin:kotlin-reflect:1.6.10";
    public static final String COROUTINES = "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4";
    public static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib:" + VERSION;

    private KotlinSupport() {
    }

    public static Coordinate stdlib() {
        return Coordinate.parse(STDLIB);
    }

    public static Coordinate stdlib(String version) {
        return Coordinate.parse("org.jetbrains.kotlin:kotlin-stdlib:" + version);
    }

    /**
     * Picks the compiler for a project from its Gradle text (root and app build files, version catalog): a Kotlin plugin or
     * kotlin version that starts with 1. selects 1.9.24, anything else (or nothing declared) the default.
     */
    public static String versionFor(String... texts) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:org\\.jetbrains\\.kotlin\\.android['\"\\)\\s]*version\\s*\\(?\\s*|kotlin_version\\s*=\\s*|\\bkotlin\\s*=\\s*|kotlin-gradle-plugin:)['\"]?(\\d+)\\.");
        if (texts != null) {
            for (String t : texts) {
                if (t == null) {
                    continue;
                }
                java.util.regex.Matcher m = p.matcher(t);
                if (m.find()) {
                    return "1".equals(m.group(1)) ? LEGACY_VERSION : DEFAULT_VERSION;
                }
            }
        }
        return DEFAULT_VERSION;
    }

    /** kotlinc's -jvm-target value for a Java level. */
    public static String jvmTarget(int javaLevel) {
        if (javaLevel <= 8) {
            return "1.8";
        }
        return String.valueOf(Math.min(javaLevel, 21));
    }

    /** Command line for K2JVMCompiler. */
    public static List<String> arguments(OnDeviceTools.KotlinJob job) {
        List<String> a = new ArrayList<String>();
        a.add("-no-stdlib");
        a.add("-no-reflect");
        a.add("-no-jdk");
        a.add("-Xskip-metadata-version-check");
        a.add("-Xsuppress-version-warnings");
        if (job.kotlinHome != null) {
            a.add("-kotlin-home");
            a.add(job.kotlinHome.getAbsolutePath());
        }
        a.add("-jvm-target");
        a.add(jvmTarget(job.javaLevel));
        a.add("-module-name");
        a.add(job.moduleName);
        StringBuilder cp = new StringBuilder();
        for (File f : job.classpath) {
            if (cp.length() > 0) {
                cp.append(File.pathSeparator);
            }
            cp.append(f.getAbsolutePath());
        }
        a.add("-classpath");
        a.add(cp.toString());
        a.add("-d");
        a.add(job.outDir.getAbsolutePath());
        for (File f : job.sourceFiles) {
            a.add(f.getAbsolutePath());
        }
        return a;
    }

    /** Downloads (or finds in the cache) the compiler and everything it needs; returns the jars in resolution order. */
    public static List<File> resolveCompiler(MavenDependencies deps, BuildLog log) throws IOException {
        return resolveCompiler(deps, log, DEFAULT_VERSION);
    }

    public static List<File> resolveCompiler(MavenDependencies deps, BuildLog log, String version) throws IOException {
        List<Resolver.Root> roots = new ArrayList<Resolver.Root>();
        roots.add(new Resolver.Root(Coordinate.parse("org.jetbrains.kotlin:kotlin-compiler-embeddable:" + version), true, true, null));
        roots.add(new Resolver.Root(Coordinate.parse(STAX_API), true, true, null));
        roots.add(new Resolver.Root(Coordinate.parse(REFLECT), true, true, null));
        // the coroutines POM names an old kotlin-stdlib-jdk8; the stdlib already comes with the compiler
        roots.add(new Resolver.Root(Coordinate.parse(COROUTINES), true, true,
                new java.util.ArrayList<String>(java.util.Arrays.asList("org.jetbrains.kotlin:*"))));
        Resolver.Result res;
        try {
            res = deps.resolver().resolve(roots, new java.util.HashMap<String, String>());
        } catch (java.net.UnknownHostException e) {
            throw new IOException("Could not reach Maven Central (" + e.getMessage() + "). The Kotlin compiler is downloaded once; "
                    + "connect to the internet and build again.");
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("The Kotlin compiler download timed out. Check the internet connection and try again.");
        }
        List<File> jars = new ArrayList<File>();
        for (Resolver.Artifact a : res.artifacts) {
            if (a.file != null && !a.aar && a.runtime) {
                jars.add(a.file);
            }
        }
        if (jars.isEmpty()) {
            throw new IOException("The Kotlin compiler could not be resolved.");
        }
        log.line("Kotlin compiler " + version + ": " + jars.size() + " jar(s) ready");
        return jars;
    }
}
