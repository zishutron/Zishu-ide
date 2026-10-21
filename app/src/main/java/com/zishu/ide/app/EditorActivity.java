package com.zishu.ide.app;

import com.zishu.ide.R;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zishu.ide.core.editor.CodeEditing;
import com.zishu.ide.core.editor.Diagnostic;
import com.zishu.ide.core.editor.FileLanguage;
import com.zishu.ide.core.editor.SyntaxChecker;
import com.zishu.ide.core.editor.TextSearch;
import com.zishu.ide.core.editor.UndoStack;
import com.zishu.ide.core.project.FileManager;
import com.zishu.ide.core.project.ProjectInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The code editor screen: file explorer, tabs, find/replace, project search, problems list,
 * symbol bar and save handling. Files are read and written only through {@link FileManager},
 * which keeps every path inside the project folder.
 */
public final class EditorActivity extends BaseActivity implements CodeEditText.Listener {
    public static final String EXTRA_PROJECT = "project";

    private static final class OpenFile {
        String path;
        final UndoStack undo = new UndoStack();
        String text = "";
        boolean crlf;
        int caret;
        boolean dirty;

        OpenFile(String path) {
            this.path = path;
        }

        String name() {
            int i = path.lastIndexOf('/');
            return i < 0 ? path : path.substring(i + 1);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<OpenFile> tabs = new ArrayList<OpenFile>();
    private final Set<String> expanded = new HashSet<String>();
    private final TextSearch.Options findOptions = new TextSearch.Options();

    private ProjectInfo project;
    private FileManager fm;
    private OpenFile current;
    private String selectedDir = "";
    private List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
    private int checkVersion;

    private CodeEditText editor;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private View emptyView;
    private FrameLayout explorerPanel;
    private LinearLayout explorerList;
    private TextView explorerTarget;
    private LinearLayout findBar;
    private EditText findEdit;
    private EditText replaceEdit;
    private TextView findCount;
    private TextView caseToggle;
    private TextView wordToggle;
    private TextView regexToggle;
    private TextView statusPos;
    private TextView statusProblems;
    private TextView titleView;
    private List<TextSearch.Match> matches = new ArrayList<TextSearch.Match>();

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            runDiagnostics();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String dirName = getIntent().getStringExtra(EXTRA_PROJECT);
        try {
            project = dirName == null ? null : App.projects(this).find(dirName);
            fm = project == null ? null : new FileManager(project.dir);
        } catch (IOException e) {
            project = null;
        }
        if (project == null || fm == null) {
            toast("This project could not be opened.");
            finish();
            return;
        }
        buildUi();
        refreshExplorer();
        showCurrent();
        explorerPanel.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = Ui.topBar(this, pal, project.name, project.type.label, R.drawable.ic_menu,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleExplorer();
                    }
                },
                Ui.iconButton(this, pal, R.drawable.ic_save, "Save", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        saveCurrent(true);
                    }
                }),
                Ui.iconButton(this, pal, R.drawable.ic_play, "Build APK", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startBuild();
                    }
                }),
                Ui.iconButton(this, pal, R.drawable.ic_more, "More", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMoreMenu(v);
                    }
                }));
        titleView = (TextView) bar.findViewWithTag("title");
        root.addView(bar);
        root.addView(Ui.divider(this, pal));

        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(pal.surface);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabStrip);
        root.addView(tabScroll, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        root.addView(Ui.divider(this, pal));

        buildFindBar();
        root.addView(findBar);

        FrameLayout body = new FrameLayout(this);
        editor = new CodeEditText(this);
        editor.configure(pal, prefs);
        editor.setBackgroundColor(pal.codeBg);
        editor.setListener(this);
        body.addView(editor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyView = Ui.emptyState(this, pal, R.drawable.ic_code, "No file open",
                "Open a file from the explorer, or create a new one.", null);
        emptyView.setBackgroundColor(pal.bg);
        body.addView(emptyView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        root.addView(body, Ui.weight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(Ui.divider(this, pal));
        root.addView(buildSymbolBar());
        root.addView(buildStatusBar());

        FrameLayout stack = new FrameLayout(this);
        stack.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildExplorer(stack);
        setScreen(stack);
    }

    private void buildFindBar() {
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.VERTICAL);
        findBar.setBackgroundColor(pal.surface);
        findBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        findBar.setVisibility(View.GONE);

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        findEdit = smallEdit("Find");
        findEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                updateMatches(false);
            }

            @Override
            public void afterTextChanged(Editable e) {
            }
        });
        row1.addView(findEdit, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        findCount = Ui.text(this, pal, "", Ui.Style.CAPTION);
        findCount.setMinWidth(dp(48));
        findCount.setGravity(Gravity.CENTER);
        row1.addView(findCount);
        row1.addView(Ui.iconButton(this, pal, R.drawable.ic_chevron_up, "Previous match", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gotoMatch(false);
            }
        }));
        row1.addView(Ui.iconButton(this, pal, R.drawable.ic_chevron_down, "Next match", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gotoMatch(true);
            }
        }));
        row1.addView(Ui.iconButton(this, pal, R.drawable.ic_close, "Close find", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeFind();
            }
        }));
        findBar.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        replaceEdit = smallEdit("Replace with");
        row2.addView(replaceEdit, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(Ui.chip(this, pal, "Replace", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                replaceCurrent();
            }
        }), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        row2.addView(Ui.chip(this, pal, "All", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                replaceAllMatches();
            }
        }), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        findBar.addView(row2, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        LinearLayout row3 = new LinearLayout(this);
        caseToggle = toggleChip("Aa", "Match case");
        wordToggle = toggleChip("Word", "Whole word");
        regexToggle = toggleChip(".*", "Regular expression");
        row3.addView(caseToggle);
        row3.addView(wordToggle, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        row3.addView(regexToggle, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0, 0, 0));
        findBar.addView(row3, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
    }

    private EditText smallEdit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(pal.textSecondary);
        e.setTextColor(pal.text);
        e.setTextSize(14.5f);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinHeight(dp(44));
        e.setPadding(dp(12), dp(6), dp(12), dp(6));
        e.setBackground(Ui.rounded(this, pal.bg, pal.outline, 10));
        e.setContentDescription(hint);
        return e;
    }

    private TextView toggleChip(final String label, final String description) {
        final TextView t = Ui.chip(this, pal, label, false, null);
        t.setContentDescription(description);
        t.setMinHeight(dp(36));
        t.setTag(Boolean.FALSE);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !Boolean.TRUE.equals(t.getTag());
                t.setTag(Boolean.valueOf(on));
                t.setTextColor(on ? pal.onTonal : pal.textSecondary);
                t.setBackground(Ui.ripple(pal, Ui.rounded(EditorActivity.this, on ? pal.tonal : 0, on ? 0 : pal.outline, 20)));
                findOptions.caseSensitive = Boolean.TRUE.equals(caseToggle.getTag());
                findOptions.wholeWord = Boolean.TRUE.equals(wordToggle.getTag());
                findOptions.regex = Boolean.TRUE.equals(regexToggle.getTag());
                updateMatches(false);
            }
        });
        return t;
    }

    private View buildSymbolBar() {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setBackgroundColor(pal.surface);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.iconButton(this, pal, R.drawable.ic_undo, "Undo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current != null && !editor.undoEdit()) {
                    toast("Nothing to undo.");
                }
            }
        }));
        row.addView(Ui.iconButton(this, pal, R.drawable.ic_redo, "Redo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current != null && !editor.redoEdit()) {
                    toast("Nothing to redo.");
                }
            }
        }));
        row.addView(symbol("Tab", "\t", 0));
        String[] keys = {"{", "}", "(", ")", "[", "]", ";", "\"", "'", "<", ">", "/", "=", ".", ",", ":", "_", "!", "&", "|", "-", "+", "*", "#", "$", "@"};
        for (String k : keys) {
            row.addView(symbol(k, k, 0));
        }
        sv.addView(row);
        return sv;
    }

    private TextView symbol(final String label, final String insert, final int back) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(pal.text);
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(40));
        t.setMinHeight(dp(44));
        t.setPadding(dp(8), 0, dp(8), 0);
        t.setClickable(true);
        t.setBackground(Ui.ripple(pal, null));
        t.setContentDescription(label.equals("Tab") ? "Insert indent" : "Insert " + label);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current == null) {
                    return;
                }
                if (insert.equals("\t")) {
                    editor.insertIndent();
                } else {
                    editor.insertAtCaret(insert, back);
                }
            }
        });
        return t;
    }

    private View buildStatusBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(pal.surfaceAlt);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setMinimumHeight(dp(32));
        statusPos = Ui.text(this, pal, "", Ui.Style.CAPTION);
        statusPos.setClickable(true);
        statusPos.setMinHeight(dp(32));
        statusPos.setGravity(Gravity.CENTER_VERTICAL);
        statusPos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goToLineDialog();
            }
        });
        row.addView(statusPos, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusProblems = Ui.text(this, pal, "", Ui.Style.CAPTION);
        statusProblems.setClickable(true);
        statusProblems.setMinHeight(dp(32));
        statusProblems.setGravity(Gravity.CENTER_VERTICAL);
        statusProblems.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showProblems();
            }
        });
        row.addView(statusProblems);
        return row;
    }

    // ------------------------------------------------------------------ explorer

    private void buildExplorer(FrameLayout stack) {
        explorerPanel = new FrameLayout(this);
        explorerPanel.setVisibility(View.GONE);
        View scrim = new View(this);
        scrim.setBackgroundColor(pal.scrim);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleExplorer();
            }
        });
        explorerPanel.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(pal.surface);
        int width = Math.min(dp(320), (int) (getResources().getDisplayMetrics().widthPixels * 0.86f));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(8), dp(4), dp(4));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, pal, "Files", Ui.Style.TITLE));
        explorerTarget = Ui.text(this, pal, "", Ui.Style.CAPTION);
        explorerTarget.setSingleLine(true);
        explorerTarget.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titles.addView(explorerTarget);
        head.addView(titles, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.iconButton(this, pal, R.drawable.ic_file, "New file", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptCreate(selectedDir, false);
            }
        }));
        head.addView(Ui.iconButton(this, pal, R.drawable.ic_folder, "New folder", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptCreate(selectedDir, true);
            }
        }));
        panel.addView(head);
        panel.addView(Ui.divider(this, pal));

        ScrollView sv = new ScrollView(this);
        explorerList = new LinearLayout(this);
        explorerList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(explorerList);
        panel.addView(sv, Ui.weight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        explorerPanel.addView(panel, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        stack.addView(explorerPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void toggleExplorer() {
        explorerPanel.setVisibility(explorerPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void refreshExplorer() {
        explorerList.removeAllViews();
        explorerTarget.setText("in " + (selectedDir.length() == 0 ? "/" : "/" + selectedDir));
        try {
            addNodes("", 0);
        } catch (IOException e) {
            explorerList.addView(Ui.text(this, pal, "Cannot read the project: " + e.getMessage(), Ui.Style.CAPTION));
        }
        if (explorerList.getChildCount() == 0) {
            TextView t = Ui.text(this, pal, "This project has no files. Use the buttons above to add one.", Ui.Style.CAPTION);
            t.setPadding(dp(16), dp(16), dp(16), dp(16));
            explorerList.addView(t);
        }
    }

    private void addNodes(String dir, int depth) throws IOException {
        for (final FileManager.FileNode n : fm.list(dir)) {
            explorerList.addView(nodeRow(n, depth));
            if (n.directory && expanded.contains(n.path)) {
                addNodes(n.path, depth + 1);
            }
        }
    }

    private View nodeRow(final FileManager.FileNode n, int depth) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setPadding(dp(8 + depth * 16), 0, 0, 0);
        boolean isOpen = current != null && current.path.equals(n.path);
        boolean isSelectedDir = n.directory && n.path.equals(selectedDir);
        if (isOpen || isSelectedDir) {
            row.setBackgroundColor(pal.tonal);
        } else {
            row.setBackground(Ui.ripple(pal, null));
        }
        row.setClickable(true);
        ImageView chev = Ui.icon(this, n.directory ? (expanded.contains(n.path) ? R.drawable.ic_chevron_down
                : R.drawable.ic_chevron_right) : R.drawable.ic_file, pal.textSecondary, 18);
        row.addView(chev, Ui.lpMargin(this, dp(18), dp(18), 0, 0, 8, 0));
        TextView name = Ui.text(this, pal, n.name, Ui.Style.BODY);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(name, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.iconButton(this, pal, R.drawable.ic_more, "Actions for " + n.name, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNodeMenu(v, n);
            }
        }));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (n.directory) {
                    selectedDir = n.path;
                    if (expanded.contains(n.path)) {
                        expanded.remove(n.path);
                    } else {
                        expanded.add(n.path);
                    }
                    refreshExplorer();
                } else {
                    openFile(n.path);
                    explorerPanel.setVisibility(View.GONE);
                }
            }
        });
        return row;
    }

    private void showNodeMenu(View anchor, final FileManager.FileNode n) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (n.directory) {
            m.getMenu().add(0, 1, 0, "New file here");
            m.getMenu().add(0, 2, 1, "New folder here");
        }
        m.getMenu().add(0, 3, 2, "Rename");
        m.getMenu().add(0, 4, 3, "Delete");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        promptCreate(n.path, false);
                        break;
                    case 2:
                        promptCreate(n.path, true);
                        break;
                    case 3:
                        promptRename(n);
                        break;
                    default:
                        confirmDelete(n);
                }
                return true;
            }
        });
        m.show();
    }

    private void promptCreate(final String dir, final boolean folder) {
        final Ui.Field f = Ui.field(this, pal, folder ? "Folder name" : "File name", folder ? "ui" : "Helper.java", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final AlertDialog d = Ui.dialog(this, pal).setTitle(folder ? "New folder" : "New file")
                .setView(padded(f.view)).setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String name = f.value().trim();
                        try {
                            String path;
                            if (folder) {
                                path = fm.createDirectory(dir, name);
                                expanded.add(dir);
                                selectedDir = path;
                                expanded.add(path);
                            } else {
                                path = fm.createFile(dir, name);
                                if (dir.length() > 0) {
                                    expanded.add(dir);
                                }
                            }
                            d.dismiss();
                            refreshExplorer();
                            if (!folder) {
                                openFile(path);
                            }
                        } catch (IOException e) {
                            f.setError(e.getMessage());
                        }
                    }
                });
            }
        });
        d.show();
    }

    private void promptRename(final FileManager.FileNode n) {
        final Ui.Field f = Ui.field(this, pal, "New name", n.name, n.name,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final AlertDialog d = Ui.dialog(this, pal).setTitle("Rename").setView(padded(f.view))
                .setNegativeButton("Cancel", null).setPositiveButton("Rename", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            String np = fm.rename(n.path, f.value().trim());
                            remapTabs(n.path, np);
                            if (expanded.remove(n.path)) {
                                expanded.add(np);
                            }
                            if (selectedDir.equals(n.path)) {
                                selectedDir = np;
                            }
                            d.dismiss();
                            refreshExplorer();
                            refreshTabs();
                        } catch (IOException e) {
                            f.setError(e.getMessage());
                        }
                    }
                });
            }
        });
        d.show();
    }

    private void confirmDelete(final FileManager.FileNode n) {
        Ui.dialog(this, pal).setTitle("Delete " + n.name + "?")
                .setMessage(n.directory ? "The folder and everything in it will be permanently deleted."
                        : "The file will be permanently deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            fm.delete(n.path);
                            closeTabsUnder(n.path);
                            if (selectedDir.equals(n.path) || selectedDir.startsWith(n.path + "/")) {
                                selectedDir = "";
                            }
                            refreshExplorer();
                        } catch (IOException e) {
                            Ui.message(EditorActivity.this, pal, "Could not delete", e.getMessage());
                        }
                    }
                }).show();
    }

    private View padded(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v);
        return f;
    }

    // ------------------------------------------------------------------ tabs and files

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".gif");
    }

    private void openFile(String path) {
        for (OpenFile f : tabs) {
            if (f.path.equals(path)) {
                switchTo(f);
                return;
            }
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (isImage(name)) {
            showImage(path);
            return;
        }
        try {
            FileManager.TextFile tf = fm.readText(path);
            OpenFile f = new OpenFile(path);
            f.text = tf.text;
            f.crlf = tf.crlf;
            tabs.add(f);
            switchTo(f);
        } catch (FileManager.NotTextException e) {
            Ui.message(this, pal, "Cannot open " + name, e.getMessage());
        } catch (IOException e) {
            Ui.message(this, pal, "Cannot open " + name, e.getMessage());
        }
    }

    private void showImage(String path) {
        try {
            File file = fm.resolve(path);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), o);
            if (bmp == null) {
                Ui.message(this, pal, "Cannot show image", "The file is not a readable image.");
                return;
            }
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setAdjustViewBounds(true);
            iv.setBackgroundColor(pal.surfaceAlt);
            iv.setContentDescription(file.getName());
            Ui.dialog(this, pal).setTitle(file.getName() + " (" + bounds.outWidth + " x " + bounds.outHeight + ")")
                    .setView(iv).setPositiveButton("Close", null).show();
        } catch (IOException e) {
            Ui.message(this, pal, "Cannot show image", e.getMessage());
        }
    }

    private void captureCurrent() {
        if (current != null) {
            current.text = editor.getText().toString();
            current.caret = editor.getSelectionStart();
        }
    }

    private void switchTo(OpenFile f) {
        if (f != current) {
            captureCurrent();
        }
        current = f;
        showCurrent();
    }

    private void showCurrent() {
        refreshTabs();
        if (current == null) {
            emptyView.setVisibility(View.VISIBLE);
            editor.setVisibility(View.GONE);
            statusPos.setText("");
            statusProblems.setText("");
            titleView.setText(project.name);
            return;
        }
        emptyView.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        editor.bindUndo(current.undo);
        editor.setLanguage(FileLanguage.fromFileName(current.name()));
        editor.loadText(current.text, current.caret);
        editor.setErrorLines(new HashSet<Integer>());
        diagnostics = new ArrayList<Diagnostic>();
        updateStatus();
        updateMatches(false);
        scheduleCheck(50);
        titleView.setText(current.name());
        refreshExplorer();
        editor.requestFocus();
    }

    private void refreshTabs() {
        tabStrip.removeAllViews();
        View active = null;
        for (final OpenFile f : tabs) {
            LinearLayout tab = new LinearLayout(this);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setClickable(true);
            boolean isCurrent = f == current;
            tab.setBackground(Ui.ripple(pal, isCurrent ? Ui.rounded(this, pal.tonal, 0, 0) : null));
            tab.setPadding(dp(12), 0, dp(2), 0);
            TextView label = Ui.text(this, pal, (f.dirty ? "● " : "") + f.name(), Ui.Style.BODY);
            label.setTextSize(14);
            label.setSingleLine(true);
            label.setTextColor(isCurrent ? pal.onTonal : pal.textSecondary);
            tab.addView(label);
            tab.addView(Ui.iconButton(this, pal, R.drawable.ic_close, "Close " + f.name(), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestCloseTab(f);
                }
            }), Ui.lp(dp(40), dp(44)));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTo(f);
                }
            });
            tabStrip.addView(tab, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (isCurrent) {
                active = tab;
            }
        }
        final View scrollTo = active;
        if (scrollTo != null) {
            tabScroll.post(new Runnable() {
                @Override
                public void run() {
                    tabScroll.smoothScrollTo(Math.max(0, scrollTo.getLeft() - dp(24)), 0);
                }
            });
        }
    }

    private void requestCloseTab(final OpenFile f) {
        if (f == current) {
            captureCurrent();
        }
        if (!f.dirty) {
            closeTab(f);
            return;
        }
        Ui.dialog(this, pal).setTitle("Save " + f.name() + "?")
                .setMessage("This file has unsaved changes.")
                .setNegativeButton("Discard", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        closeTab(f);
                    }
                })
                .setNeutralButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (save(f, true)) {
                            closeTab(f);
                        }
                    }
                }).show();
    }

    private void closeTab(OpenFile f) {
        int i = tabs.indexOf(f);
        tabs.remove(f);
        if (f == current) {
            current = tabs.isEmpty() ? null : tabs.get(Math.min(i, tabs.size() - 1));
            showCurrent();
        } else {
            refreshTabs();
        }
    }

    private void closeTabsUnder(String path) {
        boolean closedCurrent = false;
        for (int i = tabs.size() - 1; i >= 0; i--) {
            OpenFile f = tabs.get(i);
            if (f.path.equals(path) || f.path.startsWith(path + "/")) {
                tabs.remove(i);
                if (f == current) {
                    closedCurrent = true;
                }
            }
        }
        if (closedCurrent) {
            current = tabs.isEmpty() ? null : tabs.get(tabs.size() - 1);
            showCurrent();
        } else {
            refreshTabs();
        }
    }

    /** Re-points open tabs after a rename or move. */
    private void remapTabs(String oldPath, String newPath) {
        for (OpenFile f : tabs) {
            if (f.path.equals(oldPath)) {
                f.path = newPath;
            } else if (f.path.startsWith(oldPath + "/")) {
                f.path = newPath + f.path.substring(oldPath.length());
            }
        }
        if (current != null) {
            titleView.setText(current.name());
        }
    }

    // ------------------------------------------------------------------ saving

    private boolean save(OpenFile f, boolean report) {
        if (f == current) {
            captureCurrent();
        }
        try {
            fm.writeText(f.path, f.text, f.crlf);
            f.undo.markSaved();
            f.dirty = false;
            refreshTabs();
            if (report) {
                toast("Saved " + f.name());
            }
            return true;
        } catch (IOException e) {
            Ui.message(this, pal, "Could not save " + f.name(), e.getMessage());
            return false;
        }
    }

    private void saveCurrent(boolean report) {
        if (current == null) {
            toast("There is no open file to save.");
            return;
        }
        save(current, report);
    }

    private boolean saveAll() {
        captureCurrent();
        boolean ok = true;
        for (OpenFile f : tabs) {
            if (f.dirty && !save(f, false)) {
                ok = false;
            }
        }
        return ok;
    }

    private boolean hasDirty() {
        for (OpenFile f : tabs) {
            if (f.dirty) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean allowRecreate() {
        if (hasDirty() && !prefs.autoSave()) {
            return false;
        }
        saveAll();
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (fm != null && prefs.autoSave() && hasDirty()) {
            saveAll();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (explorerPanel != null && explorerPanel.getVisibility() == View.VISIBLE && current != null) {
            explorerPanel.setVisibility(View.GONE);
            return;
        }
        if (findBar != null && findBar.getVisibility() == View.VISIBLE) {
            closeFind();
            return;
        }
        captureCurrent();
        if (hasDirty()) {
            Ui.dialog(this, pal).setTitle("Unsaved changes")
                    .setMessage("Save your changes before leaving the editor?")
                    .setNegativeButton("Discard", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            finish();
                        }
                    })
                    .setNeutralButton("Cancel", null)
                    .setPositiveButton("Save all", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            if (saveAll()) {
                                finish();
                            }
                        }
                    }).show();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ editor callbacks

    @Override
    public void onUserEdit() {
        if (current == null) {
            return;
        }
        boolean dirty = !current.undo.isAtSavePoint();
        if (dirty != current.dirty) {
            current.dirty = dirty;
            refreshTabs();
        }
        updateStatus();
        scheduleCheck(700);
        if (findBar.getVisibility() == View.VISIBLE) {
            updateMatches(false);
        }
    }

    @Override
    public void onCaretChanged() {
        updateStatus();
    }

    private void updateStatus() {
        if (current == null) {
            return;
        }
        statusPos.setText("Ln " + editor.currentLine() + ", Col " + editor.currentColumn() + "  ·  "
                + editor.getLanguage().label + "  ·  " + (current.crlf ? "CRLF" : "LF"));
        int errors = 0;
        int warnings = 0;
        for (Diagnostic d : diagnostics) {
            if (d.severity == Diagnostic.Severity.ERROR) {
                errors++;
            } else {
                warnings++;
            }
        }
        if (errors > 0) {
            statusProblems.setText(errors + (errors == 1 ? " error" : " errors"));
            statusProblems.setTextColor(pal.error);
        } else if (warnings > 0) {
            statusProblems.setText(warnings + (warnings == 1 ? " warning" : " warnings"));
            statusProblems.setTextColor(pal.warning);
        } else {
            statusProblems.setText("No problems");
            statusProblems.setTextColor(pal.textSecondary);
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private void scheduleCheck(long delay) {
        handler.removeCallbacks(checkRunnable);
        handler.postDelayed(checkRunnable, delay);
    }

    private void runDiagnostics() {
        if (current == null) {
            return;
        }
        final int version = ++checkVersion;
        final String text = editor.getText().toString();
        final FileLanguage lang = editor.getLanguage();
        final OpenFile file = current;
        if (text.length() > 400000) {
            return; // very large files are not checked live
        }
        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    final List<Diagnostic> found = SyntaxChecker.check(lang, text);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (version != checkVersion || file != current) {
                                return;
                            }
                            diagnostics = found;
                            Set<Integer> lines = new HashSet<Integer>();
                            for (Diagnostic d : found) {
                                if (d.severity == Diagnostic.Severity.ERROR) {
                                    lines.add(Integer.valueOf(d.line));
                                }
                            }
                            editor.setErrorLines(lines);
                            updateStatus();
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // activity is closing
        }
    }

    private void showProblems() {
        if (current == null) {
            return;
        }
        if (diagnostics.isEmpty()) {
            Ui.message(this, pal, "Problems", "No syntax problems found in " + current.name()
                    + ".\n\nThe editor checks structure only (brackets, quotes, XML). Full compile errors appear when you build.");
            return;
        }
        final String[] items = new String[diagnostics.size()];
        for (int i = 0; i < items.length; i++) {
            Diagnostic d = diagnostics.get(i);
            items[i] = (d.severity == Diagnostic.Severity.ERROR ? "Error" : "Warning") + " at line " + d.line + ": " + d.message;
        }
        Ui.dialog(this, pal).setTitle("Problems").setItems(items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                editor.goToLine(diagnostics.get(which).line);
            }
        }).setPositiveButton("Close", null).show();
    }

    // ------------------------------------------------------------------ find / replace

    private void openFind() {
        if (current == null) {
            toast("Open a file first.");
            return;
        }
        findBar.setVisibility(View.VISIBLE);
        int a = editor.getSelectionStart();
        int b = editor.getSelectionEnd();
        if (b > a && b - a < 200) {
            String sel = editor.getText().subSequence(a, b).toString();
            if (sel.indexOf('\n') < 0) {
                findEdit.setText(sel);
            }
        }
        findEdit.requestFocus();
        findEdit.setSelection(findEdit.getText().length());
        updateMatches(false);
    }

    private void closeFind() {
        findBar.setVisibility(View.GONE);
        editor.setFindMatches(null, -1);
        matches = new ArrayList<TextSearch.Match>();
        editor.requestFocus();
    }

    private void updateMatches(boolean jump) {
        if (findBar.getVisibility() != View.VISIBLE || current == null) {
            return;
        }
        String q = findEdit.getText().toString();
        if (q.length() == 0) {
            matches = new ArrayList<TextSearch.Match>();
            findCount.setText("");
            editor.setFindMatches(null, -1);
            return;
        }
        try {
            matches = TextSearch.findAll(editor.getText(), q, findOptions, 5000);
        } catch (RuntimeException e) {
            matches = new ArrayList<TextSearch.Match>();
            findCount.setText("Invalid");
            editor.setFindMatches(null, -1);
            return;
        }
        int cur = TextSearch.nextIndex(matches, editor.getSelectionStart());
        editor.setFindMatches(matches, jump ? cur : -1);
        findCount.setText(matches.isEmpty() ? "0" : (matches.size() >= 5000 ? "5000+" : String.valueOf(matches.size())));
    }

    private void gotoMatch(boolean forward) {
        updateMatches(false);
        if (matches.isEmpty()) {
            return;
        }
        int idx = forward ? TextSearch.nextIndex(matches, editor.getSelectionEnd() > editor.getSelectionStart()
                ? editor.getSelectionEnd() : editor.getSelectionStart())
                : TextSearch.previousIndex(matches, editor.getSelectionStart());
        if (idx < 0) {
            return;
        }
        TextSearch.Match m = matches.get(idx);
        editor.setSelection(m.start, m.end);
        editor.bringPointIntoView(m.start);
        editor.setFindMatches(matches, idx);
        findCount.setText((idx + 1) + "/" + matches.size());
    }

    private void replaceCurrent() {
        if (current == null || matches.isEmpty()) {
            return;
        }
        int a = editor.getSelectionStart();
        int b = editor.getSelectionEnd();
        TextSearch.Match hit = null;
        for (TextSearch.Match m : matches) {
            if (m.start == a && m.end == b) {
                hit = m;
                break;
            }
        }
        if (hit == null) {
            gotoMatch(true);
            return;
        }
        String q = findEdit.getText().toString();
        String rep = TextSearch.replacementFor(editor.getText().toString(), hit, q, replaceEdit.getText().toString(), findOptions);
        editor.getText().replace(hit.start, hit.end, rep);
        editor.setSelection(hit.start + rep.length());
        updateMatches(false);
        gotoMatch(true);
    }

    private void replaceAllMatches() {
        if (current == null || findEdit.getText().length() == 0) {
            return;
        }
        String text = editor.getText().toString();
        String out;
        try {
            out = TextSearch.replaceAll(text, findEdit.getText().toString(), replaceEdit.getText().toString(), findOptions);
        } catch (RuntimeException e) {
            toast("The replacement is not valid: " + e.getMessage());
            return;
        }
        if (out.equals(text)) {
            toast("Nothing to replace.");
            return;
        }
        int caret = Math.min(editor.getSelectionStart(), out.length());
        editor.getText().replace(0, text.length(), out);
        editor.setSelection(caret);
        updateMatches(false);
        toast("Replaced all. Undo restores the previous text.");
    }

    // ------------------------------------------------------------------ menus and dialogs

    private void showMoreMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Find and replace");
        m.getMenu().add(0, 2, 1, "Go to line");
        m.getMenu().add(0, 3, 2, "Search in project");
        m.getMenu().add(0, 4, 3, "Problems");
        m.getMenu().add(0, 5, 4, "Save all");
        m.getMenu().add(0, 6, 5, prefs.wordWrap() ? "Word wrap: on" : "Word wrap: off");
        m.getMenu().add(0, 7, 6, prefs.lineNumbers() ? "Line numbers: on" : "Line numbers: off");
        m.getMenu().add(0, 8, 7, "Font size");
        m.getMenu().add(0, 9, 8, "Build history");
        m.getMenu().add(0, 10, 9, "Settings");
        m.getMenu().add(0, 11, 10, "Cloud build settings");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        openFind();
                        break;
                    case 2:
                        goToLineDialog();
                        break;
                    case 3:
                        searchProjectDialog();
                        break;
                    case 4:
                        showProblems();
                        break;
                    case 5:
                        if (saveAll()) {
                            toast("All files saved.");
                        }
                        break;
                    case 6:
                        prefs.setWordWrap(!prefs.wordWrap());
                        editor.setWordWrap(prefs.wordWrap());
                        break;
                    case 7:
                        prefs.setLineNumbers(!prefs.lineNumbers());
                        editor.setShowLineNumbers(prefs.lineNumbers());
                        break;
                    case 8:
                        fontSizeDialog();
                        break;
                    case 10:
                        startActivity(new Intent(EditorActivity.this, SettingsActivity.class));
                        break;
                    case 11:
                        startActivity(new Intent(EditorActivity.this, CloudSettingsActivity.class));
                        break;
                    default:
                        Intent i = new Intent(EditorActivity.this, BuildActivity.class);
                        i.putExtra(BuildActivity.EXTRA_PROJECT, project.dir.getName());
                        startActivity(i);
                }
                return true;
            }
        });
        m.show();
    }

    private void goToLineDialog() {
        if (current == null) {
            return;
        }
        final int total = CodeEditing.lineCount(editor.getText());
        final Ui.Field f = Ui.field(this, pal, "Line (1 to " + total + ")", "", String.valueOf(editor.currentLine()),
                InputType.TYPE_CLASS_NUMBER);
        final AlertDialog d = Ui.dialog(this, pal).setTitle("Go to line").setView(padded(f.view))
                .setNegativeButton("Cancel", null).setPositiveButton("Go", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            int n = Integer.parseInt(f.value().trim());
                            if (n < 1 || n > total) {
                                f.setError("Enter a number from 1 to " + total + ".");
                                return;
                            }
                            d.dismiss();
                            editor.goToLine(n);
                        } catch (NumberFormatException e) {
                            f.setError("Enter a number from 1 to " + total + ".");
                        }
                    }
                });
            }
        });
        d.show();
    }

    private void fontSizeDialog() {
        final String[] labels = {"12", "14", "16", "18", "20", "24"};
        Ui.dialog(this, pal).setTitle("Font size (sp)").setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                prefs.setFontSizeSp(Float.parseFloat(labels[which]));
                editor.setTextSize(prefs.fontSizeSp());
            }
        }).show();
    }

    private void searchProjectDialog() {
        final Ui.Field f = Ui.field(this, pal, "Search all text files", "text to find", "", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final AlertDialog d = Ui.dialog(this, pal).setTitle("Search in project").setView(padded(f.view))
                .setNegativeButton("Cancel", null).setPositiveButton("Search", null).create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String q = f.value();
                        if (q.length() == 0) {
                            f.setError("Enter some text to search for.");
                            return;
                        }
                        d.dismiss();
                        runProjectSearch(q);
                    }
                });
            }
        });
        d.show();
    }

    private void runProjectSearch(final String query) {
        captureCurrent();
        saveAllQuietlyForSearch();
        final AlertDialog wait = Ui.progress(this, pal, "Searching…");
        wait.show();
        final TextSearch.Options opts = new TextSearch.Options(false, false, false);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                List<TextSearch.FileMatch> found = null;
                String error = null;
                try {
                    found = TextSearch.searchProject(fm, query, opts, 200);
                } catch (IOException e) {
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    error = e.getMessage();
                }
                final List<TextSearch.FileMatch> results = found;
                final String err = error;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        wait.dismiss();
                        if (isFinishing()) {
                            return;
                        }
                        showSearchResults(query, results, err);
                    }
                });
            }
        });
    }

    /** Search reads files from disk, so unsaved edits are saved first when auto-save is on; otherwise warn. */
    private void saveAllQuietlyForSearch() {
        if (hasDirty() && prefs.autoSave()) {
            saveAll();
        }
    }

    private void showSearchResults(String query, final List<TextSearch.FileMatch> results, String error) {
        if (error != null) {
            Ui.message(this, pal, "Search failed", error);
            return;
        }
        if (results == null || results.isEmpty()) {
            Ui.message(this, pal, "No results", "\"" + query + "\" was not found in the project's text files."
                    + (hasDirty() ? "\n\nUnsaved changes are not searched; save first." : ""));
            return;
        }
        String[] items = new String[results.size()];
        for (int i = 0; i < items.length; i++) {
            TextSearch.FileMatch m = results.get(i);
            items[i] = m.path + ":" + m.line + "\n" + m.lineText.trim();
        }
        Ui.dialog(this, pal).setTitle(results.size() >= 200 ? "200+ results" : results.size() + " results")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        TextSearch.FileMatch m = results.get(which);
                        openFile(m.path);
                        if (current != null && current.path.equals(m.path)) {
                            editor.goToLine(m.line);
                        }
                    }
                }).setPositiveButton("Close", null).show();
    }

    // ------------------------------------------------------------------ build

    private void startBuild() {
        if (!saveAll()) {
            return;
        }
        Intent i = new Intent(this, BuildActivity.class);
        i.putExtra(BuildActivity.EXTRA_PROJECT, project.dir.getName());
        i.putExtra(BuildActivity.EXTRA_AUTOSTART, true);
        startActivity(i);
    }
}
