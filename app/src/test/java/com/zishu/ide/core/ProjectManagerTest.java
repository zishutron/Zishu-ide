package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectManager;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.validation.ValidationException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProjectManagerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private ProjectManager pm;

    @Before
    public void setUp() throws Exception {
        pm = new ProjectManager(new File(tmp.getRoot(), "workspace"));
    }

    @Test
    public void createWritesFilesAndMetadataAndListsProject() throws Exception {
        ProjectInfo info = pm.create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        assertTrue(new File(info.dir, "app/src/main/AndroidManifest.xml").isFile());
        assertTrue(ProjectInfo.metaFile(info.dir).isFile());
        List<ProjectInfo> all = pm.list();
        assertEquals(1, all.size());
        assertEquals("MyApp", all.get(0).name);
        assertEquals(ProjectType.NATIVE_JAVA, all.get(0).type);
        assertEquals("com.example.myapp", all.get(0).applicationId);
    }

    @Test
    public void createTwiceNeverOverwritesTheFirstProject() throws Exception {
        ProjectInfo a = pm.create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        IoUtil.writeUtf8Atomic(new File(a.dir, "app/src/main/java/com/example/myapp/MainActivity.java"), "// my edits");
        ProjectInfo b = pm.create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        assertFalse(a.dir.equals(b.dir));
        assertEquals("// my edits", IoUtil.readUtf8(new File(a.dir, "app/src/main/java/com/example/myapp/MainActivity.java"), 1000));
        assertEquals(2, pm.list().size());
    }

    @Test
    public void invalidConfigLeavesNoFolderBehind() throws Exception {
        ProjectConfig c = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c.applicationId = "bad id";
        try {
            pm.create(c);
            fail();
        } catch (ValidationException expected) {
            assertEquals(0, pm.getRoot().list().length);
        }
    }

    @Test
    public void projectNameWithSeparatorsIsSanitisedForTheFolder() throws Exception {
        ProjectConfig c = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c.projectName = "my.app-1";
        ProjectInfo info = pm.create(c);
        assertEquals(pm.getRoot().getCanonicalFile(), info.dir.getCanonicalFile().getParentFile());
    }

    private byte[] gradleZip(String wrapperFolder) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        String p = wrapperFolder == null ? "" : wrapperFolder + "/";
        m.put(p + "settings.gradle", TestSupport.utf8("include ':app'"));
        m.put(p + "build.gradle", TestSupport.utf8("// root"));
        m.put(p + "app/build.gradle", TestSupport.utf8("// app"));
        return TestSupport.zip(m);
    }

    @Test
    public void importsZipWithProjectAtTopLevel() throws Exception {
        ProjectInfo info = pm.importZip(new ByteArrayInputStream(gradleZip(null)), "sample.zip");
        assertEquals(ProjectType.IMPORTED, info.type);
        assertTrue(new File(info.dir, "settings.gradle").isFile());
        assertEquals("sample", info.name);
        assertEquals(1, pm.list().size());
    }

    @Test
    public void importsZipWithSingleWrapperFolder() throws Exception {
        ProjectInfo info = pm.importZip(new ByteArrayInputStream(gradleZip("MyProject-main")), "x.zip");
        assertTrue(new File(info.dir, "settings.gradle").isFile());
        assertFalse(new File(info.dir, "MyProject-main").exists());
    }

    @Test
    public void importZipRejectsNonProjectsAndLeavesNoTraces() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("readme.txt", TestSupport.utf8("hi"));
        try {
            pm.importZip(new ByteArrayInputStream(TestSupport.zip(m)), "nothing.zip");
            fail();
        } catch (ProjectManager.NotAProjectException expected) {
            assertEquals("workspace must be empty after a failed import", 0, pm.getRoot().list().length);
        }
    }

    @Test
    public void importZipExplainsWebOnlyArchives() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("index.html", TestSupport.utf8("<html></html>"));
        try {
            pm.importZip(new ByteArrayInputStream(TestSupport.zip(m)), "site.zip");
            fail();
        } catch (ProjectManager.NotAProjectException expected) {
            assertTrue(expected.getMessage().contains("WebView"));
        }
    }

    @Test
    public void importZipRejectsTraversalArchives() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("settings.gradle", TestSupport.utf8("x"));
        m.put("../evil.txt", TestSupport.utf8("x"));
        try {
            pm.importZip(new ByteArrayInputStream(TestSupport.zip(m)), "evil.zip");
            fail();
        } catch (com.zishu.ide.core.project.SafeZip.ZipSecurityException expected) {
            assertEquals(0, pm.getRoot().list().length);
            assertFalse(new File(tmp.getRoot(), "evil.txt").exists());
        }
    }

    @Test
    public void importedNameCollisionGetsNewFolder() throws Exception {
        ProjectInfo a = pm.importZip(new ByteArrayInputStream(gradleZip(null)), "same.zip");
        ProjectInfo b = pm.importZip(new ByteArrayInputStream(gradleZip(null)), "same.zip");
        assertFalse(a.dir.equals(b.dir));
    }

    @Test
    public void localWebProjectFromZipUsesTheUsersFiles() throws Exception {
        Map<String, byte[]> web = new LinkedHashMap<String, byte[]>();
        web.put("site/index.html", TestSupport.utf8("<h1>mine</h1>"));
        web.put("site/css/s.css", TestSupport.utf8("h1{}"));
        web.put("site/page2.html", TestSupport.utf8("<p>2</p>"));
        ProjectInfo info = pm.createLocalWebFromZip(ProjectConfig.forType(ProjectType.WEBVIEW_LOCAL),
                new ByteArrayInputStream(TestSupport.zip(web)));
        File www = new File(info.dir, "app/src/main/assets/www");
        assertEquals("<h1>mine</h1>", IoUtil.readUtf8(new File(www, "index.html"), 1000));
        assertTrue(new File(www, "css/s.css").isFile());
        assertTrue(new File(www, "page2.html").isFile());
        assertFalse("template pages must be replaced by the user's files", new File(www, "about.html").exists());
        assertTrue("error page is kept for load failures", new File(www, "error.html").isFile());
        assertTrue(new File(info.dir, "app/src/main/java/com/example/mywebapp/MainActivity.java").isFile());
    }

    @Test
    public void localWebZipWithoutIndexIsRejectedWithActionableError() throws Exception {
        Map<String, byte[]> web = new LinkedHashMap<String, byte[]>();
        web.put("page.html", TestSupport.utf8("x"));
        try {
            pm.createLocalWebFromZip(ProjectConfig.forType(ProjectType.WEBVIEW_LOCAL), new ByteArrayInputStream(TestSupport.zip(web)));
            fail();
        } catch (ValidationException expected) {
            assertTrue(expected.getReport().hasCode("web-entry"));
            assertEquals("no project folder may be created", 0, pm.getRoot().list().length);
        }
    }

    @Test
    public void exportThenImportRoundTrips() throws Exception {
        ProjectInfo a = pm.create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pm.exportZip(a, out);
        ProjectInfo b = pm.importZip(new ByteArrayInputStream(out.toByteArray()), "export.zip");
        assertEquals(IoUtil.readUtf8(new File(a.dir, "app/build.gradle"), 10000), IoUtil.readUtf8(new File(b.dir, "app/build.gradle"), 10000));
    }

    @Test
    public void deleteRemovesProjectButRefusesOutsideWorkspace() throws Exception {
        ProjectInfo a = pm.create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        pm.delete(a);
        assertFalse(a.dir.exists());
        File elsewhere = tmp.newFolder("elsewhere");
        ProjectInfo fake = new ProjectInfo(elsewhere, "x", ProjectType.IMPORTED, com.zishu.ide.core.model.Language.JAVA, "", "", "", 0, 0);
        try {
            pm.delete(fake);
            fail();
        } catch (IOException expected) {
            assertTrue(elsewhere.exists());
        }
    }

    @Test
    public void touchMovesProjectToTopOfRecents() throws Exception {
        ProjectConfig c1 = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c1.projectName = "First";
        ProjectInfo first = pm.create(c1);
        Thread.sleep(5);
        ProjectConfig c2 = ProjectConfig.forType(ProjectType.NATIVE_JAVA);
        c2.projectName = "Second";
        pm.create(c2);
        assertEquals("Second", pm.list().get(0).name);
        Thread.sleep(5);
        pm.touch(first);
        assertEquals("First", pm.list().get(0).name);
    }

    @Test
    public void findReturnsNullForMissing() {
        assertNull(pm.find("nope"));
        assertNotNull(pm.getRoot());
    }
}
