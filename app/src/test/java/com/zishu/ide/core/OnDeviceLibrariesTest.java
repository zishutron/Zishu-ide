package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.build.BuildListener;
import com.zishu.ide.core.build.BuildRequest;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.CancelToken;
import com.zishu.ide.core.maven.MavenCache;
import com.zishu.ide.core.maven.MavenDependencies;
import com.zishu.ide.core.maven.MavenRepo;
import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.ondevice.OnDeviceBuildEngine;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

public class OnDeviceLibrariesTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, byte[]> repoFiles = new HashMap<String, byte[]>();

    private static final String APP_MANIFEST = "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <application android:label=\"x\">\n"
            + "        <activity android:name=\".MainActivity\" android:exported=\"true\"/>\n    </application>\n</manifest>\n";

    private static final String GRADLE = "android {\n namespace 'com.example.myapp'\n compileSdk 34\n"
            + " defaultConfig {\n applicationId 'com.example.myapp'\n minSdk 21\n targetSdk 34\n }\n"
            + " compileOptions {\n sourceCompatibility JavaVersion.VERSION_11\n }\n}\n"
            + "dependencies {\n implementation 'x:lib:1.0'\n}\n";

    private void publish() throws Exception {
        Map<String, byte[]> aar = new LinkedHashMap<String, byte[]>();
        aar.put("AndroidManifest.xml", TestSupport.utf8("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"x.lib\">"
                + "<uses-permission android:name=\"android.permission.VIBRATE\"/><application>"
                + "<service android:name=\".LibService\"/></application></manifest>"));
        aar.put("classes.jar", TestSupport.utf8("classes"));
        aar.put("res/values/v.xml", TestSupport.utf8("<resources><string name=\"lib_s\">a</string></resources>"));
        aar.put("assets/lib.txt", TestSupport.utf8("asset"));
        aar.put("jni/arm64-v8a/libnative.so", TestSupport.utf8("so"));
        aar.put("jni/x86/libnative.so", TestSupport.utf8("so86"));
        repoFiles.put("x/lib/1.0/lib-1.0.aar", TestSupport.zip(aar));
        repoFiles.put("x/lib/1.0/lib-1.0.pom", TestSupport.utf8("<project><groupId>x</groupId><artifactId>lib</artifactId><version>1.0</version>"
                + "<packaging>aar</packaging><dependencies><dependency><groupId>x</groupId><artifactId>base</artifactId>"
                + "<version>2.0</version></dependency></dependencies></project>"));
        repoFiles.put("x/base/2.0/base-2.0.jar", TestSupport.utf8("basejar"));
        repoFiles.put("x/base/2.0/base-2.0.pom", TestSupport.utf8("<project><groupId>x</groupId><artifactId>base</artifactId><version>2.0</version></project>"));
    }

    private MavenDependencies deps() throws IOException {
        MavenRepo repo = new MavenRepo() {
            @Override
            public String name() {
                return "fake()";
            }

            @Override
            public boolean download(String path, File dest) throws IOException {
                byte[] b = repoFiles.get(path);
                if (b == null) {
                    return false;
                }
                IoUtil.writeAtomic(dest, b);
                return true;
            }
        };
        return new MavenDependencies(new MavenCache(tmp.newFolder("cache"), Arrays.asList(repo)), tmp.newFolder("unpacked"));
    }

    private File project(String gradle) throws IOException {
        File root = tmp.newFolder("p" + System.nanoTime());
        File main = new File(root, "app/src/main");
        IoUtil.writeUtf8Atomic(new File(root, "app/build.gradle"), gradle);
        IoUtil.writeUtf8Atomic(new File(main, "AndroidManifest.xml"), APP_MANIFEST);
        IoUtil.writeUtf8Atomic(new File(main, "res/values/s.xml"), "<resources/>");
        IoUtil.writeUtf8Atomic(new File(main, "java/com/example/myapp/MainActivity.java"), "package com.example.myapp; public class MainActivity {}");
        IoUtil.writeUtf8Atomic(new File(main, "jniLibs/arm64-v8a/libapp.so"), "appso");
        return root;
    }

    private BuildResult build(File root, OnDeviceTest.FakeTools t, MavenDependencies deps) throws IOException {
        ProjectInfo info = new ProjectInfo(root, "P", ProjectType.NATIVE_JAVA, Language.JAVA, "P", "com.example.myapp", "", 0, 0);
        OnDeviceBuildEngine engine = new OnDeviceBuildEngine(t, tmp.newFolder("work" + System.nanoTime()), deps);
        return engine.build(new BuildRequest(info, "debug", tmp.newFolder("out" + System.nanoTime())), BuildListener.NONE, new CancelToken());
    }

    @Test
    public void librariesFlowIntoEveryBuildStep() throws Exception {
        publish();
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android.jar"));
        BuildResult r = build(project(GRADLE), t, deps());
        assertTrue(r.log, r.success);
        // resources: library res compiled in, its package gets an R class, assets passed on
        assertEquals(1, t.lastResources.libs.size());
        assertEquals("lib", t.lastResources.libs.get(0).name);
        assertEquals(Arrays.asList("x.lib"), t.lastResources.extraPackages);
        assertEquals(1, t.lastResources.extraAssetsDirs.size());
        // manifest merged (permission + service from the library, relative name expanded)
        assertTrue(t.lastManifest, t.lastManifest.contains("android.permission.VIBRATE"));
        assertTrue(t.lastManifest, t.lastManifest.contains("android:name=\"x.lib.LibService\""));
        // compile classpath: android.jar, then the library's classes and its dependency
        assertEquals(t.androidJar, t.classpath.get(0));
        assertEquals(3, t.classpath.size());
        assertTrue(t.classpath.toString(), t.classpath.get(1).getName().equals("base-2.0.jar"));
        assertTrue(t.classpath.toString(), t.classpath.get(2).getName().equals("classes.jar"));
        // D8 gets both as program inputs
        assertEquals(2, t.lastDexJob.libraryJars.size());
        // native libraries: arm64 from the AAR and from jniLibs, x86 skipped
        ZipFile z = new ZipFile(r.apk);
        try {
            assertTrue(z.getEntry("lib/arm64-v8a/libnative.so") != null);
            assertTrue(z.getEntry("lib/arm64-v8a/libapp.so") != null);
            assertTrue(z.getEntry("lib/x86/libnative.so") == null);
            assertTrue(z.getEntry("classes.dex") != null);
        } finally {
            z.close();
        }
        assertTrue(r.log, r.log.contains("Java language level: 11"));
        assertTrue(r.log, r.log.contains("Libraries: 2 resolved (1 AAR, 1 JAR)"));
    }

    @Test
    public void missingLibraryFailsWithClearMessage() throws Exception {
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android2.jar"));
        BuildResult r = build(project(GRADLE), t, deps());
        assertFalse(r.success);
        assertTrue(r.errorTitle, r.errorTitle.contains("Could not find x:lib:1.0"));
        assertTrue(t.calls.isEmpty());
    }

    @Test
    public void withoutRepositoriesMavenDependenciesAreStillRefused() throws Exception {
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android3.jar"));
        BuildResult r = build(project(GRADLE), t, null);
        assertFalse(r.success);
        assertTrue(r.errorTitle, r.errorTitle.contains("x:lib:1.0"));
    }

    @Test
    public void projectModulesAndAnnotationProcessorsAreRefusedByName() throws Exception {
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android4.jar"));
        MavenDependencies d = deps();
        BuildResult r = build(project(GRADLE.replace("implementation 'x:lib:1.0'", "implementation project(':core')")), t, d);
        assertFalse(r.success);
        assertTrue(r.errorTitle, r.errorTitle.contains("project(':core')"));
        r = build(project(GRADLE.replace("implementation 'x:lib:1.0'", "annotationProcessor 'a:b:1'")), t, d);
        assertFalse(r.success);
        assertTrue(r.errorTitle, r.errorTitle.contains("annotationProcessor"));
    }
}
