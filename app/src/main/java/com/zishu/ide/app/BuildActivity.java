package com.zishu.ide.app;

import com.zishu.ide.R;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildHistory;
import com.zishu.ide.core.build.BuildLogAnalyzer;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.BuildStage;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.validation.Issue;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Starts a build, shows its stages and log while it runs, and offers the finished APK for install
 * or saving. Only a verified APK produces a success state; every failure shows its log.
 */
public final class BuildActivity extends BaseActivity implements BuildCoordinator.Observer {
    public static final String EXTRA_PROJECT = "project";
    public static final String EXTRA_AUTOSTART = "autostart";

    private static final int REQUEST_SAVE = 41;
    private static final int LOG_TAIL_CHARS = 24000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProjectInfo project;
    private BuildHistory history;

    private LinearLayout engineBox;
    private LinearLayout actionBox;
    private LinearLayout progressBox;
    private TextView stageText;
    private ProgressBar progressBar;
    private TextView logView;
    private ScrollView logScroll;
    private LinearLayout resultBox;
    private LinearLayout historyBox;

    private File pendingSave;
    private BuildResult shownResult;
    private boolean refreshQueued;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String dirName = getIntent().getStringExtra(EXTRA_PROJECT);
        try {
            project = dirName == null ? null : App.projects(this).find(dirName);
        } catch (IOException e) {
            project = null;
        }
        if (project == null) {
            toast("This project could not be found.");
            finish();
            return;
        }
        history = new BuildHistory(project.dir);
        buildUi();
        BuildCoordinator.get().addObserver(this);
        refresh();
        if (getIntent().getBooleanExtra(EXTRA_AUTOSTART, false) && savedInstanceState == null) {
            getIntent().removeExtra(EXTRA_AUTOSTART);
            startBuild();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        BuildCoordinator.get().removeObserver(this);
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (project != null) {
            refresh();
        }
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.topBar(this, pal, "Build APK", project.name, R.drawable.ic_arrow_back, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        root.addView(Ui.divider(this, pal));

        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(24));

        engineBox = new LinearLayout(this);
        engineBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(engineBox);

        actionBox = new LinearLayout(this);
        actionBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(actionBox, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));

        progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        stageText = Ui.text(this, pal, "", Ui.Style.TITLE);
        progressBox.addView(stageText);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBox.addView(progressBar, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, dp(8), 0, 10, 0, 0));
        page.addView(progressBox, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(resultBox, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));

        page.addView(Ui.section(this, pal, "Build log"));
        logView = Ui.text(this, pal, "", Ui.Style.MONO);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackground(Ui.rounded(this, pal.codeBg, pal.outline, 12));
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        page.addView(logScroll, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        page.addView(Ui.section(this, pal, "History"));
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(historyBox);

        sv.addView(page);
        root.addView(sv, Ui.weight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setScreen(root);
    }

    // ------------------------------------------------------------------ state

    @Override
    public void onBuildChanged() {
        if (refreshQueued) {
            return;
        }
        refreshQueued = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshQueued = false;
                refresh();
            }
        }, 200);
    }

    private BuildCoordinator.Session mySession() {
        BuildCoordinator.Session s = BuildCoordinator.get().session();
        return s != null && s.project.dir.equals(project.dir) ? s : null;
    }

    private void refresh() {
        BuildCoordinator coordinator = BuildCoordinator.get();
        BuildCoordinator.Session s = mySession();
        BuildCoordinator.Session any = coordinator.session();
        boolean runningHere = s != null && s.running;
        boolean runningElsewhere = any != null && any.running && s == null;

        // engine card
        engineBox.removeAllViews();
        BuildEngine engine = Engines.cloud(this);
        BuildEngine.Status st = engine.status();
        if (st.available) {
            engineBox.addView(Ui.banner(this, pal, R.drawable.ic_cloud, pal.primary, "GitHub Actions cloud build",
                    st.reason + " The project's source is uploaded to that repository for the build."));
        } else {
            LinearLayout b = Ui.banner(this, pal, R.drawable.ic_warning, pal.warning, "Cloud build is not set up", st.reason
                    + "\n" + st.action);
            engineBox.addView(b);
            engineBox.addView(Ui.button(this, pal, "Open cloud build settings", R.drawable.ic_settings, Ui.Kind.TONAL,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startActivity(new Intent(BuildActivity.this, CloudSettingsActivity.class));
                        }
                    }), Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        }

        // actions
        actionBox.removeAllViews();
        if (runningHere) {
            actionBox.addView(Ui.button(this, pal, "Cancel build", R.drawable.ic_stop, Ui.Kind.DANGER, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    BuildCoordinator.get().cancel();
                    toast("Cancelling… the remote run is stopped and temporary files are removed.");
                }
            }));
        } else if (runningElsewhere) {
            actionBox.addView(Ui.banner(this, pal, R.drawable.ic_warning, pal.warning, "Another build is running",
                    "\"" + any.project.name + "\" is building. Only one build runs at a time."));
        } else {
            LinearLayout b = Ui.button(this, pal, s == null || s.result == null ? "Build debug APK" : "Build again",
                    R.drawable.ic_play, Ui.Kind.FILLED, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startBuild();
                        }
                    });
            b.setEnabled(st.available);
            b.setAlpha(st.available ? 1f : 0.45f);
            actionBox.addView(b);
        }

        // progress
        if (s == null) {
            progressBox.setVisibility(View.GONE);
        } else {
            progressBox.setVisibility(View.VISIBLE);
            stageText.setText(s.running ? s.stage.label + "…" : (s.result != null && s.result.success ? "Build finished"
                    : s.result != null && s.result.cancelled ? "Build cancelled" : "Build failed"));
            if (s.running && s.progress >= 0) {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(s.progress);
            } else {
                progressBar.setIndeterminate(s.running);
                progressBar.setProgress(s.running ? 0 : 100);
            }
        }

        // log
        String log;
        if (s != null) {
            synchronized (coordinator) {
                log = s.log.toString();
            }
        } else {
            log = "";
        }
        if (log.length() > LOG_TAIL_CHARS) {
            log = "… (earlier lines omitted; the complete log is saved with the build)\n" + log.substring(log.length() - LOG_TAIL_CHARS);
        }
        if (s == null || log.length() == 0) {
            logView.setText(s == null ? "No build has run in this session. Start one above." : "Waiting for output…");
        } else if (!log.equals(logView.getText().toString())) {
            logView.setText(log);
            if (s.running) {
                logScroll.post(new Runnable() {
                    @Override
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        }

        // result
        BuildResult r = s == null ? null : s.result;
        if (r != shownResult) {
            shownResult = r;
            resultBox.removeAllViews();
            if (r != null) {
                showResult(r, s);
            }
            renderHistory();
        } else if (historyBox.getChildCount() == 0) {
            renderHistory();
        }
    }

    private void startBuild() {
        BuildEngine engine = Engines.cloud(this);
        BuildEngine.Status st = engine.status();
        if (!st.available) {
            Ui.message(this, pal, "Cloud build is not set up", st.reason + "\n\n" + st.action);
            return;
        }
        if (!BuildCoordinator.get().start(this, project, engine)) {
            toast("A build is already running.");
        }
        shownResult = null;
        refresh();
    }

    // ------------------------------------------------------------------ result

    private void showResult(final BuildResult r, BuildCoordinator.Session s) {
        LinearLayout card = Ui.card(this, pal);
        if (r.success && r.apk != null) {
            card.addView(Ui.text(this, pal, "APK ready", Ui.Style.TITLE));
            TextView name = Ui.text(this, pal, r.apk.getName(), Ui.Style.MONO);
            card.addView(name, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
            StringBuilder facts = new StringBuilder();
            facts.append("Size ").append(IoUtil.formatSize(r.apkInfo.sizeBytes));
            facts.append("  ·  ").append(r.apkInfo.dexCount).append(" dex");
            facts.append("  ·  built in ").append(DateUtils.formatElapsedTime(r.durationMs / 1000));
            card.addView(Ui.text(this, pal, facts.toString(), Ui.Style.CAPTION),
                    Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
            if (r.signing != null) {
                card.addView(Ui.text(this, pal, "Signed with: " + r.signing, Ui.Style.CAPTION),
                        Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
            }
            card.addView(Ui.text(this, pal, "A debug-signed APK is for testing. It cannot update an app installed with a different key.",
                    Ui.Style.CAPTION), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
            LinearLayout row = new LinearLayout(this);
            row.addView(Ui.button(this, pal, "Install", R.drawable.ic_phone, Ui.Kind.FILLED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    install(r.apk);
                }
            }), Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.button(this, pal, "Save…", R.drawable.ic_download, Ui.Kind.TONAL, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveApk(r.apk);
                }
            }), Ui.lpMargin(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
            ((LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).weight = 1f;
            card.addView(row, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 14, 0, 0));
        } else if (r.cancelled) {
            card.addView(Ui.text(this, pal, "Build cancelled", Ui.Style.TITLE));
            card.addView(Ui.text(this, pal, "No APK was produced.", Ui.Style.CAPTION),
                    Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        } else {
            TextView t = Ui.text(this, pal, "Build failed", Ui.Style.TITLE);
            t.setTextColor(pal.error);
            card.addView(t);
            if (r.errorTitle != null) {
                card.addView(Ui.text(this, pal, r.errorTitle, Ui.Style.BODY),
                        Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
            }
            if (r.validation != null) {
                for (Issue i : r.validation.all()) {
                    card.addView(Ui.text(this, pal, i.format(), Ui.Style.CAPTION),
                            Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
                }
            }
            if (r.findings != null) {
                int shown = 0;
                for (BuildLogAnalyzer.Finding f : r.findings) {
                    if (shown++ >= 6) {
                        break;
                    }
                    String where = f.file == null ? "" : "\n" + f.file + (f.line > 0 ? ":" + f.line : "");
                    String action = f.action == null ? "" : "\n→ " + f.action;
                    card.addView(Ui.text(this, pal, f.message + where + action, Ui.Style.CAPTION),
                            Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
                }
            }
        }
        if (r.runUrl != null) {
            card.addView(Ui.button(this, pal, "Open run on GitHub", R.drawable.ic_cloud, Ui.Kind.TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUrl(r.runUrl);
                }
            }), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        }
        if (!r.success) {
            final String full = r.log == null ? "" : r.log;
            card.addView(Ui.button(this, pal, "Copy full log", R.drawable.ic_copy, Ui.Kind.TEXT, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copy(full);
                }
            }), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        }
        resultBox.addView(card);
    }

    private void install(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                Ui.dialog(this, pal).setTitle("Allow installing apps")
                        .setMessage("Android needs your permission before Zishu IDE can open the installer. "
                                + "Enable \"Allow from this source\", then come back and tap Install again.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            }
                        }).show();
                return;
            }
            Uri uri = ApkProvider.uriFor(this, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (IOException e) {
            Ui.message(this, pal, "Cannot install", e.getMessage());
        } catch (RuntimeException e) {
            Ui.message(this, pal, "Cannot install", "No installer is available on this device: " + e.getMessage());
        }
    }

    private void saveApk(File apk) {
        pendingSave = apk;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_TITLE, apk.getName());
        startActivityForResult(i, REQUEST_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SAVE && resultCode == RESULT_OK && data != null && data.getData() != null && pendingSave != null) {
            try {
                DocumentIo.copyFileToUri(getContentResolver(), pendingSave, data.getData());
                toast("APK saved.");
            } catch (IOException e) {
                Ui.message(this, pal, "Could not save the APK", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ history

    private void renderHistory() {
        historyBox.removeAllViews();
        List<BuildHistory.Entry> entries = history.list();
        if (entries.isEmpty()) {
            historyBox.addView(Ui.text(this, pal, "Finished builds are listed here with their logs.", Ui.Style.CAPTION));
            return;
        }
        for (final BuildHistory.Entry e : entries) {
            LinearLayout row = Ui.card(this, pal);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    historyActions(e);
                }
            });
            String status = e.success ? "Succeeded" : e.cancelled ? "Cancelled" : "Failed";
            TextView t = Ui.text(this, pal, status + "  ·  " + DateUtils.getRelativeTimeSpanString(e.time), Ui.Style.BODY);
            t.setTextColor(e.success ? pal.success : e.cancelled ? pal.textSecondary : pal.error);
            row.addView(t);
            String detail = (e.apkSize > 0 ? IoUtil.formatSize(e.apkSize) + "  ·  " : "")
                    + DateUtils.formatElapsedTime(e.durationMs / 1000) + (e.title != null && !e.success ? "\n" + e.title : "");
            row.addView(Ui.text(this, pal, detail, Ui.Style.CAPTION),
                    Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
            historyBox.addView(row, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    private void historyActions(final BuildHistory.Entry e) {
        final File apk = history.apkFile(e);
        String[] items = apk != null ? new String[]{"View log", "Install", "Save APK…", "Delete"} : new String[]{"View log", "Delete"};
        final boolean hasApk = apk != null;
        Ui.dialog(this, pal).setTitle("Build details").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                int action = hasApk ? which : (which == 0 ? 0 : 3);
                switch (action) {
                    case 0:
                        showLog(e);
                        break;
                    case 1:
                        install(apk);
                        break;
                    case 2:
                        saveApk(apk);
                        break;
                    default:
                        try {
                            history.delete(e.id);
                            renderHistory();
                        } catch (IOException ex) {
                            Ui.message(BuildActivity.this, pal, "Could not delete", ex.getMessage());
                        }
                }
            }
        }).show();
    }

    private void showLog(BuildHistory.Entry e) {
        final String log = history.readLog(e.id);
        TextView t = Ui.text(this, pal, log.length() == 0 ? "(no log was saved)" : log, Ui.Style.MONO);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        Ui.dialog(this, pal).setTitle("Build log").setView(sv)
                .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copy(log);
                    }
                }).setPositiveButton("Close", null).show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            // very large logs exceed the clipboard transaction limit, so keep the end, where errors are
            String t = text.length() > 90000 ? text.substring(text.length() - 90000) : text;
            cm.setPrimaryClip(ClipData.newPlainText("Zishu build log", t));
            toast(text.length() > 90000 ? "Copied the last 90,000 characters of the log." : "Log copied.");
        }
    }

    private void openUrl(String url) {
        if (!url.startsWith("https://")) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast("No browser is available.");
        }
    }
}
