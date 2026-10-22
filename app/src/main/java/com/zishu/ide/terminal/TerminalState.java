package com.zishu.ide.terminal;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import com.zishu.ide.app.BuildCoordinator;
import com.zishu.ide.app.Engines;
import com.zishu.ide.app.Palette;
import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildLogAnalyzer;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.BuildStage;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.terminal.AnsiStream;
import com.zishu.ide.core.terminal.CommandLine;
import com.zishu.ide.core.terminal.IdeCommands;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything the terminal remembers while the app runs: the text on screen, the shell, the selected project, the
 * command history and the build it is watching. It outlives the screen, so leaving the terminal and coming back
 * (or rotating) keeps the output and a running build keeps reporting into it.
 */
public final class TerminalState implements BuildCoordinator.Observer, ShellSession.Listener {
    public interface View {
        /** Something changed; redraw (called on the main thread). */
        void onTerminalChanged();
    }

    private static final int MAX_CHARS = 80000;
    private static final int TRIM_TO = 56000;
    private static final int MAX_HISTORY = 200;
    private static final Pattern SECRET = Pattern.compile("(?i)(ghp_|gho_|ghu_|ghs_|github_pat_|password|passwd|token=)");

    private static TerminalState instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private final AnsiStream ansi = new AnsiStream();
    private final List<String> history = new ArrayList<String>();
    private final File homeDir;
    private final File tmpDir;
    private final File cwdFile;
    private final File historyFile;

    private ShellSession shell;
    private boolean stoppingOnPurpose;
    private Palette pal;
    private View view;
    private ProjectInfo project;
    private String cwd;
    private boolean welcomed;

    // build being reported into the terminal
    private String watchedBuild;
    private int watchedLogOffset;
    private int watchedStages;
    private boolean watchedFinished;

    public static synchronized TerminalState get(Context c) {
        if (instance == null) {
            instance = new TerminalState(c.getApplicationContext());
        }
        return instance;
    }

    private TerminalState(Context app) {
        this.app = app;
        File base = new File(app.getFilesDir(), "terminal");
        homeDir = new File(base, "home");
        tmpDir = new File(app.getCacheDir(), "terminal");
        homeDir.mkdirs();
        tmpDir.mkdirs();
        cwdFile = new File(tmpDir, "cwd");
        historyFile = new File(base, "history.txt");
        loadHistory();
        BuildCoordinator.get().addObserver(this);
    }

    // ------------------------------------------------------------------ attach / state

    public void attach(View v, Palette palette) {
        view = v;
        pal = palette;
    }

    public void detach(View v) {
        if (view == v) {
            view = null;
        }
    }

    public CharSequence text() {
        return buffer;
    }

    public ProjectInfo project() {
        return project;
    }

    public List<String> history() {
        return history;
    }

    public void welcomeOnce() {
        if (welcomed) {
            return;
        }
        welcomed = true;
        println(IdeCommands.Level.HEAD, IdeCommands.VERSION);
        println(IdeCommands.Level.DIM, "Control Zishu IDE from here. Type  zishu help  to see the commands.");
        println(IdeCommands.Level.DIM, "Other commands run in the Android shell. Interactive programs (vim, top) do not work.");
    }

    // ------------------------------------------------------------------ project / prompt

    /** Selects the project the IDE commands act on and moves the shell into its folder. */
    public void setProject(ProjectInfo p) {
        project = p;
        if (p != null) {
            cwd = p.dir.getAbsolutePath();
            if (shell != null && shell.isRunning()) {
                shell.write("cd " + CommandLine.shellQuote(p.dir.getAbsolutePath()));
            }
        }
        changed();
    }

    public String prompt() {
        readCwd();
        String where;
        if (cwd == null) {
            where = "~";
        } else if (project != null && cwd.equals(project.dir.getAbsolutePath())) {
            where = project.name;
        } else if (project != null && cwd.startsWith(project.dir.getAbsolutePath() + "/")) {
            where = project.name + cwd.substring(project.dir.getAbsolutePath().length());
        } else {
            where = cwd;
        }
        return where + " $ ";
    }

    private void readCwd() {
        if (cwdFile.isFile()) {
            try {
                String s = IoUtil.readUtf8(cwdFile, 4096).trim();
                if (s.length() > 0) {
                    cwd = s;
                }
            } catch (IOException ignored) {
                // keep the last known directory
            }
        }
    }

    public File currentDir() {
        readCwd();
        if (cwd != null) {
            File d = new File(cwd);
            if (d.isDirectory()) {
                return d;
            }
        }
        return project != null ? project.dir : homeDir;
    }

    // ------------------------------------------------------------------ input

    /** Runs one typed line: an IDE command or a shell command. */
    public void submit(String line, IdeCommands ide) {
        if (line.trim().length() == 0) {
            return;
        }
        echoPrompt(line);
        remember(line);
        List<String> words = CommandLine.tokenize(line);
        if (words != null && !words.isEmpty() && IdeCommands.handles(words.get(0))) {
            ide.run(words, new IdeCommands.Out() {
                @Override
                public void line(IdeCommands.Level level, String text) {
                    println(level, text);
                }
            });
            return;
        }
        if (shell == null || !shell.isRunning()) {
            startShell();
        }
        if (shell == null || !shell.write(line)) {
            println(IdeCommands.Level.ERROR, "The shell is not running. Press Enter again to restart it.");
            shell = null;
        }
    }

    private void echoPrompt(String line) {
        ensureLineStart();
        int start = buffer.length();
        String p = prompt();
        buffer.append(p);
        if (pal != null) {
            buffer.setSpan(new ForegroundColorSpan(pal.primary), start, buffer.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int s2 = buffer.length();
        buffer.append(line).append('\n');
        buffer.setSpan(new StyleSpan(Typeface.BOLD), s2, buffer.length() - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        changed();
    }

    private void startShell() {
        stoppingOnPurpose = false;
        shell = new ShellSession(this, cwdFile);
        File dir = project != null ? project.dir : homeDir;
        String err = shell.start(dir, homeDir, tmpDir);
        if (err != null) {
            println(IdeCommands.Level.ERROR, err);
            shell = null;
        } else if (project != null) {
            cwd = project.dir.getAbsolutePath();
        }
    }

    /** The ^C button: cancels a running build, otherwise ends the shell (and what it runs). It restarts on the next command. */
    public void interrupt() {
        if (BuildCoordinator.get().isRunning()) {
            BuildCoordinator.get().cancel();
            println(IdeCommands.Level.WARN, "^C  build cancel requested");
            return;
        }
        if (shell != null && shell.isRunning()) {
            stoppingOnPurpose = true;
            shell.stop();
            shell = null;
            println(IdeCommands.Level.WARN, "^C  shell stopped. A new one starts with your next command.");
        } else {
            println(IdeCommands.Level.DIM, "^C");
        }
    }

    public void clear() {
        buffer.clear();
        buffer.clearSpans();
        changed();
    }

    // ------------------------------------------------------------------ shell output

    @Override
    public void onText(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                appendShell(text);
            }
        });
    }

    @Override
    public void onExit(final int code) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!stoppingOnPurpose) {
                    println(IdeCommands.Level.WARN, "Shell ended (exit code " + code + "). A new one starts with your next command.");
                    shell = null;
                }
            }
        });
    }

    private void appendShell(String text) {
        for (AnsiStream.Span s : ansi.feed(text)) {
            switch (s.kind) {
                case TEXT:
                    int start = buffer.length();
                    buffer.append(s.text);
                    style(start, buffer.length(), colorFor(s.fg), s.bold, s.underline);
                    break;
                case CARRIAGE_RETURN:
                    int nl = lastIndexOf(buffer, '\n');
                    buffer.delete(nl + 1, buffer.length());
                    break;
                case BACKSPACE:
                    if (buffer.length() > 0 && buffer.charAt(buffer.length() - 1) != '\n') {
                        buffer.delete(buffer.length() - 1, buffer.length());
                    }
                    break;
                default:
                    buffer.clear();
                    buffer.clearSpans();
            }
        }
        changed();
    }

    private static int lastIndexOf(CharSequence s, char c) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ IDE output

    public void println(IdeCommands.Level level, String text) {
        ensureLineStart();
        int start = buffer.length();
        buffer.append(text).append('\n');
        int color = pal == null ? 0 : colorFor(level);
        style(start, buffer.length() - 1, color, level == IdeCommands.Level.HEAD, false);
        changed();
    }

    private void ensureLineStart() {
        if (buffer.length() > 0 && buffer.charAt(buffer.length() - 1) != '\n') {
            buffer.append('\n');
        }
    }

    private void style(int start, int end, int color, boolean bold, boolean underline) {
        if (end <= start) {
            return;
        }
        if (color != 0) {
            buffer.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (bold) {
            buffer.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (underline) {
            buffer.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private int colorFor(IdeCommands.Level level) {
        switch (level) {
            case HEAD:
                return pal.primary;
            case DIM:
                return pal.textSecondary;
            case OK:
                return pal.success;
            case WARN:
                return pal.warning;
            case ERROR:
                return pal.error;
            default:
                return pal.text;
        }
    }

    /** Maps a terminal colour to the app theme so output looks right in both light and dark mode. 0 means "default". */
    private int colorFor(int fg) {
        if (pal == null || fg == AnsiStream.DEFAULT) {
            return pal == null ? 0 : pal.text;
        }
        if (fg >= 0 && fg <= 15) {
            switch (fg % 8) {
                case 0:
                    return fg == 0 ? pal.textSecondary : pal.comment;
                case 1:
                    return pal.error;
                case 2:
                    return pal.success;
                case 3:
                    return pal.warning;
                case 4:
                    return pal.tag;
                case 5:
                    return pal.keyword;
                case 6:
                    return pal.type;
                default:
                    return pal.text;
            }
        }
        return fg; // 256-colour and true-colour values are used as given
    }

    private void changed() {
        if (buffer.length() > MAX_CHARS) {
            int cut = buffer.length() - TRIM_TO;
            int nl = buffer.toString().indexOf('\n', cut);
            buffer.delete(0, nl < 0 ? cut : nl + 1);
        }
        if (view != null) {
            view.onTerminalChanged();
        }
    }

    // ------------------------------------------------------------------ history

    private void remember(String line) {
        if (line.startsWith(" ") || SECRET.matcher(line).find()) {
            return; // not stored: may contain a secret
        }
        if (!history.isEmpty() && history.get(history.size() - 1).equals(line)) {
            return;
        }
        history.add(line);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        try {
            historyFile.getParentFile().mkdirs();
            IoUtil.writeUtf8Atomic(historyFile, IoUtil.join("\n", history));
        } catch (IOException ignored) {
            // history is a convenience
        }
    }

    private void loadHistory() {
        if (!historyFile.isFile()) {
            return;
        }
        try {
            for (String l : IoUtil.readUtf8(historyFile, 512 * 1024).split("\n")) {
                if (l.length() > 0) {
                    history.add(l);
                }
            }
        } catch (IOException ignored) {
            // start with empty history
        }
    }

    // ------------------------------------------------------------------ builds

    /** @return an error text, or null when the build started and is being reported here */
    public String startBuild(String engineChoice) {
        if (project == null) {
            return "No project is selected.";
        }
        BuildEngine e;
        if ("device".equals(engineChoice)) {
            e = Engines.onDevice(app);
        } else if ("cloud".equals(engineChoice)) {
            e = Engines.cloud(app);
        } else {
            e = Engines.preferred(app);
        }
        BuildEngine.Status st = e.status();
        if (!st.available) {
            return e.displayName() + " is not ready: " + st.reason + (st.action == null ? "" : "  " + st.action);
        }
        if (!BuildCoordinator.get().start(app, project, e)) {
            return "A build is already running. Use  zishu cancel  to stop it.";
        }
        BuildCoordinator.Session s = BuildCoordinator.get().session();
        watchedBuild = s == null ? null : s.id;
        watchedLogOffset = 0;
        watchedStages = 0;
        watchedFinished = false;
        println(IdeCommands.Level.HEAD, "Building " + project.name + " with " + e.displayName());
        return null;
    }

    @Override
    public void onBuildChanged() {
        BuildCoordinator c = BuildCoordinator.get();
        BuildCoordinator.Session s = c.session();
        if (s == null || watchedBuild == null || !watchedBuild.equals(s.id) || watchedFinished) {
            return;
        }
        String newLog;
        List<String> stageLines = new ArrayList<String>();
        BuildResult result;
        boolean running;
        synchronized (c) {
            String all = s.log.toString();
            newLog = watchedLogOffset < all.length() ? all.substring(watchedLogOffset) : "";
            watchedLogOffset = all.length();
            while (watchedStages < s.stages.size()) {
                BuildCoordinator.StageMark m = s.stages.get(watchedStages++);
                if (m.stage != BuildStage.DONE && m.detail != null) {
                    stageLines.add(m.detail);
                }
            }
            result = s.result;
            running = s.running;
        }
        for (String l : stageLines) {
            println(IdeCommands.Level.DIM, "> " + l);
        }
        if (newLog.length() > 0) {
            for (String l : newLog.split("\n", -1)) {
                if (l.length() > 0) {
                    println(IdeCommands.Level.PLAIN, l);
                }
            }
        }
        if (!running && result != null) {
            watchedFinished = true;
            reportResult(result);
        }
    }

    private void reportResult(BuildResult r) {
        if (r.success && r.apk != null) {
            println(IdeCommands.Level.OK, "BUILD SUCCESSFUL  " + IoUtil.formatSize(r.apkInfo.sizeBytes) + " in "
                    + (r.durationMs / 1000) + "s");
            println(IdeCommands.Level.OK, r.apk.getPath());
            println(IdeCommands.Level.DIM, "Run  zishu install  to install it.");
        } else if (r.cancelled) {
            println(IdeCommands.Level.WARN, "Build cancelled.");
        } else {
            println(IdeCommands.Level.ERROR, "BUILD FAILED  " + r.errorTitle);
            String summary = r.findings == null || r.findings.isEmpty() ? "" : BuildLogAnalyzer.summarize(r.findings);
            for (String l : summary.split("\n")) {
                if (l.length() > 0) {
                    println(IdeCommands.Level.ERROR, l);
                }
            }
            println(IdeCommands.Level.DIM, "Run  zishu log  for the end of the full log.");
        }
    }

    /** The APK of the last successful build of the selected project, or null. */
    public File lastApk() {
        BuildCoordinator.Session s = BuildCoordinator.get().session();
        if (s != null && s.result != null && s.result.success && s.result.apk != null && s.result.apk.isFile()
                && project != null && s.project.dir.equals(project.dir)) {
            return s.result.apk;
        }
        if (project != null) {
            com.zishu.ide.core.build.BuildHistory h = new com.zishu.ide.core.build.BuildHistory(project.dir);
            for (com.zishu.ide.core.build.BuildHistory.Entry e : h.list()) {
                if (e.success) {
                    File f = h.apkFile(e);
                    if (f != null && f.isFile()) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    public String lastLog() {
        BuildCoordinator c = BuildCoordinator.get();
        BuildCoordinator.Session s = c.session();
        if (s != null && (project == null || s.project.dir.equals(project.dir))) {
            synchronized (c) {
                return s.log.toString();
            }
        }
        if (project != null) {
            com.zishu.ide.core.build.BuildHistory h = new com.zishu.ide.core.build.BuildHistory(project.dir);
            List<com.zishu.ide.core.build.BuildHistory.Entry> l = h.list();
            if (!l.isEmpty()) {
                return h.readLog(l.get(0).id);
            }
        }
        return null;
    }
}
