package com.zishu.ide.core.build;

/**
 * A way of turning a project into an APK. Implementations must do the real work or report
 * themselves unavailable; they must never return success without an APK they produced.
 */
public interface BuildEngine {

    /** Whether the engine can run right now, and if not, why and what to do about it. */
    final class Status {
        public final boolean available;
        public final String reason;
        public final String action;

        public Status(boolean available, String reason, String action) {
            this.available = available;
            this.reason = reason;
            this.action = action;
        }

        public static Status ok(String note) {
            return new Status(true, note, null);
        }

        public static Status unavailable(String reason, String action) {
            return new Status(false, reason, action);
        }
    }

    String id();

    String displayName();

    Status status();

    /** Runs a build to completion on the calling thread. Never returns null. */
    BuildResult build(BuildRequest request, BuildListener listener, CancelToken cancel);
}
