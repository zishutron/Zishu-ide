package com.zishu.ide.app;

import com.zishu.ide.R;
import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.Toast;

/** Applies the theme, edge-to-edge insets (required on Android 15) and system-bar styling. */
public abstract class BaseActivity extends Activity {
    protected Prefs prefs;
    protected Palette pal;
    private boolean created;
    private boolean wasDark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = App.prefs(this);
        wasDark = Palette.isDark(this, prefs);
        setTheme(wasDark ? R.style.Theme_Zishu_Dark : R.style.Theme_Zishu_Light);
        super.onCreate(savedInstanceState);
        pal = Palette.of(wasDark);
        created = true;
    }

    /** Sets the screen content inside a root that handles system-bar and keyboard insets. */
    protected void setScreen(View content) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(pal.bg);
        root.addView(content, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        installInsets(root);
        setContentView(root);
        styleSystemBars();
    }

    private void installInsets(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            // From API 30 we lay out edge to edge on every version, so behaviour is the same on Android 11-15.
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                    return WindowInsets.CONSUMED;
                }
            });
        }
        // Below API 30 the decor view fits the system windows itself and adjustResize handles the keyboard.
    }

    private void styleSystemBars() {
        boolean light = !pal.dark;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            // The flag API is the only way to choose icon colour before API 30.
            @SuppressWarnings("deprecation")
            int flags = light ? (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0)) : 0;
            @SuppressWarnings("deprecation")
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(flags);
        } else {
            // API 21-22 cannot draw dark status-bar icons, so use a solid dark bar to keep them readable.
            getWindow().setStatusBarColor(0xFF2E1D8A);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (created && Palette.isDark(this, prefs) != wasDark && allowRecreate()) {
            recreate(); // system dark mode flipped while the theme preference is "follow system"
        }
    }

    /** Screens holding unsaved work return false so a system theme change cannot discard it. */
    protected boolean allowRecreate() {
        return true;
    }

    protected void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    protected int dp(float v) {
        return Ui.dp(this, v);
    }
}
