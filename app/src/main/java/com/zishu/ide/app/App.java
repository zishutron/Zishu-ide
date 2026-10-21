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
        installCrashRecorder(this);
    }

    private static final String CRASH_FILE = "last-crash.txt";

    /** Saves the stack trace of a crash so the next start can show what really went wrong. */
    private static void installCrashRecorder(final Context c) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    com.zishu.ide.core.util.IoUtil.writeUtf8Atomic(new File(c.getFilesDir(), CRASH_FILE),
                            "Thread: " + t.getName() + "\n" + sw);
                } catch (Throwable ignored) {
                    // nothing more can be done while crashing
                }
                if (previous != null) {
                    previous.uncaughtException(t, e);
                }
            }
        });
    }

    /** @return the saved crash report (and removes it), or null */
    public static String takeCrashReport(Context c) {
        File f = new File(c.getFilesDir(), CRASH_FILE);
        if (!f.isFile()) {
            return null;
        }
        try {
            return com.zishu.ide.core.util.IoUtil.readUtf8(f, 200 * 1024);
        } catch (IOException e) {
            return null;
        } finally {
            f.delete();
        }
    }

    public static synchronized Prefs prefs(Context c) {
        if (prefs == null) {
            prefs = new Prefs(c.getApplicationContext());
        }
        return prefs;
    }

    /** The workspace lives in app-private storage: no storage permission is needed and other apps cannot read it. */
    public static File privateWorkspaceDir(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "workspace");
    }

    /**
     * Where projects live. By default app-private storage; if the user chose the visible phone folder
     * and Android has granted access, /storage/emulated/0/ZishuIDE. If access is later revoked this falls
     * back to the private folder instead of failing.
     */
    public static File workspaceDir(Context c) {
        if (prefs(c).sharedStorage() && StorageAccess.granted(c)) {
            return StorageAccess.sharedDir();
        }
        return privateWorkspaceDir(c);
    }

    public static synchronized ProjectManager projects(Context c) throws IOException {
        File w = workspaceDir(c);
        if (projects == null || !projects.getRoot().equals(w)) {
            projects = new ProjectManager(w);
        }
        return projects;
    }
}
