package com.zishu.ide.ondevice;

import android.content.Context;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildListener;
import com.zishu.ide.core.build.BuildRequest;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.BuildStage;
import com.zishu.ide.core.build.CancelToken;
import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.ondevice.OnDeviceBuildEngine;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.template.ProjectTemplates;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Builds a freshly generated sample project with the real on-device pipeline and reports what happened.
 * The report only contains facts taken from the run: tool output, stage timings and the resulting APK.
 */
public final class SelfTest {
    private SelfTest() {
    }

    /** Runs on the calling thread (may take a minute). Never throws. */
    public static String run(Context ctx) {
        StringBuilder r = new StringBuilder();
        AndroidTools tools = new AndroidTools(ctx);
        BuildEngine.Status st = tools.status();
        r.append("Toolchain status: ").append(st.available ? "ready" : "NOT READY").append('\n').append(st.reason).append('\n');
        if (st.action != null) {
            r.append(st.action).append('\n');
        }
        if (!st.available) {
            return r.toString();
        }
        r.append("Platform jar: API ").append(tools.androidJarApi()).append('\n');
        try {
            r.append("aapt2: ").append(tools.aapt2Version()).append('\n');
        } catch (Exception e) {
            r.append("aapt2 FAILED to run: ").append(e.getMessage()).append('\n');
            return r.toString();
        }
        File root = new File(ctx.getCacheDir(), "selftest");
        try {
            if (root.exists()) {
                IoUtil.deleteRecursively(root);
            }
            File project = new File(root, "SelfTestApp");
            ProjectConfig cfg = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
            cfg.projectName = "SelfTestApp";
            cfg.appName = "Self Test";
            cfg.applicationId = "com.zishu.selftest";
            Map<String, byte[]> files = ProjectTemplates.generate(cfg);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                File f = new File(project, e.getKey());
                f.getParentFile().mkdirs();
                IoUtil.writeAtomic(f, e.getValue());
            }
            ProjectInfo info = new ProjectInfo(project, "SelfTestApp", ProjectType.NATIVE_JAVA, cfg.language, "Self Test",
                    cfg.applicationId, "", 0, 0);
            final StringBuilder timeline = new StringBuilder();
            final long t0 = System.currentTimeMillis();
            BuildListener l = new BuildListener() {
                @Override
                public void onStage(BuildStage stage, String detail) {
                    if (detail != null) {
                        timeline.append(String.format("%5.1fs  %s\n", (System.currentTimeMillis() - t0) / 1000.0, detail));
                    }
                }

                @Override
                public void onLog(String line) {
                }

                @Override
                public void onProgress(int percent) {
                }
            };
            OnDeviceBuildEngine engine = new OnDeviceBuildEngine(tools, new File(root, "work"));
            BuildResult res = engine.build(new BuildRequest(info, "debug", new File(root, "out")), l, new CancelToken());
            r.append("\nStages:\n").append(timeline);
            if (res.success) {
                r.append("\nRESULT: SUCCESS\nAPK: ").append(res.apk.getName()).append(", ")
                        .append(IoUtil.formatSize(res.apkInfo.sizeBytes)).append(", ").append(res.apkInfo.dexCount).append(" dex, signed=")
                        .append(res.apkInfo.isSigned()).append("\nThis APK was not installed by the self-test.\n");
            } else {
                r.append("\nRESULT: FAILED - ").append(res.errorTitle).append("\n\n--- log ---\n").append(res.log).append('\n');
            }
        } catch (com.zishu.ide.core.validation.ValidationException e) {
            r.append("Self-test could not generate the sample project: ").append(e.getMessage()).append('\n');
        } catch (IOException e) {
            r.append("Self-test could not prepare the sample project: ").append(e.getMessage()).append('\n');
        } catch (RuntimeException e) {
            r.append("Self-test crashed: ").append(e).append('\n');
        } catch (OutOfMemoryError e) {
            r.append("Self-test ran out of memory.\n");
        }
        return r.toString();
    }
}
