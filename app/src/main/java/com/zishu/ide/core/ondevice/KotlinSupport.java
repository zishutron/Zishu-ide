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
 * Kotlin on the phone (experimental). The compiler is the official kotlin-compiler-embeddable, downloaded once from
 * Maven Central; the Android side converts it to dex and runs it. One fixed version is used: 1.9.24 (the K1 front end),
 * which reads metadata of newer Kotlin libraries but cannot compile Kotlin 2.x-only language features.
 */
public final class KotlinSupport {
    public static final String VERSION = "1.9.24";
    public static final String COMPILER = "org.jetbrains.kotlin:kotlin-compiler-embeddable:" + VERSION;
    public static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib:" + VERSION;

    private KotlinSupport() {
    }

    public static Coordinate stdlib() {
        return Coordinate.parse(STDLIB);
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
        List<Resolver.Root> roots = new ArrayList<Resolver.Root>();
        roots.add(new Resolver.Root(Coordinate.parse(COMPILER), true, true, null));
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
        log.line("Kotlin compiler " + VERSION + ": " + jars.size() + " jar(s) ready");
        return jars;
    }
}
