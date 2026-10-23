package com.zishu.ide.ondevice;

import android.content.Context;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.OutputMode;
import com.zishu.ide.core.build.BuildLog;
import com.zishu.ide.core.ondevice.KotlinSupport;
import com.zishu.ide.core.ondevice.OnDeviceTools;
import com.zishu.ide.core.ondevice.ToolException;
import com.zishu.ide.core.util.IoUtil;

import dalvik.system.DexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runs the official Kotlin compiler on the phone (experimental). The compiler jars are converted to dex once with
 * D8 (kept under files/toolchains/kotlin-VERSION), loaded with a DexClassLoader and called through reflection.
 * Every stage logs what it does; nothing is assumed to work.
 */
final class KotlinRunner {
    private static final String MAIN_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";
    private static final int DEX_MIN_API = 26;

    private static ClassLoader loader;
    private static String loaderKey;

    private KotlinRunner() {
    }

    static void compile(Context ctx, final OnDeviceTools.KotlinJob job, File androidJar, final BuildLog log) throws ToolException {
        ClassLoader cl = prepare(ctx, job, androidJar, log);
        final List<String> args = KotlinSupport.arguments(job);
        log.line("$ kotlinc (" + countKt(job) + " Kotlin file(s), jvm-target " + KotlinSupport.jvmTarget(job.javaLevel) + ")");
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        String exit;
        try {
            exit = runCompiler(cl, args, buf);
        } catch (InvocationTargetException e) {
            flush(buf, log);
            throw new ToolException("The Kotlin compiler crashed on this phone:\n" + shortTrace(e.getCause() != null ? e.getCause() : e), e);
        } catch (Throwable e) {
            flush(buf, log);
            throw new ToolException("The Kotlin compiler could not run on this phone:\n" + shortTrace(e), e);
        }
        flush(buf, log);
        if (!"OK".equals(exit)) {
            throw new ToolException("Kotlin compilation failed (" + exit + ").");
        }
    }

    private static int countKt(OnDeviceTools.KotlinJob job) {
        int n = 0;
        for (File f : job.sourceFiles) {
            if (f.getName().endsWith(".kt")) {
                n++;
            }
        }
        return n;
    }

    private static void flush(ByteArrayOutputStream buf, BuildLog log) {
        try {
            String out = buf.toString("UTF-8").trim();
            if (out.length() > 0) {
                log.block(out);
            }
        } catch (IOException ignored) {
            // cannot happen for UTF-8
        }
        buf.reset();
    }

    private static String runCompiler(final ClassLoader cl, final List<String> args, final ByteArrayOutputStream buf) throws Exception {
        return BigThread.run(new Callable<String>() {
            @Override
            public String call() throws Exception {
                System.setProperty("java.awt.headless", "true");
                System.setProperty("idea.io.use.nio2", "true");
                System.setProperty("idea.use.native.fs.for.win", "false");
                System.setProperty("kotlin.environment.keepalive", "false");
                Thread.currentThread().setContextClassLoader(cl);
                Class<?> c = Class.forName(MAIN_CLASS, true, cl);
                Object compiler = c.getDeclaredConstructor().newInstance();
                Method exec = c.getMethod("exec", PrintStream.class, String[].class);
                PrintStream ps = new PrintStream(buf, true, "UTF-8");
                Object result = exec.invoke(compiler, ps, (Object) args.toArray(new String[0]));
                ps.flush();
                return String.valueOf(result);
            }
        });
    }

    // ------------------------------------------------------------------------ toolchain

    private static synchronized ClassLoader prepare(Context ctx, OnDeviceTools.KotlinJob job, File androidJar, BuildLog log)
            throws ToolException {
        File dir = new File(ctx.getFilesDir(), "toolchains/kotlin-" + KotlinSupport.VERSION);
        String key = marker(job.compilerJars);
        if (loader != null && key.equals(loaderKey)) {
            return loader;
        }
        File ready = new File(dir, "READY");
        List<File> dexJars = new ArrayList<File>();
        try {
            boolean cached = false;
            if (ready.isFile() && key.equals(IoUtil.readUtf8(ready, 1024 * 1024))) {
                cached = true;
                for (int i = 0; i < job.compilerJars.size(); i++) {
                    File d = dexJar(dir, i, job.compilerJars.get(i));
                    if (!d.isFile()) {
                        cached = false;
                    }
                    dexJars.add(d);
                }
            }
            if (!cached) {
                dexJars.clear();
                log.line("Preparing the Kotlin compiler for this phone (first time only; this can take several minutes)...");
                if (dir.exists()) {
                    IoUtil.deleteRecursively(dir);
                }
                if (!dir.mkdirs()) {
                    throw new ToolException("Cannot create " + dir);
                }
                for (int i = 0; i < job.compilerJars.size(); i++) {
                    File jar = job.compilerJars.get(i);
                    File out = dexJar(dir, i, jar);
                    log.line("  converting " + jar.getName() + " (" + IoUtil.formatSize(jar.length()) + ")");
                    convert(jar, job.compilerJars, androidJar, out, new File(dir, "tmp-" + i), log);
                    dexJars.add(out);
                }
                IoUtil.writeUtf8Atomic(ready, key);
                log.line("Kotlin compiler converted and cached.");
            } else {
                log.line("Kotlin compiler: using the converted copy on this phone.");
            }
        } catch (IOException e) {
            throw new ToolException("Could not prepare the Kotlin compiler: " + e.getMessage(), e);
        }
        StringBuilder path = new StringBuilder();
        for (File f : dexJars) {
            if (path.length() > 0) {
                path.append(File.pathSeparator);
            }
            path.append(f.getAbsolutePath());
        }
        // Parent = the boot loader chain only, so the IDE's own classes never shadow the compiler's.
        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
        loader = new DexClassLoader(path.toString(), null, null, parent);
        loaderKey = key;
        return loader;
    }

    private static String marker(List<File> jars) {
        StringBuilder sb = new StringBuilder(KotlinSupport.VERSION);
        for (File f : jars) {
            sb.append('\n').append(f.getName()).append(':').append(f.length());
        }
        return sb.toString();
    }

    private static File dexJar(File dir, int index, File jar) {
        return new File(dir, String.format(java.util.Locale.US, "%02d-%s.dex.jar", index, jar.getName().replace(".jar", "")));
    }

    /** Jar to dex-in-a-jar: D8 writes classes.dex, classes2.dex ... which are zipped into one file the class loader accepts. */
    private static void convert(final File jar, final List<File> allJars, final File androidJar, File out, File tmp, final BuildLog log)
            throws ToolException, IOException {
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            throw new IOException("Cannot create " + tmp);
        }
        final File dexOut = tmp;
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
                            // the compiler references many JDK classes Android does not have; those warnings are expected
                        }

                        @Override
                        public void info(Diagnostic d) {
                        }
                    };
                    List<Path> classpath = new ArrayList<Path>();
                    for (File f : allJars) {
                        if (!f.equals(jar)) {
                            classpath.add(f.toPath());
                        }
                    }
                    D8Command cmd = D8Command.builder(handler)
                            .addProgramFiles(jar.toPath())
                            .addClasspathFiles(classpath)
                            .addLibraryFiles(androidJar.toPath())
                            .setMinApiLevel(DEX_MIN_API)
                            .setMode(CompilationMode.RELEASE)
                            .setOutput(dexOut.toPath(), OutputMode.DexIndexed)
                            .build();
                    D8.run(cmd);
                    return null;
                }
            });
        } catch (CompilationFailedException e) {
            throw new ToolException("D8 could not convert " + jar.getName() + ": " + e.getMessage(), e);
        } catch (OutOfMemoryError e) {
            throw new ToolException("The phone ran out of memory converting " + jar.getName()
                    + ". Close other apps and build again, or use the cloud build for Kotlin.");
        } catch (Exception e) {
            throw new ToolException("D8 crashed converting " + jar.getName() + ": " + e, e);
        }
        File[] dexes = tmp.listFiles();
        if (dexes == null || dexes.length == 0) {
            throw new ToolException("D8 produced no dex for " + jar.getName() + ".");
        }
        java.util.Arrays.sort(dexes);
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        try {
            byte[] b = new byte[64 * 1024];
            for (File d : dexes) {
                if (!d.getName().endsWith(".dex")) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(d.getName()));
                java.io.FileInputStream in = new java.io.FileInputStream(d);
                try {
                    int n;
                    while ((n = in.read(b)) > 0) {
                        zos.write(b, 0, n);
                    }
                } finally {
                    in.close();
                }
                zos.closeEntry();
            }
            copyResources(jar, zos);
        } finally {
            zos.close();
        }
        IoUtil.deleteRecursively(tmp);
        // Android 14+ refuses to load code from a writable file.
        out.setReadOnly();
    }

    /**
     * The compiler reads extension descriptors and services (META-INF/extensions/*.xml, META-INF/services/*, builtins)
     * from its own jar with getResource, so every non-class entry is copied next to the dex files.
     */
    private static void copyResources(File jar, ZipOutputStream zos) throws IOException {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar);
        try {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            byte[] b = new byte[64 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                String lower = n.toLowerCase(java.util.Locale.US);
                if (e.isDirectory() || n.endsWith(".class") || n.startsWith("classes") && n.endsWith(".dex")
                        || lower.equals("meta-inf/manifest.mf") || lower.startsWith("meta-inf/") && (lower.endsWith(".sf")
                        || lower.endsWith(".rsa") || lower.endsWith(".dsa") || lower.endsWith(".ec"))
                        || !seen.add(n)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(n));
                java.io.InputStream in = zf.getInputStream(e);
                try {
                    int r;
                    while ((r = in.read(b)) > 0) {
                        zos.write(b, 0, r);
                    }
                } finally {
                    in.close();
                }
                zos.closeEntry();
            }
        } finally {
            zf.close();
        }
    }

    private static String shortTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(lines.length, 40);
        for (int i = 0; i < n; i++) {
            sb.append(lines[i]).append('\n');
        }
        if (lines.length > n) {
            sb.append("... (").append(lines.length - n).append(" more lines)");
        }
        return sb.toString().trim();
    }
}
