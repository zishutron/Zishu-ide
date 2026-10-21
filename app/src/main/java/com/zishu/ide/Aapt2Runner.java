package com.zishu.ide.ondevice;

import com.zishu.ide.core.build.BuildLog;
import com.zishu.ide.core.ondevice.OnDeviceTools;
import com.zishu.ide.core.ondevice.ToolException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Runs the aapt2 binary that ships in the app's native library folder (the only place Android lets an app execute from). */
final class Aapt2Runner {
    private final File aapt2;
    private final File tmpDir;

    Aapt2Runner(File aapt2, File tmpDir) {
        this.aapt2 = aapt2;
        this.tmpDir = tmpDir;
    }

    /** Returns the version string, or throws with the real reason it cannot run. */
    String version() throws ToolException {
        List<String> cmd = new ArrayList<String>();
        cmd.add("version");
        StringBuilder out = new StringBuilder();
        run(cmd, out, null);
        return out.toString().trim();
    }

    void compileAndLink(OnDeviceTools.ResourceJob job, BuildLog log) throws ToolException {
        File compiled = null;
        if (job.resDir != null) {
            compiled = new File(job.workDir, "compiled.zip");
            List<String> c = new ArrayList<String>();
            c.add("compile");
            c.add("--dir");
            c.add(job.resDir.getAbsolutePath());
            c.add("-o");
            c.add(compiled.getAbsolutePath());
            log.line("$ aapt2 compile");
            run(c, null, log);
        }
        List<String> l = new ArrayList<String>();
        l.add("link");
        l.add("-o");
        l.add(job.outApk.getAbsolutePath());
        l.add("--manifest");
        l.add(job.manifest.getAbsolutePath());
        l.add("-I");
        l.add(job.androidJar.getAbsolutePath());
        l.add("--min-sdk-version");
        l.add(String.valueOf(job.minSdk));
        l.add("--target-sdk-version");
        l.add(String.valueOf(job.targetSdk));
        l.add("--version-code");
        l.add(String.valueOf(job.versionCode));
        l.add("--version-name");
        l.add(job.versionName);
        l.add("--java");
        l.add(job.genDir.getAbsolutePath());
        l.add("--auto-add-overlay");
        if (job.applicationId != null) {
            l.add("--rename-manifest-package");
            l.add(job.applicationId);
            l.add("--rename-instrumentation-target-package");
            l.add(job.applicationId);
        }
        if (job.assetsDir != null) {
            l.add("-A");
            l.add(job.assetsDir.getAbsolutePath());
        }
        if (compiled != null) {
            l.add(compiled.getAbsolutePath());
        }
        log.line("$ aapt2 link");
        run(l, null, log);
    }

    private void run(List<String> args, StringBuilder capture, BuildLog log) throws ToolException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(aapt2.getAbsolutePath());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new ToolException("Cannot start aapt2: " + e.getMessage()
                    + ". The binary must be inside the app's native library folder and built for this phone's CPU (arm64).", e);
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (capture != null) {
                    capture.append(line).append('\n');
                }
                if (log != null) {
                    log.line(line);
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                throw new ToolException("aapt2 " + args.get(0) + " failed (exit code " + exit + ").");
            }
        } catch (IOException e) {
            throw new ToolException("Reading aapt2 output failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            p.destroy();
            Thread.currentThread().interrupt();
            throw new ToolException("Interrupted while running aapt2.", e);
        }
    }
}
