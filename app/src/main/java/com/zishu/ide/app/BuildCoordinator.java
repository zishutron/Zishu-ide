package com.zishu.ide.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildHistory;
import com.zishu.ide.core.build.BuildListener;
import com.zishu.ide.core.build.BuildRequest;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.BuildStage;
import com.zishu.ide.core.build.CancelToken;
import com.zishu.ide.core.project.ProjectInfo;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one build at a time on a background thread and keeps its state, so the build screen can be
 * left and re-opened (or rotated) without losing the log or the result. The build only survives
 * while Android keeps this app's process alive; it is not a foreground service.
 */
public final class BuildCoordinator {

    public interface Observer {
        void onBuildChanged();
    }

    public static final class StageMark {
        public final BuildStage stage;
        public final String detail;
        public final long at;

        StageMark(BuildStage stage, String detail, long at) {
            this.stage = stage;
            this.detail = detail;
            this.at = at;
        }
    }

    public static final class Session {
        public final String id;
        public final ProjectInfo project;
        public final String engineName;
        public final long startedAt = System.currentTimeMillis();
        public final StringBuilder log = new StringBuilder();
        public final List<StageMark> stages = new ArrayList<StageMark>();
        public volatile BuildStage stage = BuildStage.VALIDATE;
        public volatile int progress = -1;
        public volatile boolean running = true;
        public volatile BuildResult result;
        final CancelToken cancel = new CancelToken();

        Session(String id, ProjectInfo project, String engineName) {
            this.id = id;
            this.project = project;
            this.engineName = engineName;
        }
    }

    private static final int MAX_LIVE_LOG = 400000;
    private static BuildCoordinator instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Observer> observers = new ArrayList<Observer>();
    private Session session;

    public static synchronized BuildCoordinator get() {
        if (instance == null) {
            instance = new BuildCoordinator();
        }
        return instance;
    }

    public synchronized Session session() {
        return session;
    }

    public synchronized boolean isRunning() {
        return session != null && session.running;
    }

    public synchronized void addObserver(Observer o) {
        if (!observers.contains(o)) {
            observers.add(o);
        }
    }

    public synchronized void removeObserver(Observer o) {
        observers.remove(o);
    }

    private void notifyObservers() {
        main.post(new Runnable() {
            @Override
            public void run() {
                List<Observer> copy;
                synchronized (BuildCoordinator.this) {
                    copy = new ArrayList<Observer>(observers);
                }
                for (Observer o : copy) {
                    o.onBuildChanged();
                }
            }
        });
    }

    private static String newId() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(Long.toString(System.currentTimeMillis(), 36)).append('-');
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    /** @return false if a build is already running */
    public synchronized boolean start(Context ctx, final ProjectInfo project, final BuildEngine engine) {
        if (session != null && session.running) {
            return false;
        }
        final Session s = new Session(newId(), project, engine.displayName());
        session = s;
        final BuildHistory history = new BuildHistory(project.dir);
        final BuildRequest request = new BuildRequest(project, "debug", history.apkDir(s.id));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BuildListener l = new BuildListener() {
                    @Override
                    public void onStage(BuildStage stage, String detail) {
                        synchronized (BuildCoordinator.this) {
                            s.stage = stage;
                            s.stages.add(new StageMark(stage, detail, System.currentTimeMillis()));
                        }
                        notifyObservers();
                    }

                    @Override
                    public void onLog(String line) {
                        synchronized (BuildCoordinator.this) {
                            if (s.log.length() < MAX_LIVE_LOG) {
                                s.log.append(line).append('\n');
                            }
                        }
                        notifyObservers();
                    }

                    @Override
                    public void onProgress(int percent) {
                        s.progress = percent;
                        notifyObservers();
                    }
                };
                BuildResult r;
                try {
                    r = engine.build(request, l, s.cancel);
                } catch (RuntimeException e) {
                    r = BuildResult.failure("Unexpected error: " + e, System.currentTimeMillis() - s.startedAt,
                            engine.id(), "debug", s.log.toString() + "\n" + android.util.Log.getStackTraceString(e), null, null);
                }
                try {
                    history.record(s.id, r);
                } catch (java.io.IOException ignored) {
                    // history is a convenience; the result is still shown
                }
                synchronized (BuildCoordinator.this) {
                    s.result = r;
                    s.running = false;
                    s.stage = BuildStage.DONE;
                }
                notifyObservers();
            }
        }, "zishu-build");
        t.start();
        return true;
    }

    public synchronized void cancel() {
        if (session != null && session.running) {
            session.cancel.cancel();
        }
    }
}
