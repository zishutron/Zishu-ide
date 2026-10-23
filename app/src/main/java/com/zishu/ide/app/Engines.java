package com.zishu.ide.app;

import android.content.Context;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.CloudBuildEngine;
import com.zishu.ide.core.build.LocalBuildEngine;
import com.zishu.ide.core.build.Toolchain;
import com.zishu.ide.core.build.UrlTransport;
import com.zishu.ide.core.ondevice.OnDeviceBuildEngine;
import com.zishu.ide.ondevice.AndroidTools;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** Creates the build engines from the user's settings. */
public final class Engines {
    private Engines() {
    }

    public static String workflowYaml(Context c) {
        InputStream in = null;
        try {
            in = c.getAssets().open("cloud/zishu-build.yml");
            return new String(IoUtil.readAll(in, 256 * 1024), IoUtil.UTF8);
        } catch (IOException e) {
            return null;
        } finally {
            IoUtil.closeQuietly(in);
        }
    }

    public static CloudBuildEngine cloud(Context c) {
        Prefs p = App.prefs(c);
        CloudBuildEngine.Config cfg = new CloudBuildEngine.Config();
        cfg.token = TokenStore.load(c);
        cfg.ownerRepo = p.cloudRepo();
        cfg.workflowYaml = workflowYaml(c);
        cfg.installWorkflow = p.cloudInstallWorkflow();
        cfg.allowPublicRepo = p.cloudAllowPublic();
        return new CloudBuildEngine(new UrlTransport(), cfg);
    }

    public static BuildEngine local() {
        return new LocalBuildEngine(Toolchain.fromEnvironment(), 30L * 60 * 1000);
    }

    public static OnDeviceBuildEngine onDevice(Context c) {
        java.util.List<com.zishu.ide.core.maven.MavenRepo> repos = new java.util.ArrayList<com.zishu.ide.core.maven.MavenRepo>();
        repos.add(new com.zishu.ide.core.maven.HttpMavenRepo("google()", com.zishu.ide.core.maven.HttpMavenRepo.GOOGLE));
        repos.add(new com.zishu.ide.core.maven.HttpMavenRepo("mavenCentral()", com.zishu.ide.core.maven.HttpMavenRepo.CENTRAL));
        File libs = new File(c.getFilesDir(), "libraries");
        com.zishu.ide.core.maven.MavenDependencies deps = new com.zishu.ide.core.maven.MavenDependencies(
                new com.zishu.ide.core.maven.MavenCache(new File(libs, "maven"), repos), new File(libs, "unpacked"));
        return new OnDeviceBuildEngine(new AndroidTools(c), new File(c.getCacheDir(), "ondevice"), deps);
    }

    /** The engine the user's choice and the phone's abilities select: "device", "cloud" or automatic. */
    public static BuildEngine preferred(Context c) {
        String choice = App.prefs(c).engineChoice();
        if ("cloud".equals(choice)) {
            return cloud(c);
        }
        OnDeviceBuildEngine d = onDevice(c);
        if (d.status().available || "device".equals(choice)) {
            return d;
        }
        return cloud(c);
    }
}
