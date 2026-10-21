package com.zishu.ide.core.template;

import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.model.UiApproach;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.validation.ConfigValidator;
import com.zishu.ide.core.validation.ValidationException;
import com.zishu.ide.core.validation.ValidationReport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates the complete file set of a new Gradle based Android project. The returned map
 * is project-relative path to file bytes. The configuration is validated first, so an invalid
 * configuration never produces a half-working project.
 *
 * Generated projects use no AndroidX libraries: activities extend android.app.Activity and the
 * theme is the platform Material theme, so the only external dependency is the Android Gradle
 * Plugin (and the Kotlin plugin for Kotlin projects).
 */
public final class ProjectTemplates {
    public static final String WEB_ROOT = "app/src/main/assets/www/";

    private ProjectTemplates() {
    }

    public static Map<String, byte[]> generate(ProjectConfig c) throws ValidationException {
        ValidationReport report = ConfigValidator.validate(c);
        if (report.hasErrors()) {
            throw new ValidationException(report);
        }
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        boolean kotlin = c.language == Language.KOTLIN;

        put(files, "settings.gradle", settingsGradle(c));
        put(files, "build.gradle", rootBuildGradle(c, kotlin));
        put(files, "gradle.properties",
                "org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\n"
                        + "android.nonTransitiveRClass=true\n");
        put(files, ".gitignore", "build/\n.gradle/\nlocal.properties\n.idea/\n*.iml\n.cxx/\n");
        put(files, "app/build.gradle", appBuildGradle(c, kotlin));

        String pkgPath = c.applicationId.replace('.', '/');
        put(files, "app/src/main/AndroidManifest.xml", manifest(c));
        put(files, "app/src/main/res/values/strings.xml", strings(c));
        put(files, "app/src/main/res/values/colors.xml", colors(c));
        put(files, "app/src/main/res/values/themes.xml", themes());
        if (c.iconPng != null) {
            files.put("app/src/main/res/drawable-xxhdpi/ic_launcher.png", c.iconPng);
        } else {
            put(files, "app/src/main/res/drawable/ic_launcher.xml", launcherIcon(c));
        }

        String srcRoot = "app/src/main/java/" + pkgPath + "/";
        switch (c.type) {
            case NATIVE_JAVA:
            case NATIVE_KOTLIN:
                if (c.ui == UiApproach.XML_LAYOUT) {
                    put(files, "app/src/main/res/layout/activity_main.xml", layoutXml());
                }
                if (kotlin) {
                    put(files, "app/src/main/java/" + pkgPath + "/" + c.initialActivity + ".kt", nativeKotlin(c));
                } else {
                    put(files, srcRoot + c.initialActivity + ".java", nativeJava(c));
                }
                break;
            case WEBVIEW_LOCAL:
                put(files, srcRoot + "MainActivity.java", webViewJavaLocal(c));
                addLocalWebAssets(files, c);
                break;
            case WEBVIEW_SITE:
                put(files, srcRoot + "MainActivity.java", webViewJavaSite(c));
                break;
            default:
                throw new IllegalStateException("Unsupported type " + c.type);
        }
        return files;
    }

    private static void put(Map<String, byte[]> files, String path, String text) {
        files.put(path, text.getBytes(IoUtil.UTF8));
    }

    // ------------------------------------------------------------------ gradle

    static String settingsGradle(ProjectConfig c) {
        return "pluginManagement {\n"
                + "    repositories {\n"
                + "        google()\n"
                + "        mavenCentral()\n"
                + "        gradlePluginPortal()\n"
                + "    }\n"
                + "}\n"
                + "dependencyResolutionManagement {\n"
                + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
                + "    repositories {\n"
                + "        google()\n"
                + "        mavenCentral()\n"
                + "    }\n"
                + "}\n"
                + "rootProject.name = '" + Escape.groovyString(c.projectName.trim()) + "'\n"
                + "include ':app'\n";
    }

    static String rootBuildGradle(ProjectConfig c, boolean kotlin) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Top-level build file. Plugin versions are declared here and applied in app/build.gradle.\n");
        sb.append("plugins {\n");
        sb.append("    id 'com.android.application' version '").append(c.agpVersion).append("' apply false\n");
        if (kotlin) {
            sb.append("    id 'org.jetbrains.kotlin.android' version '").append(c.kotlinVersion).append("' apply false\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    static String appBuildGradle(ProjectConfig c, boolean kotlin) {
        String jv = "JavaVersion.VERSION_" + (c.javaLevel == 8 ? "1_8" : String.valueOf(c.javaLevel));
        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n");
        sb.append("    id 'com.android.application'\n");
        if (kotlin) {
            sb.append("    id 'org.jetbrains.kotlin.android'\n");
        }
        sb.append("}\n\n");
        sb.append("android {\n");
        sb.append("    namespace '").append(c.applicationId).append("'\n");
        sb.append("    compileSdk ").append(c.compileSdk).append("\n\n");
        sb.append("    defaultConfig {\n");
        sb.append("        applicationId '").append(c.applicationId).append("'\n");
        sb.append("        minSdk ").append(c.minSdk).append("\n");
        sb.append("        targetSdk ").append(c.targetSdk).append("\n");
        sb.append("        versionCode ").append(c.versionCode).append("\n");
        sb.append("        versionName '").append(Escape.groovyString(c.versionName)).append("'\n");
        sb.append("    }\n\n");
        sb.append("    buildTypes {\n");
        sb.append("        release {\n");
        sb.append("            minifyEnabled false\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    compileOptions {\n");
        sb.append("        sourceCompatibility ").append(jv).append("\n");
        sb.append("        targetCompatibility ").append(jv).append("\n");
        sb.append("    }\n");
        if (kotlin) {
            sb.append("\n    kotlinOptions {\n");
            sb.append("        jvmTarget = '").append(c.javaLevel == 8 ? "1.8" : String.valueOf(c.javaLevel)).append("'\n");
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- manifest

    static String manifest(ProjectConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n\n");
        if (c.type == ProjectType.WEBVIEW_SITE) {
            sb.append("    <uses-permission android:name=\"android.permission.INTERNET\" />\n\n");
        }
        sb.append("    <application\n");
        sb.append("        android:allowBackup=\"false\"\n");
        sb.append("        android:icon=\"@drawable/ic_launcher\"\n");
        sb.append("        android:label=\"@string/app_name\"\n");
        sb.append("        android:theme=\"@style/AppTheme\"");
        if (c.type == ProjectType.WEBVIEW_SITE) {
            boolean cleartext = c.webUrl.trim().toLowerCase(java.util.Locale.US).startsWith("http://");
            sb.append("\n        android:usesCleartextTraffic=\"").append(cleartext ? "true" : "false").append("\"");
        } else if (c.type == ProjectType.WEBVIEW_LOCAL) {
            sb.append("\n        android:usesCleartextTraffic=\"false\"");
        }
        sb.append(">\n");
        String activity = c.type.isWebView() ? "MainActivity" : c.initialActivity;
        sb.append("        <activity\n");
        sb.append("            android:name=\".").append(activity).append("\"\n");
        if (c.type.isWebView()) {
            sb.append("            android:configChanges=\"orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|uiMode\"\n");
        }
        sb.append("            android:exported=\"true\">\n");
        sb.append("            <intent-filter>\n");
        sb.append("                <action android:name=\"android.intent.action.MAIN\" />\n");
        sb.append("                <category android:name=\"android.intent.category.LAUNCHER\" />\n");
        sb.append("            </intent-filter>\n");
        sb.append("        </activity>\n");
        sb.append("    </application>\n\n");
        sb.append("</manifest>\n");
        return sb.toString();
    }

    // --------------------------------------------------------------- resources

    static String strings(ProjectConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("<resources>\n");
        sb.append("    <string name=\"app_name\">").append(Escape.androidString(c.appName.trim())).append("</string>\n");
        if (c.type.isNative()) {
            sb.append("    <string name=\"hello_message\">").append(Escape.androidString("Hello from " + c.appName.trim()))
                    .append("</string>\n");
            sb.append("    <string name=\"button_label\">Tap me</string>\n");
            sb.append("    <string name=\"toast_message\">Button tapped</string>\n");
        }
        sb.append("</resources>\n");
        return sb.toString();
    }

    static String colors(ProjectConfig c) {
        return "<resources>\n"
                + "    <color name=\"colorPrimary\">" + c.themeColor.toUpperCase(java.util.Locale.US) + "</color>\n"
                + "    <color name=\"colorPrimaryDark\">" + Escape.darken(c.themeColor) + "</color>\n"
                + "    <color name=\"colorAccent\">" + c.themeColor.toUpperCase(java.util.Locale.US) + "</color>\n"
                + "</resources>\n";
    }

    static String themes() {
        return "<resources>\n"
                + "    <style name=\"AppTheme\" parent=\"android:Theme.Material.Light.NoActionBar\">\n"
                + "        <item name=\"android:colorPrimary\">@color/colorPrimary</item>\n"
                + "        <item name=\"android:colorPrimaryDark\">@color/colorPrimaryDark</item>\n"
                + "        <item name=\"android:colorAccent\">@color/colorAccent</item>\n"
                + "    </style>\n"
                + "</resources>\n";
    }

    static String launcherIcon(ProjectConfig c) {
        return "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:width=\"108dp\"\n"
                + "    android:height=\"108dp\"\n"
                + "    android:viewportWidth=\"108\"\n"
                + "    android:viewportHeight=\"108\">\n"
                + "    <path\n"
                + "        android:fillColor=\"" + c.iconColor.toUpperCase(java.util.Locale.US) + "\"\n"
                + "        android:pathData=\"M24,0H84A24,24 0 0 1 108,24V84A24,24 0 0 1 84,108H24A24,24 0 0 1 0,84V24A24,24 0 0 1 24,0Z\" />\n"
                + "    <path\n"
                + "        android:fillColor=\"#FFFFFF\"\n"
                + "        android:pathData=\"M54,30A24,24 0 1 0 54,78A24,24 0 1 0 54,30Z\" />\n"
                + "</vector>\n";
    }

    static String layoutXml() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:layout_width=\"match_parent\"\n"
                + "    android:layout_height=\"match_parent\"\n"
                + "    android:gravity=\"center\"\n"
                + "    android:orientation=\"vertical\"\n"
                + "    android:padding=\"24dp\">\n\n"
                + "    <TextView\n"
                + "        android:id=\"@+id/title\"\n"
                + "        android:layout_width=\"wrap_content\"\n"
                + "        android:layout_height=\"wrap_content\"\n"
                + "        android:text=\"@string/hello_message\"\n"
                + "        android:textSize=\"22sp\" />\n\n"
                + "    <Button\n"
                + "        android:id=\"@+id/action_button\"\n"
                + "        android:layout_width=\"wrap_content\"\n"
                + "        android:layout_height=\"wrap_content\"\n"
                + "        android:layout_marginTop=\"16dp\"\n"
                + "        android:text=\"@string/button_label\" />\n"
                + "</LinearLayout>\n";
    }

    // ------------------------------------------------------------ native code

    static String nativeJava(ProjectConfig c) {
        boolean xml = c.ui == UiApproach.XML_LAYOUT;
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(c.applicationId).append(";\n\n");
        sb.append("import android.app.Activity;\n");
        sb.append("import android.os.Build;\n");
        sb.append("import android.os.Bundle;\n");
        sb.append("import android.view.View;\n");
        sb.append("import android.view.WindowInsets;\n");
        sb.append("import android.widget.Button;\n");
        if (!xml) {
            sb.append("import android.view.Gravity;\n");
            sb.append("import android.view.ViewGroup;\n");
            sb.append("import android.widget.FrameLayout;\n");
            sb.append("import android.widget.LinearLayout;\n");
        }
        sb.append("import android.widget.TextView;\n");
        sb.append("import android.widget.Toast;\n\n");
        sb.append("public class ").append(c.initialActivity).append(" extends Activity {\n\n");
        sb.append("    @Override\n");
        sb.append("    protected void onCreate(Bundle savedInstanceState) {\n");
        sb.append("        super.onCreate(savedInstanceState);\n");
        if (xml) {
            sb.append("        setContentView(R.layout.activity_main);\n");
            sb.append("        applyEdgeToEdgeInsets(findViewById(android.R.id.content));\n\n");
            sb.append("        Button button = findViewById(R.id.action_button);\n");
        } else {
            sb.append("        float density = getResources().getDisplayMetrics().density;\n");
            sb.append("        int padding = (int) (24 * density);\n\n");
            sb.append("        LinearLayout column = new LinearLayout(this);\n");
            sb.append("        column.setOrientation(LinearLayout.VERTICAL);\n");
            sb.append("        column.setGravity(Gravity.CENTER);\n");
            sb.append("        column.setPadding(padding, padding, padding, padding);\n\n");
            sb.append("        TextView title = new TextView(this);\n");
            sb.append("        title.setText(R.string.hello_message);\n");
            sb.append("        title.setTextSize(22);\n");
            sb.append("        column.addView(title);\n\n");
            sb.append("        Button button = new Button(this);\n");
            sb.append("        button.setText(R.string.button_label);\n");
            sb.append("        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n");
            sb.append("                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);\n");
            sb.append("        params.topMargin = (int) (16 * density);\n");
            sb.append("        column.addView(button, params);\n\n");
            sb.append("        FrameLayout container = new FrameLayout(this);\n");
            sb.append("        container.addView(column, new FrameLayout.LayoutParams(\n");
            sb.append("                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));\n");
            sb.append("        setContentView(container);\n");
            sb.append("        applyEdgeToEdgeInsets(container);\n\n");
        }
        sb.append("        button.setOnClickListener(new View.OnClickListener() {\n");
        sb.append("            @Override\n");
        sb.append("            public void onClick(View v) {\n");
        sb.append("                Toast.makeText(").append(c.initialActivity).append(".this, R.string.toast_message, Toast.LENGTH_SHORT).show();\n");
        sb.append("            }\n");
        sb.append("        });\n");
        sb.append("    }\n\n");
        sb.append("    /** Android 15 (API 35) draws apps edge to edge, so keep content out from under the system bars. */\n");
        sb.append("    private static void applyEdgeToEdgeInsets(View root) {\n");
        sb.append("        if (Build.VERSION.SDK_INT < 35) {\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        sb.append("        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {\n");
        sb.append("            @Override\n");
        sb.append("            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {\n");
        sb.append("                android.graphics.Insets bars = insets.getInsets(\n");
        sb.append("                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());\n");
        sb.append("                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);\n");
        sb.append("                return WindowInsets.CONSUMED;\n");
        sb.append("            }\n");
        sb.append("        });\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    static String nativeKotlin(ProjectConfig c) {
        boolean xml = c.ui == UiApproach.XML_LAYOUT;
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(c.applicationId).append("\n\n");
        sb.append("import android.app.Activity\n");
        sb.append("import android.os.Build\n");
        sb.append("import android.os.Bundle\n");
        sb.append("import android.view.View\n");
        sb.append("import android.view.WindowInsets\n");
        sb.append("import android.widget.Button\n");
        if (!xml) {
            sb.append("import android.view.Gravity\n");
            sb.append("import android.view.ViewGroup\n");
            sb.append("import android.widget.FrameLayout\n");
            sb.append("import android.widget.LinearLayout\n");
        }
        sb.append("import android.widget.TextView\n");
        sb.append("import android.widget.Toast\n\n");
        sb.append("class ").append(c.initialActivity).append(" : Activity() {\n\n");
        sb.append("    override fun onCreate(savedInstanceState: Bundle?) {\n");
        sb.append("        super.onCreate(savedInstanceState)\n");
        if (xml) {
            sb.append("        setContentView(R.layout.activity_main)\n");
            sb.append("        applyEdgeToEdgeInsets(findViewById<View>(android.R.id.content))\n\n");
            sb.append("        val button = findViewById<Button>(R.id.action_button)\n");
        } else {
            sb.append("        val density = resources.displayMetrics.density\n");
            sb.append("        val padding = (24 * density).toInt()\n\n");
            sb.append("        val column = LinearLayout(this)\n");
            sb.append("        column.orientation = LinearLayout.VERTICAL\n");
            sb.append("        column.gravity = Gravity.CENTER\n");
            sb.append("        column.setPadding(padding, padding, padding, padding)\n\n");
            sb.append("        val title = TextView(this)\n");
            sb.append("        title.setText(R.string.hello_message)\n");
            sb.append("        title.textSize = 22f\n");
            sb.append("        column.addView(title)\n\n");
            sb.append("        val button = Button(this)\n");
            sb.append("        button.setText(R.string.button_label)\n");
            sb.append("        val params = LinearLayout.LayoutParams(\n");
            sb.append("            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT\n");
            sb.append("        )\n");
            sb.append("        params.topMargin = (16 * density).toInt()\n");
            sb.append("        column.addView(button, params)\n\n");
            sb.append("        val container = FrameLayout(this)\n");
            sb.append("        container.addView(\n");
            sb.append("            column,\n");
            sb.append("            FrameLayout.LayoutParams(\n");
            sb.append("                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT\n");
            sb.append("            )\n");
            sb.append("        )\n");
            sb.append("        setContentView(container)\n");
            sb.append("        applyEdgeToEdgeInsets(container)\n\n");
        }
        sb.append("        button.setOnClickListener {\n");
        sb.append("            Toast.makeText(this, R.string.toast_message, Toast.LENGTH_SHORT).show()\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    /** Android 15 (API 35) draws apps edge to edge, so keep content out from under the system bars. */\n");
        sb.append("    private fun applyEdgeToEdgeInsets(root: View) {\n");
        sb.append("        if (Build.VERSION.SDK_INT < 35) return\n");
        sb.append("        root.setOnApplyWindowInsetsListener { v, insets ->\n");
        sb.append("            val bars = insets.getInsets(\n");
        sb.append("                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()\n");
        sb.append("            )\n");
        sb.append("            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)\n");
        sb.append("            WindowInsets.CONSUMED\n");
        sb.append("        }\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ----------------------------------------------------------------- webview

    private static String webViewCommonImports() {
        return "import android.app.Activity;\n"
                + "import android.content.ActivityNotFoundException;\n"
                + "import android.content.Intent;\n"
                + "import android.net.Uri;\n"
                + "import android.os.Build;\n"
                + "import android.os.Bundle;\n"
                + "import android.view.View;\n"
                + "import android.view.ViewGroup;\n"
                + "import android.view.WindowInsets;\n"
                + "import android.webkit.DownloadListener;\n"
                + "import android.webkit.WebResourceError;\n"
                + "import android.webkit.WebResourceRequest;\n"
                + "import android.webkit.WebSettings;\n"
                + "import android.webkit.WebView;\n"
                + "import android.webkit.WebViewClient;\n"
                + "import android.widget.FrameLayout;\n\n";
    }

    private static String webViewHelpers() {
        return "    private void openExternal(Uri uri) {\n"
                + "        try {\n"
                + "            startActivity(new Intent(Intent.ACTION_VIEW, uri));\n"
                + "        } catch (ActivityNotFoundException ignored) {\n"
                + "            // no app can handle this link\n"
                + "        }\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    public void onBackPressed() {\n"
                + "        // onBackPressed is used (instead of the AndroidX back dispatcher) because this project has no AndroidX dependency.\n"
                + "        if (webView != null && webView.canGoBack()) {\n"
                + "            webView.goBack();\n"
                + "        } else {\n"
                + "            super.onBackPressed();\n"
                + "        }\n"
                + "    }\n\n"
                + "    @Override\n"
                + "    protected void onDestroy() {\n"
                + "        if (webView != null) {\n"
                + "            ((ViewGroup) webView.getParent()).removeView(webView);\n"
                + "            webView.destroy();\n"
                + "            webView = null;\n"
                + "        }\n"
                + "        super.onDestroy();\n"
                + "    }\n\n"
                + "    /** Android 15 (API 35) draws apps edge to edge, so keep content out from under the system bars. */\n"
                + "    private static void applyEdgeToEdgeInsets(View root) {\n"
                + "        if (Build.VERSION.SDK_INT < 35) {\n"
                + "            return;\n"
                + "        }\n"
                + "        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {\n"
                + "            @Override\n"
                + "            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {\n"
                + "                android.graphics.Insets bars = insets.getInsets(\n"
                + "                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());\n"
                + "                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);\n"
                + "                return WindowInsets.CONSUMED;\n"
                + "            }\n"
                + "        });\n"
                + "    }\n";
    }

    static String webViewJavaLocal(ProjectConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(c.applicationId).append(";\n\n");
        sb.append(webViewCommonImports());
        sb.append("/**\n");
        sb.append(" * Shows the pages packaged in assets/www. Only pages from that folder are loaded inside the\n");
        sb.append(" * WebView; web links open in the browser. No JavaScript bridge to native code is exposed.\n");
        sb.append(" */\n");
        sb.append("public class MainActivity extends Activity {\n\n");
        sb.append("    private static final String START_URL = \"file:///android_asset/www/index.html\";\n");
        sb.append("    private static final String ERROR_URL = \"file:///android_asset/www/error.html\";\n\n");
        sb.append("    private WebView webView;\n\n");
        sb.append("    @Override\n");
        sb.append("    protected void onCreate(Bundle savedInstanceState) {\n");
        sb.append("        super.onCreate(savedInstanceState);\n");
        sb.append("        webView = new WebView(this);\n");
        sb.append("        FrameLayout container = new FrameLayout(this);\n");
        sb.append("        container.addView(webView, new FrameLayout.LayoutParams(\n");
        sb.append("                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));\n");
        sb.append("        setContentView(container);\n");
        sb.append("        applyEdgeToEdgeInsets(container);\n\n");
        sb.append("        WebSettings settings = webView.getSettings();\n");
        sb.append("        settings.setJavaScriptEnabled(true); // local pages are your own code\n");
        sb.append("        settings.setAllowFileAccess(false); // does not affect file:///android_asset\n");
        sb.append("        settings.setAllowContentAccess(false);\n\n");
        sb.append("        webView.setWebViewClient(new WebViewClient() {\n");
        sb.append("            @Override\n");
        sb.append("            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {\n");
        sb.append("                return handleUrl(request.getUrl());\n");
        sb.append("            }\n\n");
        sb.append("            @SuppressWarnings(\"deprecation\") // needed on API 21-23\n");
        sb.append("            @Override\n");
        sb.append("            public boolean shouldOverrideUrlLoading(WebView view, String url) {\n");
        sb.append("                return handleUrl(Uri.parse(url));\n");
        sb.append("            }\n\n");
        sb.append("            @Override\n");
        sb.append("            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {\n");
        sb.append("                if (request.isForMainFrame()) {\n");
        sb.append("                    view.loadUrl(ERROR_URL);\n");
        sb.append("                }\n");
        sb.append("            }\n\n");
        sb.append("            @SuppressWarnings(\"deprecation\") // needed on API 21-22\n");
        sb.append("            @Override\n");
        sb.append("            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {\n");
        sb.append("                if (Build.VERSION.SDK_INT < 23 && failingUrl != null && failingUrl.equals(view.getUrl())) {\n");
        sb.append("                    view.loadUrl(ERROR_URL);\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        });\n\n");
        sb.append("        webView.loadUrl(START_URL);\n");
        sb.append("    }\n\n");
        sb.append("    /** Returns true when the URL is handled here and must not load in the WebView. */\n");
        sb.append("    private boolean handleUrl(Uri uri) {\n");
        sb.append("        String scheme = uri.getScheme();\n");
        sb.append("        if (\"file\".equals(scheme)) {\n");
        sb.append("            String path = uri.getPath();\n");
        sb.append("            boolean packaged = path != null && path.startsWith(\"/android_asset/www/\") && !path.contains(\"..\");\n");
        sb.append("            return !packaged;\n");
        sb.append("        }\n");
        sb.append("        if (\"http\".equals(scheme) || \"https\".equals(scheme) || \"mailto\".equals(scheme) || \"tel\".equals(scheme)) {\n");
        sb.append("            openExternal(uri);\n");
        sb.append("        }\n");
        sb.append("        return true;\n");
        sb.append("    }\n\n");
        sb.append(webViewHelpers());
        sb.append("}\n");
        return sb.toString();
    }

    static String webViewJavaSite(ProjectConfig c) {
        String url = c.webUrl.trim();
        String host = "";
        try {
            host = new URI(url).getHost();
        } catch (URISyntaxException ignored) {
            // validated before generation
        }
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(c.applicationId).append(";\n\n");
        sb.append(webViewCommonImports());
        sb.append("/**\n");
        sb.append(" * Loads one website. Pages on the same host stay inside the app, every other link opens in the\n");
        sb.append(" * browser. Downloads are handed to the browser. No JavaScript bridge to native code is exposed.\n");
        sb.append(" */\n");
        sb.append("public class MainActivity extends Activity {\n\n");
        sb.append("    private static final String START_URL = \"").append(Escape.javaString(url)).append("\";\n");
        sb.append("    private static final String START_HOST = \"").append(Escape.javaString(host == null ? "" : host)).append("\";\n");
        sb.append("    private static final boolean ENABLE_JAVASCRIPT = ").append(c.webJavaScript).append(";\n");
        sb.append("    private static final String OFFLINE_HTML = \"<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>\"\n");
        sb.append("            + \"<body style='font-family:sans-serif;text-align:center;padding:48px 24px;color:#333'>\"\n");
        sb.append("            + \"<h2>Can't reach the site</h2><p>Check your connection and try again.</p>\"\n");
        sb.append("            + \"<p><a href='zishu-retry:go' style='font-size:18px'>Retry</a></p></body></html>\";\n\n");
        sb.append("    private WebView webView;\n\n");
        sb.append("    @Override\n");
        sb.append("    protected void onCreate(Bundle savedInstanceState) {\n");
        sb.append("        super.onCreate(savedInstanceState);\n");
        sb.append("        webView = new WebView(this);\n");
        sb.append("        FrameLayout container = new FrameLayout(this);\n");
        sb.append("        container.addView(webView, new FrameLayout.LayoutParams(\n");
        sb.append("                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));\n");
        sb.append("        setContentView(container);\n");
        sb.append("        applyEdgeToEdgeInsets(container);\n\n");
        sb.append("        WebSettings settings = webView.getSettings();\n");
        sb.append("        settings.setJavaScriptEnabled(ENABLE_JAVASCRIPT);\n");
        sb.append("        settings.setAllowFileAccess(false);\n");
        sb.append("        settings.setAllowContentAccess(false);\n");
        sb.append("        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);\n\n");
        sb.append("        webView.setDownloadListener(new DownloadListener() {\n");
        sb.append("            @Override\n");
        sb.append("            public void onDownloadStart(String url, String userAgent, String contentDisposition,\n");
        sb.append("                                        String mimeType, long contentLength) {\n");
        sb.append("                Uri uri = Uri.parse(url);\n");
        sb.append("                if (\"http\".equals(uri.getScheme()) || \"https\".equals(uri.getScheme())) {\n");
        sb.append("                    openExternal(uri);\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        });\n\n");
        sb.append("        webView.setWebViewClient(new WebViewClient() {\n");
        sb.append("            @Override\n");
        sb.append("            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {\n");
        sb.append("                return handleUrl(request.getUrl());\n");
        sb.append("            }\n\n");
        sb.append("            @SuppressWarnings(\"deprecation\") // needed on API 21-23\n");
        sb.append("            @Override\n");
        sb.append("            public boolean shouldOverrideUrlLoading(WebView view, String url) {\n");
        sb.append("                return handleUrl(Uri.parse(url));\n");
        sb.append("            }\n\n");
        sb.append("            @Override\n");
        sb.append("            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {\n");
        sb.append("                if (request.isForMainFrame()) {\n");
        sb.append("                    showOffline(view);\n");
        sb.append("                }\n");
        sb.append("            }\n\n");
        sb.append("            @SuppressWarnings(\"deprecation\") // needed on API 21-22\n");
        sb.append("            @Override\n");
        sb.append("            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {\n");
        sb.append("                if (Build.VERSION.SDK_INT < 23 && failingUrl != null && failingUrl.equals(view.getUrl())) {\n");
        sb.append("                    showOffline(view);\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        });\n\n");
        sb.append("        webView.loadUrl(START_URL);\n");
        sb.append("    }\n\n");
        sb.append("    private void showOffline(WebView view) {\n");
        sb.append("        view.loadDataWithBaseURL(null, OFFLINE_HTML, \"text/html\", \"UTF-8\", null);\n");
        sb.append("    }\n\n");
        sb.append("    /** Returns true when the URL is handled here and must not load in the WebView. */\n");
        sb.append("    private boolean handleUrl(Uri uri) {\n");
        sb.append("        String scheme = uri.getScheme();\n");
        sb.append("        if (\"zishu-retry\".equals(scheme)) {\n");
        sb.append("            webView.loadUrl(START_URL);\n");
        sb.append("            return true;\n");
        sb.append("        }\n");
        sb.append("        if (\"http\".equals(scheme) || \"https\".equals(scheme)) {\n");
        sb.append("            String host = uri.getHost();\n");
        sb.append("            if (host != null && host.equalsIgnoreCase(START_HOST)) {\n");
        sb.append("                return false;\n");
        sb.append("            }\n");
        sb.append("            openExternal(uri);\n");
        sb.append("            return true;\n");
        sb.append("        }\n");
        sb.append("        if (\"mailto\".equals(scheme) || \"tel\".equals(scheme)) {\n");
        sb.append("            openExternal(uri);\n");
        sb.append("        }\n");
        sb.append("        return true;\n");
        sb.append("    }\n\n");
        sb.append(webViewHelpers());
        sb.append("}\n");
        return sb.toString();
    }

    private static void addLocalWebAssets(Map<String, byte[]> files, ProjectConfig c) {
        String title = Escape.xml(c.appName.trim());
        put(files, WEB_ROOT + "index.html",
                "<!DOCTYPE html>\n"
                        + "<html lang=\"en\">\n"
                        + "<head>\n"
                        + "  <meta charset=\"utf-8\">\n"
                        + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                        + "  <title>" + title + "</title>\n"
                        + "  <link rel=\"stylesheet\" href=\"css/style.css\">\n"
                        + "</head>\n"
                        + "<body>\n"
                        + "  <header>\n"
                        + "    <img src=\"images/logo.svg\" alt=\"\" width=\"56\" height=\"56\">\n"
                        + "    <h1>" + title + "</h1>\n"
                        + "  </header>\n"
                        + "  <main>\n"
                        + "    <p id=\"greeting\">Your web app is running inside a native Android shell.</p>\n"
                        + "    <button id=\"counter\" type=\"button\">Tapped 0 times</button>\n"
                        + "    <p><a href=\"about.html\">About this app</a></p>\n"
                        + "  </main>\n"
                        + "  <script src=\"js/app.js\"></script>\n"
                        + "</body>\n"
                        + "</html>\n");
        put(files, WEB_ROOT + "about.html",
                "<!DOCTYPE html>\n"
                        + "<html lang=\"en\">\n"
                        + "<head>\n"
                        + "  <meta charset=\"utf-8\">\n"
                        + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                        + "  <title>About</title>\n"
                        + "  <link rel=\"stylesheet\" href=\"css/style.css\">\n"
                        + "</head>\n"
                        + "<body>\n"
                        + "  <header><h1>About</h1></header>\n"
                        + "  <main>\n"
                        + "    <p>Edit the files in <code>app/src/main/assets/www</code> to change this app.</p>\n"
                        + "    <p><a href=\"index.html\">Back to start</a></p>\n"
                        + "  </main>\n"
                        + "</body>\n"
                        + "</html>\n");
        put(files, WEB_ROOT + "error.html",
                "<!DOCTYPE html>\n"
                        + "<html lang=\"en\">\n"
                        + "<head>\n"
                        + "  <meta charset=\"utf-8\">\n"
                        + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                        + "  <title>Page not available</title>\n"
                        + "  <link rel=\"stylesheet\" href=\"css/style.css\">\n"
                        + "</head>\n"
                        + "<body>\n"
                        + "  <header><h1>Page not available</h1></header>\n"
                        + "  <main>\n"
                        + "    <p>This page could not be loaded.</p>\n"
                        + "    <p><a href=\"index.html\">Go to the start page</a></p>\n"
                        + "  </main>\n"
                        + "</body>\n"
                        + "</html>\n");
        put(files, WEB_ROOT + "css/style.css",
                ":root {\n"
                        + "  --primary: " + c.themeColor + ";\n"
                        + "}\n"
                        + "* { box-sizing: border-box; }\n"
                        + "body {\n"
                        + "  margin: 0;\n"
                        + "  font-family: sans-serif;\n"
                        + "  line-height: 1.5;\n"
                        + "  color: #1d1b20;\n"
                        + "  background: #fbfaff;\n"
                        + "}\n"
                        + "header {\n"
                        + "  display: flex;\n"
                        + "  align-items: center;\n"
                        + "  gap: 12px;\n"
                        + "  padding: 20px 16px;\n"
                        + "  color: #fff;\n"
                        + "  background: var(--primary);\n"
                        + "}\n"
                        + "h1 { margin: 0; font-size: 1.4rem; }\n"
                        + "main { padding: 16px; max-width: 40rem; margin: 0 auto; }\n"
                        + "a { color: var(--primary); }\n"
                        + "button {\n"
                        + "  min-height: 48px;\n"
                        + "  padding: 0 20px;\n"
                        + "  font-size: 1rem;\n"
                        + "  color: #fff;\n"
                        + "  background: var(--primary);\n"
                        + "  border: 0;\n"
                        + "  border-radius: 8px;\n"
                        + "}\n");
        put(files, WEB_ROOT + "js/app.js",
                "(function () {\n"
                        + "  var count = 0;\n"
                        + "  var button = document.getElementById('counter');\n"
                        + "  if (!button) {\n"
                        + "    return;\n"
                        + "  }\n"
                        + "  button.addEventListener('click', function () {\n"
                        + "    count += 1;\n"
                        + "    button.textContent = 'Tapped ' + count + (count === 1 ? ' time' : ' times');\n"
                        + "  });\n"
                        + "})();\n");
        put(files, WEB_ROOT + "images/logo.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 56 56\">\n"
                        + "  <circle cx=\"28\" cy=\"28\" r=\"26\" fill=\"#ffffff\"/>\n"
                        + "  <circle cx=\"28\" cy=\"28\" r=\"14\" fill=\"" + c.themeColor + "\"/>\n"
                        + "</svg>\n");
    }
}
