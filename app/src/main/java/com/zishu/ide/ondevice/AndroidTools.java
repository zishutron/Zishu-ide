package com.zishu.ide.ondevice;

import android.content.Context;
import android.os.Build;

import com.android.apksig.ApkSigner;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildLog;
import com.zishu.ide.core.ondevice.JavaLevels;
import com.zishu.ide.core.ondevice.OnDeviceTools;
import com.zishu.ide.core.ondevice.ToolException;
import com.zishu.ide.core.util.IoUtil;

import org.eclipse.jdt.core.compiler.batch.BatchCompiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The real on-device tools: aapt2 (native binary), ECJ (Java compiler), D8 (dexer) and apksig (signer).
 * Nothing here is simulated: each step either produces its output file or throws with the tool's message.
 */
public final class AndroidTools implements OnDeviceTools {
    private final Context ctx;

    public AndroidTools(Context context) {
        this.ctx = context.getApplicationContext();
    }

    private File aapt2File() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
    }

    private Aapt2Runner aapt2() {
        File tmp = new File(ctx.getCacheDir(), "aapt2-tmp");
        tmp.mkdirs();
        return new Aapt2Runner(aapt2File(), tmp);
    }

    private boolean hasAsset(String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            IoUtil.closeQuietly(in);
        }
    }

    @Override
    public BuildEngine.Status status() {
        if (Build.VERSION.SDK_INT < 26) {
            return BuildEngine.Status.unavailable("The on-device build needs Android 8.0 or newer (this phone has API "
                    + Build.VERSION.SDK_INT + ").", "Use the cloud build on this phone.");
        }
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                arm64 = true;
            }
        }
        if (!arm64) {
            return BuildEngine.Status.unavailable("The on-device build needs a 64-bit ARM phone.", "Use the cloud build.");
        }
        if (Build.VERSION.SDK_INT < 23) {
            return BuildEngine.Status.unavailable("Secure key storage needs Android 6+.", "Use the cloud build.");
        }
        File aapt = aapt2File();
        if (!aapt.isFile()) {
            return BuildEngine.Status.unavailable("This build of Zishu IDE does not include aapt2.",
                    "Install the Zishu IDE APK built by the CI workflow (it bundles the toolchain).");
        }
        if (!hasAsset("toolchain/android.jar")) {
            return BuildEngine.Status.unavailable("This build of Zishu IDE does not include the Android platform jar.",
                    "Install the Zishu IDE APK built by the CI workflow.");
        }
        return BuildEngine.Status.ok("Builds run on this phone. No account or internet needed.");
    }

    // ------------------------------------------------------------------------ android.jar

    private File androidJarCache;

    @Override
    public synchronized File androidJar() {
        if (androidJarCache != null && androidJarCache.isFile()) {
            return androidJarCache;
        }
        File dir = new File(ctx.getFilesDir(), "toolchain");
        dir.mkdirs();
        File target = new File(dir, "android-" + androidJarApi() + "-" + ctx.getApplicationInfo().sourceDir.hashCode() + ".jar");
        if (!target.isFile() || target.length() == 0) {
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    f.delete();
                }
            }
            InputStream in = null;
            OutputStream out = null;
            try {
                in = ctx.getAssets().open("toolchain/android.jar");
                File tmp = new File(dir, "android.jar.tmp");
                out = new FileOutputStream(tmp);
                IoUtil.copy(in, out);
                out.close();
                out = null;
                if (!tmp.renameTo(target)) {
                    throw new IOException("rename failed");
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot unpack android.jar: " + e.getMessage(), e);
            } finally {
                IoUtil.closeQuietly(in);
                IoUtil.closeQuietly(out);
            }
        }
        androidJarCache = target;
        return target;
    }

    @Override
    public int androidJarApi() {
        InputStream in = null;
        try {
            in = ctx.getAssets().open("toolchain/api.txt");
            return Integer.parseInt(new String(IoUtil.readAll(in, 64), IoUtil.UTF8).trim());
        } catch (IOException e) {
            return 0;
        } catch (NumberFormatException e) {
            return 0;
        } finally {
            IoUtil.closeQuietly(in);
        }
    }

    /** aapt2 version for the self-test. */
    public String aapt2Version() throws ToolException {
        return aapt2().version();
    }

    // ------------------------------------------------------------------------ steps

    @Override
    public void compileResources(ResourceJob job, BuildLog log) throws ToolException {
        aapt2().compileAndLink(job, log);
    }

    @Override
    public void compileJava(final JavaJob job, final BuildLog log) throws ToolException {
        if (!JavaLevels.compilerAccepts(job.javaLevel)) {
            throw new ToolException(JavaLevels.unsupportedHint(job.javaLevel));
        }
        List<String> args = new ArrayList<String>();
        args.add("-source");
        args.add(JavaLevels.option(job.javaLevel));
        args.add("-target");
        args.add(JavaLevels.option(job.javaLevel));
        args.add("-proc:none");
        args.add("-nowarn");
        args.add("-Xemacs");
        args.add("-encoding");
        args.add("UTF-8");
        StringBuilder cp = new StringBuilder();
        if (JavaLevels.usesBootclasspath(job.javaLevel)) {
            args.add("-bootclasspath");
            args.add(job.classpath.get(0).getAbsolutePath());
        } else {
            // ECJ: "option -bootclasspath not supported at compliance level 9 and above"
            cp.append(job.classpath.get(0).getAbsolutePath());
            log.line("Java " + job.javaLevel + ": android.jar is passed on the classpath (ECJ has no -bootclasspath here).");
        }
        for (int i = 1; i < job.classpath.size(); i++) {
            if (cp.length() > 0) {
                cp.append(File.pathSeparator);
            }
            cp.append(job.classpath.get(i).getAbsolutePath());
        }
        if (cp.length() > 0) {
            args.add("-classpath");
            args.add(cp.toString());
        }
        args.add("-d");
        args.add(job.outDir.getAbsolutePath());
        for (File f : job.sourceFiles) {
            args.add(f.getAbsolutePath());
        }
        final String[] argv = args.toArray(new String[0]);
        log.line("$ ecj (" + job.sourceFiles.size() + " files)");
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        boolean ok;
        try {
            ok = BigThread.run(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    PrintWriter o = new PrintWriter(out);
                    PrintWriter e = new PrintWriter(err);
                    boolean r = BatchCompiler.compile(argv, o, e, null);
                    o.flush();
                    e.flush();
                    return r;
                }
            });
        } catch (Exception e) {
            throw new ToolException("The Java compiler crashed: " + e, e);
        }
        if (out.toString().length() > 0) {
            log.block(out.toString().trim());
        }
        if (err.toString().length() > 0) {
            log.block(err.toString().trim());
        }
        if (!ok) {
            if (JavaLevels.looksLikeLevelRejection(out.toString() + err.toString())) {
                throw new ToolException(JavaLevels.unsupportedHint(job.javaLevel));
            }
            throw new ToolException("Java compilation failed.");
        }
    }

    @Override
    public void compileKotlin(KotlinJob job, BuildLog log) throws ToolException {
        KotlinRunner.compile(ctx, job, androidJar(), log);
    }

    /** First-run setup: converts the Kotlin compiler for this phone ahead of the first Kotlin build. */
    public void prepareKotlin(List<File> compilerJars, BuildLog log) throws ToolException {
        KotlinRunner.prepareOnly(ctx, compilerJars, androidJar(), log);
    }

    public boolean kotlinPrepared(List<File> compilerJars) {
        return KotlinRunner.isPrepared(ctx, compilerJars);
    }

    @Override
    public void dex(final DexJob job, final BuildLog log) throws ToolException {
        final List<Path> programs = new ArrayList<Path>();
        collectClasses(job.classesDir, programs);
        if (job.libraryJars != null) {
            for (File j : job.libraryJars) {
                programs.add(j.toPath());
            }
        }
        if (programs.isEmpty()) {
            throw new ToolException("The compiler produced no class files.");
        }
        log.line("$ d8 (" + programs.size() + " inputs)");
        try {
            BigThread.run(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    DiagnosticsHandler handler = new DiagnosticsHandler() {
                        @Override
                        public void error(Diagnostic d) {
                            log.line("d8 error: " + d.getDiagnosticMessage());
                        }

                        @Override
                        public void warning(Diagnostic d) {
                            log.line("d8 warning: " + d.getDiagnosticMessage());
                        }

                        @Override
                        public void info(Diagnostic d) {
                            // informational output is not useful to the user
                        }
                    };
                    D8Command cmd = D8Command.builder(handler)
                            .addProgramFiles(programs)
                            .addLibraryFiles(job.androidJar.toPath())
                            .setMinApiLevel(job.minSdk)
                            .setMode(CompilationMode.DEBUG)
                            .setOutput(job.outDir.toPath(), OutputMode.DexIndexed)
                            .build();
                    D8.run(cmd);
                    return null;
                }
            });
        } catch (CompilationFailedException e) {
            throw new ToolException("D8 failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ToolException("D8 crashed: " + e, e);
        }
    }

    private static void collectClasses(File dir, List<Path> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectClasses(f, out);
            } else if (f.getName().endsWith(".class")) {
                out.add(f.toPath());
            }
        }
    }

    @Override
    public void sign(File in, File out, BuildLog log) throws ToolException {
        try {
            DebugKey key = DebugKey.loadOrCreate(ctx);
            ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("zishu-debug", key.privateKey,
                    Collections.singletonList(key.certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(sc))
                    .setInputApk(in)
                    .setOutputApk(out)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(false)
                    .setCreatedBy("Zishu IDE")
                    .build()
                    .sign();
            log.line("Signed with the debug key of this install.");
        } catch (Exception e) {
            throw new ToolException("Signing failed: " + e, e);
        }
    }
}
