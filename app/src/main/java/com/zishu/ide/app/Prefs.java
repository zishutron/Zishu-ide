package com.zishu.ide.app;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings. Contains no secrets; the GitHub token is kept by {@link TokenStore}. */
public final class Prefs {
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("zishu_prefs", Context.MODE_PRIVATE);
    }

    public String theme() {
        return sp.getString("theme", THEME_SYSTEM);
    }

    public void setTheme(String v) {
        sp.edit().putString("theme", v).apply();
    }

    public float fontSizeSp() {
        return sp.getFloat("fontSize", 14f);
    }

    public void setFontSizeSp(float v) {
        sp.edit().putFloat("fontSize", Math.max(9f, Math.min(28f, v))).apply();
    }

    public boolean wordWrap() {
        return sp.getBoolean("wordWrap", false);
    }

    public void setWordWrap(boolean v) {
        sp.edit().putBoolean("wordWrap", v).apply();
    }

    public boolean lineNumbers() {
        return sp.getBoolean("lineNumbers", true);
    }

    public void setLineNumbers(boolean v) {
        sp.edit().putBoolean("lineNumbers", v).apply();
    }

    public int tabSize() {
        return sp.getInt("tabSize", 4);
    }

    public void setTabSize(int v) {
        sp.edit().putInt("tabSize", v).apply();
    }

    public boolean useTabs() {
        return sp.getBoolean("useTabs", false);
    }

    public void setUseTabs(boolean v) {
        sp.edit().putBoolean("useTabs", v).apply();
    }

    public boolean autoIndent() {
        return sp.getBoolean("autoIndent", true);
    }

    public void setAutoIndent(boolean v) {
        sp.edit().putBoolean("autoIndent", v).apply();
    }

    public boolean autoSave() {
        return sp.getBoolean("autoSave", true);
    }

    public void setAutoSave(boolean v) {
        sp.edit().putBoolean("autoSave", v).apply();
    }

    public boolean highlighting() {
        return sp.getBoolean("highlighting", true);
    }

    public void setHighlighting(boolean v) {
        sp.edit().putBoolean("highlighting", v).apply();
    }

    // ---- cloud build (the token itself is NOT here)

    public String cloudRepo() {
        return sp.getString("cloudRepo", "");
    }

    public void setCloudRepo(String v) {
        sp.edit().putString("cloudRepo", v).apply();
    }

    public boolean cloudInstallWorkflow() {
        return sp.getBoolean("cloudInstallWorkflow", false);
    }

    public void setCloudInstallWorkflow(boolean v) {
        sp.edit().putBoolean("cloudInstallWorkflow", v).apply();
    }

    public boolean cloudAllowPublic() {
        return sp.getBoolean("cloudAllowPublic", false);
    }

    public void setCloudAllowPublic(boolean v) {
        sp.edit().putBoolean("cloudAllowPublic", v).apply();
    }

    // ---- project storage

    /** True (the default) when projects are kept in the visible ZishuIDE folder on the phone instead of app-private storage. */
    public boolean sharedStorage() {
        return sp.getBoolean("sharedStorage", true);
    }

    public void setSharedStorage(boolean v) {
        sp.edit().putBoolean("sharedStorage", v).apply();
    }
}
