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
import com.zishu.ide.core.ondevice.ClassStubs;
import com.zishu.ide.core.ondevice.JarSplitter;
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
    /** Jars bigger than this (compressed) are converted in several D8 runs of about this size each. */
    private static final long SPLIT_BYTES = 4L * 1024 * 1024;

    private static ClassLoader loader;
    private static String loaderKey;

    private KotlinRunner() {
    }

    static void compile(Context ctx, final OnDeviceTools.KotlinJob job, File androidJar, final BuildLog log) throws ToolException {
        ClassLoader cl = prepare(ctx, job, androidJar, log);
        job.kotlinHome = new File(ctx.getFilesDir(), "toolchains/kotlin-" + KotlinSupport.VERSION);
        ensurePluginRoot(new File(job.kotlinHome, "plugin-root"), job.compilerJars);
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
        List<File> dexJars = new ArrayList<File>();
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new ToolException("Cannot create " + dir);
            }
            migrateLegacy(dir, job.compilerJars);
            boolean converted = false;
            for (File jar : job.compilerJars) {
                File out = dexJar(dir, jar);
                File ok = new File(dir, jar.getName() + ".ok");
                String want = "split-" + SPLIT_BYTES + ":" + jar.length();
                if (!(out.isFile() && ok.isFile() && want.equals(IoUtil.readUtf8(ok, 1024)))) {
                    if (!converted) {
                        log.line("Preparing the Kotlin compiler for this phone (only jars that are not converted yet; "
                                + "the big one takes several minutes)...");
                        converted = true;
                    }
                    ok.delete();
                    out.delete();
                    log.line("  converting " + jar.getName() + " (" + IoUtil.formatSize(jar.length()) + ")");
                    convert(jar, job.compilerJars, androidJar, out, new File(dir, "tmp-" + jar.getName()), log);
                    IoUtil.writeUtf8Atomic(ok, want);
                }
                dexJars.add(out);
            }
            File standIns = ensureStandIns(dir, job.compilerJars, androidJar, log);
            if (standIns != null) {
                dexJars.add(standIns);
            }
            log.line(converted ? "Kotlin compiler converted and cached." : "Kotlin compiler: using the converted copy on this phone.");
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
        loader = new CompilerLoader(path.toString(), parent, new File(dir, "plugin-root"));
        loaderKey = key;
        return loader;
    }

    /**
     * Empty stand-in classes for JDK classes the compiler mentions but Android lacks (javax.swing.Icon ...), converted to a
     * dex jar that is last on the class path, so real classes always win. Rebuilt only when the compiler jars change.
     */
    private static File ensureStandIns(File dir, List<File> jars, File androidJar, BuildLog log) throws ToolException, IOException {
        File out = new File(dir, "jdk-stand-ins.dex.jar");
        File ok = new File(dir, "jdk-stand-ins.ok");
        String key = "stand-ins-1:" + marker(jars);
        if (ok.isFile() && key.equals(IoUtil.readUtf8(ok, 1024 * 1024))) {
            return out.isFile() ? out : null;
        }
        log.line("Scanning the Kotlin compiler for JDK classes that Android does not have...");
        java.util.Map<String, Boolean> stubs = ClassStubs.scan(jars, androidJar);
        ok.delete();
        out.delete();
        if (!stubs.isEmpty()) {
            File tmp = new File(dir, "tmp-stand-ins");
            if (tmp.exists()) {
                IoUtil.deleteRecursively(tmp);
            }
            tmp.mkdirs();
            File jar = new File(tmp, "stand-ins.jar");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar));
            try {
                for (java.util.Map.Entry<String, Boolean> e : stubs.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                    zos.write(ClassStubs.stubClass(e.getKey(), e.getValue()));
                    zos.closeEntry();
                }
            } finally {
                zos.close();
            }
            convert(jar, java.util.Collections.singletonList(jar), androidJar, out, new File(dir, "tmp-stand-ins-dex"), log);
            IoUtil.deleteRecursively(tmp);
            StringBuilder names = new StringBuilder();
            int n = 0;
            for (String k : stubs.keySet()) {
                if (n++ < 6) {
                    names.append(names.length() == 0 ? "" : ", ").append(k.replace('/', '.'));
                }
            }
            log.line("Created " + stubs.size() + " empty stand-in class(es), for example " + names + ".");
        } else {
            log.line("No missing JDK classes found.");
        }
        IoUtil.writeUtf8Atomic(ok, key);
        return out.isFile() ? out : null;
    }

    /** The first version cached NN-name.dex.jar plus one READY marker; keep those converted jars instead of redoing 15 minutes. */
    private static void migrateLegacy(File dir, List<File> jars) throws IOException {
        File ready = new File(dir, "READY");
        if (!ready.isFile()) {
            return;
        }
        String text = IoUtil.readUtf8(ready, 1024 * 1024);
        for (int i = 0; i < jars.size(); i++) {
            File jar = jars.get(i);
            File old = new File(dir, String.format(java.util.Locale.US, "%02d-%s.dex.jar", i, jar.getName().replace(".jar", "")));
            File neu = dexJar(dir, jar);
            if (old.isFile() && !neu.exists() && text.contains("\n" + jar.getName() + ":" + jar.length()) && old.renameTo(neu)) {
                IoUtil.writeUtf8Atomic(new File(dir, jar.getName() + ".ok"), "split-" + SPLIT_BYTES + ":" + jar.length());
            }
        }
        ready.delete();
    }

    /**
     * The compiler asks "which jar did this class come from?" (class.getResource) to find its own configuration. From dex
     * there is no such file, so for the compiler's own classes this loader answers with a real folder that holds the
     * configuration (META-INF/extensions/*.xml, unpacked once from the compiler jar).
     */
    private static final class CompilerLoader extends DexClassLoader {
        private final File pluginRoot;

        CompilerLoader(String dexPath, ClassLoader parent, File pluginRoot) {
            super(dexPath, null, null, parent);
            this.pluginRoot = pluginRoot;
        }

        @Override
        protected java.net.URL findResource(String name) {
            java.net.URL url = super.findResource(name);
            if (url != null || !name.endsWith(".class") || !name.startsWith("org/jetbrains/kotlin/")) {
                return url;
            }
            try {
                File marker = new File(pluginRoot, name);
                if (!marker.isFile()) {
                    File parent = marker.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        return null;
                    }
                    if (!marker.createNewFile() && !marker.isFile()) {
                        return null;
                    }
                }
                return marker.toURI().toURL();
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** Unpacks META-INF/extensions/* of the compiler jar into {@code root} (the folder the compiler reads its configuration from). */
    private static void ensurePluginRoot(File root, List<File> compilerJars) throws ToolException {
        File ok = new File(root, "META-INF/extensions/compiler.xml");
        if (ok.isFile()) {
            return;
        }
        File jar = null;
        for (File f : compilerJars) {
            if (f.getName().startsWith("kotlin-compiler-embeddable")) {
                jar = f;
            }
        }
        if (jar == null) {
            throw new ToolException("kotlin-compiler-embeddable is missing from the resolved compiler jars.");
        }
        try {
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar);
            try {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                byte[] b = new byte[16 * 1024];
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || !n.startsWith("META-INF/extensions/") || n.contains("..")) {
                        continue;
                    }
                    File target = new File(root, n);
                    target.getParentFile().mkdirs();
                    java.io.InputStream in = zf.getInputStream(e);
                    FileOutputStream os = new FileOutputStream(target);
                    try {
                        int r;
                        while ((r = in.read(b)) > 0) {
                            os.write(b, 0, r);
                        }
                    } finally {
                        in.close();
                        os.close();
                    }
                }
            } finally {
                zf.close();
            }
        } catch (IOException e) {
            throw new ToolException("Could not unpack the Kotlin compiler configuration: " + e.getMessage(), e);
        }
        if (!ok.isFile()) {
            throw new ToolException("The Kotlin compiler jar has no META-INF/extensions/compiler.xml.");
        }
    }

    private static String marker(List<File> jars) {
        StringBuilder sb = new StringBuilder(KotlinSupport.VERSION + " split-" + SPLIT_BYTES);
        for (File f : jars) {
            sb.append('\n').append(f.getName()).append(':').append(f.length());
        }
        return sb.toString();
    }

    private static File dexJar(File dir, File jar) {
        return new File(dir, jar.getName().replace(".jar", "") + ".dex.jar");
    }

    /**
     * Jar to dex-in-a-jar. A big jar is cut into parts of about 4 MB (compressed); each part is converted by its own D8
     * run so the phone never holds the whole compiler in memory at once. The dex files of all parts are numbered
     * classes.dex, classes2.dex ... inside one jar, together with the jar's resources.
     */
    private static void convert(final File jar, final List<File> allJars, final File androidJar, File out, File tmp, final BuildLog log)
            throws ToolException, IOException {
        if (tmp.exists()) {
            IoUtil.deleteRecursively(tmp);
        }
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            throw new IOException("Cannot create " + tmp);
        }
        List<List<String>> parts = JarSplitter.plan(jar, SPLIT_BYTES);
        if (parts.isEmpty()) {
            throw new ToolException(jar.getName() + " contains no classes.");
        }
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        try {
            int dexCount = 0;
            long start = System.currentTimeMillis();
            for (int p = 0; p < parts.size(); p++) {
                File partJar = jar;
                File partOut = new File(tmp, "out-" + p);
                partOut.mkdirs();
                if (parts.size() > 1) {
                    partJar = new File(tmp, "part-" + p + ".jar");
                    JarSplitter.write(jar, parts.get(p), partJar);
                }
                runD8(partJar, jar, allJars, androidJar, partOut, log);
                File[] dexes = partOut.listFiles();
                if (dexes == null || dexes.length == 0) {
                    throw new ToolException("D8 produced no dex for part " + (p + 1) + " of " + jar.getName() + ".");
                }
                java.util.Arrays.sort(dexes, new java.util.Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return dexIndex(a.getName()) - dexIndex(b.getName());
                    }
                });
                for (File d : dexes) {
                    if (d.getName().endsWith(".dex")) {
                        dexCount++;
                        addFile(zos, dexCount == 1 ? "classes.dex" : "classes" + dexCount + ".dex", d);
                    }
                }
                IoUtil.deleteRecursively(partOut);
                if (partJar != jar) {
                    partJar.delete();
                }
                if (parts.size() > 1) {
                    long elapsed = (System.currentTimeMillis() - start) / 1000;
                    long left = elapsed * (parts.size() - p - 1) / (p + 1);
                    log.line("    part " + (p + 1) + " of " + parts.size() + " done (" + (100 * (p + 1) / parts.size()) + "%), "
                            + com.zishu.ide.core.maven.DownloadMeter.duration(elapsed) + " elapsed"
                            + (p + 1 < parts.size() ? ", about " + com.zishu.ide.core.maven.DownloadMeter.duration(left) + " left" : ""));
                }
                System.gc();
            }
            copyResources(jar, zos);
        } finally {
            zos.close();
        }
        IoUtil.deleteRecursively(tmp);
        // Android 14+ refuses to load code from a writable file.
        out.setReadOnly();
    }

    private static int dexIndex(String name) {
        if (name.equals("classes.dex")) {
            return 1;
        }
        try {
            return Integer.parseInt(name.substring("classes".length(), name.length() - ".dex".length()));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void addFile(ZipOutputStream zos, String entryName, File f) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                zos.write(b, 0, n);
            }
        } finally {
            in.close();
        }
        zos.closeEntry();
    }

    private static void runD8(final File program, final File wholeJar, final List<File> allJars, final File androidJar,
                              final File dexOut, final BuildLog log) throws ToolException {
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
                        // a part of a split jar still sees all of its jar (supertypes live in the other parts)
                        if (!program.equals(wholeJar) || !f.equals(wholeJar)) {
                            classpath.add(f.toPath());
                        }
                    }
                    D8Command cmd = D8Command.builder(handler)
                            .addProgramFiles(program.toPath())
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
            throw new ToolException("D8 could not convert " + wholeJar.getName() + ": " + e.getMessage(), e);
        } catch (OutOfMemoryError e) {
            throw new ToolException("The phone ran out of memory converting " + wholeJar.getName()
                    + ". Close other apps and build again, or use the cloud build for Kotlin.");
        } catch (Exception e) {
            throw new ToolException("D8 crashed converting " + wholeJar.getName() + ": " + e, e);
        }
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
