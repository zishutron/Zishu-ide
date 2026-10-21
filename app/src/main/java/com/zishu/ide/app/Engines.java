package com.zishu.ide.app;

import android.content.Context;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.CloudBuildEngine;
import com.zishu.ide.core.build.LocalBuildEngine;
import com.zishu.ide.core.build.Toolchain;
import com.zishu.ide.core.build.UrlTransport;
import com.zishu.ide.core.util.IoUtil;

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
}
