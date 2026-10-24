package com.zishu.ide.app;

import com.zishu.ide.R;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.zishu.ide.core.model.Defaults;
import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.model.UiApproach;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectManager;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.validation.ConfigValidator;
import com.zishu.ide.core.validation.Issue;
import com.zishu.ide.core.validation.ValidationException;
import com.zishu.ide.core.validation.ValidationReport;

import java.io.IOException;
import java.io.InputStream;

/** The new-project wizard. Every field is validated live with the same rules the generator enforces. */
public final class NewProjectActivity extends BaseActivity {
    private static final int REQ_ICON = 10;
    private static final int REQ_WEB_ZIP = 11;
    private static final String[] TYPES = {"Native Java", "Native Kotlin", "WebView · local files", "WebView · website"};
    private static final ProjectType[] TYPE_VALUES = {ProjectType.NATIVE_JAVA, ProjectType.NATIVE_KOTLIN,
            ProjectType.WEBVIEW_LOCAL, ProjectType.WEBVIEW_SITE};
    private static final String[] TYPE_HINTS = {
            "Activities written in Java with XML or code-only screens.",
            "Activities written in Kotlin with XML or code-only screens.",
            "An app that shows your own HTML, CSS and JavaScript from inside the APK.",
            "An app that loads a website you choose."};

    private final Handler main = new Handler(Looper.getMainLooper());
    private int typeIndex;
    private boolean idEdited;
    private boolean appNameEdited;
    private byte[] iconPng;
    private Uri webZip;
    private String webZipName;

    private LinearLayout typeBox;
    private TextView typeHint;
    private Ui.Field fName;
    private Ui.Field fAppName;
    private Ui.Field fId;
    private Ui.Field fVersionName;
    private Ui.Field fVersionCode;
    private Ui.Field fActivity;
    private Ui.Field fUrl;
    private Ui.Field fTheme;
    private Ui.Field fIconColor;
    private Ui.Choice cMin;
    private Ui.Choice cTarget;
    private Ui.Choice cCompile;
    private Ui.Choice cJava;
    private Ui.Choice cUi;
    private Ui.Choice cWebSource;
    private Switch swJs;
    private LinearLayout nativeBox;
    private LinearLayout localWebBox;
    private LinearLayout siteBox;
    private TextView webZipLabel;
    private LinearLayout summary;
    private ImageView iconPreview;
    private TextView iconLabel;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        applyType(0);
    }

    private String[] sdkList() {
        String[] a = new String[Defaults.HIGHEST_COMPILE_SDK - 20];
        for (int i = 0; i < a.length; i++) {
            a[i] = String.valueOf(21 + i);
        }
        return a;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.topBar(this, pal, "New project", null, R.drawable.ic_arrow_back, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(page);
        root.addView(scroll, Ui.weight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        page.addView(Ui.section(this, pal, "Project type"));
        typeBox = new LinearLayout(this);
        typeBox.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(wrapScroll(typeBox));
        typeHint = Ui.text(this, pal, "", Ui.Style.CAPTION);
        page.addView(typeHint, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 10, 0, 0));

        page.addView(Ui.section(this, pal, "App"));
        ProjectConfig d = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        fName = Ui.field(this, pal, "Project name", "My App", d.projectName, InputType.TYPE_CLASS_TEXT);
        fAppName = Ui.field(this, pal, "App display name", "Shown under the launcher icon", d.appName, InputType.TYPE_CLASS_TEXT);
        fId = Ui.field(this, pal, "Application ID", "com.example.myapp", d.applicationId,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addGap(page, fName.view);
        addGap(page, fAppName.view);
        addGap(page, fId.view);

        nativeBox = new LinearLayout(this);
        nativeBox.setOrientation(LinearLayout.VERTICAL);
        fActivity = Ui.field(this, pal, "Initial activity", "MainActivity", d.initialActivity, InputType.TYPE_CLASS_TEXT);
        cUi = Ui.choice(this, pal, "UI approach", new String[]{UiApproach.XML_LAYOUT.label, UiApproach.PROGRAMMATIC.label}, 0, null);
        addGap(nativeBox, fActivity.view);
        addGap(nativeBox, cUi.view);
        page.addView(nativeBox);

        siteBox = new LinearLayout(this);
        siteBox.setOrientation(LinearLayout.VERTICAL);
        fUrl = Ui.field(this, pal, "Website address", "https://example.com", "https://example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addGap(siteBox, fUrl.view);
        swJs = Ui.toggle(this, pal, "Enable JavaScript (needed by most websites)", false);
        siteBox.addView(swJs);
        siteBox.addView(Ui.text(this, pal, "The app requests the INTERNET permission only for website apps. "
                + "Links to other sites open in the browser, and no native bridge is exposed to the page.", Ui.Style.CAPTION));
        page.addView(siteBox);

        localWebBox = new LinearLayout(this);
        localWebBox.setOrientation(LinearLayout.VERTICAL);
        cWebSource = Ui.choice(this, pal, "Start from", new String[]{"Sample page", "ZIP of my web files"}, 0, new Ui.ChoiceListener() {
            @Override
            public void onChosen(int index, String label) {
                updateWebZipVisibility();
                validate();
            }
        });
        addGap(localWebBox, cWebSource.view);
        webZipLabel = Ui.text(this, pal, "", Ui.Style.CAPTION);
        webZipLabel.setVisibility(View.GONE);
        localWebBox.addView(Ui.button(this, pal, "Choose ZIP…", R.drawable.ic_zip, Ui.Kind.OUTLINE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                startActivityForResult(i, REQ_WEB_ZIP);
            }
        }), Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        localWebBox.addView(webZipLabel, Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 6, 0, 0));
        localWebBox.addView(Ui.text(this, pal, "The ZIP needs an index.html at its top level (or inside one folder). "
                + "The app uses no internet permission and opens only your packaged pages.", Ui.Style.CAPTION),
                Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 6, 0, 0));
        page.addView(localWebBox);

        page.addView(Ui.section(this, pal, "Versions and SDK levels"));
        fVersionName = Ui.field(this, pal, "Version name", "1.0", d.versionName, InputType.TYPE_CLASS_TEXT);
        fVersionCode = Ui.field(this, pal, "Version code", "1", String.valueOf(d.versionCode), InputType.TYPE_CLASS_NUMBER);
        LinearLayout vr = new LinearLayout(this);
        vr.setOrientation(LinearLayout.HORIZONTAL);
        vr.addView(fVersionName.view, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams vp = Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vp.leftMargin = dp(12);
        vr.addView(fVersionCode.view, vp);
        addGap(page, vr);
        String[] sdks = sdkList();
        cMin = Ui.choice(this, pal, "Minimum SDK (minSdk)", sdks, d.minSdk - 21, null);
        cTarget = Ui.choice(this, pal, "Target SDK (targetSdk)", sdks, d.targetSdk - 21, null);
        cCompile = Ui.choice(this, pal, "Compile SDK (compileSdk)", sdks, d.compileSdk - 21, null);
        addGap(page, cMin.view);
        addGap(page, cTarget.view);
        addGap(page, cCompile.view);
        cJava = Ui.choice(this, pal, "Java language level", new String[]{"Java 8", "Java 11", "Java 15", "Java 17"}, 2, null);
        addGap(page, cJava.view);
        LinearLayout info = Ui.banner(this, pal, R.drawable.ic_chip, pal.primary, "Build system: Gradle",
                "Android Gradle Plugin " + Defaults.AGP_VERSION + ", Gradle " + Defaults.GRADLE_VERSION + ", JDK " + Defaults.JDK_VERSION
                        + ". Choosing 35 only sets the number; the cloud build installs the matching SDK Platform when it runs, "
                        + "and your app code and libraries must still be compatible with API 35.");
        page.addView(info, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        page.addView(Ui.section(this, pal, "Appearance"));
        fTheme = Ui.field(this, pal, "Theme colour", "#5B3FD9", d.themeColor, InputType.TYPE_CLASS_TEXT);
        fIconColor = Ui.field(this, pal, "Icon colour", "#5B3FD9", d.iconColor, InputType.TYPE_CLASS_TEXT);
        LinearLayout cr = new LinearLayout(this);
        cr.setOrientation(LinearLayout.HORIZONTAL);
        cr.addView(fTheme.view, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams cp = Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(12);
        cr.addView(fIconColor.view, cp);
        addGap(page, cr);
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconPreview = new ImageView(this);
        iconPreview.setBackground(Ui.rounded(this, pal.surfaceAlt, 0, 12));
        iconPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconPreview.setPadding(dp(4), dp(4), dp(4), dp(4));
        iconRow.addView(iconPreview, Ui.lp(dp(48), dp(48)));
        iconLabel = Ui.text(this, pal, "Default icon (uses the icon colour)", Ui.Style.CAPTION);
        iconRow.addView(iconLabel, Ui.lpMargin(this, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0, 0, 0));
        ((LinearLayout.LayoutParams) iconLabel.getLayoutParams()).weight = 1f;
        iconRow.addView(Ui.button(this, pal, "Choose PNG…", 0, Ui.Kind.TEXT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/png");
                startActivityForResult(i, REQ_ICON);
            }
        }));
        page.addView(iconRow, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        page.addView(summary, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setBackgroundColor(pal.surface);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(10));
        bottom.addView(Ui.button(this, pal, "Create project", R.drawable.ic_check, Ui.Kind.FILLED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                create();
            }
        }), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);

        for (int i = 0; i < TYPES.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(this, pal, TYPES[i], false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyType(idx);
                }
            });
            LinearLayout.LayoutParams p = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            p.rightMargin = dp(8);
            typeBox.addView(c, p);
        }

        TextWatcher w = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!updating) {
                    validate();
                }
            }
        };
        for (Ui.Field f : new Ui.Field[]{fAppName, fId, fVersionName, fVersionCode, fActivity, fUrl, fTheme, fIconColor}) {
            f.edit.addTextChangedListener(w);
        }
        fName.edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (updating) {
                    return;
                }
                updating = true;
                if (!appNameEdited) {
                    fAppName.edit.setText(e.toString());
                }
                if (!idEdited) {
                    fId.edit.setText(suggestId(e.toString()));
                }
                updating = false;
                validate();
            }
        });
        fAppName.edit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    appNameEdited = true;
                }
            }
        });
        fId.edit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    idEdited = true;
                }
            }
        });
        setScreen(root);
    }

    private static String suggestId(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = Character.toLowerCase(name.charAt(i));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                sb.append(ch);
            }
        }
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) {
            sb.insert(0, "app");
        }
        return "com.example." + sb;
    }

    private View wrapScroll(View inner) {
        android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.addView(inner);
        return h;
    }

    private void addGap(LinearLayout parent, View child) {
        parent.addView(child, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 14));
    }

    // ------------------------------------------------------------------ type switching

    private void applyType(int idx) {
        typeIndex = idx;
        ProjectType t = TYPE_VALUES[idx];
        for (int i = 0; i < typeBox.getChildCount(); i++) {
            TextView c = (TextView) typeBox.getChildAt(i);
            boolean sel = i == idx;
            c.setTextColor(sel ? pal.onTonal : pal.textSecondary);
            c.setBackground(Ui.ripple(pal, Ui.rounded(this, sel ? pal.tonal : 0, sel ? 0 : pal.outline, 22)));
        }
        typeHint.setText(TYPE_HINTS[idx]);
        nativeBox.setVisibility(t.isNative() ? View.VISIBLE : View.GONE);
        siteBox.setVisibility(t == ProjectType.WEBVIEW_SITE ? View.VISIBLE : View.GONE);
        localWebBox.setVisibility(t == ProjectType.WEBVIEW_LOCAL ? View.VISIBLE : View.GONE);
        cJava.view.setVisibility(View.VISIBLE);
        ProjectConfig d = ProjectConfig.forType(t);
        updating = true;
        if (!idEdited) {
            fId.edit.setText(d.applicationId);
        }
        if (fName.edit.getText().length() == 0 || isDefaultName(fName.value())) {
            fName.edit.setText(d.projectName);
            if (!appNameEdited) {
                fAppName.edit.setText(d.appName);
            }
            if (!idEdited) {
                fId.edit.setText(d.applicationId);
            }
        }
        updating = false;
        updateWebZipVisibility();
        validate();
    }

    private static boolean isDefaultName(String n) {
        return n.equals("MyApp") || n.equals("MyKotlinApp") || n.equals("MyWebApp") || n.equals("MySiteApp");
    }

    private void updateWebZipVisibility() {
        boolean zip = cWebSource.index() == 1;
        webZipLabel.setVisibility(zip ? View.VISIBLE : View.GONE);
        webZipLabel.setText(webZipName == null ? "No ZIP chosen yet" : "Selected: " + webZipName);
    }

    // ------------------------------------------------------------------ config + validation

    private ProjectConfig readConfig() {
        ProjectType t = TYPE_VALUES[typeIndex];
        ProjectConfig c = ProjectConfig.forType(t);
        c.projectName = fName.value().trim();
        c.appName = fAppName.value().trim();
        c.applicationId = fId.value().trim();
        c.versionName = fVersionName.value().trim();
        try {
            c.versionCode = Integer.parseInt(fVersionCode.value().trim());
        } catch (NumberFormatException e) {
            c.versionCode = 0;
        }
        c.minSdk = 21 + cMin.index();
        c.targetSdk = 21 + cTarget.index();
        c.compileSdk = 21 + cCompile.index();
        c.javaLevel = new int[]{8, 11, 15, 17}[cJava.index()];
        c.language = t == ProjectType.NATIVE_KOTLIN ? Language.KOTLIN : Language.JAVA;
        if (t.isNative()) {
            c.initialActivity = fActivity.value().trim();
            c.ui = cUi.index() == 0 ? UiApproach.XML_LAYOUT : UiApproach.PROGRAMMATIC;
        }
        if (t == ProjectType.WEBVIEW_SITE) {
            c.webUrl = fUrl.value().trim();
            c.webJavaScript = swJs.isChecked();
        }
        c.themeColor = fTheme.value().trim();
        c.iconColor = fIconColor.value().trim();
        c.iconPng = iconPng;
        return c;
    }

    private ValidationReport validate() {
        ProjectConfig c = readConfig();
        ValidationReport r = ConfigValidator.validate(c);
        Ui.Field[] fields = {fName, fAppName, fId, fVersionName, fVersionCode, fActivity, fUrl, fTheme, fIconColor};
        String[][] codes = {{"project-name"}, {"app-name"}, {"application-id"}, {"version-name"}, {"version-code"},
                {"activity-name"}, {"web-url"}, {"theme-color"}, {"icon-color"}};
        java.util.Set<String> handled = new java.util.HashSet<String>();
        for (int i = 0; i < fields.length; i++) {
            String msg = null;
            for (Issue is : r.errors()) {
                for (String code : codes[i]) {
                    if (code.equals(is.code) && msg == null) {
                        msg = is.message;
                        handled.add(is.code);
                    }
                }
            }
            fields[i].setError(msg);
        }
        summary.removeAllViews();
        for (Issue is : r.all()) {
            if (handled.contains(is.code) || is.severity == Issue.Severity.INFO && !is.code.equals("java-level-note")) {
                continue;
            }
            boolean err = is.severity == Issue.Severity.ERROR;
            LinearLayout b = Ui.banner(this, pal, err ? R.drawable.ic_error : R.drawable.ic_warning,
                    err ? pal.error : pal.warning, null, is.message + (is.action != null ? " " + is.action : ""));
            summary.addView(b, Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));
        }
        return r;
    }

    // ------------------------------------------------------------------ create

    private void create() {
        final ProjectConfig c = readConfig();
        ValidationReport r = validate();
        if (c.type == ProjectType.WEBVIEW_LOCAL && cWebSource.index() == 1 && webZip == null) {
            Ui.message(this, pal, "Choose your web files", "You selected \"ZIP of my web files\" but no ZIP is chosen yet.");
            return;
        }
        if (r.hasErrors()) {
            StringBuilder sb = new StringBuilder();
            for (Issue i : r.errors()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(i.format());
            }
            Ui.message(this, pal, "Fix these first", sb.toString());
            return;
        }
        boolean warn = false;
        StringBuilder wb = new StringBuilder();
        for (Issue i : r.warnings()) {
            warn = true;
            wb.append("• ").append(i.message).append('\n');
        }
        if (warn) {
            Ui.dialog(this, pal).setTitle("Create with warnings?").setMessage(wb.toString().trim())
                    .setNegativeButton("Back", null)
                    .setPositiveButton("Create anyway", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            doCreate(c);
                        }
                    }).show();
        } else {
            doCreate(c);
        }
    }

    private void doCreate(final ProjectConfig c) {
        final AlertDialog pd = Ui.progress(this, pal, "Creating project…");
        pd.show();
        final Uri zip = (c.type == ProjectType.WEBVIEW_LOCAL && cWebSource.index() == 1) ? webZip : null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                ProjectInfo info = null;
                String error = null;
                try {
                    ProjectManager pm = App.projects(NewProjectActivity.this);
                    if (zip != null) {
                        InputStream in = DocumentIo.open(getContentResolver(), zip);
                        try {
                            info = pm.createLocalWebFromZip(c, in);
                        } finally {
                            IoUtil.closeQuietly(in);
                        }
                    } else {
                        info = pm.create(c);
                    }
                } catch (ValidationException e) {
                    error = e.getReport().format();
                } catch (IOException e) {
                    error = e.getMessage();
                } catch (RuntimeException e) {
                    error = "Unexpected error: " + e;
                }
                final ProjectInfo fi = info;
                final String fe = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        pd.dismiss();
                        if (fe != null) {
                            Ui.message(NewProjectActivity.this, pal, "Could not create the project", fe);
                            return;
                        }
                        Intent i = new Intent(NewProjectActivity.this, EditorActivity.class);
                        i.putExtra(EditorActivity.EXTRA_PROJECT, fi.dir.getName());
                        startActivity(i);
                        finish();
                    }
                });
            }
        }, "zishu-create").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_WEB_ZIP) {
            webZip = uri;
            webZipName = DocumentIo.displayName(getContentResolver(), uri);
            updateWebZipVisibility();
        } else if (requestCode == REQ_ICON) {
            InputStream in = null;
            try {
                in = DocumentIo.open(getContentResolver(), uri);
                byte[] bytes = IoUtil.readAll(in, ConfigValidator.MAX_ICON_BYTES + 1L);
                if (!ConfigValidator.isPng(bytes)) {
                    Ui.message(this, pal, "Not a PNG", "Pick a PNG image (for example 512 × 512 pixels).");
                    return;
                }
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) {
                    Ui.message(this, pal, "Unreadable image", "That file could not be decoded as an image.");
                    return;
                }
                iconPng = bytes;
                iconPreview.setImageBitmap(bmp);
                iconLabel.setText(DocumentIo.displayName(getContentResolver(), uri));
                validate();
            } catch (IOException e) {
                Ui.message(this, pal, "Cannot use that icon", e.getMessage() + "\nIcons must be 1 MB or smaller.");
            } finally {
                IoUtil.closeQuietly(in);
            }
        }
    }
}
