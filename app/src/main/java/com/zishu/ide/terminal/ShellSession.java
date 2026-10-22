package com.zishu.ide.terminal;

import com.zishu.ide.core.terminal.CommandLine;
import com.zishu.ide.core.terminal.Utf8Chunker;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One persistent Android shell (/system/bin/sh) whose output (stdout and stderr together, in order) is delivered
 * to a listener. There is no pseudo-terminal, so interactive programs (vim, top, anything that reads the keyboard)
 * do not work; ordinary commands do. Stopping the session ends the shell and every command it is running.
 */
public final class ShellSession {

    public interface Listener {
        /** Called on a background thread. */
        void onText(String text);

        /** Called on a background thread when the shell ended. */
        void onExit(int code);
    }

    private final Listener listener;
    private final File cwdFile;
    private Process process;
    private OutputStream stdin;
    private volatile boolean running;

    public ShellSession(Listener listener, File cwdFile) {
        this.listener = listener;
        this.cwdFile = cwdFile;
    }

    public boolean isRunning() {
        return running;
    }

    /** @return null when started, otherwise the reason it could not start */
    public synchronized String start(File startDir, File home, File tmp) {
        if (running) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh");
            if (startDir != null && startDir.isDirectory()) {
                pb.directory(startDir);
            }
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", home.getAbsolutePath());
            pb.environment().put("TMPDIR", tmp.getAbsolutePath());
            pb.environment().put("TERM", "dumb");
            pb.environment().put("PS1", "");
            pb.environment().put("PS2", "");
            pb.environment().put("ENV", "");
            final Process p = pb.start();
            process = p;
            stdin = p.getOutputStream();
            running = true;
            final InputStream in = p.getInputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[4096];
                    Utf8Chunker chunker = new Utf8Chunker();
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            String s = chunker.decode(buf, 0, n);
                            if (s.length() > 0) {
                                listener.onText(s);
                            }
                        }
                    } catch (IOException ignored) {
                        // the stream closes when the shell ends
                    }
                    int code = -1;
                    try {
                        code = p.waitFor();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    running = false;
                    listener.onExit(code);
                }
            }, "zishu-shell-reader");
            reader.setDaemon(true);
            reader.start();
            return null;
        } catch (IOException e) {
            running = false;
            return "The Android shell could not be started: " + e.getMessage();
        }
    }

    /**
     * Sends one command line. After it, the shell records its working directory in a file so the prompt can
     * show where the next command will run.
     */
    public synchronized boolean write(String line) {
        if (!running || stdin == null) {
            return false;
        }
        try {
            String text = line + "\npwd > " + CommandLine.shellQuote(cwdFile.getAbsolutePath()) + "\n";
            stdin.write(text.getBytes("UTF-8"));
            stdin.flush();
            return true;
        } catch (IOException e) {
            running = false;
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
            // already closed
        }
        if (process != null) {
            process.destroy();
        }
        process = null;
        stdin = null;
    }
}
