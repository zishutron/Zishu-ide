package com.zishu.ide.app;

import com.zishu.ide.R;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.CloudBuildEngine;
import com.zishu.ide.core.build.Toolchain;
import com.zishu.ide.core.model.Defaults;

import java.util.List;

/**
 * Build tools screen. It reports what is true: what this app targets, what exists on this phone
 * (nothing a build can use), and what the cloud build provides on its runner. It does not offer
 * downloads, because an Android app cannot run the JDK, Gradle or the SDK's native tools.
 */
public final class SdkActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.topBar(this, pal, "Build tools", "SDK, Gradle and build engines", R.drawable.ic_arrow_back,
                new View.OnClickListener() {
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

        final BuildEngine device = Engines.onDevice(this);
        BuildEngine.Status ds = device.status();
        page.addView(Ui.section(this, pal, "On-phone build (aapt2, ECJ, D8)"));
        page.addView(row("On-phone engine", ds.available ? "Ready" : "Unavailable", ds.available ? pal.success : pal.warning,
                ds.available ? ds.reason : ds.reason + (ds.action == null ? "" : " " + ds.action)));
        page.addView(Ui.button(this, pal, "Run toolchain self-test", R.drawable.ic_play, Ui.Kind.FILLED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final android.app.AlertDialog d = Ui.progress(SdkActivity.this, pal, "Building a sample app on this phone…");
                d.show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String report = com.zishu.ide.ondevice.SelfTest.run(SdkActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                d.dismiss();
                                Ui.message(SdkActivity.this, pal, "Self-test result", report);
                            }
                        });
                    }
                }).start();
            }
        }), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        page.addView(Ui.text(this, pal, "The self-test builds a generated sample project with the real tools and reports each stage. "
                + "It does not install anything.", Ui.Style.CAPTION),
                Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 8, 0, 0));

        page.addView(Ui.section(this, pal, "Cloud build (GitHub Actions)"));
        BuildEngine cloud = Engines.cloud(this);
        BuildEngine.Status cs = cloud.status();
        page.addView(row("GitHub Actions engine", cs.available ? "Ready" : "Not set up", cs.available ? pal.success : pal.warning,
                cs.available ? cs.reason : cs.reason + " " + cs.action));
        page.addView(Ui.button(this, pal, "Cloud build settings", R.drawable.ic_cloud, Ui.Kind.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SdkActivity.this, CloudSettingsActivity.class));
            }
        }), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));

        page.addView(Ui.section(this, pal, "Installed on the cloud runner at build time"));
        page.addView(fact("Java", "JDK " + Defaults.JDK_VERSION + " (Temurin)"));
        page.addView(fact("Android SDK platform", "android-<compileSdk> of the project, installed by the workflow (default " + Defaults.COMPILE_SDK + ")"));
        page.addView(fact("Build tools", Defaults.BUILD_TOOLS_VERSION));
        page.addView(fact("Android Gradle Plugin", Defaults.AGP_VERSION + " (project default)"));
        page.addView(fact("Gradle", Defaults.GRADLE_VERSION + " (project default; a project's own wrapper is used when present)"));
        page.addView(fact("Kotlin", Defaults.KOTLIN_VERSION + " (Kotlin project template)"));
        page.addView(Ui.text(this, pal, "SDK " + Defaults.COMPILE_SDK + " support is configured in the templates and workflow but has not been "
                + "verified by a real build in this environment. Run a build to find out.", Ui.Style.CAPTION),
                Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 8, 0, 0));

        page.addView(Ui.section(this, pal, "This device"));
        page.addView(fact("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"));
        page.addView(fact("CPU", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown"));
        BuildEngine local = Engines.local();
        BuildEngine.Status ls = local.status();
        page.addView(row("Local Gradle engine", ls.available ? "Available" : "Unavailable", ls.available ? pal.success : pal.error,
                ls.available ? ls.reason : ls.reason + (ls.action == null ? "" : " " + ls.action)));

        Toolchain tc = Toolchain.fromEnvironment();
        List<Toolchain.Component> comps = tc.check(Defaults.COMPILE_SDK, Defaults.AGP_VERSION, Defaults.GRADLE_VERSION);
        page.addView(Ui.section(this, pal, "Local toolchain detection"));
        for (Toolchain.Component c : comps) {
            int color = c.state == Toolchain.State.INSTALLED ? pal.success : c.state == Toolchain.State.INCOMPATIBLE ? pal.warning : pal.error;
            String label = c.state == Toolchain.State.INSTALLED ? "Installed" : c.state == Toolchain.State.INCOMPATIBLE ? "Incompatible" : "Missing";
            StringBuilder d = new StringBuilder();
            if (c.version != null) {
                d.append("Version ").append(c.version).append(". ");
            }
            if (c.detail != null) {
                d.append(c.detail).append(' ');
            }
            if (c.action != null && c.state != Toolchain.State.INSTALLED) {
                d.append(c.action);
            }
            page.addView(row(c.name, label, color, d.toString().trim()));
        }
        page.addView(Ui.text(this, pal, "Detection reads environment variables (JAVA_HOME, ANDROID_HOME, GRADLE_HOME). On a phone these are not set, "
                + "so everything above is reported missing. On a desktop computer where they are set, the local engine can run Gradle.",
                Ui.Style.CAPTION), Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 8, 0, 0));

        sv.addView(page);
        root.addView(sv, Ui.weight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setScreen(root);
    }

    private View row(String name, String state, int stateColor, String detail) {
        LinearLayout card = Ui.card(this, pal);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView n = Ui.text(this, pal, name, Ui.Style.BODY);
        top.addView(n, Ui.weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView s = Ui.text(this, pal, state, Ui.Style.LABEL);
        s.setTextColor(stateColor);
        top.addView(s);
        card.addView(top);
        if (detail != null && detail.length() > 0) {
            card.addView(Ui.text(this, pal, detail, Ui.Style.CAPTION),
                    Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        }
        card.setLayoutParams(Ui.lpMargin(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 8));
        return card;
    }

    private View fact(String name, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(4), dp(8), dp(4), dp(8));
        r.addView(Ui.text(this, pal, name, Ui.Style.LABEL));
        r.addView(Ui.text(this, pal, value, Ui.Style.BODY),
                Ui.lpMargin(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        return r;
    }
}
