package com.zishu.ide.core.model;

public enum ProjectType {
    NATIVE_JAVA("Native Java", true, false),
    NATIVE_KOTLIN("Native Kotlin", true, false),
    WEBVIEW_LOCAL("WebView (local files)", false, true),
    WEBVIEW_SITE("WebView (website)", false, true),
    IMPORTED("Imported project", true, false);

    public final String label;
    private final boolean nativeApp;
    private final boolean webView;

    ProjectType(String label, boolean nativeApp, boolean webView) {
        this.label = label;
        this.nativeApp = nativeApp;
        this.webView = webView;
    }

    public boolean isNative() {
        return nativeApp;
    }

    public boolean isWebView() {
        return webView;
    }

    public static ProjectType fromName(String name, ProjectType fallback) {
        if (name != null) {
            for (ProjectType t : values()) {
                if (t.name().equals(name)) {
                    return t;
                }
            }
        }
        return fallback;
    }
}
