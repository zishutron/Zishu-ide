package com.zishu.ide.core.model;

/** Everything the new-project wizard collects. Plain mutable data holder with sane defaults. */
public final class ProjectConfig {
    public ProjectType type = ProjectType.NATIVE_JAVA;
    /** Directory name and display name inside the IDE. */
    public String projectName = "MyApp";
    /** android:label shown under the launcher icon. */
    public String appName = "My App";
    public String applicationId = "com.example.myapp";
    public Language language = Language.JAVA;
    public int minSdk = Defaults.MIN_SDK;
    public int targetSdk = Defaults.TARGET_SDK;
    public int compileSdk = Defaults.COMPILE_SDK;
    public String versionName = "1.0";
    public int versionCode = 1;
    public String initialActivity = "MainActivity";
    public UiApproach ui = UiApproach.XML_LAYOUT;
    /** Java source/target level used by compileOptions: 8, 11 or 17. */
    public int javaLevel = 15;
    /** Only for WEBVIEW_SITE. */
    public String webUrl = "";
    /** Only for WEBVIEW_SITE: local pages always run with JavaScript enabled. */
    public boolean webJavaScript = false;
    public String themeColor = "#5B3FD9";
    public String iconColor = "#5B3FD9";
    /** Optional launcher icon as PNG bytes (replaces the generated vector icon). */
    public byte[] iconPng;
    public String agpVersion = Defaults.AGP_VERSION;
    public String gradleVersion = Defaults.GRADLE_VERSION;
    public String kotlinVersion = Defaults.KOTLIN_VERSION;

    public ProjectConfig copy() {
        ProjectConfig c = new ProjectConfig();
        c.type = type;
        c.projectName = projectName;
        c.appName = appName;
        c.applicationId = applicationId;
        c.language = language;
        c.minSdk = minSdk;
        c.targetSdk = targetSdk;
        c.compileSdk = compileSdk;
        c.versionName = versionName;
        c.versionCode = versionCode;
        c.initialActivity = initialActivity;
        c.ui = ui;
        c.javaLevel = javaLevel;
        c.webUrl = webUrl;
        c.webJavaScript = webJavaScript;
        c.themeColor = themeColor;
        c.iconColor = iconColor;
        c.iconPng = iconPng;
        c.agpVersion = agpVersion;
        c.gradleVersion = gradleVersion;
        c.kotlinVersion = kotlinVersion;
        return c;
    }

    /** Suggested defaults for a given project type. */
    public static ProjectConfig forType(ProjectType type) {
        ProjectConfig c = new ProjectConfig();
        c.type = type;
        switch (type) {
            case NATIVE_KOTLIN:
                c.language = Language.KOTLIN;
                c.projectName = "MyKotlinApp";
                c.applicationId = "com.example.mykotlinapp";
                break;
            case WEBVIEW_LOCAL:
                c.projectName = "MyWebApp";
                c.appName = "My Web App";
                c.applicationId = "com.example.mywebapp";
                break;
            case WEBVIEW_SITE:
                c.projectName = "MySiteApp";
                c.appName = "My Site";
                c.applicationId = "com.example.mysiteapp";
                c.webUrl = "https://example.com";
                break;
            default:
                break;
        }
        return c;
    }
}
