package com.zishu.ide.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/** Colour tokens for the light and dark themes. One violet accent, otherwise neutral. */
public final class Palette {
    public final boolean dark;
    public final int bg;
    public final int surface;
    public final int surfaceAlt;
    public final int outline;
    public final int text;
    public final int textSecondary;
    public final int primary;
    public final int onPrimary;
    public final int tonal;
    public final int onTonal;
    public final int ripple;
    public final int error;
    public final int warning;
    public final int success;
    public final int scrim;

    // editor
    public final int codeBg;
    public final int gutterBg;
    public final int gutterText;
    public final int currentLine;
    public final int selection;
    public final int matchBg;
    public final int findBg;
    public final int findCurrentBg;
    public final int keyword;
    public final int type;
    public final int string;
    public final int comment;
    public final int number;
    public final int annotation;
    public final int tag;
    public final int attribute;
    public final int property;
    public final int heading;

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = c("#121218");
            surface = c("#1B1B24");
            surfaceAlt = c("#242430");
            outline = c("#383846");
            text = c("#E8E6F0");
            textSecondary = c("#A6A3B5");
            primary = c("#B7A6FF");
            onPrimary = c("#1F1050");
            tonal = c("#2E2850");
            onTonal = c("#DCD4FF");
            ripple = c("#33FFFFFF");
            error = c("#FF8A80");
            warning = c("#FFC66D");
            success = c("#7FD99A");
            scrim = c("#99000000");
            codeBg = c("#14141B");
            gutterBg = c("#181821");
            gutterText = c("#6F6C80");
            currentLine = c("#1D1D29");
            selection = c("#553F3A99");
            matchBg = c("#66B7A6FF");
            findBg = c("#55FFC66D");
            findCurrentBg = c("#AAFFC66D");
            keyword = c("#C9A7FF");
            type = c("#7FD1E8");
            string = c("#9CD68A");
            comment = c("#7A7890");
            number = c("#F2A66B");
            annotation = c("#E6C07B");
            tag = c("#8FB4FF");
            attribute = c("#E6A0C4");
            property = c("#8FB4FF");
            heading = c("#C9A7FF");
        } else {
            bg = c("#FAF9FD");
            surface = c("#FFFFFF");
            surfaceAlt = c("#F1EFF8");
            outline = c("#DAD6E8");
            text = c("#1D1B26");
            textSecondary = c("#625F73");
            primary = c("#4B2FC4");
            onPrimary = c("#FFFFFF");
            tonal = c("#E8E2FF");
            onTonal = c("#2A1A80");
            ripple = c("#1F4B2FC4");
            error = c("#B3261E");
            warning = c("#8A5A00");
            success = c("#1B6E3A");
            scrim = c("#66000000");
            codeBg = c("#FFFFFF");
            gutterBg = c("#F4F2FA");
            gutterText = c("#8D89A0");
            currentLine = c("#F5F3FF");
            selection = c("#664B2FC4");
            matchBg = c("#554B2FC4");
            findBg = c("#66FFD54F");
            findCurrentBg = c("#CCFFB300");
            keyword = c("#6A3FD0");
            type = c("#0B6E8A");
            string = c("#2E7D32");
            comment = c("#8A879B");
            number = c("#B5530B");
            annotation = c("#8A6A00");
            tag = c("#1F55C4");
            attribute = c("#A8326B");
            property = c("#1F55C4");
            heading = c("#6A3FD0");
        }
    }

    private static int c(String hex) {
        return Color.parseColor(hex);
    }

    private static final Palette LIGHT = new Palette(false);
    private static final Palette DARK = new Palette(true);

    public static Palette of(boolean dark) {
        return dark ? DARK : LIGHT;
    }

    public static boolean isDark(Context ctx, Prefs prefs) {
        String t = prefs.theme();
        if (Prefs.THEME_DARK.equals(t)) {
            return true;
        }
        if (Prefs.THEME_LIGHT.equals(t)) {
            return false;
        }
        int night = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }
}
