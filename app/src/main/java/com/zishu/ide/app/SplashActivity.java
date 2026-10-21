package com.zishu.ide.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zishu.ide.R;

/** White splash screen: the logo centred, "Zishu IDE" beneath it, then the dashboard. */
public final class SplashActivity extends Activity {
    private static final long SHOW_MS = 900;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            if (started || isFinishing()) {
                return;
            }
            started = true;
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.zishu_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("Zishu IDE logo");
        col.addView(logo, new LinearLayout.LayoutParams((int) (120 * d), (int) (120 * d)));

        TextView name = new TextView(this);
        name.setText("Zishu IDE");
        name.setTextColor(0xFF1B1B1F);
        name.setTextSize(26);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = (int) (20 * d);
        col.addView(name, np);

        root.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        darkSystemBarIcons();
        handler.postDelayed(next, SHOW_MS);
    }

    private void darkSystemBarIcons() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(mask, mask);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            @SuppressWarnings("deprecation")
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            @SuppressWarnings("deprecation")
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(next);
    }
}
