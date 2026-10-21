package com.zishu.ide.core.build;

public final class CancelToken {
    /** Thrown inside an engine when the user cancelled. */
    public static final class CancelledException extends Exception {
        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("Build cancelled");
        }
    }

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void throwIfCancelled() throws CancelledException {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    /** Sleeps up to millis, waking early (and throwing) if cancelled. */
    public void sleep(long millis) throws CancelledException {
        long end = System.currentTimeMillis() + millis;
        while (true) {
            throwIfCancelled();
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(left, 250));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
                throw new CancelledException();
            }
        }
    }
}
