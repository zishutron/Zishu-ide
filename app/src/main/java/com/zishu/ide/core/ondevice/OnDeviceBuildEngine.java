package com.zishu.ide.core.ondevice;

import com.zishu.ide.core.build.ApkInspector;
import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildListener;
import com.zishu.ide.core.build.BuildLog;
import com.zishu.ide.core.build.BuildLogAnalyzer;
import com.zishu.ide.core.build.BuildRequest;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.BuildStage;
import com.zishu.ide.core.build.CancelToken;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a simple Android project entirely on the device: aapt2 -> Java compiler -> D8 -> package -> sign.
 * It supports Java projects without Maven dependencies (optionally jars in libs/). Anything else is
 * refused with a clear reason instead of being half-built.
 */
public final class OnDeviceBuildEngine implements BuildEngine {
    public static final String ID = "on-device";

    private final OnDeviceTools tools;
    private final File workRoot;

    public OnDeviceBuildEngine(OnDeviceTools tools, File workRoot) {
        this.tools = tools;
        this.workRoot = workRoot;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "On this phone";
    }

    @Override
    public Status status() {
        return tools.status();
    }

    @Override
    public BuildResult build(BuildRequest request, BuildListener listener, CancelToken cancel) {
        long start = System.currentTimeMillis();
        String variant = request.variant;
        BuildLog log = new BuildLog(listener);
        File work = new File(workRoot, "job-" + start);
        try {
            Status st = tools.status();
            if (!st.available) {
                log.line("The on-device toolchain is not ready: " + st.reason);
                return BuildResult.failure("The on-device toolchain is not ready: " + st.reason,
                        elapsed(start), ID, variant, log.text(), null, null);
            }

            listener.onStage(BuildStage.VALIDATE, "Checking project");
            listener.onProgress(3);
            Plan plan = Plan.create(request, tools.androidJarApi(), log);
            cancel.throwIfCancelled();

            if (work.exists()) {
                IoUtil.deleteRecursively(work);
            }
            if (!work.mkdirs()) {
                throw new IOException("Cannot create the work folder " + work);
            }

            // 1. Manifest + resources
            listener.onStage(BuildStage.BUILD, "Compiling resources (aapt2)");
            listener.onProgress(10);
            File manifest = new File(work, "AndroidManifest.xml");
            IoUtil.writeUtf8Atomic(manifest, plan.patchedManifest);
            OnDeviceTools.ResourceJob rj = new OnDeviceTools.ResourceJob();
            rj.manifest = manifest;
            rj.resDir = plan.resDir;
            rj.assetsDir = plan.assetsDir;
            rj.androidJar = tools.androidJar();
            rj.genDir = new File(work, "gen");
            rj.outApk = new File(work, "resources.ap_");
            rj.workDir = new File(work, "aapt2");
            rj.minSdk = plan.minSdk;
            rj.targetSdk = plan.targetSdk;
            rj.versionCode = plan.versionCode;
            rj.versionName = plan.versionName;
            rj.applicationId = plan.applicationId;
            rj.genDir.mkdirs();
            rj.workDir.mkdirs();
            tools.compileResources(rj, log);
            requireFile(rj.outApk, "aapt2 did not produce the resource package");
            cancel.throwIfCancelled();

            // 2. Java
            listener.onStage(BuildStage.BUILD, "Compiling Java");
            listener.onProgress(35);
            List<File> sources = new ArrayList<File>(plan.javaSources);
            collectJava(rj.genDir, sources);
            if (sources.isEmpty()) {
                throw new ToolException("The project has no Java source files.");
            }
            OnDeviceTools.JavaJob jj = new OnDeviceTools.JavaJob();
            jj.sourceFiles = sources;
            jj.classpath = new ArrayList<File>();
            jj.classpath.add(tools.androidJar());
            jj.classpath.addAll(plan.libJars);
            jj.outDir = new File(work, "classes");
            jj.outDir.mkdirs();
            tools.compileJava(jj, log);
            cancel.throwIfCancelled();

            // 3. DEX
            listener.onStage(BuildStage.BUILD, "Converting to DEX (D8)");
            listener.onProgress(60);
            OnDeviceTools.DexJob dj = new OnDeviceTools.DexJob();
            dj.classesDir = jj.outDir;
            dj.libraryJars = plan.libJars;
            dj.androidJar = tools.androidJar();
            dj.minSdk = plan.minSdk;
            dj.outDir = new File(work, "dex");
            dj.outDir.mkdirs();
            tools.dex(dj, log);
            Map<String, File> dexFiles = new LinkedHashMap<String, File>();
            File[] produced = dj.outDir.listFiles();
            if (produced != null) {
                java.util.Arrays.sort(produced);
                for (File f : produced) {
                    if (f.getName().endsWith(".dex")) {
                        dexFiles.put(f.getName(), f);
                    }
                }
            }
            if (!dexFiles.containsKey("classes.dex")) {
                throw new ToolException("D8 did not produce classes.dex.");
            }
            cancel.throwIfCancelled();

            // 4. Package + align
            listener.onStage(BuildStage.BUILD, "Packaging");
            listener.onProgress(75);
            File unsigned = new File(work, "unsigned.apk");
            ApkPackager.assemble(rj.outApk, dexFiles, unsigned);
            cancel.throwIfCancelled();

            // 5. Sign
            listener.onStage(BuildStage.BUILD, "Signing (debug key)");
            listener.onProgress(85);
            if (!request.outputDir.isDirectory() && !request.outputDir.mkdirs()) {
                throw new IOException("Cannot create the output folder " + request.outputDir);
            }
            File apk = uniqueApk(request.outputDir, plan.apkBaseName);
            tools.sign(unsigned, apk, log);
            requireFile(apk, "The signer did not produce an APK");

            listener.onStage(BuildStage.VERIFY, null);
            listener.onProgress(95);
            ApkInspector.Info info = ApkInspector.inspect(apk);
            if (!info.isSigned()) {
                throw new ToolException("The APK was produced but is not signed, so it cannot be installed.");
            }
            log.line("APK verified: " + IoUtil.formatSize(info.sizeBytes));
            listener.onStage(BuildStage.CLEANUP, null);
            IoUtil.deleteRecursively(work);
            listener.onProgress(100);
            return BuildResult.success(apk, info, elapsed(start), ID, variant, log.text(), null,
                    "Debug key (generated on this phone)");
        } catch (CancelToken.CancelledException e) {
            log.line("Build cancelled.");
            cleanQuietly(work);
            return BuildResult.cancelled(elapsed(start), ID, variant, log.text(), null);
        } catch (ToolException e) {
            log.line("ERROR: " + e.getMessage());
            cleanQuietly(work);
            List<BuildLogAnalyzer.Finding> findings = BuildLogAnalyzer.analyze(log.text());
            return BuildResult.failure(firstLine(e.getMessage()), elapsed(start), ID, variant, log.text(), findings, null);
        } catch (IOException e) {
            log.line("ERROR: " + e.getMessage());
            cleanQuietly(work);
            return BuildResult.failure(firstLine(e.getMessage()), elapsed(start), ID, variant, log.text(), null, null);
        } catch (RuntimeException e) {
            return unexpected(e, work, start, variant, log);
        } catch (OutOfMemoryError e) {
            log.line("ERROR: The phone ran out of memory while building.");
            cleanQuietly(work);
            return BuildResult.failure("The phone ran out of memory while building. Close other apps and try again.",
                    elapsed(start), ID, variant, log.text(), null, null);
        } catch (LinkageError e) {
            // A class or native library the tools need is missing or broken (NoClassDefFoundError, UnsatisfiedLinkError...).
            return unexpected(e, work, start, variant, log);
        } catch (StackOverflowError e) {
            return unexpected(e, work, start, variant, log);
        }
    }

    private BuildResult unexpected(Throwable e, File work, long start, String variant, BuildLog log) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        log.block(sw.toString());
        cleanQuietly(work);
        return BuildResult.failure("Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                elapsed(start), ID, variant, log.text(), null, null);
    }

    // ---------------------------------------------------------------------------------------------

    /** Everything derived from the project before any tool runs. */
    static final class Plan {
        File resDir;
        File assetsDir;
        String patchedManifest;
        String applicationId;
        int minSdk;
        int targetSdk;
        int versionCode;
        String versionName;
        String apkBaseName;
        final List<File> javaSources = new ArrayList<File>();
        final List<File> libJars = new ArrayList<File>();

        static Plan create(BuildRequest request, int androidJarApi, BuildLog log) throws ToolException, IOException {
            File root = request.project.dir;
            File module = new File(root, "app");
            if (!new File(module, "src/main/AndroidManifest.xml").isFile()) {
                module = root;
            }
            File main = new File(module, "src/main");
            File manifestFile = new File(main, "AndroidManifest.xml");
            if (!manifestFile.isFile()) {
                throw new ToolException("AndroidManifest.xml was not found in app/src/main. "
                        + "The on-device build supports the standard single-module layout.");
            }
            File gradle = new File(module, "build.gradle");
            if (!gradle.isFile()) {
                gradle = new File(module, "build.gradle.kts");
            }
            if (!gradle.isFile()) {
                throw new ToolException("No build.gradle was found next to src/main.");
            }
            GradleValues v = GradleValues.parse(IoUtil.readUtf8(gradle, 1024 * 1024));
            if (v.usesKotlin || hasFileWithSuffix(new File(main, "java"), ".kt") || hasFileWithSuffix(new File(main, "kotlin"), ".kt")) {
                throw new ToolException("Kotlin is not supported by the on-device build yet. Use the cloud build for Kotlin projects.");
            }
            if (!v.unsupportedDependencies.isEmpty()) {
                throw new ToolException("The on-device build cannot download libraries. Unsupported dependency: "
                        + v.unsupportedDependencies.get(0) + ". Use the cloud build, or put the .jar in app/libs.");
            }
            String manifestXml = IoUtil.readUtf8(manifestFile, 2 * 1024 * 1024);
            String declared = ManifestPatcher.declaredPackage(manifestXml);
            String pkg = declared != null ? declared : v.namespace != null ? v.namespace : v.applicationId;
            if (!ManifestPatcher.isValidPackage(pkg)) {
                throw new ToolException("Could not find a valid package name (namespace) in build.gradle.");
            }
            Plan p = new Plan();
            try {
                p.patchedManifest = ManifestPatcher.ensurePackage(manifestXml, pkg);
            } catch (IllegalArgumentException e) {
                throw new ToolException(e.getMessage());
            }
            p.applicationId = v.applicationId != null ? v.applicationId : pkg;
            p.minSdk = v.minSdk > 0 ? v.minSdk : 21;
            p.targetSdk = v.targetSdk > 0 ? v.targetSdk : p.minSdk;
            p.versionCode = v.versionCode > 0 ? v.versionCode : 1;
            p.versionName = v.versionName != null ? v.versionName : "1.0";
            File res = new File(main, "res");
            p.resDir = res.isDirectory() ? res : null;
            File assets = new File(main, "assets");
            p.assetsDir = assets.isDirectory() ? assets : null;
            collectJava(new File(main, "java"), p.javaSources);
            if (v.usesFileTreeJars) {
                File[] jars = new File(module, "libs").listFiles();
                if (jars != null) {
                    java.util.Arrays.sort(jars);
                    for (File j : jars) {
                        if (j.getName().endsWith(".jar") && j.isFile()) {
                            p.libJars.add(j);
                        }
                    }
                }
            }
            if (new File(main, "jniLibs").isDirectory()) {
                log.line("WARNING: src/main/jniLibs is ignored by the on-device build.");
            }
            if (v.compileSdk > androidJarApi) {
                log.line("WARNING: the project sets compileSdk " + v.compileSdk + " but this phone builds against API "
                        + androidJarApi + ". APIs added after " + androidJarApi + " will not compile.");
            }
            String base = request.project.appName != null && request.project.appName.length() > 0
                    ? request.project.appName : request.project.name;
            p.apkBaseName = sanitize(base);
            log.line("Package: " + pkg + (p.applicationId.equals(pkg) ? "" : " (applicationId " + p.applicationId + ")")
                    + ", minSdk " + p.minSdk + ", targetSdk " + p.targetSdk);
            log.line("Sources: " + p.javaSources.size() + " Java file(s)" + (p.libJars.isEmpty() ? "" : ", " + p.libJars.size() + " jar(s) from libs"));
            return p;
        }
    }

    static String sanitize(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._-]", "_");
        t = t.replaceAll("^[._-]+", "");
        return t.isEmpty() ? "app" : t;
    }

    private static File uniqueApk(File dir, String base) {
        File f = new File(dir, base + "-debug.apk");
        int n = 2;
        while (f.exists()) {
            f = new File(dir, base + "-debug-" + n++ + ".apk");
        }
        return f;
    }

    private static void collectJava(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                collectJava(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    private static boolean hasFileWithSuffix(File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isDirectory() ? hasFileWithSuffix(f, suffix) : f.getName().endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static void requireFile(File f, String message) throws ToolException {
        if (!f.isFile() || f.length() == 0) {
            throw new ToolException(message + " (" + f.getName() + ").");
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "Build failed";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static void cleanQuietly(File dir) {
        try {
            if (dir.exists()) {
                IoUtil.deleteRecursively(dir);
            }
        } catch (IOException ignored) {
            // temp files only
        }
    }
}
