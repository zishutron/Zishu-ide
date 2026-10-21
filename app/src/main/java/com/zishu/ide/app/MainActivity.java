package com.zishu.ide.app;

import com.zishu.ide.R;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The dashboard: create, open and import projects, recent projects, build and tools shortcuts. */
public final class MainActivity extends BaseActivity {
    private static final int REQ_IMPORT_ZIP = 1;
    private static final int REQ_OPEN_TREE = 2;
    private static final int REQ_EXPORT_ZIP = 3;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_NATIVE = 1;
    private static final int FILTER_WEB = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout recentBox;
    private LinearLayout cloudBanner;
    private int filter = FILTER_ALL;
    private ProjectInfo exporting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // centred header: title, tagline, logo, "Get started"
        TextView title = Ui.text(this, pal, "Zishu IDE", Ui.Style.HEADLINE);
        title.setGravity(Gravity.CENTER);
        page.addView(title, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));
        TextView tag = Ui.text(this, pal, "Your ideas, anywhere", Ui.Style.BODY);
        tag.setTextColor(pal.textSecondary);
        tag.setGravity(Gravity.CENTER);
        page.addView(tag, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.zishu_launcher);
        logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("Zishu IDE logo");
        LinearLayout.LayoutParams lpLogo = Ui.lp(dp(112), dp(112));
        lpLogo.gravity = Gravity.CENTER_HORIZONTAL;
        lpLogo.topMargin = dp(28);
        page.addView(logo, lpLogo);

        TextView started = Ui.text(this, pal, "Get started", Ui.Style.TITLE);
        started.setTextSize(20);
        started.setGravity(Gravity.CENTER);
        page.addView(started, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 28, 0, 0));
        TextView sub = Ui.text(this, pal, "Start your new Android project!", Ui.Style.BODY);
        sub.setGravity(Gravity.CENTER);
        page.addView(sub, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 20));

        // menu list (one full-width row per action)
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.addView(menuRow("Create project", R.drawable.ic_add, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NewProjectActivity.class));
            }
        }));
        menu.addView(menuRow("Open existing project", R.drawable.ic_folder, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickProject(false);
            }
        }));
        menu.addView(menuRow("Open folder from device", R.drawable.ic_download, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFolder();
            }
        }));
        menu.addView(menuRow("Import ZIP", R.drawable.ic_zip, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importZip();
            }
        }));
        menu.addView(menuRow("Build APK", R.drawable.ic_package, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickProject(true);
            }
        }));
        menu.addView(menuRow("Build tools", R.drawable.ic_chip, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SdkActivity.class));
            }
        }));
        menu.addView(menuRow("Cloud build", R.drawable.ic_cloud, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CloudSettingsActivity.class));
            }
        }));
        menu.addView(menuRow("Settings", R.drawable.ic_settings, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }));
        LinearLayout.LayoutParams mp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        page.addView(menu, mp);

        cloudBanner = new LinearLayout(this);
        cloudBanner.setOrientation(LinearLayout.VERTICAL);
        page.addView(cloudBanner, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));

        page.addView(Ui.section(this, pal, "Recent projects"));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setTag("chips");
        page.addView(chips, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(recentBox);

        setScreen(scroll);
    }

    private View menuRow(String label, int icon, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(0), dp(20), dp(0));
        r.setMinimumHeight(dp(60));
        r.setBackground(Ui.ripple(pal, Ui.rounded(this, pal.surface, pal.dark ? 0 : pal.outline, 16)));
        r.setClickable(true);
        r.setFocusable(true);
        r.setContentDescription(label);
        r.addView(Ui.icon(this, icon, pal.text, 24));
        TextView tv = Ui.text(this, pal, label, Ui.Style.TITLE);
        tv.setTextSize(17);
        r.addView(tv, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16, 0, 0, 0));
        r.setOnClickListener(l);
        LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        r.setLayoutParams(p);
        return r;
    }

    // ------------------------------------------------------------------ list

    private void refresh() {
        // cloud banner
        cloudBanner.removeAllViews();
        com.zishu.ide.core.build.BuildEngine.Status st = Engines.cloud(this).status();
        if (!st.available) {
            LinearLayout b = Ui.banner(this, pal, R.drawable.ic_cloud, pal.warning, "Cloud build is not set up",
                    st.reason + " Tap to set it up.");
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, CloudSettingsActivity.class));
                }
            });
            cloudBanner.addView(b);
        }

        LinearLayout chips = (LinearLayout) ((ViewGroup) recentBox.getParent()).findViewWithTag("chips");
        chips.removeAllViews();
        String[] names = {"All", "Native Android", "WebView"};
        for (int i = 0; i < names.length; i++) {
            final int f = i;
            TextView c = Ui.chip(this, pal, names[i], filter == i, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    filter = f;
                    refresh();
                }
            });
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            p.rightMargin = dp(8);
            chips.addView(c, p);
        }

        recentBox.removeAllViews();
        List<ProjectInfo> all;
        try {
            all = App.projects(this).list();
        } catch (IOException e) {
            all = new ArrayList<ProjectInfo>();
            toast("Cannot read the workspace: " + e.getMessage());
        }
        List<ProjectInfo> shown = new ArrayList<ProjectInfo>();
        for (ProjectInfo p : all) {
            boolean web = p.type.isWebView();
            if (filter == FILTER_ALL || (filter == FILTER_WEB && web) || (filter == FILTER_NATIVE && !web)) {
                shown.add(p);
            }
        }
        if (all.isEmpty()) {
            recentBox.addView(Ui.emptyState(this, pal, R.drawable.ic_code, "No projects yet",
                    "Create a Java, Kotlin or WebView app, or import a project from a ZIP file or a folder.",
                    Ui.button(this, pal, "Create your first project", R.drawable.ic_add, Ui.Kind.TONAL, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            startActivity(new Intent(MainActivity.this, NewProjectActivity.class));
                        }
                    })));
            return;
        }
        if (shown.isEmpty()) {
            TextView t = Ui.text(this, pal, "No projects of this kind yet.", Ui.Style.CAPTION);
            t.setPadding(dp(4), dp(8), 0, 0);
            recentBox.addView(t);
            return;
        }
        for (ProjectInfo p : shown) {
            recentBox.addView(projectCard(p), Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 10));
        }
    }

    private View projectCard(final ProjectInfo p) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(4), dp(8));
        row.setMinimumHeight(dp(72));
        row.setBackground(Ui.ripple(pal, Ui.rounded(this, pal.surface, pal.outline, 16)));
        row.setClickable(true);
        row.setFocusable(true);
        int icon = p.type.isWebView() ? R.drawable.ic_web : R.drawable.ic_phone;
        android.widget.FrameLayout disc = new android.widget.FrameLayout(this);
        disc.setBackground(Ui.rounded(this, pal.tonal, 0, 12));
        disc.addView(Ui.icon(this, icon, pal.onTonal, 22), new android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        row.addView(disc, Ui.lp(dp(44), dp(44)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(this, pal, p.name, Ui.Style.TITLE);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        String detail = p.type.label + (p.applicationId.length() > 0 ? " · " + p.applicationId : "");
        TextView sub = Ui.text(this, pal, detail, Ui.Style.CAPTION);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(sub, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 3, 0, 0));
        TextView when = Ui.text(this, pal, "Opened " + DateUtils.getRelativeTimeSpanString(p.lastOpened,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), Ui.Style.CAPTION);
        col.addView(when, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        LinearLayout.LayoutParams cp = Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(12);
        row.addView(col, cp);
        final View more = Ui.iconButton(this, pal, R.drawable.ic_more, "Project actions", null);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                projectMenu(v, p);
            }
        });
        row.addView(more);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProject(p);
            }
        });
        return row;
    }

    private void projectMenu(View anchor, final ProjectInfo p) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Open");
        m.getMenu().add(0, 2, 1, "Build APK");
        m.getMenu().add(0, 3, 2, "Export as ZIP");
        m.getMenu().add(0, 4, 3, "Delete");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        openProject(p);
                        break;
                    case 2:
                        startBuildScreen(p);
                        break;
                    case 3:
                        exportZip(p);
                        break;
                    default:
                        confirmDelete(p);
                }
                return true;
            }
        });
        m.show();
    }

    private void openProject(ProjectInfo p) {
        try {
            App.projects(this).touch(p);
        } catch (IOException ignored) {
            // opening still works without updating the recent time
        }
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_PROJECT, p.dir.getName());
        startActivity(i);
    }

    private void startBuildScreen(ProjectInfo p) {
        Intent i = new Intent(this, BuildActivity.class);
        i.putExtra(BuildActivity.EXTRA_PROJECT, p.dir.getName());
        startActivity(i);
    }

    private void pickProject(final boolean forBuild) {
        List<ProjectInfo> all;
        try {
            all = App.projects(this).list();
        } catch (IOException e) {
            toast(e.getMessage());
            return;
        }
        if (all.isEmpty()) {
            Ui.message(this, pal, "No projects", forBuild ? "Create or import a project first, then build it from here."
                    : "There are no projects on this phone yet. Create one, or import a ZIP or folder.");
            return;
        }
        final List<ProjectInfo> list = all;
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = list.get(i).name;
        }
        Ui.dialog(this, pal).setTitle(forBuild ? "Build which project?" : "Open which project?")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (forBuild) {
                            startBuildScreen(list.get(which));
                        } else {
                            openProject(list.get(which));
                        }
                    }
                }).show();
    }

    private void confirmDelete(final ProjectInfo p) {
        Ui.dialog(this, pal).setTitle("Delete \"" + p.name + "\"?")
                .setMessage("This permanently deletes the project and its build history from this phone. "
                        + "Export it as a ZIP first if you want a copy.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            App.projects(MainActivity.this).delete(p);
                        } catch (IOException e) {
                            toast("Could not delete: " + e.getMessage());
                        }
                        refresh();
                    }
                }).show();
    }

    // ------------------------------------------------------------------ import / export

    private void importZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(i, REQ_IMPORT_ZIP);
    }

    private void openFolder() {
        Ui.dialog(this, pal).setTitle("Open a project folder")
                .setMessage("Zishu IDE copies the folder you pick into its own private workspace. Your original files are not changed, "
                        + "and build output folders (build, .gradle, .git) are skipped.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose folder", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_OPEN_TREE);
                    }
                }).show();
    }

    private void exportZip(ProjectInfo p) {
        exporting = p;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, p.name.replace(' ', '_') + ".zip");
        startActivityForResult(i, REQ_EXPORT_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_ZIP) {
            runWithProgress("Importing project…", new Job() {
                @Override
                public String run() throws Exception {
                    InputStream in = DocumentIo.open(getContentResolver(), uri);
                    try {
                        ProjectInfo info = App.projects(MainActivity.this).importZip(in, DocumentIo.displayName(getContentResolver(), uri));
                        return "Imported \"" + info.name + "\"";
                    } finally {
                        com.zishu.ide.core.util.IoUtil.closeQuietly(in);
                    }
                }
            });
        } else if (requestCode == REQ_OPEN_TREE) {
            runWithProgress("Copying project folder…", new Job() {
                @Override
                public String run() throws Exception {
                    ProjectManager pm = App.projects(MainActivity.this);
                    String name = android.provider.DocumentsContract.getTreeDocumentId(uri);
                    name = name.substring(name.lastIndexOf(':') + 1);
                    name = name.substring(name.lastIndexOf('/') + 1);
                    File dest = pm.uniqueDir(name.length() == 0 ? "project" : name);
                    DocumentIo.importTree(getContentResolver(), uri, dest, new DocumentIo.Limits());
                    try {
                        ProjectInfo info = pm.registerImported(dest, dest.getName());
                        return "Opened \"" + info.name + "\"";
                    } catch (IOException e) {
                        com.zishu.ide.core.util.IoUtil.deleteRecursively(dest);
                        throw e;
                    }
                }
            });
        } else if (requestCode == REQ_EXPORT_ZIP && exporting != null) {
            final ProjectInfo p = exporting;
            runWithProgress("Exporting ZIP…", new Job() {
                @Override
                public String run() throws Exception {
                    java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                    if (out == null) {
                        throw new IOException("Cannot write to the selected location");
                    }
                    try {
                        App.projects(MainActivity.this).exportZip(p, out);
                    } finally {
                        com.zishu.ide.core.util.IoUtil.closeQuietly(out);
                    }
                    return "Exported \"" + p.name + "\"";
                }
            });
        }
    }

    private interface Job {
        String run() throws Exception;
    }

    private void runWithProgress(String message, final Job job) {
        final AlertDialog pd = Ui.progress(this, pal, message);
        pd.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String ok = null;
                String err = null;
                try {
                    ok = job.run();
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fok = ok;
                final String ferr = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            pd.dismiss();
                        } catch (RuntimeException ignored) {
                            // activity already gone
                        }
                        if (ferr != null) {
                            Ui.message(MainActivity.this, pal, "That did not work", ferr);
                        } else {
                            toast(fok);
                        }
                        refresh();
                    }
                });
            }
        }, "zishu-io").start();
    }
}
