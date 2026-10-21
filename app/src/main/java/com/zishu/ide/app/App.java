package com.zishu.ide.app;

import android.app.Application;
import android.content.Context;

import com.zishu.ide.core.project.ProjectManager;

import java.io.File;
import java.io.IOException;

/** Process-wide singletons: preferences and the project workspace. */
public final class App extends Application {
    private static Prefs prefs;
    private static ProjectManager projects;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
    }

    public static synchronized Prefs prefs(Context c) {
        if (prefs == null) {
            prefs = new Prefs(c.getApplicationContext());
        }
        return prefs;
    }

    /** The workspace lives in app-private storage: no storage permission is needed and other apps cannot read it. */
    public static File workspaceDir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "workspace");
    }

    public static synchronized ProjectManager projects(Context c) throws IOException {
        if (projects == null) {
            projects = new ProjectManager(workspaceDir(c));
        }
        return projects;
    }
}
