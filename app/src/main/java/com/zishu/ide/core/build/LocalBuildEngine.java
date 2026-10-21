package com.zishu.ide.core.build;

import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.validation.ProjectValidator;
import com.zishu.ide.core.validation.ValidationReport;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs Gradle as a child process using a toolchain already installed on the machine.
 *
 * This is real: it launches {@code gradle assembleDebug}, streams the output, supports
 * cancellation and returns the APK Gradle produced. It is NOT usable on a stock Android phone:
 * a normal app cannot ship or execute a JDK, Gradle and the Android SDK's native tools (aapt2),
 * because Android forbids executing binaries from app-writable storage. It exists so the
 * architecture has a second backend and so a future bundled toolchain can plug in; on a phone
 * {@link #status()} reports it as unavailable.
 */
public final class LocalBuildEngine implements BuildEngine {
    public static final String ID = "local-gradle";

    private final Toolchain toolchain;
    private final long timeoutMs;

    public LocalBuildEngine(Toolchain toolchain, long timeoutMs) {
        this.toolchain = toolchain;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Local Gradle";
    }

    @Override
    public Status status() {
        if (isAndroid()) {
            return Status.unavailable("Android does not allow an app to run a JDK, Gradle and the SDK's native tools "
                    + "from its own storage, so a local build cannot run on this device.",
                    "Use the GitHub Actions cloud build.");
        }
        File java = toolchain.javaExecutable();
        File gradle = toolchain.gradleExecutable();
        if (java == null || !java.isFile()) {
            return Status.unavailable("No JDK found for local builds.", "Set JAVA_HOME to a JDK 17 installation.");
        }
        if (toolchain.androidHome == null || !new File(toolchain.androidHome, "platforms").isDirectory()) {
            return Status.unavailable("No Android SDK found for local builds.", "Set ANDROID_HOME to an Android SDK.");
        }
        if (gradle == null || !gradle.isFile()) {
            return Status.unavailable("No Gradle found for local builds.", "Set GRADLE_HOME, or add a Gradle wrapper to the project.");
        }
        return Status.ok("Using JDK at " + toolchain.javaHome + " and Android SDK at " + toolchain.androidHome + ".");
    }

    static boolean isAndroid() {
        String vm = System.getProperty("java.vm.name", "");
        return "Dalvik".equalsIgnoreCase(vm) || System.getProperty("java.vendor", "").toLowerCase(java.util.Locale.US).contains("android")
                || System.getProperty("java.specification.vendor", "").toLowerCase(java.util.Locale.US).contains("android");
    }

    @Override
    public BuildResult build(BuildRequest request, BuildListener listener, CancelToken cancel) {
        long start = System.currentTimeMillis();
        BuildLog log = new BuildLog(listener);
        String variant = request.variant;
        Status st = status();
        if (!st.available) {
            log.line(st.reason + " " + st.action);
            return BuildResult.failure(st.reason, 0, ID, variant, log.text(), null, null);
        }
        if (!"debug".equals(variant)) {
            return BuildResult.failure("Only debug builds are supported in this release.", 0, ID, variant, log.text(), null, null);
        }
        ProjectInfo project = request.project;
        Process process = null;
        try {
            listener.onStage(BuildStage.VALIDATE, project.name);
            ValidationReport report = ProjectValidator.validate(project);
            for (com.zishu.ide.core.validation.Issue i : report.all()) {
                log.line(i.severity.name() + ": " + i.message);
            }
            if (report.hasErrors()) {
                return BuildResult.invalid(report, System.currentTimeMillis() - start, ID, variant);
            }
            cancel.throwIfCancelled();

            File wrapper = new File(project.dir, "gradlew");
            File exe = wrapper.isFile() && wrapper.canExecute() ? wrapper : toolchain.gradleExecutable();
            File apkDir = new File(project.dir, "app/build/outputs/apk/debug");
            long before = System.currentTimeMillis() - 2000;

            List<String> cmd = new ArrayList<String>();
            cmd.add(exe.getPath());
            cmd.add("assembleDebug");
            cmd.add("--no-daemon");
            cmd.add("--console=plain");
            cmd.add("-Dorg.gradle.welcome=never");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(project.dir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("JAVA_HOME", toolchain.javaHome.getPath());
            env.put("ANDROID_HOME", toolchain.androidHome.getPath());
            env.put("ANDROID_SDK_ROOT", toolchain.androidHome.getPath());

            listener.onStage(BuildStage.BUILD, "gradle assembleDebug");
            log.line("$ " + IoUtil.join(" ", cmd));
            process = pb.start();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), IoUtil.UTF8));
            final BuildLog buildLog = log;
            Thread pump = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            buildLog.line(line);
                        }
                    } catch (IOException ignored) {
                        // stream closed because the process ended or was stopped
                    }
                }
            });
            pump.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (isRunning(process)) {
                if (cancel.isCancelled()) {
                    process.destroy();
                    pump.join(3000);
                    throw new CancelToken.CancelledException();
                }
                if (System.currentTimeMillis() > deadline) {
                    process.destroy();
                    pump.join(3000);
                    return BuildResult.failure("The build exceeded the " + timeoutMs / 60000 + " minute limit and was stopped.",
                            System.currentTimeMillis() - start, ID, variant, log.text(), null, null);
                }
                Thread.sleep(100);
            }
            pump.join(5000);
            int exit = process.exitValue();
            if (exit != 0) {
                List<BuildLogAnalyzer.Finding> findings = BuildLogAnalyzer.analyze(log.text());
                String title = findings.isEmpty() ? "Gradle failed with exit code " + exit + "."
                        : "The build failed: " + findings.get(0).message;
                return BuildResult.failure(title, System.currentTimeMillis() - start, ID, variant, log.text(), findings, null);
            }

            listener.onStage(BuildStage.VERIFY, null);
            File newest = newestApk(apkDir, before);
            if (newest == null) {
                throw new IOException("Gradle reported success but no APK was found in " + apkDir.getPath() + ".");
            }
            if (!request.outputDir.isDirectory() && !request.outputDir.mkdirs()) {
                throw new IOException("Cannot create the output folder " + request.outputDir);
            }
            File copy = new File(request.outputDir, newest.getName());
            int n = 2;
            while (copy.exists()) {
                copy = new File(request.outputDir, newest.getName().replace(".apk", "") + "-" + n++ + ".apk");
            }
            java.io.FileInputStream in = new java.io.FileInputStream(newest);
            java.io.FileOutputStream out = new java.io.FileOutputStream(copy);
            try {
                IoUtil.copy(in, out);
            } finally {
                IoUtil.closeQuietly(in);
                IoUtil.closeQuietly(out);
            }
            ApkInspector.Info info = ApkInspector.inspect(copy);
            if (!info.isSigned()) {
                throw new IOException("The APK is not signed and cannot be installed.");
            }
            log.line("APK verified: " + IoUtil.formatSize(info.sizeBytes));
            return BuildResult.success(copy, info, System.currentTimeMillis() - start, ID, variant, log.text(), null,
                    "Debug key (generated by the Android Gradle Plugin)");
        } catch (CancelToken.CancelledException e) {
            log.line("Build cancelled.");
            return BuildResult.cancelled(System.currentTimeMillis() - start, ID, variant, log.text(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroy();
            }
            return BuildResult.cancelled(System.currentTimeMillis() - start, ID, variant, log.text(), null);
        } catch (IOException e) {
            log.line("ERROR: " + e.getMessage());
            return BuildResult.failure(e.getMessage(), System.currentTimeMillis() - start, ID, variant, log.text(), null, null);
        }
    }

    private static boolean isRunning(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static File newestApk(File dir, long notBefore) {
        File[] files = dir.listFiles();
        File best = null;
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".apk") && f.lastModified() >= notBefore
                        && (best == null || f.lastModified() > best.lastModified())) {
                    best = f;
                }
            }
        }
        return best;
    }
}
