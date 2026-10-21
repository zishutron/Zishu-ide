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

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.zishu_launcher);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(logo, Ui.lp(dp(44), dp(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, pal, "Zishu IDE", Ui.Style.HEADLINE));
        titles.addView(Ui.text(this, pal, "Build Android apps on your phone", Ui.Style.CAPTION),
                Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        header.addView(titles, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) titles.getLayoutParams()).leftMargin = dp(12);
        header.addView(Ui.iconButton(this, pal, R.drawable.ic_settings, "Settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }));
        page.addView(header);

        page.addView(Ui.space(this, 20));
        page.addView(Ui.button(this, pal, "Create new project", R.drawable.ic_add, Ui.Kind.FILLED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NewProjectActivity.class));
            }
        }), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // quick actions
        page.addView(Ui.space(this, 12));
        LinearLayout row1 = tileRow(
                tile("Open folder", R.drawable.ic_folder, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openFolder();
                    }
                }),
                tile("Import ZIP", R.drawable.ic_zip, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        importZip();
                    }
                }));
        page.addView(row1);
        page.addView(Ui.space(this, 12));
        LinearLayout row2 = tileRow(
                tile("Build APK", R.drawable.ic_package, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickProjectToBuild();
                    }
                }),
                tile("Build tools", R.drawable.ic_chip, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, SdkActivity.class));
                    }
                }));
        page.addView(row2);

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

    private View tile(String label, int icon, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setMinimumHeight(dp(88));
        t.setBackground(Ui.ripple(pal, Ui.rounded(this, pal.surface, pal.outline, 16)));
        t.setClickable(true);
        t.setFocusable(true);
        t.setContentDescription(label);
        t.addView(Ui.icon(this, icon, pal.primary, 24));
        TextView tv = Ui.text(this, pal, label, Ui.Style.TITLE);
        t.addView(tv, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout tileRow(View a, View b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pa = Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pa.rightMargin = dp(6);
        LinearLayout.LayoutParams pb = Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pb.leftMargin = dp(6);
        row.addView(a, pa);
        row.addView(b, pb);
        return row;
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

    private void pickProjectToBuild() {
        List<ProjectInfo> all;
        try {
            all = App.projects(this).list();
        } catch (IOException e) {
            toast(e.getMessage());
            return;
        }
        if (all.isEmpty()) {
            Ui.message(this, pal, "No projects", "Create or import a project first, then build it from here.");
            return;
        }
        final List<ProjectInfo> list = all;
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = list.get(i).name;
        }
        Ui.dialog(this, pal).setTitle("Build which project?").setItems(names, new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                startBuildScreen(list.get(which));
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
