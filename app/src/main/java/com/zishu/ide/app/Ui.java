package com.zishu.ide.app;

import com.zishu.ide.R;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Zishu IDE's small design system, built in code: 8dp spacing grid, 12dp corner radius, 48dp
 * minimum touch targets, one accent colour. Every screen composes these pieces, which keeps
 * buttons, cards, inputs and bars consistent.
 */
public final class Ui {
    public enum Kind { FILLED, TONAL, OUTLINE, TEXT, DANGER }

    public enum Style { HEADLINE, TITLE, BODY, LABEL, CAPTION, MONO }

    public interface ChoiceListener {
        void onChosen(int index, String label);
    }

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpMargin(Context c, int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    public static LinearLayout.LayoutParams weight(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    // ------------------------------------------------------------------ drawables

    public static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) {
            g.setStroke(Math.max(1, dp(c, 1)), stroke);
        }
        return g;
    }

    public static Drawable ripple(Palette pal, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(pal.ripple), content, null);
    }

    // ------------------------------------------------------------------ text

    public static TextView text(Context c, Palette pal, CharSequence s, Style style) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(pal.text);
        t.setIncludeFontPadding(false);
        switch (style) {
            case HEADLINE:
                t.setTextSize(24);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.setLineSpacing(0, 1.1f);
                break;
            case TITLE:
                t.setTextSize(17);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                break;
            case LABEL:
                t.setTextSize(13);
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                t.setTextColor(pal.textSecondary);
                break;
            case CAPTION:
                t.setTextSize(12.5f);
                t.setTextColor(pal.textSecondary);
                t.setLineSpacing(0, 1.15f);
                break;
            case MONO:
                t.setTextSize(12);
                t.setTypeface(Typeface.MONOSPACE);
                break;
            default:
                t.setTextSize(15);
                t.setLineSpacing(0, 1.2f);
        }
        return t;
    }

    public static TextView section(Context c, Palette pal, String label) {
        TextView t = text(c, pal, label.toUpperCase(java.util.Locale.US), Style.LABEL);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), dp(c, 24), 0, dp(c, 8));
        return t;
    }

    // ------------------------------------------------------------------ icons

    public static ImageView icon(Context c, int res, int color, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** 48dp round icon button with a ripple. */
    public static ImageButton iconButton(Context c, Palette pal, int res, String description, View.OnClickListener l) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setColorFilter(pal.text);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(description);
        int pad = dp(c, 12);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(pal.ripple), null, mask));
        b.setLayoutParams(lp(dp(c, 48), dp(c, 48)));
        if (l != null) {
            b.setOnClickListener(l);
        }
        return b;
    }

    // ------------------------------------------------------------------ buttons

    public static LinearLayout button(Context c, Palette pal, String label, int iconRes, Kind kind, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        b.setClickable(true);
        b.setFocusable(true);
        int fill;
        int stroke = 0;
        int fg;
        switch (kind) {
            case FILLED:
                fill = pal.primary;
                fg = pal.onPrimary;
                break;
            case TONAL:
                fill = pal.tonal;
                fg = pal.onTonal;
                break;
            case DANGER:
                fill = 0;
                stroke = pal.error;
                fg = pal.error;
                break;
            case OUTLINE:
                fill = 0;
                stroke = pal.outline;
                fg = pal.primary;
                break;
            default:
                fill = 0;
                fg = pal.primary;
        }
        b.setBackground(ripple(pal, rounded(c, fill, stroke, 12)));
        if (iconRes != 0) {
            ImageView i = icon(c, iconRes, fg, 20);
            LinearLayout.LayoutParams ip = lp(dp(c, 20), dp(c, 20));
            ip.rightMargin = dp(c, 8);
            b.addView(i, ip);
        }
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(fg);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        b.addView(t);
        if (l != null) {
            b.setOnClickListener(l);
        }
        b.setContentDescription(label);
        return b;
    }

    /** A selectable pill, used for filters and file tabs. */
    public static TextView chip(Context c, Palette pal, String label, boolean selected, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 40));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        t.setTextColor(selected ? pal.onTonal : pal.textSecondary);
        t.setBackground(ripple(pal, rounded(c, selected ? pal.tonal : 0, selected ? 0 : pal.outline, 20)));
        t.setClickable(true);
        if (l != null) {
            t.setOnClickListener(l);
        }
        return t;
    }

    // ------------------------------------------------------------------ containers

    public static LinearLayout card(Context c, Palette pal) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(c, pal.surface, pal.outline, 16));
        card.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        return card;
    }

    public static View divider(Context c, Palette pal) {
        View v = new View(c);
        v.setBackgroundColor(pal.outline);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    public static View space(Context c, float h) {
        View v = new View(c);
        v.setLayoutParams(lp(1, dp(c, h)));
        return v;
    }

    // ------------------------------------------------------------------ app bar

    public static LinearLayout topBar(Context c, Palette pal, String title, String subtitle, int navIcon,
                                      View.OnClickListener navListener, View... actions) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(pal.surface);
        bar.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        bar.setMinimumHeight(dp(c, 56));
        if (navIcon != 0) {
            bar.addView(iconButton(c, pal, navIcon, navIcon == R.drawable.ic_arrow_back ? "Back" : "Menu", navListener));
        } else {
            bar.addView(space(c, 0), lp(dp(c, 12), 1));
        }
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(c, pal, title, Style.TITLE);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTag("title");
        titles.addView(t);
        if (subtitle != null) {
            TextView s = text(c, pal, subtitle, Style.CAPTION);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            s.setTag("subtitle");
            titles.addView(s, lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
        }
        bar.addView(titles, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (View a : actions) {
            bar.addView(a);
        }
        return bar;
    }

    // ------------------------------------------------------------------ inputs

    /** A labelled text input with an inline error line. */
    public static final class Field {
        public final LinearLayout view;
        public final EditText edit;
        public final TextView error;

        Field(LinearLayout view, EditText edit, TextView error) {
            this.view = view;
            this.edit = edit;
            this.error = error;
        }

        public String value() {
            return edit.getText().toString();
        }

        public void setError(String message) {
            if (message == null) {
                error.setVisibility(View.GONE);
            } else {
                error.setText(message);
                error.setVisibility(View.VISIBLE);
            }
        }
    }

    public static Field field(Context c, Palette pal, String label, String hint, String initial, int inputType) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(c, pal, label, Style.LABEL);
        box.addView(l, lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 0, 6));
        EditText e = new EditText(c);
        e.setText(initial);
        e.setHint(hint);
        e.setHintTextColor(pal.textSecondary);
        e.setTextColor(pal.text);
        e.setTextSize(15.5f);
        e.setInputType(inputType);
        e.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        e.setMinHeight(dp(c, 48));
        e.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        e.setBackground(rounded(c, pal.surface, pal.outline, 12));
        e.setContentDescription(label);
        box.addView(e, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView err = text(c, pal, "", Style.CAPTION);
        err.setTextColor(pal.error);
        err.setVisibility(View.GONE);
        box.addView(err, lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 4, 0, 0));
        return new Field(box, e, err);
    }

    /** A labelled button that opens a list of options; the chosen option's text is shown on the button. */
    public static final class Choice {
        public final LinearLayout view;
        private final TextView value;
        private final String[] options;
        private int selected;

        Choice(LinearLayout view, TextView value, String[] options, int selected) {
            this.view = view;
            this.value = value;
            this.options = options;
            this.selected = selected;
        }

        public int index() {
            return selected;
        }

        public String selectedLabel() {
            return options[selected];
        }

        public void select(int i) {
            selected = i;
            value.setText(options[i]);
        }
    }

    public static Choice choice(final Context c, final Palette pal, final String label, final String[] options,
                                int selected, final ChoiceListener listener) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(c, pal, label, Style.LABEL),
                lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 0, 6));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 48));
        row.setPadding(dp(c, 14), dp(c, 8), dp(c, 10), dp(c, 8));
        row.setBackground(ripple(pal, rounded(c, pal.surface, pal.outline, 12)));
        row.setClickable(true);
        row.setContentDescription(label);
        TextView value = text(c, pal, options[selected], Style.BODY);
        row.addView(value, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(icon(c, R.drawable.ic_chevron_down, pal.textSecondary, 20));
        box.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final Choice ch = new Choice(box, value, options, selected);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog(c, pal).setTitle(label).setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        ch.select(which);
                        if (listener != null) {
                            listener.onChosen(which, options[which]);
                        }
                    }
                }).show();
            }
        });
        return ch;
    }

    public static Switch toggle(Context c, Palette pal, String label, boolean checked) {
        Switch s = new Switch(c);
        s.setText(label);
        s.setTextColor(pal.text);
        s.setTextSize(15);
        s.setChecked(checked);
        s.setMinHeight(dp(c, 48));
        return s;
    }

    // ------------------------------------------------------------------ dialogs and states

    public static AlertDialog.Builder dialog(Context c, Palette pal) {
        return new AlertDialog.Builder(c, pal.dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    /** A non-cancellable "working…" dialog (ProgressDialog is deprecated). */
    public static AlertDialog progress(Context c, Palette pal, String message) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 24), dp(c, 20), dp(c, 24), dp(c, 20));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(c);
        row.addView(bar, lp(dp(c, 40), dp(c, 40)));
        TextView t = text(c, pal, message, Style.BODY);
        row.addView(t, lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16, 0, 0, 0));
        AlertDialog d = dialog(c, pal).setView(row).setCancelable(false).create();
        return d;
    }

    public static void message(Context c, Palette pal, String title, String body) {
        dialog(c, pal).setTitle(title).setMessage(body).setPositiveButton("OK", null).show();
    }

    public static View emptyState(Context c, Palette pal, int iconRes, String title, String body, View action) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 24), dp(c, 32), dp(c, 24), dp(c, 32));
        FrameLayout disc = new FrameLayout(c);
        disc.setBackground(rounded(c, pal.tonal, 0, 36));
        ImageView i = icon(c, iconRes, pal.onTonal, 32);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(c, 32), dp(c, 32), Gravity.CENTER);
        disc.addView(i, fp);
        box.addView(disc, lp(dp(c, 72), dp(c, 72)));
        TextView t = text(c, pal, title, Style.TITLE);
        t.setGravity(Gravity.CENTER);
        box.addView(t, lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16, 0, 0));
        TextView b = text(c, pal, body, Style.CAPTION);
        b.setGravity(Gravity.CENTER);
        box.addView(b, lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 6, 0, 0));
        if (action != null) {
            box.addView(action, lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 20, 0, 0));
        }
        return box;
    }

    /** An inline banner for warnings and notes. */
    public static LinearLayout banner(Context c, Palette pal, int iconRes, int accent, String title, String body) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(rounded(c, pal.surfaceAlt, 0, 12));
        row.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams ip = lp(dp(c, 22), dp(c, 22));
        ip.rightMargin = dp(c, 12);
        row.addView(icon(c, iconRes, accent, 22), ip);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        if (title != null) {
            col.addView(text(c, pal, title, Style.TITLE));
        }
        if (body != null) {
            TextView b = text(c, pal, body, Style.CAPTION);
            col.addView(b, lpMargin(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, title == null ? 0 : 4, 0, 0));
        }
        row.addView(col, weight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }
}
