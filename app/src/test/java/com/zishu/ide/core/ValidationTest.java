package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectManager;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.validation.ConfigValidator;
import com.zishu.ide.core.validation.GradleCompat;
import com.zishu.ide.core.validation.Issue;
import com.zishu.ide.core.validation.ProjectValidator;
import com.zishu.ide.core.validation.ValidationReport;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class ValidationTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private ProjectManager pm;

    @Before
    public void setUp() throws Exception {
        pm = new ProjectManager(new File(tmp.getRoot(), "ws"));
    }

    // ---- application id

    @Test
    public void acceptsGoodApplicationIds() {
        for (String id : new String[]{"com.example.app", "a.b", "com.My_App.v2", "org.x1.y2"}) {
            assertNull(id, ConfigValidator.checkApplicationId(id));
        }
    }

    @Test
    public void rejectsBadApplicationIds() {
        for (String id : new String[]{null, "", "single", "com..app", ".com.app", "com.app.", "1com.app", "com.1app",
                "com.my-app", "com.my app", "com.app$", "com.class.x", "com.example.int", "com.exämple.app"}) {
            assertTrue("should reject '" + id + "'", ConfigValidator.checkApplicationId(id) != null);
        }
    }

    // ---- sdk levels

    @Test
    public void sdkOrderingAndRangesAreEnforced() {
        ProjectConfig c = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c.minSdk = 30;
        c.targetSdk = 28;
        assertTrue(ConfigValidator.validate(c).hasCode("sdk-order"));

        c = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c.targetSdk = 35;
        c.compileSdk = 34;
        assertTrue(ConfigValidator.validate(c).hasCode("sdk-order"));

        c = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c.compileSdk = 36;
        assertTrue(ConfigValidator.validate(c).hasCode("compile-sdk"));

        c = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c.minSdk = 19;
        assertTrue(ConfigValidator.validate(c).hasCode("min-sdk"));
    }

    @Test
    public void defaultsAreValidForEveryType() {
        for (ProjectType t : new ProjectType[]{ProjectType.NATIVE_JAVA, ProjectType.NATIVE_KOTLIN,
                ProjectType.WEBVIEW_LOCAL, ProjectType.WEBVIEW_SITE}) {
            ValidationReport r = ConfigValidator.validate(ProjectConfig.forType(t));
            assertFalse(t + ": " + r.format(), r.hasErrors());
        }
    }

    @Test
    public void webViewUrlValidation() {
        String[] bad = {"", "   ", "example.com", "ftp://example.com", "javascript:alert(1)", "https://", "https://user:pw@example.com",
                "https://exa mple.com", "file:///etc/passwd"};
        for (String url : bad) {
            ProjectConfig c = ProjectConfig.forType(ProjectType.WEBVIEW_SITE);
            c.webUrl = url;
            assertTrue("should reject url '" + url + "'", ConfigValidator.validate(c).hasCode("web-url"));
        }
        ProjectConfig ok = ProjectConfig.forType(ProjectType.WEBVIEW_SITE);
        ok.webUrl = "https://example.com/path?q=1#frag";
        assertFalse(ConfigValidator.validate(ok).hasErrors());
        ok.webUrl = "http://example.com";
        ValidationReport r = ConfigValidator.validate(ok);
        assertFalse(r.hasErrors());
        assertTrue("cleartext must at least be flagged", r.hasCode("web-url-cleartext"));
    }

    @Test
    public void everyErrorCarriesAnAction() {
        ProjectConfig c = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c.projectName = "";
        c.appName = "";
        c.applicationId = "x";
        c.minSdk = 99;
        c.versionCode = 0;
        c.versionName = "";
        c.initialActivity = "1bad";
        c.javaLevel = 9;
        c.themeColor = "red";
        ValidationReport r = ConfigValidator.validate(c);
        assertTrue(r.errors().size() >= 8);
        for (Issue i : r.errors()) {
            assertTrue(i.code + " has no action", i.action != null && i.action.length() > 0);
        }
        assertTrue(r.format().contains("ERROR:") && r.format().contains("ACTION:"));
    }

    // ---- gradle compatibility

    @Test
    public void gradleAgpCompatibility() {
        assertEquals("8.9", GradleCompat.minGradleForAgp("8.7.3"));
        assertEquals("8.0", GradleCompat.minGradleForAgp("8.1.4"));
        assertNull(GradleCompat.minGradleForAgp("99.1.0"));
        ValidationReport r = new ValidationReport();
        GradleCompat.check("8.7.3", "8.5", 35, r);
        assertTrue(r.hasCode("gradle-agp-incompatible"));
        assertTrue(r.errors().get(0).format().contains("Gradle 8.9"));

        r = new ValidationReport();
        GradleCompat.check("8.7.3", "8.9", 35, r);
        assertFalse(r.hasErrors());
        r = new ValidationReport();
        GradleCompat.check("8.7.3", "8.14.3", 35, r);
        assertFalse("newer Gradle than the minimum must be accepted", r.hasErrors());
        r = new ValidationReport();
        GradleCompat.check("8.1.0", "8.0", 35, r);
        assertTrue("compileSdk 35 with AGP 8.1 must be flagged", r.hasCode("agp-compilesdk"));
        r = new ValidationReport();
        GradleCompat.check("99.0.0", "8.9", 35, r);
        assertTrue("unknown AGP must be reported as not validated, not silently accepted", r.hasCode("agp-not-validated"));
        r = new ValidationReport();
        GradleCompat.check(null, "8.9", 35, r);
        assertFalse(r.hasErrors());
    }

    @Test
    public void versionComparisonIsNumeric() {
        assertTrue(GradleCompat.compareVersions("8.10", "8.9") > 0);
        assertTrue(GradleCompat.compareVersions("8.9", "8.9.0") == 0);
        assertTrue(GradleCompat.compareVersions("8.7.3", "8.7.10") < 0);
        assertTrue(GradleCompat.compareVersions("8.9-rc-1", "8.9") == 0);
    }

    @Test
    public void jdkRequirementByAgpGeneration() {
        assertEquals(17, GradleCompat.minJdkForAgp("8.7.3"));
        assertEquals(11, GradleCompat.minJdkForAgp("7.4.2"));
        assertEquals(0, GradleCompat.minJdkForAgp(""));
    }

    // ---- project validator

    private ProjectInfo fresh(ProjectType t) throws Exception {
        return pm.create(ProjectConfig.forType(t));
    }

    @Test
    public void freshProjectsFromEveryTemplateValidateWithoutErrors() throws Exception {
        for (ProjectType t : new ProjectType[]{ProjectType.NATIVE_JAVA, ProjectType.NATIVE_KOTLIN,
                ProjectType.WEBVIEW_LOCAL, ProjectType.WEBVIEW_SITE}) {
            ValidationReport r = ProjectValidator.validate(fresh(t));
            assertFalse(t + " should validate cleanly:\n" + r.format(), r.hasErrors());
        }
    }

    @Test
    public void missingManifestIsReportedWithAction() throws Exception {
        ProjectInfo p = fresh(ProjectType.NATIVE_JAVA);
        assertTrue(new File(p.dir, "app/src/main/AndroidManifest.xml").delete());
        ValidationReport r = ProjectValidator.validate(p);
        assertTrue(r.hasCode("no-manifest"));
        assertTrue(r.errors().get(0).action.length() > 0);
    }

    @Test
    public void brokenManifestXmlIsReportedWithLine() throws Exception {
        ProjectInfo p = fresh(ProjectType.NATIVE_JAVA);
        IoUtil.writeUtf8Atomic(new File(p.dir, "app/src/main/AndroidManifest.xml"),
                "<manifest>\n  <application>\n</manifest>\n");
        ValidationReport r = ProjectValidator.validate(p);
        assertTrue(r.hasCode("xml-invalid"));
        Issue i = r.errors().get(0);
        assertEquals("app/src/main/AndroidManifest.xml", i.path);
        assertTrue(i.line >= 1);
    }

    @Test
    public void invalidResourceXmlIsFound() throws Exception {
        ProjectInfo p = fresh(ProjectType.NATIVE_JAVA);
        IoUtil.writeUtf8Atomic(new File(p.dir, "app/src/main/res/layout/activity_main.xml"), "<LinearLayout><TextView></LinearLayout>");
        assertTrue(ProjectValidator.validate(p).hasCode("xml-invalid"));
    }

    @Test
    public void incompatibleWrapperGradleIsDetected() throws Exception {
        ProjectInfo p = fresh(ProjectType.NATIVE_JAVA);
        File wrapper = new File(p.dir, "gradle/wrapper/gradle-wrapper.properties");
        IoUtil.writeUtf8Atomic(wrapper, "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.6-bin.zip\n");
        ValidationReport r = ProjectValidator.validate(p);
        assertTrue(r.hasCode("gradle-agp-incompatible"));
        assertTrue(r.format().contains("ACTION"));
    }

    @Test
    public void kotlinFilesWithoutKotlinPluginAreFlagged() throws Exception {
        ProjectInfo p = fresh(ProjectType.NATIVE_JAVA);
        IoUtil.writeUtf8Atomic(new File(p.dir, "app/src/main/java/com/example/myapp/Extra.kt"), "class Extra");
        assertTrue(ProjectValidator.validate(p).hasCode("kotlin-plugin-missing"));
    }

    @Test
    public void webProjectWithoutIndexHtmlIsFlagged() throws Exception {
        ProjectInfo p = fresh(ProjectType.WEBVIEW_LOCAL);
        assertTrue(new File(p.dir, "app/src/main/assets/www/index.html").delete());
        assertTrue(ProjectValidator.validate(p).hasCode("web-entry"));
    }

    @Test
    public void projectWithoutGradleFilesIsRejected() throws Exception {
        File dir = tmp.newFolder("empty");
        ProjectInfo p = new ProjectInfo(dir, "empty", ProjectType.IMPORTED, com.zishu.ide.core.model.Language.JAVA, "", "", "", 0, 0);
        assertTrue(ProjectValidator.validate(p).hasCode("no-gradle"));
    }

    @Test
    public void invalidApplicationIdInBuildFileIsFlagged() throws Exception {
        ProjectInfo p = fresh(ProjectType.NATIVE_JAVA);
        File g = new File(p.dir, "app/build.gradle");
        IoUtil.writeUtf8Atomic(g, IoUtil.readUtf8(g, 100000).replace("applicationId 'com.example.myapp'", "applicationId 'bad id'"));
        assertTrue(ProjectValidator.validate(p).hasCode("application-id"));
    }
}
