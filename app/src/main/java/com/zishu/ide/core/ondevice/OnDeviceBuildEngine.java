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
import com.zishu.ide.core.maven.GradleDeps;
import com.zishu.ide.core.maven.MavenDependencies;
import com.zishu.ide.core.maven.VersionCatalog;
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
    private final MavenDependencies maven;

    public OnDeviceBuildEngine(OnDeviceTools tools, File workRoot) {
        this(tools, workRoot, null);
    }

    /** @param maven where libraries come from; null means Maven dependencies are refused */
    public OnDeviceBuildEngine(OnDeviceTools tools, File workRoot, MavenDependencies maven) {
        this.tools = tools;
        this.workRoot = workRoot;
        this.maven = maven;
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
        StageTimer timer = new StageTimer(start);
        try {
            Status st = tools.status();
            if (!st.available) {
                log.line("The on-device toolchain is not ready: " + st.reason);
                return BuildResult.failure("The on-device toolchain is not ready: " + st.reason,
                        elapsed(start), ID, variant, log.text(), null, null);
            }

            listener.onStage(BuildStage.VALIDATE, "Checking project");
            listener.onProgress(3);
            Plan plan = Plan.create(request, tools.androidJarApi(), maven != null, log);
            cancel.throwIfCancelled();

            removeStaleJobs(workRoot, start);
            if (work.exists()) {
                IoUtil.deleteRecursively(work);
            }
            if (!work.mkdirs()) {
                throw new IOException("Cannot create the work folder " + work);
            }

            // 0. Libraries
            LibraryPlan libs = null;
            if (!plan.gradleDeps.deps.isEmpty() || !plan.gradleDeps.localJars.isEmpty()) {
                listener.onStage(BuildStage.BUILD, "Resolving libraries");
                listener.onProgress(6);
                if (maven != null) {
                    final BuildLog dl = log;
                    com.zishu.ide.core.maven.DownloadReporter.attach(maven.cache(), new com.zishu.ide.core.maven.DownloadReporter.Sink() {
                        @Override
                        public void line(String text) {
                            dl.line(text);
                        }
                    });
                }
                if (!plan.gradleDeps.deps.isEmpty()) {
                    libs = LibraryPlan.build(plan.gradleDeps, maven, plan.moduleDir, plan.manifestPackage, log);
                } else {
                    libs = LibraryPlan.build(plan.gradleDeps, null, plan.moduleDir, plan.manifestPackage, log);
                }
                for (String n : libs.notes) {
                    log.line("NOTE: " + n);
                }
                cancel.throwIfCancelled();
            }

            timer.mark("libraries");

            // 1. Manifest + resources
            listener.onStage(BuildStage.BUILD, "Compiling resources (aapt2)");
            listener.onProgress(10);
            File manifest = new File(work, "AndroidManifest.xml");
            String manifestText = plan.patchedManifest;
            if (libs != null && !libs.manifests.isEmpty()) {
                List<String> mergeNotes = new ArrayList<String>();
                manifestText = ManifestMerger.merge(manifestText, libs.manifests, plan.applicationId, plan.minSdk, mergeNotes);
                for (String n : mergeNotes) {
                    log.line(n);
                }
            }
            IoUtil.writeUtf8Atomic(manifest, manifestText);
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
            if (libs != null) {
                rj.libs.addAll(libs.resLibs);
                rj.extraAssetsDirs.addAll(libs.assetDirs);
                rj.extraPackages.addAll(libs.extraPackages);
                if (!libs.rTxts.isEmpty()) {
                    rj.symbolsFile = new File(work, "R.txt");
                }
            }
            rj.genDir.mkdirs();
            rj.workDir.mkdirs();
            tools.compileResources(rj, log);
            requireFile(rj.outApk, "aapt2 did not produce the resource package");
            if (rj.symbolsFile != null) {
                requireFile(rj.symbolsFile, "aapt2 did not write the resource symbols (R.txt)");
                writeLibraryR(libs, rj.symbolsFile, rj.genDir, log);
            }
            timer.mark("resources");
            cancel.throwIfCancelled();

            // 2. Kotlin (optional), then Java
            List<File> generated = new ArrayList<File>();
            collectJava(rj.genDir, generated);
            List<File> sources = new ArrayList<File>(plan.javaSources);
            sources.addAll(generated);
            List<File> classpath = new ArrayList<File>();
            classpath.add(tools.androidJar());
            classpath.addAll(plan.libJars);
            if (libs != null) {
                classpath.addAll(libs.compileJars);
            }
            File classesDir = new File(work, "classes");
            classesDir.mkdirs();
            if (!plan.kotlinSources.isEmpty()) {
                listener.onStage(BuildStage.BUILD, "Compiling Kotlin");
                listener.onProgress(25);
                OnDeviceTools.KotlinJob kj = new OnDeviceTools.KotlinJob();
                kj.version = plan.kotlinVersion;
                kj.compilerJars = KotlinSupport.resolveCompiler(maven, log, plan.kotlinVersion);
                cancel.throwIfCancelled();
                // R.java and Manifest.java are compiled first (they only hold constants) and given to kotlinc as classes, which
                // is how Gradle does it; only the project's own Java files go to kotlinc as sources (read for symbols).
                // They are compiled into the same folder as the Kotlin classes, so the Java step later skips them.
                File rClasses = classesDir;
                rClasses.mkdirs();
                if (!generated.isEmpty()) {
                    OnDeviceTools.JavaJob rjob = new OnDeviceTools.JavaJob();
                    rjob.sourceFiles = generated;
                    rjob.classpath = new ArrayList<File>();
                    rjob.classpath.add(tools.androidJar());
                    rjob.outDir = rClasses;
                    rjob.javaLevel = plan.javaLevel;
                    tools.compileJava(rjob, log);
                    cancel.throwIfCancelled();
                }
                kj.sourceFiles = new ArrayList<File>(plan.kotlinSources);
                kj.sourceFiles.addAll(plan.javaSources);
                kj.classpath = new ArrayList<File>(classpath);
                kj.classpath.add(rClasses);
                kj.outDir = classesDir;
                kj.javaLevel = plan.javaLevel;
                kj.moduleName = plan.manifestPackage.replace('.', '_');
                tools.compileKotlin(kj, log);
                timer.mark("Kotlin");
                cancel.throwIfCancelled();
            }
            listener.onStage(BuildStage.BUILD, "Compiling Java");
            listener.onProgress(45);
            boolean kotlinUsed = !plan.kotlinSources.isEmpty();
            // with Kotlin the generated R classes are already compiled (above); only the project's own Java is left
            List<File> javaToCompile = kotlinUsed ? new ArrayList<File>(plan.javaSources) : sources;
            if (sources.isEmpty()) {
                throw new ToolException("The project has no Java source files.");
            }
            OnDeviceTools.JavaJob jj = new OnDeviceTools.JavaJob();
            jj.sourceFiles = javaToCompile;
            jj.classpath = new ArrayList<File>(classpath);
            if (kotlinUsed) {
                jj.classpath.add(classesDir);
            }
            jj.javaLevel = plan.javaLevel;
            jj.outDir = classesDir;
            if (!javaToCompile.isEmpty()) {
                tools.compileJava(jj, log);
            }
            timer.mark("Java");
            cancel.throwIfCancelled();

            // 3. DEX
            listener.onStage(BuildStage.BUILD, "Converting to DEX (D8)");
            listener.onProgress(60);
            OnDeviceTools.DexJob dj = new OnDeviceTools.DexJob();
            dj.classesDir = jj.outDir;
            dj.libraryJars = new ArrayList<File>(plan.libJars);
            if (libs != null) {
                dj.libraryJars.addAll(libs.dexJars);
            }
            dj.androidJar = tools.androidJar();
            dj.minSdk = plan.minSdk;
            dj.outDir = new File(work, "dex");
            dj.outDir.mkdirs();
            tools.dex(dj, log);
            timer.mark("D8");
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
            Map<String, File> extras = new LinkedHashMap<String, File>(dexFiles);
            extras.putAll(plan.nativeLibs);
            if (libs != null) {
                extras.putAll(libs.nativeLibs);
            }
            ApkPackager.assemble(rj.outApk, extras, unsigned);
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
            timer.mark("packaging and signing");

            listener.onStage(BuildStage.VERIFY, null);
            listener.onProgress(95);
            ApkInspector.Info info = ApkInspector.inspect(apk);
            if (!info.isSigned()) {
                throw new ToolException("The APK was produced but is not signed, so it cannot be installed.");
            }
            log.line("APK verified: " + IoUtil.formatSize(info.sizeBytes));
            log.line(timer.summary());
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
            if (noSpace(e)) {
                log.line(NO_SPACE);
            }
            cleanQuietly(work);
            List<BuildLogAnalyzer.Finding> findings = BuildLogAnalyzer.analyze(log.text());
            return BuildResult.failure(noSpace(e) ? NO_SPACE : firstLine(e.getMessage()), elapsed(start), ID, variant, log.text(), findings, null);
        } catch (IOException e) {
            log.line("ERROR: " + e.getMessage());
            if (noSpace(e)) {
                log.line(NO_SPACE);
            }
            cleanQuietly(work);
            return BuildResult.failure(noSpace(e) ? NO_SPACE : firstLine(e.getMessage()), elapsed(start), ID, variant, log.text(), null, null);
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
        int javaLevel = JavaLevels.DEFAULT;
        String kotlinVersion = KotlinSupport.DEFAULT_VERSION;
        GradleDeps gradleDeps = new GradleDeps();
        File moduleDir;
        String manifestPackage;
        final Map<String, File> nativeLibs = new LinkedHashMap<String, File>();
        final List<File> javaSources = new ArrayList<File>();
        final List<File> kotlinSources = new ArrayList<File>();
        final List<File> libJars = new ArrayList<File>();

        static Plan create(BuildRequest request, int androidJarApi, boolean mavenAvailable, BuildLog log) throws ToolException, IOException {
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
            List<File> kotlinFiles = new ArrayList<File>();
            collectWithSuffix(new File(main, "java"), ".kt", kotlinFiles);
            collectWithSuffix(new File(main, "kotlin"), ".kt", kotlinFiles);
            if (!kotlinFiles.isEmpty() && !mavenAvailable) {
                throw new ToolException("Kotlin projects need the library downloader for the Kotlin compiler, which is not available here. "
                        + "Use the cloud build for Kotlin projects.");
            }
            String gradleText = IoUtil.readUtf8(gradle, 1024 * 1024);
            File catalogFile = new File(root, "gradle/libs.versions.toml");
            VersionCatalog catalog = catalogFile.isFile() ? VersionCatalog.parse(IoUtil.readUtf8(catalogFile, 1024 * 1024)) : null;
            GradleDeps gd = GradleDeps.parse(gradleText, catalog);
            if (!gd.unsupported.isEmpty()) {
                throw new ToolException("The on-device build cannot use this dependency: " + gd.unsupported.get(0)
                        + ". Use the cloud build for it.");
            }
            if (!gd.deps.isEmpty() && !mavenAvailable) {
                throw new ToolException("The on-device build cannot download libraries. Unsupported dependency: "
                        + gd.deps.get(0).configuration + " " + gd.deps.get(0).coordinate
                        + ". Use the cloud build, or put the .jar in app/libs.");
            }
            String manifestXml = IoUtil.readUtf8(manifestFile, 2 * 1024 * 1024);
            String declared = ManifestPatcher.declaredPackage(manifestXml);
            String pkg = declared != null ? declared : v.namespace != null ? v.namespace : v.applicationId;
            if (!ManifestPatcher.isValidPackage(pkg)) {
                throw new ToolException("Could not find a valid package name (namespace) in build.gradle.");
            }
            Plan p = new Plan();
            p.kotlinSources.addAll(kotlinFiles);
            if (!kotlinFiles.isEmpty()) {
                File rootGradle = new File(root, "build.gradle");
                String rootText = rootGradle.isFile() ? IoUtil.readUtf8(rootGradle, 1024 * 1024) : null;
                p.kotlinVersion = KotlinSupport.versionFor(gradleText, rootText,
                        catalog == null || !catalogFile.isFile() ? null : IoUtil.readUtf8(catalogFile, 1024 * 1024));
                gd.addImplementation(KotlinSupport.stdlib(p.kotlinVersion));
                log.line("Kotlin: " + kotlinFiles.size() + " file(s), compiled with Kotlin " + p.kotlinVersion);
            }
            p.gradleDeps = gd;
            p.moduleDir = module;
            p.manifestPackage = pkg;
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
            File jni = new File(main, "jniLibs");
            File[] abiDirs = jni.listFiles();
            if (abiDirs != null) {
                java.util.Arrays.sort(abiDirs);
                for (File abi : abiDirs) {
                    File[] so = abi.listFiles();
                    if (!abi.isDirectory() || so == null) {
                        continue;
                    }
                    if (!LibraryPlan.ABIS.contains(abi.getName())) {
                        log.line("NOTE: jniLibs/" + abi.getName() + " is not packaged (only arm64-v8a and armeabi-v7a are).");
                        continue;
                    }
                    java.util.Arrays.sort(so);
                    for (File f : so) {
                        if (f.isFile() && f.getName().endsWith(".so")) {
                            p.nativeLibs.put("lib/" + abi.getName() + "/" + f.getName(), f);
                        }
                    }
                }
            }
            if (v.javaLevel != 0) {
                if (v.javaLevel < JavaLevels.MIN || v.javaLevel > JavaLevels.MAX) {
                    throw new ToolException("Java " + v.javaLevel + " is outside the supported range "
                            + JavaLevels.MIN + " to " + JavaLevels.MAX + ".");
                }
                p.javaLevel = v.javaLevel;
            }
            if (p.javaLevel > JavaLevels.ECJ_MAX) {
                log.line("WARNING: build.gradle asks for Java " + p.javaLevel + "; this phone's compiler goes up to Java "
                        + JavaLevels.ECJ_MAX + ", so Java " + JavaLevels.ECJ_MAX + " is used. Code that needs a newer language "
                        + "level will fail with a compiler error.");
                p.javaLevel = JavaLevels.ECJ_MAX;
            }
            log.line("Java language level: " + p.javaLevel + (v.javaLevel == 0 ? " (default, build.gradle sets none)" : ""));
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

    /**
     * Writes one small R.java per library package: only the resources that library declares (its R.txt), with the ids aapt2
     * gave them in this app. Needs the final symbol table written by aapt2 (--output-text-symbols).
     */
    /** Work folders left behind by a killed build (older than two hours) are removed; a running build's folder is never that old. */
    private static void removeStaleJobs(File root, long now) {
        File[] old = root.listFiles();
        if (old == null) {
            return;
        }
        for (File f : old) {
            if (f.isDirectory() && f.getName().startsWith("job-") && now - f.lastModified() > 2L * 60 * 60 * 1000) {
                try {
                    IoUtil.deleteRecursively(f);
                } catch (IOException ignored) {
                    // a leftover folder is harmless
                }
            }
        }
    }

    private static final String NO_SPACE = "The phone is out of storage space. Free some space (the build needs several hundred MB) and build again.";

    private static boolean noSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains("No space left on device")) {
                return true;
            }
        }
        return false;
    }

    private static void writeLibraryR(LibraryPlan libs, File finalSymbols, File genDir, BuildLog log) throws IOException {
        LibraryR.Table finalIds = LibraryR.parse(IoUtil.readUtf8(finalSymbols, 64L * 1024 * 1024));
        int fields = 0;
        for (Map.Entry<String, List<File>> e : libs.rTxts.entrySet()) {
            LibraryR.Table lib = new LibraryR.Table();
            for (File f : e.getValue()) {
                LibraryR.merge(lib, LibraryR.parse(IoUtil.readUtf8(f, 16L * 1024 * 1024)));
            }
            File out = new File(genDir, e.getKey().replace('.', '/') + "/R.java");
            if (out.exists()) {
                continue; // aapt2 already wrote the full R of the app's own package
            }
            fields += lib.size();
            File parent = out.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create " + parent);
            }
            IoUtil.writeUtf8Atomic(out, LibraryR.generate(e.getKey(), lib, finalIds));
        }
        log.line("Library R classes: " + libs.rTxts.size() + " package(s), " + fields + " field(s) in total.");
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

    private static void collectWithSuffix(File dir, String suffix, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                collectWithSuffix(f, suffix, out);
            } else if (f.getName().endsWith(suffix)) {
                out.add(f);
            }
        }
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
