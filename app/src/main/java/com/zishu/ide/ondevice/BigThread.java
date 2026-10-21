package com.zishu.ide.ondevice;

import java.util.concurrent.Callable;

/** Runs a task on a thread with a large stack (compilers recurse deeply) and returns its result or rethrows. */
final class BigThread {
    private BigThread() {
    }

    static <T> T run(final Callable<T> task) throws Exception {
        final Object[] box = new Object[2];
        Thread t = new Thread(null, new Runnable() {
            @Override
            public void run() {
                try {
                    box[0] = task.call();
                } catch (Throwable e) {
                    box[1] = e;
                }
            }
        }, "zishu-build-tool", 64L * 1024 * 1024);
        t.start();
        t.join();
        if (box[1] instanceof Exception) {
            throw (Exception) box[1];
        }
        if (box[1] instanceof Error) {
            throw (Error) box[1];
        }
        @SuppressWarnings("unchecked")
        T result = (T) box[0];
        return result;
    }
}
