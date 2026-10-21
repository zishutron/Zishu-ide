package com.zishu.ide.app;

import com.zishu.ide.R;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;

import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.WorkspaceMover;

import java.io.File;

/** Editor and appearance preferences. */
public final class SettingsActivity extends BaseActivity {
    private LinearLayout storageBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.topBar(this, pal, "Settings", null, R.drawable.ic_arrow_back, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        root.addView(Ui.divider(this, pal));
        ScrollView sv = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(24));

        page.addView(Ui.section(this, pal, "Appearance"));
        String[] themes = {"Follow system", "Light", "Dark"};
        int sel = Prefs.THEME_LIGHT.equals(prefs.theme()) ? 1 : Prefs.THEME_DARK.equals(prefs.theme()) ? 2 : 0;
        page.addView(Ui.choice(this, pal, "Theme", themes, sel, new Ui.ChoiceListener() {
            @Override
            public void onChosen(int index, String label) {
                prefs.setTheme(index == 1 ? Prefs.THEME_LIGHT : index == 2 ? Prefs.THEME_DARK : Prefs.THEME_SYSTEM);
                recreate();
            }
        }).view);

        page.addView(Ui.section(this, pal, "Editor"));
        final String[] sizes = {"12", "13", "14", "15", "16", "18", "20", "24"};
        int si = 2;
        for (int i = 0; i < sizes.length; i++) {
            if (Float.parseFloat(sizes[i]) == prefs.fontSizeSp()) {
                si = i;
            }
        }
        page.addView(Ui.choice(this, pal, "Font size (sp)", sizes, si, new Ui.ChoiceListener() {
            @Override
            public void onChosen(int index, String label) {
                prefs.setFontSizeSp(Float.parseFloat(label));
            }
        }).view, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 12));

        final String[] tabs = {"2", "4", "8"};
        int ti = prefs.tabSize() == 2 ? 0 : prefs.tabSize() == 8 ? 2 : 1;
        page.addView(Ui.choice(this, pal, "Indent size", tabs, ti, new Ui.ChoiceListener() {
            @Override
            public void onChosen(int index, String label) {
                prefs.setTabSize(Integer.parseInt(label));
            }
        }).view, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));

        page.addView(toggle("Word wrap", prefs.wordWrap(), new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setWordWrap(on);
            }
        }));
        page.addView(toggle("Line numbers", prefs.lineNumbers(), new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setLineNumbers(on);
            }
        }));
        page.addView(toggle("Syntax highlighting", prefs.highlighting(), new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setHighlighting(on);
            }
        }));
        page.addView(toggle("Auto-indent on new line", prefs.autoIndent(), new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setAutoIndent(on);
            }
        }));
        page.addView(toggle("Indent with tabs instead of spaces", prefs.useTabs(), new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setUseTabs(on);
            }
        }));
        page.addView(toggle("Save changes automatically when leaving the app", prefs.autoSave(), new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setAutoSave(on);
            }
        }));
        page.addView(Ui.text(this, pal, "Changes apply the next time a file is opened in the editor.", Ui.Style.CAPTION),
                Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 4, 0, 0));

        page.addView(Ui.section(this, pal, "Project storage"));
        String[] places = {"App only (private)", "Phone storage (ZishuIDE folder)"};
        page.addView(Ui.choice(this, pal, "Where projects are kept", places, prefs.sharedStorage() ? 1 : 0, new Ui.ChoiceListener() {
            @Override
            public void onChosen(int index, String label) {
                chooseStorage(index == 1);
            }
        }).view);
        storageBox = new LinearLayout(this);
        storageBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(storageBox, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
        renderStorage();

        page.addView(Ui.section(this, pal, "Build"));
        page.addView(Ui.button(this, pal, "Cloud build settings", R.drawable.ic_cloud, Ui.Kind.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, CloudSettingsActivity.class));
            }
        }));
        page.addView(Ui.button(this, pal, "Build tools", R.drawable.ic_chip, Ui.Kind.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, SdkActivity.class));
            }
        }), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        page.addView(Ui.section(this, pal, "About"));
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // version stays blank
        }
        page.addView(Ui.text(this, pal, "Zishu IDE " + version + "\n" + getPackageName(), Ui.Style.BODY));
        page.addView(Ui.text(this, pal, "Projects are stored in this app's private storage; no storage permission is used. "
                + "Use Export as ZIP on the home screen to back a project up.", Ui.Style.CAPTION),
                Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        sv.addView(page);
        root.addView(sv, Ui.weight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setScreen(root);
    }

    // ------------------------------------------------------------------ project storage

    @Override
    protected void onResume() {
        super.onResume();
        if (storageBox != null) {
            renderStorage();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        renderStorage();
    }

    private void chooseStorage(boolean shared) {
        prefs.setSharedStorage(shared);
        if (shared && !StorageAccess.granted(this)) {
            askForAccess();
        }
        renderStorage();
    }

    private void askForAccess() {
        Ui.dialog(this, pal).setTitle("Allow access to phone storage")
                .setMessage("To keep projects in a ZishuIDE folder that your file manager can see, Android must let Zishu IDE "
                        + "manage files on this phone. Zishu IDE only reads and writes inside its ZishuIDE folder and your own "
                        + "projects. If you do not allow it, projects stay private inside the app.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        StorageAccess.request(SettingsActivity.this);
                    }
                }).show();
    }

    private static int countProjects(File dir) {
        int n = 0;
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory() && ProjectInfo.read(k) != null) {
                    n++;
                }
            }
        }
        return n;
    }

    private void renderStorage() {
        storageBox.removeAllViews();
        boolean wantShared = prefs.sharedStorage();
        boolean granted = StorageAccess.granted(this);
        File priv = App.privateWorkspaceDir(this);
        File shared = StorageAccess.sharedDir();
        if (!wantShared) {
            storageBox.addView(Ui.text(this, pal, "Projects are kept inside the app. File managers cannot see them; use "
                    + "Export as ZIP on the home screen to copy one out.", Ui.Style.CAPTION));
            int inShared = granted ? countProjects(shared) : 0;
            if (inShared > 0) {
                addMoveButton("Move " + inShared + " project" + (inShared == 1 ? "" : "s") + " from the phone folder into the app",
                        shared, priv);
            }
            return;
        }
        if (!granted) {
            storageBox.addView(Ui.banner(this, pal, R.drawable.ic_warning, pal.warning, "Waiting for permission",
                    "Allow Zishu IDE to manage files, then come back. Until then projects stay private inside the app."));
            storageBox.addView(Ui.button(this, pal, "Allow access", R.drawable.ic_key, Ui.Kind.TONAL, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    askForAccess();
                }
            }), Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10, 0, 0));
            return;
        }
        storageBox.addView(Ui.text(this, pal, "Projects are kept in:\n" + shared.getPath()
                + "\nEach project is its own folder. Open it in any file manager, copy files in, or replace them, then reopen "
                + "the file in the editor to see the change.", Ui.Style.CAPTION));
        int inPrivate = countProjects(priv);
        if (inPrivate > 0) {
            addMoveButton("Move " + inPrivate + " project" + (inPrivate == 1 ? "" : "s") + " from the app to the phone folder",
                    priv, shared);
        }
    }

    private void addMoveButton(String label, final File from, final File to) {
        storageBox.addView(Ui.button(this, pal, label, R.drawable.ic_move, Ui.Kind.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                moveProjects(from, to);
            }
        }), Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 12, 0, 0));
    }

    private void moveProjects(final File from, final File to) {
        final android.app.AlertDialog wait = Ui.progress(this, pal, "Moving projects\u2026");
        wait.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    WorkspaceMover.Result r = WorkspaceMover.moveAll(from, to);
                    StringBuilder sb = new StringBuilder("Moved " + r.moved + " project" + (r.moved == 1 ? "" : "s") + ".");
                    for (String f : r.failed) {
                        sb.append("\nNot moved (still in the old place): ").append(f);
                    }
                    msg = sb.toString();
                } catch (java.io.IOException e) {
                    msg = "Could not move the projects: " + e.getMessage();
                }
                final String text = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        wait.dismiss();
                        Ui.message(SettingsActivity.this, pal, "Move finished", text);
                        renderStorage();
                    }
                });
            }
        }, "zishu-move").start();
    }

    private Switch toggle(String label, boolean on, CompoundButton.OnCheckedChangeListener l) {
        Switch s = Ui.toggle(this, pal, label, on);
        s.setOnCheckedChangeListener(l);
        return s;
    }
}
