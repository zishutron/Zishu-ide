package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.editor.Diagnostic;
import com.zishu.ide.core.editor.SyntaxChecker;
import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.model.UiApproach;
import com.zishu.ide.core.template.ProjectTemplates;
import com.zishu.ide.core.validation.ValidationException;

import org.junit.Test;

import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic.Kind;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.util.JavacTask;

public class TemplateTest {

    private static String text(Map<String, byte[]> files, String path) {
        byte[] b = files.get(path);
        assertNotNull("missing " + path, b);
        return new String(b, java.nio.charset.Charset.forName("UTF-8"));
    }

    private static ProjectConfig cfg(ProjectType t) {
        return ProjectConfig.forType(t);
    }

    /** Parses (syntax only, no symbol resolution) Java source with the JDK's own compiler front end. */
    private static List<String> javaSyntaxErrors(String name, String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("tests must run on a JDK", compiler);
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return source;
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(new StringWriter(), null, diags,
                Arrays.asList("-proc:none"), null, Arrays.asList(file));
        task.parse();
        List<String> errors = new ArrayList<String>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() == Kind.ERROR) {
                errors.add(d.getLineNumber() + ": " + d.getMessage(null));
            }
        }
        return errors;
    }

    private static void assertAllXmlWellFormed(Map<String, byte[]> files) {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (e.getKey().endsWith(".xml") || e.getKey().endsWith(".svg")) {
                Diagnostic d = SyntaxChecker.firstXmlError(text(files, e.getKey()));
                assertNull(e.getKey() + " is not well-formed XML: " + d, d);
            }
        }
    }

    private static void assertJavaSourcesParse(Map<String, byte[]> files) throws Exception {
        for (String path : files.keySet()) {
            if (path.endsWith(".java")) {
                List<String> errs = javaSyntaxErrors(path.substring(path.lastIndexOf('/') + 1), text(files, path));
                assertTrue(path + " has syntax errors: " + errs, errs.isEmpty());
            }
        }
    }

    /** Every R.string/R.layout/R.id the sources use must be defined by the generated resources. */
    private static void assertResourceReferencesResolve(Map<String, byte[]> files) {
        Set<String> strings = new HashSet<String>();
        Set<String> layouts = new HashSet<String>();
        Set<String> ids = new HashSet<String>();
        for (String path : files.keySet()) {
            if (path.endsWith("values/strings.xml")) {
                Matcher m = Pattern.compile("<string name=\"(\\w+)\"").matcher(text(files, path));
                while (m.find()) {
                    strings.add(m.group(1));
                }
            }
            if (path.contains("/res/layout/")) {
                layouts.add(path.substring(path.lastIndexOf('/') + 1).replace(".xml", ""));
                Matcher m = Pattern.compile("@\\+id/(\\w+)").matcher(text(files, path));
                while (m.find()) {
                    ids.add(m.group(1));
                }
            }
        }
        for (String path : files.keySet()) {
            if (!path.endsWith(".java") && !path.endsWith(".kt")) {
                continue;
            }
            Matcher m = Pattern.compile("(?<![\\w.])R\\.(string|layout|id)\\.(\\w+)").matcher(text(files, path));
            while (m.find()) {
                Set<String> pool = m.group(1).equals("string") ? strings : (m.group(1).equals("layout") ? layouts : ids);
                assertTrue(path + " uses undefined R." + m.group(1) + "." + m.group(2), pool.contains(m.group(2)));
            }
        }
    }

    // ------------------------------------------------------------ native java

    @Test
    public void nativeJavaXmlProjectIsComplete() throws Exception {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        Map<String, byte[]> f = ProjectTemplates.generate(c);
        for (String p : new String[]{"settings.gradle", "build.gradle", "gradle.properties", "app/build.gradle",
                "app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml",
                "app/src/main/res/values/themes.xml", "app/src/main/res/layout/activity_main.xml",
                "app/src/main/java/com/example/myapp/MainActivity.java"}) {
            assertTrue("missing " + p, f.containsKey(p));
        }
        assertAllXmlWellFormed(f);
        assertJavaSourcesParse(f);
        assertResourceReferencesResolve(f);
        String gradle = text(f, "app/build.gradle");
        assertTrue(gradle.contains("namespace 'com.example.myapp'"));
        assertTrue(gradle.contains("compileSdk 35"));
        assertTrue(gradle.contains("targetSdk 35"));
        assertTrue(gradle.contains("minSdk 21"));
        assertFalse("java project must not apply the Kotlin plugin", gradle.contains("kotlin"));
        assertTrue(text(f, "build.gradle").contains("com.android.application' version '8.7.3'"));
    }

    @Test
    public void nativeJavaProgrammaticProjectHasNoLayoutFile() throws Exception {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        c.ui = UiApproach.PROGRAMMATIC;
        Map<String, byte[]> f = ProjectTemplates.generate(c);
        assertFalse(f.containsKey("app/src/main/res/layout/activity_main.xml"));
        assertJavaSourcesParse(f);
        assertResourceReferencesResolve(f);
        assertFalse(text(f, "app/src/main/java/com/example/myapp/MainActivity.java").contains("R.layout"));
    }

    @Test
    public void javaLevelsAndSdkValuesAreWrittenIndependently() throws Exception {
        for (int level : new int[]{8, 11, 17}) {
            ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
            c.javaLevel = level;
            c.minSdk = 24;
            c.targetSdk = 34;
            c.compileSdk = 35;
            c.versionCode = 7;
            c.versionName = "2.1.0-beta";
            String g = text(ProjectTemplates.generate(c), "app/build.gradle");
            assertTrue(g.contains(level == 8 ? "VERSION_1_8" : "VERSION_" + level));
            assertTrue(g.contains("minSdk 24") && g.contains("targetSdk 34") && g.contains("compileSdk 35"));
            assertTrue(g.contains("versionCode 7") && g.contains("versionName '2.1.0-beta'"));
        }
    }

    @Test
    public void customActivityNameIsUsedInManifestAndFile() throws Exception {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        c.initialActivity = "HomeScreen";
        Map<String, byte[]> f = ProjectTemplates.generate(c);
        assertTrue(f.containsKey("app/src/main/java/com/example/myapp/HomeScreen.java"));
        assertTrue(text(f, "app/src/main/AndroidManifest.xml").contains("android:name=\".HomeScreen\""));
        assertTrue(text(f, "app/src/main/java/com/example/myapp/HomeScreen.java").contains("class HomeScreen extends Activity"));
    }

    // ----------------------------------------------------------------- kotlin

    @Test
    public void kotlinProjectUsesKotlinPluginAndKtSource() throws Exception {
        ProjectConfig c = cfg(ProjectType.NATIVE_KOTLIN);
        Map<String, byte[]> f = ProjectTemplates.generate(c);
        assertTrue(f.containsKey("app/src/main/java/com/example/mykotlinapp/MainActivity.kt"));
        assertTrue(text(f, "build.gradle").contains("org.jetbrains.kotlin.android' version '2.0.21'"));
        assertTrue(text(f, "app/build.gradle").contains("id 'org.jetbrains.kotlin.android'"));
        assertTrue(text(f, "app/build.gradle").contains("jvmTarget = '17'"));
        assertAllXmlWellFormed(f);
        assertResourceReferencesResolve(f);
    }

    // ---------------------------------------------------------------- webview

    @Test
    public void localWebViewProjectBundlesPagesAndNeedsNoPermissions() throws Exception {
        Map<String, byte[]> f = ProjectTemplates.generate(cfg(ProjectType.WEBVIEW_LOCAL));
        for (String p : new String[]{"index.html", "about.html", "error.html", "css/style.css", "js/app.js", "images/logo.svg"}) {
            assertTrue("missing web asset " + p, f.containsKey(ProjectTemplates.WEB_ROOT + p));
        }
        String manifest = text(f, "app/src/main/AndroidManifest.xml");
        assertFalse("local app must not request INTERNET", manifest.contains("uses-permission"));
        assertTrue(manifest.contains("usesCleartextTraffic=\"false\""));
        String java = text(f, "app/src/main/java/com/example/mywebapp/MainActivity.java");
        assertTrue(java.contains("file:///android_asset/www/index.html"));
        assertFalse("no JavaScript bridge may be exposed", java.contains("addJavascriptInterface"));
        assertAllXmlWellFormed(f);
        assertJavaSourcesParse(f);
        String index = text(f, ProjectTemplates.WEB_ROOT + "index.html");
        assertTrue(index.contains("href=\"about.html\"") && index.contains("css/style.css") && index.contains("js/app.js"));
    }

    @Test
    public void siteWebViewProjectRequestsInternetAndKeepsHostLocal() throws Exception {
        ProjectConfig c = cfg(ProjectType.WEBVIEW_SITE);
        c.webUrl = "https://www.example.org/start?a=1&b=$2";
        Map<String, byte[]> f = ProjectTemplates.generate(c);
        String manifest = text(f, "app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android.permission.INTERNET"));
        assertTrue(manifest.contains("usesCleartextTraffic=\"false\""));
        String java = text(f, "app/src/main/java/com/example/mysiteapp/MainActivity.java");
        assertTrue(java.contains("\"https://www.example.org/start?a=1&b=$2\""));
        assertTrue(java.contains("START_HOST = \"www.example.org\""));
        assertTrue(java.contains("ENABLE_JAVASCRIPT = false"));
        assertFalse(java.contains("addJavascriptInterface"));
        assertFalse("site apps must not bundle www assets", f.containsKey(ProjectTemplates.WEB_ROOT + "index.html"));
        assertJavaSourcesParse(f);
        assertAllXmlWellFormed(f);
    }

    @Test
    public void siteWebViewJavaScriptIsOnlyEnabledWhenRequested() throws Exception {
        ProjectConfig c = cfg(ProjectType.WEBVIEW_SITE);
        c.webJavaScript = true;
        assertTrue(text(ProjectTemplates.generate(c), "app/src/main/java/com/example/mysiteapp/MainActivity.java")
                .contains("ENABLE_JAVASCRIPT = true"));
    }

    @Test
    public void httpSiteEnablesCleartextOnlyForThatCase() throws Exception {
        ProjectConfig c = cfg(ProjectType.WEBVIEW_SITE);
        c.webUrl = "http://intranet.example.com/";
        assertTrue(text(ProjectTemplates.generate(c), "app/src/main/AndroidManifest.xml").contains("usesCleartextTraffic=\"true\""));
    }

    // --------------------------------------------------------------- escaping

    @Test
    public void hostileNamesCannotBreakGeneratedFiles() throws Exception {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        c.appName = "Tom & Jerry's <\"App\"> @home";
        Map<String, byte[]> f = ProjectTemplates.generate(c);
        assertAllXmlWellFormed(f);
        assertTrue(text(f, "app/src/main/res/values/strings.xml").contains("Tom &amp; Jerry\\'s &lt;\\\"App\\\"&gt; @home"));

        ProjectConfig w = cfg(ProjectType.WEBVIEW_LOCAL);
        w.appName = "<script>alert(1)</script>";
        Map<String, byte[]> wf = ProjectTemplates.generate(w);
        assertFalse(text(wf, ProjectTemplates.WEB_ROOT + "index.html").contains("<script>alert"));
        assertAllXmlWellFormed(wf);

        ProjectConfig s = cfg(ProjectType.WEBVIEW_SITE);
        s.webUrl = "https://example.com/a\"b";
        try {
            ProjectTemplates.generate(s);
            fail("a URL containing a double quote must be refused, not written into Java source");
        } catch (ValidationException expected) {
            assertTrue(expected.getReport().hasCode("web-url"));
        }
    }

    @Test
    public void apostropheInProjectNameIsRefusedInsteadOfEscaped() throws Exception {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        c.projectName = "It's a test";
        // apostrophes are not allowed in project names, so generation must be refused rather than escaped silently
        try {
            ProjectTemplates.generate(c);
            fail("expected a ValidationException");
        } catch (ValidationException e) {
            assertTrue(e.getReport().hasCode("project-name"));
        }
    }

    // ------------------------------------------------------------ validation

    @Test
    public void invalidConfigIsRejectedAndNothingIsGenerated() {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        c.applicationId = "NotAPackage";
        try {
            ProjectTemplates.generate(c);
            fail("expected a ValidationException");
        } catch (ValidationException e) {
            assertTrue(e.getReport().hasCode("application-id"));
            assertTrue(e.getReport().errors().get(0).action.length() > 0);
        }
    }

    @Test
    public void languageMustMatchProjectType() {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        c.language = Language.KOTLIN;
        try {
            ProjectTemplates.generate(c);
            fail();
        } catch (ValidationException e) {
            assertTrue(e.getReport().hasCode("language"));
        }
    }

    @Test
    public void customPngIconReplacesVectorIcon() throws Exception {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        byte[] png = new byte[64];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, png, 0, 8);
        c.iconPng = png;
        Map<String, byte[]> f = ProjectTemplates.generate(c);
        assertTrue(f.containsKey("app/src/main/res/drawable-xxhdpi/ic_launcher.png"));
        assertFalse(f.containsKey("app/src/main/res/drawable/ic_launcher.xml"));
    }

    @Test
    public void nonPngIconIsRejected() {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        c.iconPng = new byte[64];
        try {
            ProjectTemplates.generate(c);
            fail();
        } catch (ValidationException e) {
            assertTrue(e.getReport().hasCode("icon-png"));
        }
    }

    @Test
    public void generatedKotlinAndJavaPackageDirectoryMatchesApplicationId() throws Exception {
        ProjectConfig c = cfg(ProjectType.NATIVE_JAVA);
        c.applicationId = "org.acme.tools.demo";
        Map<String, byte[]> f = ProjectTemplates.generate(c);
        assertTrue(f.containsKey("app/src/main/java/org/acme/tools/demo/MainActivity.java"));
        assertEquals(true, text(f, "app/src/main/java/org/acme/tools/demo/MainActivity.java").startsWith("package org.acme.tools.demo;"));
    }
}
