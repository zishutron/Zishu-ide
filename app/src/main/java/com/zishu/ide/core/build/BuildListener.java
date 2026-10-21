package com.zishu.ide.core.build;

/** Receives build progress. Methods may be called from the build thread; UIs must post to their own thread. */
public interface BuildListener {
    void onStage(BuildStage stage, String detail);

    void onLog(String line);

    /** Percent 0-100 when the engine can measure progress, otherwise not called. */
    void onProgress(int percent);

    /** A listener that ignores everything. */
    BuildListener NONE = new BuildListener() {
        @Override
        public void onStage(BuildStage stage, String detail) {
        }

        @Override
        public void onLog(String line) {
        }

        @Override
        public void onProgress(int percent) {
        }
    };
}
