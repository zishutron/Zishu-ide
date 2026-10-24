package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.build.BuildListener;
import com.zishu.ide.core.build.BuildRequest;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.CancelToken;
import com.zishu.ide.core.maven.Coordinate;
import com.zishu.ide.core.maven.GradleDeps;
import com.zishu.ide.core.maven.MavenCache;
import com.zishu.ide.core.maven.MavenDependencies;
import com.zishu.ide.core.maven.MavenRepo;
import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.ondevice.KotlinSupport;
import com.zishu.ide.core.ondevice.OnDeviceBuildEngine;
import com.zishu.ide.core.ondevice.OnDeviceTools;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KotlinTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, byte[]> repoFiles = new HashMap<String, byte[]>();

    private static final String GRADLE = "plugins { id 'org.jetbrains.kotlin.android' }\nandroid {\n namespace 'com.example.myapp'\n compileSdk 34\n"
            + " defaultConfig {\n applicationId 'com.example.myapp'\n minSdk 21\n targetSdk 34\n }\n"
            + " compileOptions {\n sourceCompatibility JavaVersion.VERSION_11\n }\n}\ndependencies {\n}\n";

    private void publish() {
        String k = "org/jetbrains/kotlin/";
        repoFiles.put(k + "kotlin-compiler-embeddable/1.9.24/kotlin-compiler-embeddable-1.9.24.jar", TestSupport.utf8("compiler"));
        repoFiles.put(k + "kotlin-compiler-embeddable/1.9.24/kotlin-compiler-embeddable-1.9.24.pom", TestSupport.utf8(
                "<project><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-compiler-embeddable</artifactId><version>1.9.24</version>"
                + "<dependencies><dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId>"
                + "<version>1.9.24</version></dependency></dependencies></project>"));
        repoFiles.put("javax/xml/stream/stax-api/1.0-2/stax-api-1.0-2.jar", TestSupport.utf8("stax"));
        repoFiles.put("javax/xml/stream/stax-api/1.0-2/stax-api-1.0-2.pom", TestSupport.utf8(
                "<project><groupId>javax.xml.stream</groupId><artifactId>stax-api</artifactId><version>1.0-2</version></project>"));
        repoFiles.put(k + "kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar", TestSupport.utf8("reflect"));
        repoFiles.put(k + "kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.pom", TestSupport.utf8(
                "<project><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-reflect</artifactId><version>1.6.10</version></project>"));
        repoFiles.put("org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.jar", TestSupport.utf8("coroutines"));
        repoFiles.put("org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.6.4/kotlinx-coroutines-core-jvm-1.6.4.pom", TestSupport.utf8(
                "<project><groupId>org.jetbrains.kotlinx</groupId><artifactId>kotlinx-coroutines-core-jvm</artifactId><version>1.6.4</version>"
                + "<dependencies><dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib-jdk8</artifactId>"
                + "<version>1.6.21</version></dependency></dependencies></project>"));
        repoFiles.put(k + "kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar", TestSupport.utf8("stdlib"));
        repoFiles.put(k + "kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.pom", TestSupport.utf8(
                "<project><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>1.9.24</version></project>"));
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

    private File project(String gradle, boolean withKt) throws IOException {
        File root = tmp.newFolder("p" + System.nanoTime());
        File main = new File(root, "app/src/main");
        IoUtil.writeUtf8Atomic(new File(root, "app/build.gradle"), gradle);
        IoUtil.writeUtf8Atomic(new File(main, "AndroidManifest.xml"),
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:label=\"x\">"
                + "<activity android:name=\".MainActivity\" android:exported=\"true\"/></application></manifest>");
        IoUtil.writeUtf8Atomic(new File(main, "res/values/s.xml"), "<resources/>");
        IoUtil.writeUtf8Atomic(new File(main, "java/com/example/myapp/MainActivity.java"), "package com.example.myapp; public class MainActivity {}");
        if (withKt) {
            IoUtil.writeUtf8Atomic(new File(main, "java/com/example/myapp/Util.kt"), "package com.example.myapp\nfun hi() = 1");
            IoUtil.writeUtf8Atomic(new File(main, "kotlin/com/example/myapp/Other.kt"), "package com.example.myapp\nfun ho() = 2");
        }
        return root;
    }

    private BuildResult build(File root, OnDeviceTest.FakeTools t, MavenDependencies deps) throws IOException {
        ProjectInfo info = new ProjectInfo(root, "P", ProjectType.NATIVE_JAVA, Language.KOTLIN, "P", "com.example.myapp", "", 0, 0);
        OnDeviceBuildEngine engine = new OnDeviceBuildEngine(t, tmp.newFolder("work" + System.nanoTime()), deps);
        return engine.build(new BuildRequest(info, "debug", tmp.newFolder("out" + System.nanoTime())), BuildListener.NONE, new CancelToken());
    }

    @Test
    public void kotlinIsCompiledBeforeJavaAndStdlibIsAdded() throws Exception {
        publish();
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android.jar"));
        BuildResult r = build(project(GRADLE, true), t, deps());
        assertTrue(r.log, r.success);
        assertEquals(Arrays.asList("aapt2", "kotlinc", "javac", "d8", "sign"), t.calls);
        OnDeviceTools.KotlinJob kj = t.lastKotlinJob;
        assertEquals(2, countKt(kj.sourceFiles));
        assertTrue("Java files are passed to kotlinc as symbols", kj.sourceFiles.toString().contains("MainActivity.java"));
        assertTrue(kj.sourceFiles.toString().contains("R.java"));
        assertEquals(t.androidJar, kj.classpath.get(0));
        assertTrue(kj.classpath.toString(), kj.classpath.toString().contains("kotlin-stdlib-1.9.24.jar"));
        assertEquals(5, kj.compilerJars.size());
        assertTrue(kj.compilerJars.toString(), kj.compilerJars.toString().contains("kotlinx-coroutines-core-jvm-1.6.4.jar"));
        assertTrue(kj.compilerJars.toString(), kj.compilerJars.toString().contains("kotlin-reflect-1.6.10.jar"));
        assertFalse("the old jdk8 shim is excluded", kj.compilerJars.toString().contains("jdk8"));
        assertTrue(kj.compilerJars.toString(), kj.compilerJars.toString().contains("stax-api-1.0-2.jar"));
        assertEquals("com_example_myapp", kj.moduleName);
        // ECJ sees the Kotlin output and the stdlib
        assertTrue(t.classpath.toString(), t.classpath.contains(kj.outDir));
        assertTrue(t.classpath.toString(), t.classpath.toString().contains("kotlin-stdlib-1.9.24.jar"));
        // the stdlib is packaged into the app
        assertTrue(t.lastDexJob.libraryJars.toString(), t.lastDexJob.libraryJars.toString().contains("kotlin-stdlib-1.9.24.jar"));
        assertTrue(r.log, r.log.contains("Kotlin: 2 file(s)"));
    }

    @Test
    public void levelsAboveTheCompilerAreLoweredWithAWarning() throws Exception {
        publish();
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android.jar"));
        BuildResult r = build(project(GRADLE.replace("VERSION_11", "VERSION_17"), true), t, deps());
        assertTrue(r.log, r.success);
        assertEquals(15, t.lastKotlinJob.javaLevel);
        assertTrue(r.log, r.log.contains("WARNING: build.gradle asks for Java 17"));
        assertTrue(r.log, r.log.contains("Java language level: 15"));
    }

    @Test
    public void kotlinErrorsFailTheBuildWithTheCompilerMessage() throws Exception {
        publish();
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android.jar"));
        t.failAt = "kotlinc";
        BuildResult r = build(project(GRADLE, true), t, deps());
        assertFalse(r.success);
        assertTrue(r.errorTitle, r.errorTitle.contains("Kotlin compilation failed"));
        assertTrue(r.log, r.log.contains("unresolved reference: foo"));
        assertFalse(t.calls.contains("javac"));
    }

    @Test
    public void javaOnlyProjectWithKotlinPluginDoesNotNeedTheCompiler() throws Exception {
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android.jar"));
        BuildResult r = build(project(GRADLE, false), t, deps());
        assertTrue(r.log, r.success);
        assertEquals(Arrays.asList("aapt2", "javac", "d8", "sign"), t.calls);
    }

    @Test
    public void missingCompilerGivesAnError() throws Exception {
        OnDeviceTest.FakeTools t = new OnDeviceTest.FakeTools(tmp.newFile("android.jar"));
        BuildResult r = build(project(GRADLE, true), t, deps());
        assertFalse(r.success);
        assertFalse(t.calls.contains("kotlinc"));
    }

    @Test
    public void argumentsAndTargets() throws Exception {
        assertEquals("1.8", KotlinSupport.jvmTarget(8));
        assertEquals("1.8", KotlinSupport.jvmTarget(6));
        assertEquals("11", KotlinSupport.jvmTarget(11));
        assertEquals("21", KotlinSupport.jvmTarget(30));
        OnDeviceTools.KotlinJob job = new OnDeviceTools.KotlinJob();
        job.classpath = Arrays.asList(new File("/a/android.jar"), new File("/b/stdlib.jar"));
        job.sourceFiles = Arrays.asList(new File("/s/A.kt"), new File("/s/B.java"));
        job.outDir = new File("/o");
        job.javaLevel = 11;
        job.kotlinHome = new File("/home/kt");
        List<String> a = KotlinSupport.arguments(job);
        assertTrue(a.toString(), a.containsAll(Arrays.asList("-no-stdlib", "-no-jdk", "-no-reflect")));
        assertEquals("11", a.get(a.indexOf("-jvm-target") + 1));
        assertEquals("/a/android.jar" + File.pathSeparator + "/b/stdlib.jar", a.get(a.indexOf("-classpath") + 1));
        assertEquals("/home/kt", a.get(a.indexOf("-kotlin-home") + 1));
        assertEquals("/o", a.get(a.indexOf("-d") + 1));
        assertEquals("/s/B.java", a.get(a.size() - 1));
    }

    @Test
    public void stdlibIsNotAddedTwice() {
        GradleDeps gd = GradleDeps.parse("dependencies {\n implementation 'org.jetbrains.kotlin:kotlin-stdlib:1.8.0'\n}\n", null);
        gd.addImplementation(KotlinSupport.stdlib());
        assertEquals(1, gd.deps.size());
        GradleDeps empty = new GradleDeps();
        empty.addImplementation(Coordinate.parse(KotlinSupport.STDLIB));
        assertEquals(1, empty.deps.size());
    }

    private static int countKt(List<File> files) {
        int n = 0;
        for (File f : files) {
            if (f.getName().endsWith(".kt")) {
                n++;
            }
        }
        return n;
    }
}
