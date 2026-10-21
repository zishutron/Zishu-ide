package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildListener;
import com.zishu.ide.core.build.BuildLog;
import com.zishu.ide.core.build.BuildRequest;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.CancelToken;
import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.ondevice.ApkPackager;
import com.zishu.ide.core.ondevice.GradleValues;
import com.zishu.ide.core.ondevice.ManifestPatcher;
import com.zishu.ide.core.ondevice.OnDeviceBuildEngine;
import com.zishu.ide.core.ondevice.OnDeviceTools;
import com.zishu.ide.core.ondevice.ToolException;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class OnDeviceTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- ManifestPatcher ----------------------------------------------------------------------

    private static final String MANIFEST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!-- <manifest> in a comment -->\n"
            + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <application android:label=\"x\"/>\n</manifest>\n";

    @Test
    public void manifestGetsPackage() {
        String out = ManifestPatcher.ensurePackage(MANIFEST, "com.example.myapp");
        assertEquals("com.example.myapp", ManifestPatcher.declaredPackage(out));
        assertTrue(out.contains("<!-- <manifest> in a comment -->"));
        assertNull(ManifestPatcher.declaredPackage(MANIFEST));
    }

    @Test
    public void manifestWithPackageIsUntouched() {
        String xml = "<manifest xmlns:android=\"a\" package='a.b'><application/></manifest>";
        assertEquals(xml, ManifestPatcher.ensurePackage(xml, "c.d"));
        assertEquals("a.b", ManifestPatcher.declaredPackage(xml));
    }

    @Test
    public void manifestRejectsBadInput() {
        try {
            ManifestPatcher.ensurePackage(MANIFEST, "notapackage");
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not a valid package"));
        }
        try {
            ManifestPatcher.ensurePackage("<application/>", "a.b");
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("<manifest>"));
        }
    }

    // ---- GradleValues -------------------------------------------------------------------------

    @Test
    public void gradleValuesAreRead() {
        GradleValues v = GradleValues.parse("plugins { id 'com.android.application' }\n"
                + "android {\n namespace 'com.example.myapp'\n compileSdk 35\n"
                + " defaultConfig {\n applicationId \"com.example.real\"\n minSdk 21\n targetSdk 35\n"
                + " versionCode 7\n versionName '2.1'\n }\n}\n"
                + "dependencies {\n // implementation 'x:y:1'\n testImplementation 'junit:junit:4.13.2'\n"
                + " implementation fileTree(dir: 'libs', include: ['*.jar'])\n}\n");
        assertEquals("com.example.myapp", v.namespace);
        assertEquals("com.example.real", v.applicationId);
        assertEquals(35, v.compileSdk);
        assertEquals(21, v.minSdk);
        assertEquals(35, v.targetSdk);
        assertEquals(7, v.versionCode);
        assertEquals("2.1", v.versionName);
        assertTrue(v.usesFileTreeJars);
        assertTrue(v.unsupportedDependencies.isEmpty());
        assertFalse(v.usesKotlin);
    }

    @Test
    public void gradleValuesDetectUnsupported() {
        GradleValues v = GradleValues.parse("plugins { id 'org.jetbrains.kotlin.android' }\n"
                + "dependencies {\n implementation 'androidx.core:core:1.9.0'\n}\n");
        assertTrue(v.usesKotlin);
        assertEquals(1, v.unsupportedDependencies.size());
        assertTrue(v.unsupportedDependencies.get(0).contains("androidx.core"));
    }

    @Test
    public void gradleKtsStyleIsRead() {
        GradleValues v = GradleValues.parse("android {\n namespace = \"a.b\"\n compileSdk = 34\n"
                + " defaultConfig { minSdk = 24\n versionName = \"1.0\" }\n}\n");
        assertEquals("a.b", v.namespace);
        assertEquals(34, v.compileSdk);
        assertEquals(24, v.minSdk);
    }

    // ---- ApkPackager --------------------------------------------------------------------------

    /** Offsets of the data of every STORED entry, read from the raw ZIP bytes. */
    private static Map<String, Long> storedDataOffsets(File zip) throws IOException {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        RandomAccessFile f = new RandomAccessFile(zip, "r");
        try {
            byte[] all = new byte[(int) f.length()];
            f.readFully(all);
            int eocd = all.length - 22;
            while (eocd >= 0 && !(all[eocd] == 0x50 && all[eocd + 1] == 0x4b && all[eocd + 2] == 5 && all[eocd + 3] == 6)) {
                eocd--;
            }
            int count = u16(all, eocd + 10);
            int p = (int) u32(all, eocd + 16);
            for (int i = 0; i < count; i++) {
                int method = u16(all, p + 10);
                int nameLen = u16(all, p + 28);
                int extraLen = u16(all, p + 30);
                int commentLen = u16(all, p + 32);
                long lho = u32(all, p + 42);
                String name = new String(all, p + 46, nameLen, "UTF-8");
                int lhName = u16(all, (int) lho + 26);
                int lhExtra = u16(all, (int) lho + 28);
                if (method == 0) {
                    out.put(name, lho + 30 + lhName + lhExtra);
                }
                p += 46 + nameLen + extraLen + commentLen;
            }
        } finally {
            f.close();
        }
        return out;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return u16(b, o) | ((long) u16(b, o + 2) << 16);
    }

    private static void writeStoredZip(File f, String[] names, byte[][] data) throws IOException {
        ZipOutputStream z = new ZipOutputStream(new java.io.FileOutputStream(f));
        for (int i = 0; i < names.length; i++) {
            ZipEntry e = new ZipEntry(names[i]);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data[i].length);
            e.setCompressedSize(data[i].length);
            CRC32 c = new CRC32();
            c.update(data[i], 0, data[i].length);
            e.setCrc(c.getValue());
            z.putNextEntry(e);
            z.write(data[i]);
            z.closeEntry();
        }
        z.close();
    }

    @Test
    public void packagerAlignsStoredEntriesAndKeepsContent() throws Exception {
        File base = tmp.newFile("base.zip");
        // Names of odd lengths so unaligned input is guaranteed.
        writeStoredZip(base, new String[]{"a", "resources.arsc", "xyz.bin", "res/layout/main.xml"},
                new byte[][]{{1}, {2, 2, 2, 2, 2}, {3, 3, 3}, {4, 4}});
        File dex = tmp.newFile("classes.dex");
        IoUtil.writeAtomic(dex, new byte[]{9, 9, 9, 9, 9, 9});
        Map<String, File> extra = new LinkedHashMap<String, File>();
        extra.put("classes.dex", dex);
        File out = new File(tmp.getRoot(), "out.apk");
        ApkPackager.assemble(base, extra, out);

        Map<String, Long> offsets = storedDataOffsets(out);
        assertEquals(4, offsets.size());
        for (Map.Entry<String, Long> e : offsets.entrySet()) {
            assertEquals(e.getKey() + " is not 4-byte aligned", 0, e.getValue() % 4);
        }
        ZipFile z = new ZipFile(out);
        try {
            assertEquals(5, java.util.Collections.list(z.entries()).size());
            assertNotNull(z.getEntry("classes.dex"));
            assertEquals(6, z.getEntry("classes.dex").getSize());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            IoUtil.copy(z.getInputStream(z.getEntry("resources.arsc")), bos);
            assertEquals(5, bos.size());
        } finally {
            z.close();
        }
    }

    @Test
    public void packagerReplacesDuplicateNames() throws Exception {
        File base = tmp.newFile("b.zip");
        writeStoredZip(base, new String[]{"classes.dex", "other"}, new byte[][]{{1}, {2}});
        File dex = tmp.newFile("new.dex");
        IoUtil.writeAtomic(dex, new byte[]{7, 7, 7});
        Map<String, File> extra = new LinkedHashMap<String, File>();
        extra.put("classes.dex", dex);
        File out = new File(tmp.getRoot(), "o.apk");
        ApkPackager.assemble(base, extra, out);
        ZipFile z = new ZipFile(out);
        try {
            assertEquals(2, java.util.Collections.list(z.entries()).size());
            assertEquals(3, z.getEntry("classes.dex").getSize());
        } finally {
            z.close();
        }
    }

    // ---- Engine with fake tools ---------------------------------------------------------------

    static final class FakeTools implements OnDeviceTools {
        boolean available = true;
        String failAt;
        String lastManifest;
        List<File> javaSources;
        List<File> classpath;
        File lastDexOut;
        final File androidJar;
        final List<String> calls = new ArrayList<String>();
        OnDeviceTools.ResourceJob lastResources;

        FakeTools(File androidJar) {
            this.androidJar = androidJar;
        }

        @Override
        public BuildEngine.Status status() {
            return available ? BuildEngine.Status.ok("fake") : BuildEngine.Status.unavailable("fake missing", "install");
        }

        @Override
        public File androidJar() {
            return androidJar;
        }

        @Override
        public int androidJarApi() {
            return 34;
        }

        @Override
        public void compileResources(ResourceJob job, BuildLog log) throws ToolException {
            calls.add("aapt2");
            lastResources = job;
            if ("aapt2".equals(failAt)) {
                log.line("/p/app/src/main/res/layout/a.xml:3: error: attribute foo not found.");
                throw new ToolException("aapt2 link failed");
            }
            try {
                lastManifest = IoUtil.readUtf8(job.manifest, 1 << 20);
                writeStoredZip(job.outApk, new String[]{"AndroidManifest.xml", "resources.arsc"},
                        new byte[][]{{3, 0, 8, 0, 1}, {2, 0, 12, 0, 1, 2, 3}});
                File r = new File(job.genDir, "com/example/myapp/R.java");
                r.getParentFile().mkdirs();
                IoUtil.writeUtf8Atomic(r, "package com.example.myapp; public final class R {}");
            } catch (IOException e) {
                throw new ToolException(e.getMessage());
            }
        }

        @Override
        public void compileJava(JavaJob job, BuildLog log) throws ToolException {
            calls.add("javac");
            javaSources = job.sourceFiles;
            classpath = job.classpath;
            if ("javac".equals(failAt)) {
                throw new ToolException("Java compilation failed");
            }
            try {
                File c = new File(job.outDir, "com/example/myapp/A.class");
                c.getParentFile().mkdirs();
                IoUtil.writeAtomic(c, new byte[]{(byte) 0xca, (byte) 0xfe});
            } catch (IOException e) {
                throw new ToolException(e.getMessage());
            }
        }

        @Override
        public void dex(DexJob job, BuildLog log) throws ToolException {
            calls.add("d8");
            lastDexOut = job.outDir;
            if ("d8".equals(failAt)) {
                throw new ToolException("D8 failed");
            }
            try {
                IoUtil.writeAtomic(new File(job.outDir, "classes.dex"), new byte[]{'d', 'e', 'x', '\n', '0', '3', '5', 0});
            } catch (IOException e) {
                throw new ToolException(e.getMessage());
            }
        }

        @Override
        public void sign(File in, File out, BuildLog log) throws ToolException {
            calls.add("sign");
            if ("sign".equals(failAt)) {
                throw new ToolException("Signing failed");
            }
            try {
                File rsa = File.createTempFile("cert", ".rsa");
                IoUtil.writeAtomic(rsa, new byte[]{1, 2, 3});
                Map<String, File> extra = new LinkedHashMap<String, File>();
                extra.put("META-INF/CERT.RSA", rsa);
                ApkPackager.assemble(in, extra, out);
                rsa.delete();
            } catch (IOException e) {
                throw new ToolException(e.getMessage());
            }
        }
    }

    private File makeProject(String gradle, String extraJava) throws IOException {
        File root = tmp.newFolder("proj" + System.nanoTime());
        File main = new File(root, "app/src/main");
        write(new File(root, "app/build.gradle"), gradle);
        write(new File(main, "AndroidManifest.xml"), MANIFEST);
        write(new File(main, "res/values/strings.xml"), "<resources><string name=\"app_name\">X</string></resources>");
        write(new File(main, "java/com/example/myapp/MainActivity.java"), "package com.example.myapp; public class MainActivity {}");
        if (extraJava != null) {
            write(new File(main, "java/com/example/myapp/" + extraJava), "package com.example.myapp; class Extra {}");
        }
        return root;
    }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        IoUtil.writeUtf8Atomic(f, text);
    }

    private static final String GOOD_GRADLE = "android {\n namespace 'com.example.myapp'\n compileSdk 35\n"
            + " defaultConfig {\n applicationId 'com.example.myapp'\n minSdk 21\n targetSdk 35\n versionCode 3\n versionName '1.3'\n }\n}\n"
            + "dependencies {\n}\n";

    private BuildResult run(File project, FakeTools tools, CancelToken cancel) throws IOException {
        ProjectInfo info = new ProjectInfo(project, "My App!", ProjectType.NATIVE_JAVA, Language.JAVA, "My App!",
                "com.example.myapp", "", 0, 0);
        File out = tmp.newFolder("out" + System.nanoTime());
        OnDeviceBuildEngine engine = new OnDeviceBuildEngine(tools, tmp.newFolder("work" + System.nanoTime()));
        return engine.build(new BuildRequest(info, "debug", out), BuildListener.NONE, cancel);
    }

    @Test
    public void engineBuildsWithFakeTools() throws Exception {
        FakeTools t = new FakeTools(tmp.newFile("android.jar"));
        File project = makeProject(GOOD_GRADLE, "Extra.java");
        BuildResult r = run(project, t, new CancelToken());
        assertTrue(r.log, r.success);
        assertEquals(java.util.Arrays.asList("aapt2", "javac", "d8", "sign"), t.calls);
        assertEquals("on-device", r.engineId);
        assertTrue(r.apk.getName().equals("My_App_-debug.apk"));
        assertTrue(r.apkInfo.isSigned());
        assertTrue(r.apkInfo.hasResourceTable);
        // manifest got its package, versions passed to aapt2
        assertEquals("com.example.myapp", ManifestPatcher.declaredPackage(t.lastManifest));
        assertEquals(3, t.lastResources.versionCode);
        assertEquals("1.3", t.lastResources.versionName);
        assertEquals(21, t.lastResources.minSdk);
        // 2 project sources + generated R.java
        assertEquals(3, t.javaSources.size());
        // android.jar is first on the classpath
        assertEquals(t.androidJar, t.classpath.get(0));
        // resources.arsc stays aligned after packaging + fake signing
        for (Map.Entry<String, Long> e : storedDataOffsets(r.apk).entrySet()) {
            assertEquals(e.getKey(), 0, e.getValue() % 4);
        }
    }

    @Test
    public void toolFailureBecomesRealFailure() throws Exception {
        for (String stage : new String[]{"aapt2", "javac", "d8", "sign"}) {
            FakeTools t = new FakeTools(tmp.newFile("a" + stage + ".jar"));
            t.failAt = stage;
            BuildResult r = run(makeProject(GOOD_GRADLE, null), t, new CancelToken());
            assertFalse(stage, r.success);
            assertNull(stage, r.apk);
            assertTrue(r.errorTitle, r.errorTitle.length() > 0);
        }
    }

    @Test
    public void aaptErrorsAreAnalysed() throws Exception {
        FakeTools t = new FakeTools(tmp.newFile("android2.jar"));
        t.failAt = "aapt2";
        BuildResult r = run(makeProject(GOOD_GRADLE, null), t, new CancelToken());
        assertFalse(r.success);
        assertFalse(r.findings.isEmpty());
    }

    @Test
    public void unavailableToolchainIsReportedNotFaked() throws Exception {
        FakeTools t = new FakeTools(tmp.newFile("android3.jar"));
        t.available = false;
        BuildResult r = run(makeProject(GOOD_GRADLE, null), t, new CancelToken());
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("not ready"));
        assertTrue(t.calls.isEmpty());
    }

    @Test
    public void unsupportedProjectsAreRefusedWithReason() throws Exception {
        FakeTools t = new FakeTools(tmp.newFile("android4.jar"));
        BuildResult dep = run(makeProject(GOOD_GRADLE.replace("dependencies {\n}", "dependencies {\n implementation 'androidx.core:core:1.9.0'\n}"), null),
                t, new CancelToken());
        assertFalse(dep.success);
        assertTrue(dep.errorTitle, dep.errorTitle.contains("androidx.core"));
        BuildResult kt = run(makeProject(GOOD_GRADLE.replace("android {", "apply plugin: 'kotlin-android'\nandroid {"), null),
                t, new CancelToken());
        assertFalse(kt.success);
        assertTrue(kt.errorTitle, kt.errorTitle.contains("Kotlin"));
        assertTrue(t.calls.isEmpty());
    }

    @Test
    public void cancelStopsBuild() throws Exception {
        FakeTools t = new FakeTools(tmp.newFile("android5.jar"));
        CancelToken c = new CancelToken();
        c.cancel();
        BuildResult r = run(makeProject(GOOD_GRADLE, null), t, c);
        assertTrue(r.cancelled);
        assertFalse(r.success);
        assertTrue(t.calls.isEmpty());
    }

    @Test
    public void missingManifestIsRefused() throws Exception {
        FakeTools t = new FakeTools(tmp.newFile("android6.jar"));
        File root = tmp.newFolder("empty");
        BuildResult r = run(root, t, new CancelToken());
        assertFalse(r.success);
        assertTrue(r.errorTitle, r.errorTitle.contains("AndroidManifest"));
    }
}
