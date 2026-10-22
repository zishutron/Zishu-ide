package com.zishu.ide.app;

import com.zishu.ide.R;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildHistory;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.terminal.Completer;
import com.zishu.ide.core.terminal.IdeCommands;
import com.zishu.ide.ondevice.SelfTest;
import com.zishu.ide.terminal.TerminalState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Zishu Terminal: a themed command line inside the IDE. Zishu commands control projects, on-phone and cloud
 * builds and installing; everything else goes to the Android shell. State lives in {@link TerminalState}.
 */
public final class TerminalActivity extends BaseActivity implements TerminalState.View {
    public static final String EXTRA_PROJECT = "project";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TerminalState state;
    private IdeCommands ide;
    private TerminalActions actions;
    private TextView output;
    private ScrollView scroll;
    private EditText input;
    private TextView promptView;
    private TextView subtitle;
    private int historyPos;
    private boolean renderQueued;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = TerminalState.get(this);
        state.attach(this, pal);
        actions = new TerminalActions(this, pal, state, new Runnable() {
            @Override
            public void run() {
                updatePrompt();
            }
        });
        ide = new IdeCommands(new Host());

        String dirName = getIntent().getStringExtra(EXTRA_PROJECT);
        if (dirName != null) {
            try {
                ProjectInfo p = App.projects(this).find(dirName);
                if (p != null && (state.project() == null || !state.project().dir.equals(p.dir))) {
                    state.setProject(p);
                }
            } catch (IOException ignored) {
                // the terminal still works without a selected project
            }
        }
        state.welcomeOnce();
        if (state.project() == null) {
            // Select the only project automatically; with several, the user chooses.
            try {
                List<ProjectInfo> all = App.projects(this).list();
                if (all.size() == 1) {
                    state.setProject(all.get(0));
                }
            } catch (IOException ignored) {
                // no workspace yet
            }
        }
        historyPos = state.history().size();
        buildScreen();
        render();
    }

    @Override
    protected void onDestroy() {
        if (state != null) {
            state.detach(this);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        View clear = Ui.iconButton(this, pal, R.drawable.ic_trash, "Clear screen", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.clear();
            }
        });
        View copy = Ui.iconButton(this, pal, R.drawable.ic_copy, "Copy all output", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Zishu Terminal", state.text().toString()));
                    toast("Output copied.");
                }
            }
        });
        LinearLayout bar = Ui.topBar(this, pal, "Terminal", projectLabel(), R.drawable.ic_arrow_back, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }, copy, clear);
        subtitle = (TextView) bar.findViewWithTag("subtitle");
        root.addView(bar);
        root.addView(Ui.divider(this, pal));

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.codeBg);
        scroll.setFillViewport(true);
        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12.5f);
        output.setTextColor(pal.text);
        output.setLineSpacing(0, 1.12f);
        output.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        output.setTextIsSelectable(true);
        scroll.addView(output, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, Ui.weight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(Ui.divider(this, pal));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(pal.surface);

        HorizontalScrollView keys = new HorizontalScrollView(this);
        keys.setHorizontalScrollBarEnabled(false);
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), 0);
        keyRow.addView(key("Tab", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                complete();
            }
        }));
        keyRow.addView(key("↑", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                historyStep(-1);
            }
        }));
        keyRow.addView(key("↓", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                historyStep(1);
            }
        }));
        keyRow.addView(key("^C", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.interrupt();
            }
        }));
        String[] symbols = {"/", "-", "~", "|", "$", "\"", "'", "&", ">", "*"};
        for (final String sym : symbols) {
            keyRow.addView(key(sym, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int pos = Math.max(0, input.getSelectionStart());
                    input.getText().insert(pos, sym);
                }
            }));
        }
        keys.addView(keyRow);
        bottom.addView(keys);

        promptView = new TextView(this);
        promptView.setTypeface(Typeface.MONOSPACE);
        promptView.setTextSize(11.5f);
        promptView.setTextColor(pal.primary);
        promptView.setSingleLine(true);
        promptView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        promptView.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), 0);
        bottom.addView(promptView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), Ui.dp(this, 4));
        input = new EditText(this);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(14.5f);
        input.setTextColor(pal.text);
        input.setHintTextColor(pal.textSecondary);
        input.setHint("zishu help");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setBackground(null);
        input.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    send();
                    return true;
                }
                return false;
            }
        });
        row.addView(input, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.iconButton(this, pal, R.drawable.ic_play, "Run", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        }));
        bottom.addView(row);
        root.addView(bottom);
        setScreen(root);
        updatePrompt();
    }

    private TextView key(String label, View.OnClickListener l) {
        TextView t = Ui.chip(this, pal, label, false, l);
        t.setTypeface(Typeface.MONOSPACE);
        t.setMinWidth(Ui.dp(this, 44));
        t.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 38));
        lp.rightMargin = Ui.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private String projectLabel() {
        ProjectInfo p = state.project();
        return p == null ? "No project selected" : p.name;
    }

    private void updatePrompt() {
        if (promptView != null) {
            promptView.setText(state.prompt().trim());
        }
        if (subtitle != null) {
            subtitle.setText(projectLabel());
        }
    }

    // ------------------------------------------------------------------ input handling

    private void send() {
        String line = input.getText().toString();
        input.setText("");
        if (line.trim().length() == 0) {
            return;
        }
        state.submit(line, ide);
        historyPos = state.history().size();
        updatePrompt();
        scrollToBottom();
    }

    private void historyStep(int dir) {
        List<String> h = state.history();
        if (h.isEmpty()) {
            return;
        }
        historyPos = Math.max(0, Math.min(h.size(), historyPos + dir));
        String s = historyPos >= h.size() ? "" : h.get(historyPos);
        input.setText(s);
        input.setSelection(s.length());
    }

    private void complete() {
        String line = input.getText().toString();
        int cursor = Math.max(0, Math.min(line.length(), input.getSelectionStart()));
        String before = line.substring(0, cursor);
        String after = line.substring(cursor);
        List<String> names = new ArrayList<String>();
        try {
            for (ProjectInfo p : App.projects(this).list()) {
                names.add(p.name);
            }
        } catch (IOException ignored) {
            // complete without project names
        }
        Completer.Result r = Completer.complete(before, names, state.currentDir());
        input.setText(r.line + after);
        input.setSelection(r.line.length());
        if (r.options.size() > 1) {
            Collections.sort(r.options);
            state.println(IdeCommands.Level.DIM, com.zishu.ide.core.util.IoUtil.join("   ", r.options));
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void onTerminalChanged() {
        if (renderQueued) {
            return;
        }
        renderQueued = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderQueued = false;
                render();
            }
        }, 60);
    }

    private void render() {
        if (output == null) {
            return;
        }
        int bottomGap = output.getHeight() - scroll.getScrollY() - scroll.getHeight();
        boolean stick = output.getHeight() == 0 || bottomGap < Ui.dp(this, 80);
        output.setText(state.text());
        updatePrompt();
        if (stick) {
            scrollToBottom();
        }
    }

    private void scrollToBottom() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ------------------------------------------------------------------ what the commands do

    private final class Host implements IdeCommands.Host {
        @Override
        public List<ProjectInfo> projects() {
            try {
                return App.projects(TerminalActivity.this).list();
            } catch (IOException e) {
                return new ArrayList<ProjectInfo>();
            }
        }

        @Override
        public ProjectInfo currentProject() {
            return state.project();
        }

        @Override
        public void setCurrentProject(ProjectInfo p) {
            state.setProject(p);
            updatePrompt();
        }

        @Override
        public String engineChoice() {
            return prefs.engineChoice();
        }

        @Override
        public void setEngineChoice(String choice) {
            prefs.setEngineChoice(choice);
        }

        @Override
        public BuildEngine.Status engineStatus(String engine) {
            return "device".equals(engine) ? Engines.onDevice(TerminalActivity.this).status()
                    : Engines.cloud(TerminalActivity.this).status();
        }

        @Override
        public String startBuild(String engine) {
            return state.startBuild(engine);
        }

        @Override
        public boolean isBuilding() {
            return BuildCoordinator.get().isRunning();
        }

        @Override
        public void cancelBuild() {
            BuildCoordinator.get().cancel();
        }

        @Override
        public String lastLog() {
            return state.lastLog();
        }

        @Override
        public File lastApk() {
            return state.lastApk();
        }

        @Override
        public String installLastApk() {
            File apk = state.lastApk();
            if (apk == null) {
                return "There is no APK to install yet. Run  zishu build  first.";
            }
            return ApkInstaller.install(TerminalActivity.this, pal, apk);
        }

        @Override
        public List<BuildHistory.Entry> history() {
            ProjectInfo p = state.project();
            return p == null ? new ArrayList<BuildHistory.Entry>() : new BuildHistory(p.dir).list();
        }

        @Override
        public String cloudRepo() {
            return prefs.cloudRepo();
        }

        @Override
        public String setCloudRepo(String ownerRepo) {
            prefs.setCloudRepo(ownerRepo);
            return null;
        }

        @Override
        public boolean cloudWorkflow() {
            return prefs.cloudInstallWorkflow();
        }

        @Override
        public void setCloudWorkflow(boolean on) {
            prefs.setCloudInstallWorkflow(on);
        }

        @Override
        public boolean cloudAllowPublic() {
            return prefs.cloudAllowPublic();
        }

        @Override
        public void setCloudAllowPublic(boolean on) {
            prefs.setCloudAllowPublic(on);
        }

        @Override
        public boolean hasToken() {
            return TokenStore.has(TerminalActivity.this);
        }

        @Override
        public void openCloudSettings() {
            startActivity(new Intent(TerminalActivity.this, CloudSettingsActivity.class));
        }

        @Override
        public void openEditor() {
            ProjectInfo p = state.project();
            if (p != null) {
                Intent i = new Intent(TerminalActivity.this, EditorActivity.class);
                i.putExtra(EditorActivity.EXTRA_PROJECT, p.dir.getName());
                startActivity(i);
            }
        }

        @Override
        public void runSelfTest() {
            state.println(IdeCommands.Level.HEAD, "Running the on-phone toolchain self-test (about a minute)...");
            final Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String report = SelfTest.run(app);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            for (String l : report.split("\n")) {
                                IdeCommands.Level level = l.startsWith("RESULT: SUCCESS") ? IdeCommands.Level.OK
                                        : l.startsWith("RESULT: FAILED") ? IdeCommands.Level.ERROR : IdeCommands.Level.PLAIN;
                                state.println(level, l);
                            }
                        }
                    });
                }
            }, "zishu-selftest").start();
        }

        @Override
        public void clearScreen() {
            state.clear();
        }

        @Override
        public String downloadApk(File apk) {
            return actions.downloadApk(apk);
        }

        @Override
        public String signApk(File apk, File keystore, String alias) {
            return actions.signApk(apk, keystore, alias);
        }

        @Override
        public String verifyApk(File apk) {
            return actions.verifyApk(apk);
        }

        @Override
        public String createKeystore(String fileName, String alias, String cn, String org, String country, int years) {
            return actions.createKeystore(fileName, alias, cn, org, country, years);
        }

        @Override
        public String importKeystore() {
            return actions.importKeystore();
        }

        @Override
        public String keystoreInfo(File keystore) {
            return actions.keystoreInfo(keystore);
        }

        @Override
        public String deleteKeystore(File keystore) {
            return actions.deleteKeystore(keystore);
        }

        @Override
        public String cloneRepo(String repo, String ref, String projectName, boolean useToken) {
            return actions.cloneRepo(repo, ref, projectName, useToken);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        actions.onActivityResult(requestCode, resultCode, data);
    }
}
