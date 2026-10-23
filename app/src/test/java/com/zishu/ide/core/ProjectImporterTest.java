package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectManager;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProjectImporterTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private ProjectManager pm;

    private static final String MANIFEST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    package=\"com.acme.hello\"\n"
            + "    android:versionCode=\"7\"\n"
            + "    android:versionName=\"2.1\">\n"
            + "    <uses-sdk android:minSdkVersion=\"19\" android:targetSdkVersion=\"33\" />\n"
            + "    <application android:label=\"Hello App\">\n"
            + "        <activity android:name=\".MainActivity\">\n"
            + "            <intent-filter>\n"
            + "                <action android:name=\"android.intent.action.MAIN\" />\n"
            + "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
            + "            </intent-filter>\n"
            + "        </activity>\n"
            + "    </application>\n"
            + "</manifest>\n";

    @Before
    public void setUp() throws Exception {
        pm = new ProjectManager(new File(tmp.getRoot(), "workspace"));
    }

    private ProjectManager.ImportOutcome importZip(Map<String, String> files) throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            m.put(e.getKey(), TestSupport.utf8(e.getValue()));
        }
        return pm.importZipDetailed(new ByteArrayInputStream(TestSupport.zip(m)), "proj.zip");
    }

    private static String read(ProjectInfo i, String rel) throws Exception {
        return IoUtil.readUtf8(new File(i.dir, rel), 1 << 20);
    }

    @Test
    public void flatLayoutIsMovedIntoStandardModuleWithoutGradleFiles() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("AndroidManifest.xml", MANIFEST);
        f.put("src/com/acme/hello/MainActivity.java", "package com.acme.hello; public class MainActivity {}");
        f.put("res/values/strings.xml", "<resources/>");
        f.put("assets/a.txt", "a");
        f.put("libs/x.jar", "jar");
        f.put("README.md", "hi");
        ProjectInfo i = importZip(f).info;
        assertTrue(new File(i.dir, "app/src/main/java/com/acme/hello/MainActivity.java").isFile());
        assertTrue(new File(i.dir, "app/src/main/res/values/strings.xml").isFile());
        assertTrue(new File(i.dir, "app/src/main/assets/a.txt").isFile());
        assertTrue(new File(i.dir, "app/libs/x.jar").isFile());
        assertTrue(new File(i.dir, "README.md").isFile());
        assertFalse(new File(i.dir, "src").exists());
        assertFalse(new File(i.dir, "AndroidManifest.xml").exists());
        assertFalse(new File(i.dir, ".zishu-move").exists());
        String g = read(i, "app/build.gradle");
        assertTrue(g, g.contains("namespace 'com.acme.hello'"));
        assertTrue(g, g.contains("applicationId 'com.acme.hello'"));
        assertTrue(g, g.contains("versionCode 7"));
        assertTrue(g, g.contains("versionName '2.1'"));
        assertTrue(g, g.contains("minSdk 21"));
        assertTrue(g, g.contains("targetSdk 33"));
        assertTrue(g, g.contains("fileTree(dir: 'libs'"));
        String man = read(i, "app/src/main/AndroidManifest.xml");
        assertFalse(man, man.contains("package="));
        assertFalse(man, man.contains("uses-sdk"));
        assertFalse(man, man.contains("versionCode"));
        assertTrue(man, man.contains("android:label=\"Hello App\""));
        assertTrue(read(i, "settings.gradle").contains("include ':app'"));
        assertTrue(new File(i.dir, "build.gradle").isFile());
        assertEquals("com.acme.hello", i.applicationId);
        assertEquals("Hello App", i.appName);
        assertEquals(Language.JAVA, i.language);
    }

    @Test
    public void wrapperFolderAndModuleWithoutGradleGetsGradleFiles() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("repo-main/app/src/main/AndroidManifest.xml", MANIFEST.replace("<activity android:name", "<activity android:name"));
        f.put("repo-main/app/src/main/java/com/acme/hello/Main.kt", "class Main");
        f.put("repo-main/LICENSE", "x");
        ProjectManager.ImportOutcome o = importZip(f);
        ProjectInfo i = o.info;
        assertTrue(new File(i.dir, "app/src/main/java/com/acme/hello/Main.kt").isFile());
        assertTrue(new File(i.dir, "LICENSE").isFile());
        String g = read(i, "app/build.gradle");
        assertTrue(g, g.contains("org.jetbrains.kotlin.android"));
        assertEquals(Language.KOTLIN, i.language);
        assertTrue(new File(i.dir, "settings.gradle").isFile());
    }

    @Test
    public void moduleAtRootMovesSrcIntoApp() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("src/main/AndroidManifest.xml", MANIFEST);
        f.put("src/main/java/a/B.java", "class B {}");
        f.put("proguard-rules.pro", "-keep");
        ProjectInfo i = importZip(f).info;
        assertTrue(new File(i.dir, "app/src/main/java/a/B.java").isFile());
        assertTrue(new File(i.dir, "app/proguard-rules.pro").isFile());
        assertTrue(new File(i.dir, "app/build.gradle").isFile());
        assertFalse(new File(i.dir, "src").exists());
    }

    @Test
    public void exportedIsAddedForIntentFiltersWhenTargetIs31OrMore() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("AndroidManifest.xml", MANIFEST.replace("targetSdkVersion=\"33\"", "targetSdkVersion=\"34\""));
        f.put("src/a/B.java", "class B {}");
        ProjectInfo i = importZip(f).info;
        String man = read(i, "app/src/main/AndroidManifest.xml");
        assertTrue(man, man.contains("<activity android:exported=\"true\" android:name"));
    }

    @Test
    public void existingGradleProjectIsLeftAsItIs() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("Wrap/settings.gradle", "include ':app'");
        f.put("Wrap/build.gradle", "// root");
        f.put("Wrap/app/build.gradle", "// mine");
        f.put("Wrap/app/src/main/AndroidManifest.xml", MANIFEST);
        ProjectManager.ImportOutcome o = importZip(f);
        assertEquals("// mine", read(o.info, "app/build.gradle"));
        assertEquals("include ':app'", read(o.info, "settings.gradle"));
        assertEquals("com.acme.hello", o.info.applicationId);
    }

    @Test
    public void missingSettingsGradleIsCreatedFromModuleFolders() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("build.gradle", "// root");
        f.put("app/build.gradle", "// app");
        f.put("app/src/main/AndroidManifest.xml", MANIFEST);
        ProjectManager.ImportOutcome o = importZip(f);
        assertTrue(read(o.info, "settings.gradle").contains("include ':app'"));
        assertEquals("// app", read(o.info, "app/build.gradle"));
    }

    @Test
    public void monorepoPicksLauncherModuleAndReportsOthers() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("lib/AndroidManifest.xml", "<manifest package=\"x.lib\"><application/></manifest>");
        f.put("lib/src/x/L.java", "class L {}");
        f.put("sample/AndroidManifest.xml", MANIFEST);
        f.put("sample/src/a/S.java", "class S {}");
        ProjectManager.ImportOutcome o = importZip(f);
        assertTrue(new File(o.info.dir, "app/src/main/java/a/S.java").isFile());
        boolean mentioned = false;
        for (String n : o.notes) {
            mentioned |= n.contains("2 Android modules");
        }
        assertTrue(o.notes.toString(), mentioned);
    }

    @Test
    public void archiveWithoutManifestOrGradleIsRejectedCleanly() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("notes.txt", "x");
        f.put("src/a/B.java", "class B {}");
        try {
            importZip(f);
            fail();
        } catch (ProjectManager.NotAProjectException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("AndroidManifest.xml"));
            assertEquals(0, pm.getRoot().list().length);
        }
    }

    @Test
    public void manifestWithoutPackageGetsDerivedIdAndNote() throws Exception {
        Map<String, String> f = new LinkedHashMap<String, String>();
        f.put("AndroidManifest.xml", "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application/></manifest>");
        f.put("src/a/B.java", "class B {}");
        ProjectManager.ImportOutcome o = importZip(f);
        assertTrue(o.info.applicationId, o.info.applicationId.startsWith("com.imported."));
        boolean noted = false;
        for (String n : o.notes) {
            noted |= n.contains("no package name");
        }
        assertTrue(noted);
    }
}
