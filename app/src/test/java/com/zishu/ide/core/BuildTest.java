package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.build.ApkInspector;
import com.zishu.ide.core.build.BuildLogAnalyzer;
import com.zishu.ide.core.build.BuildListener;
import com.zishu.ide.core.build.BuildRequest;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.build.BuildStage;
import com.zishu.ide.core.build.CancelToken;
import com.zishu.ide.core.build.CloudBuildEngine;
import com.zishu.ide.core.build.GitHubApi;
import com.zishu.ide.core.build.LocalBuildEngine;
import com.zishu.ide.core.build.Toolchain;
import com.zishu.ide.core.build.UrlTransport;
import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectManager;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

public class BuildTest {
    private static final String WORKFLOW = "name: Zishu cloud build\non: workflow_dispatch\n";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private ProjectManager pm;
    private ProjectInfo project;
    private FakeGitHub gh;
    private final List<String> stages = new ArrayList<String>();
    private final List<Integer> progress = new ArrayList<Integer>();

    private final BuildListener listener = new BuildListener() {
        @Override
        public void onStage(BuildStage stage, String detail) {
            stages.add(stage.name());
        }

        @Override
        public void onLog(String line) {
        }

        @Override
        public void onProgress(int percent) {
            progress.add(percent);
        }
    };

    @Before
    public void setUp() throws Exception {
        pm = new ProjectManager(new File(tmp.getRoot(), "ws"));
        project = pm.create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        gh = new FakeGitHub();
        gh.workflowText = WORKFLOW;
    }

    private CloudBuildEngine engine() {
        CloudBuildEngine.Config c = new CloudBuildEngine.Config();
        c.token = FakeGitHub.TOKEN;
        c.ownerRepo = "me/builds";
        c.workflowYaml = WORKFLOW;
        c.pollIntervalMs = 1;
        c.runDiscoveryTimeoutMs = 2000;
        c.buildTimeoutMs = 20000;
        configure(c);
        return new CloudBuildEngine(gh, c);
    }

    protected void configure(CloudBuildEngine.Config c) {
    }

    private BuildResult run(CloudBuildEngine e, CancelToken cancel) {
        return e.build(new BuildRequest(project, "debug", new File(tmp.getRoot(), "out")), listener, cancel);
    }

    private BuildResult run(CloudBuildEngine e) {
        return run(e, new CancelToken());
    }

    // ================================================================ cloud: success

    @Test
    public void successfulCloudBuildProducesARealVerifiedApk() throws Exception {
        BuildResult r = run(engine());
        assertTrue(r.errorTitle, r.success);
        assertNotNull(r.apk);
        assertTrue(r.apk.isFile());
        assertEquals("app-debug.apk", r.apk.getName());
        assertTrue(r.apkInfo.isSigned());
        assertTrue(r.apkInfo.hasManifest && r.apkInfo.dexCount == 1);
        assertEquals("debug", r.variant);
        assertTrue(r.signing.contains("Debug key"));
        assertEquals("https://github.com/me/builds/actions/runs/777", r.runUrl);
        assertTrue(r.durationMs >= 0);
        assertTrue(r.log.contains("apk-info"));
        assertTrue(r.log.contains("package: name='com.example.myapp'"));
    }

    @Test
    public void stagesRunInOrderAndProgressIsMonotonic() throws Exception {
        BuildResult r = run(engine());
        assertTrue(r.success);
        List<String> expected = java.util.Arrays.asList("VALIDATE", "PACKAGE", "UPLOAD", "QUEUE", "BUILD", "DOWNLOAD", "VERIFY", "CLEANUP");
        int last = -1;
        for (String s : expected) {
            int idx = stages.indexOf(s);
            assertTrue("stage " + s + " missing from " + stages, idx >= 0);
            assertTrue("stage " + s + " out of order in " + stages, idx > last);
            last = idx;
        }
        int prev = 0;
        for (int p : progress) {
            assertTrue("progress went backwards: " + progress, p >= prev);
            prev = p;
        }
        assertEquals(100, (int) progress.get(progress.size() - 1));
    }

    @Test
    public void uploadedZipHoldsTheProjectButNoBuildOutputOrLocalPaths() throws Exception {
        IoUtil.writeUtf8Atomic(new File(project.dir, "local.properties"), "sdk.dir=/home/me/sdk");
        new File(project.dir, "app/build").mkdirs();
        IoUtil.writeUtf8Atomic(new File(project.dir, "app/build/junk.txt"), "junk");
        assertTrue(run(engine()).success);
        byte[] zip = null;
        for (Map.Entry<String, byte[]> e : gh.files.entrySet()) {
            if (e.getKey().endsWith("/project.zip")) {
                zip = e.getValue();
                assertTrue(e.getKey().startsWith("zishu-job-"));
            }
        }
        assertNotNull(zip);
        List<String> names = new ArrayList<String>();
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip));
        java.util.zip.ZipEntry en;
        while ((en = z.getNextEntry()) != null) {
            names.add(en.getName());
        }
        assertTrue(names.contains("app/build.gradle"));
        assertTrue(names.contains("app/src/main/AndroidManifest.xml"));
        assertFalse(names.contains("local.properties"));
        for (String n : names) {
            assertFalse("leaked: " + n, n.startsWith("app/build/") || n.startsWith(".zishu"));
        }
    }

    @Test
    public void temporaryBranchIsAlwaysDeleted() throws Exception {
        assertTrue(run(engine()).success);
        assertEquals(1, gh.branchesCreated.size());
        assertEquals(gh.branchesCreated, gh.branchesDeleted);
        gh = new FakeGitHub();
        gh.workflowText = WORKFLOW;
        gh.conclusion = "failure";
        assertFalse(run(engine()).success);
        assertEquals("branch must be cleaned up after a failed build too", gh.branchesCreated, gh.branchesDeleted);
    }

    // ================================================================ cloud: secrets

    @Test
    public void tokenIsOnlySentToApiGithubComAndNeverInUrls() throws Exception {
        assertTrue(run(engine()).success);
        int apiCalls = 0;
        int blobCalls = 0;
        for (FakeGitHub.Req r : gh.requests) {
            assertFalse("token in URL: " + r.url, r.url.contains(FakeGitHub.TOKEN));
            if (r.url.startsWith("https://api.github.com/")) {
                apiCalls++;
                assertEquals("Bearer " + FakeGitHub.TOKEN, r.headers.get("Authorization"));
            } else {
                blobCalls++;
                for (String k : r.headers.keySet()) {
                    assertFalse("credential header sent to " + r.url, k.equalsIgnoreCase("Authorization"));
                }
            }
        }
        assertTrue(apiCalls > 10);
        assertEquals("logs and artifact are downloaded from the redirect target", 2, blobCalls);
    }

    @Test
    public void tokenIsRedactedEvenWhenTheRemoteLogContainsIt() throws Exception {
        gh.leakTokenInLog = true;
        BuildResult r = run(engine());
        assertTrue(r.success);
        assertFalse(r.log.contains(FakeGitHub.TOKEN));
        assertTrue(r.log.contains("***"));
        gh = new FakeGitHub();
        gh.workflowText = WORKFLOW;
        gh.conclusion = "failure";
        BuildResult f = run(engine());
        assertFalse(f.log.contains(FakeGitHub.TOKEN));
        for (BuildLogAnalyzer.Finding x : f.findings) {
            assertFalse(x.message.contains(FakeGitHub.TOKEN));
        }
    }

    @Test
    public void unregisteredGithubTokensAreStillRedacted() {
        com.zishu.ide.core.build.BuildLog log = new com.zishu.ide.core.build.BuildLog(BuildListener.NONE);
        log.line("x ghp_abcdefghijklmnopqrstuvwxyz0123456789 y github_pat_11ABCDEFG0abcdefghijkl_mnopqrstuvwxyz z");
        assertFalse(log.text().contains("ghp_abc"));
        assertFalse(log.text().contains("github_pat_11"));
    }

    @Test
    public void transportRefusesPlainHttp() {
        try {
            new UrlTransport().execute("GET", "http://api.github.com/", new LinkedHashMap<String, String>(), null, 100);
            org.junit.Assert.fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("https"));
        }
    }

    // ================================================================ cloud: failures are never "success"

    @Test
    public void compileFailureIsReportedWithFileLineAndNoApk() throws Exception {
        gh.conclusion = "failure";
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertNull(r.apk);
        assertTrue(r.errorTitle, r.errorTitle.contains("cannot find symbol"));
        assertFalse(r.findings.isEmpty());
        BuildLogAnalyzer.Finding f = r.findings.get(0);
        assertEquals(BuildLogAnalyzer.Category.JAVA_COMPILE, f.category);
        assertEquals("MainActivity.java", f.file.substring(f.file.lastIndexOf('/') + 1));
        assertEquals(12, f.line);
        assertFalse("no APK may exist after a failed build", new File(tmp.getRoot(), "out").exists()
                && new File(tmp.getRoot(), "out").list().length > 0);
    }

    @Test
    public void succeededRunWithoutArtifactIsAFailure() throws Exception {
        gh.artifactMissing = true;
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("no APK artifact"));
    }

    @Test
    public void corruptArtifactIsAFailure() throws Exception {
        gh.artifactCorrupt = true;
        assertFalse(run(engine()).success);
    }

    @Test
    public void unsignedApkIsAFailure() throws Exception {
        gh.artifactUnsigned = true;
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("not signed"));
    }

    @Test
    public void invalidProjectIsRejectedBeforeAnyNetworkCall() throws Exception {
        assertTrue(new File(project.dir, "app/src/main/AndroidManifest.xml").delete());
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertNotNull(r.validation);
        assertTrue(r.validation.hasCode("no-manifest"));
        assertEquals("nothing may be uploaded for an invalid project", 0, gh.requests.size());
    }

    @Test
    public void publicRepositoryIsRefusedUnlessExplicitlyAllowed() throws Exception {
        gh.isPrivate = false;
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("public repository"));
        assertEquals("no branch or upload before the check", 0, gh.branchesCreated.size());
        assertTrue(gh.files.isEmpty());

        gh = new FakeGitHub();
        gh.workflowText = WORKFLOW;
        gh.isPrivate = false;
        CloudBuildEngine.Config c = new CloudBuildEngine.Config();
        c.token = FakeGitHub.TOKEN;
        c.ownerRepo = "me/builds";
        c.workflowYaml = WORKFLOW;
        c.pollIntervalMs = 1;
        c.allowPublicRepo = true;
        assertTrue(run(new CloudBuildEngine(gh, c)).success);
    }

    @Test
    public void tokenWithoutWriteAccessGetsPermissionHelp() throws Exception {
        gh.canPush = false;
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("Contents (read and write)"));
        assertEquals(0, gh.branchesCreated.size());
    }

    @Test
    public void rejectedTokenGivesClearMessage() throws Exception {
        gh.unauthorized = true;
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("rejected the access token"));
        assertFalse(r.errorTitle.contains(FakeGitHub.TOKEN));
    }

    @Test
    public void missingWorkflowRequiresPermissionToInstall() throws Exception {
        gh.workflowInstalled = false;
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("Allow Zishu IDE to install"));
        assertTrue("repository must be untouched", gh.files.isEmpty());

        gh = new FakeGitHub();
        gh.workflowInstalled = false;
        gh.workflowText = null;
        CloudBuildEngine.Config c = new CloudBuildEngine.Config();
        c.token = FakeGitHub.TOKEN;
        c.ownerRepo = "me/builds";
        c.workflowYaml = WORKFLOW;
        c.pollIntervalMs = 1;
        c.installWorkflow = true;
        assertTrue(run(new CloudBuildEngine(gh, c)).success);
        assertTrue(gh.files.containsKey("main:.github/workflows/zishu-build.yml"));
    }

    @Test
    public void outdatedWorkflowIsNotSilentlyOverwritten() throws Exception {
        gh.workflowText = "name: something the user customised\n";
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("out of date"));
        assertTrue(gh.files.isEmpty());
    }

    @Test
    public void freshlyInstalledWorkflowIsRetriedUntilGitHubRegistersIt() throws Exception {
        gh.dispatchNotFoundTimes = 3;
        assertTrue(run(engine()).success);
        assertEquals(4, gh.requestsTo("POST", "/dispatches").size());
    }

    @Test
    public void runThatNeverStartsTimesOut() throws Exception {
        gh.neverStart = true;
        CloudBuildEngine.Config c = new CloudBuildEngine.Config();
        c.token = FakeGitHub.TOKEN;
        c.ownerRepo = "me/builds";
        c.workflowYaml = WORKFLOW;
        c.pollIntervalMs = 1;
        c.runDiscoveryTimeoutMs = 100;
        BuildResult r = run(new CloudBuildEngine(gh, c));
        assertFalse(r.success);
        assertTrue(r.errorTitle.contains("did not start"));
        assertEquals(gh.branchesCreated, gh.branchesDeleted);
    }

    @Test
    public void oversizedProjectIsRefusedWithoutUploading() throws Exception {
        File big = new File(project.dir, "assets-big.bin");
        java.util.Random rnd = new java.util.Random(1);
        byte[] chunk = new byte[1 << 20];
        FileOutputStream out = new FileOutputStream(big);
        for (int i = 0; i < 45; i++) {
            rnd.nextBytes(chunk); // random bytes do not compress
            out.write(chunk);
        }
        out.close();
        BuildResult r = run(engine());
        assertFalse(r.success);
        assertTrue(r.errorTitle, r.errorTitle.contains("limit for cloud builds"));
        assertTrue(gh.files.isEmpty());
    }

    @Test
    public void onlyDebugVariantIsAccepted() throws Exception {
        BuildResult r = engine().build(new BuildRequest(project, "release", new File(tmp.getRoot(), "o")), listener, new CancelToken());
        assertFalse(r.success);
        assertEquals(0, gh.requests.size());
    }

    @Test
    public void unconfiguredEngineExplainsWhatToDo() {
        CloudBuildEngine.Config c = new CloudBuildEngine.Config();
        CloudBuildEngine e = new CloudBuildEngine(gh, c);
        assertFalse(e.status().available);
        assertTrue(e.status().action.contains("token"));
        c.token = "x";
        assertFalse(e.status().available);
        assertTrue(e.status().action.contains("repository"));
        c.ownerRepo = "me/builds";
        c.workflowYaml = "w";
        assertTrue(e.status().available);
        BuildResult r = new CloudBuildEngine(gh, new CloudBuildEngine.Config()).build(
                new BuildRequest(project, "debug", tmp.getRoot()), listener, new CancelToken());
        assertFalse(r.success);
        assertEquals(0, gh.requests.size());
    }

    // ================================================================ cloud: cancellation

    @Test
    public void cancellingAfterDispatchCancelsTheGithubRunAndCleansUp() throws Exception {
        CancelToken cancel = new CancelToken();
        gh.cancelOnDispatch = cancel;
        BuildResult r = run(engine(), cancel);
        assertTrue(r.cancelled);
        assertFalse(r.success);
        assertNull(r.apk);
        assertEquals(gh.branchesCreated, gh.branchesDeleted);
    }

    @Test
    public void cancelBeforeStartDoesNothingRemote() throws Exception {
        CancelToken cancel = new CancelToken();
        cancel.cancel();
        BuildResult r = run(engine(), cancel);
        assertTrue(r.cancelled);
        assertEquals(0, gh.branchesCreated.size());
    }

    @Test
    public void cancellingWhileTheRunIsActiveAsksGithubToCancel() throws Exception {
        final CancelToken cancel = new CancelToken();
        gh.pollsUntilDone = 1000;
        BuildListener l = new BuildListener() {
            @Override
            public void onStage(BuildStage stage, String detail) {
                if (stage == BuildStage.BUILD) {
                    cancel.cancel();
                }
            }

            @Override
            public void onLog(String line) {
            }

            @Override
            public void onProgress(int percent) {
            }
        };
        BuildResult r = engine().build(new BuildRequest(project, "debug", new File(tmp.getRoot(), "o")), l, cancel);
        assertTrue(r.cancelled);
        assertTrue("GitHub must be told to stop the run", gh.cancelRequested);
        assertEquals(gh.branchesCreated, gh.branchesDeleted);
    }

    // ================================================================ github api helpers

    @Test
    public void parsesRepositoryReferences() {
        assertEquals("me", GitHubApi.parseRepo("me/builds")[0]);
        assertEquals("builds", GitHubApi.parseRepo("https://github.com/me/builds.git")[1]);
        assertEquals("builds", GitHubApi.parseRepo(" me/builds/ ")[1]);
        for (String bad : new String[]{null, "", "me", "me/", "/builds", "a/b/c", "me/..", "-me/x", "me/x y", "me/x;rm", "https://evil.com/me/x"}) {
            assertNull("should reject " + bad, GitHubApi.parseRepo(bad));
        }
    }

    // ================================================================ log analysis

    @Test
    public void analyzerCategorisesRealWorldLogLines() {
        String log = ""
                + "2026-10-19T10:00:00.0000000Z /home/runner/work/p/p/app/src/main/java/com/x/A.java:7: error: ';' expected\n"
                + "e: file:///home/runner/work/p/p/app/src/main/java/com/x/B.kt:3:9 Unresolved reference: foo\n"
                + "ERROR: /home/runner/work/p/p/app/src/main/res/layout/main.xml:5: AAPT: error: attribute android:foo not found.\n"
                + "> Could not resolve com.example:missing:1.0.\n"
                + "SDK location not found. Define a valid SDK location\n"
                + "Failed to find target with hash string 'android-35' in: /usr/local/lib/android/sdk\n"
                + "Minimum supported Gradle version is 8.9. Current version is 8.5.\n"
                + "Execution failed for task ':app:packageDebug'. Keystore was tampered with, or password was incorrect\n"
                + "just a normal line\n";
        List<BuildLogAnalyzer.Finding> f = BuildLogAnalyzer.analyze(log);
        java.util.Set<BuildLogAnalyzer.Category> cats = new java.util.HashSet<BuildLogAnalyzer.Category>();
        for (BuildLogAnalyzer.Finding x : f) {
            cats.add(x.category);
        }
        assertTrue(cats.contains(BuildLogAnalyzer.Category.JAVA_COMPILE));
        assertTrue(cats.contains(BuildLogAnalyzer.Category.KOTLIN_COMPILE));
        assertTrue(cats.contains(BuildLogAnalyzer.Category.RESOURCE));
        assertTrue(cats.contains(BuildLogAnalyzer.Category.DEPENDENCY));
        assertTrue(cats.contains(BuildLogAnalyzer.Category.SDK));
        assertTrue(cats.contains(BuildLogAnalyzer.Category.TOOLCHAIN));
        assertTrue(cats.contains(BuildLogAnalyzer.Category.SIGNING));
        BuildLogAnalyzer.Finding java = f.get(0);
        assertEquals("com/x/A.java".length() > 0 ? 7 : 0, java.line);
        assertEquals("';' expected", java.message);
        assertTrue(BuildLogAnalyzer.summarize(f).contains("Java compilation:"));
    }

    @Test
    public void analyzerFindsNothingInACleanLogAndNeverInventsErrors() {
        assertTrue(BuildLogAnalyzer.analyze("> Task :app:compileDebugJavaWithJavac\nBUILD SUCCESSFUL in 41s\n").isEmpty());
        assertEquals("", BuildLogAnalyzer.summarize(new ArrayList<BuildLogAnalyzer.Finding>()));
    }

    @Test
    public void analyzerUsesWhatWentWrongBlock() {
        List<BuildLogAnalyzer.Finding> f = BuildLogAnalyzer.analyze("* What went wrong:\nA problem occurred evaluating project ':app'.\n> boom\n");
        assertEquals(1, f.size());
        assertEquals(BuildLogAnalyzer.Category.CONFIGURATION, f.get(0).category);
    }

    // ================================================================ apk inspector (synthetic archives)

    private File write(String name, byte[] data) throws IOException {
        File f = new File(tmp.getRoot(), name);
        IoUtil.writeAtomic(f, data);
        return f;
    }

    @Test
    public void inspectorAcceptsStructurallyValidSignedApk() throws Exception {
        ApkInspector.Info i = ApkInspector.inspect(write("ok.apk", TestSupport.syntheticApk(true)));
        assertTrue(i.hasManifest && i.hasResourceTable && i.isSigned() && i.hasV1Signature);
        assertEquals(1, i.dexCount);
        assertFalse(i.hasAssetsWww);
    }

    @Test
    public void inspectorFlagsUnsignedAndBrokenFiles() throws Exception {
        assertFalse(ApkInspector.inspect(write("u.apk", TestSupport.syntheticApk(false))).isSigned());
        for (String bad : new String[]{"empty", "text", "nomanifest", "nodex"}) {
            byte[] data;
            if (bad.equals("empty")) {
                data = new byte[0];
            } else if (bad.equals("text")) {
                data = "not a zip".getBytes("UTF-8");
            } else {
                Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
                if (bad.equals("nodex")) {
                    m.put("AndroidManifest.xml", new byte[]{1});
                } else {
                    m.put("classes.dex", new byte[]{1});
                }
                data = TestSupport.zip(m);
            }
            try {
                ApkInspector.inspect(write(bad + ".apk", data));
                org.junit.Assert.fail(bad + " must be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().length() > 10);
            }
        }
    }

    @Test
    public void inspectorDetectsV2SigningBlockAndWebAssets() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("AndroidManifest.xml", new byte[]{1});
        m.put("classes.dex", new byte[]{1});
        m.put("assets/www/index.html", new byte[]{1});
        byte[] zip = TestSupport.zip(m);
        assertFalse(ApkInspector.inspect(write("nosig.apk", zip)).isSigned());

        // splice a fake "APK Sig Block 42" just before the central directory and fix the EOCD offset
        int eocd = -1;
        for (int i = zip.length - 22; i >= 0; i--) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4b && zip[i + 2] == 5 && zip[i + 3] == 6) {
                eocd = i;
                break;
            }
        }
        long cd = (zip[eocd + 16] & 0xFFL) | ((zip[eocd + 17] & 0xFFL) << 8) | ((zip[eocd + 18] & 0xFFL) << 16) | ((zip[eocd + 19] & 0xFFL) << 24);
        byte[] block = new byte[24];
        System.arraycopy("APK Sig Block 42".getBytes("ISO-8859-1"), 0, block, 8, 16);
        byte[] out = new byte[zip.length + block.length];
        System.arraycopy(zip, 0, out, 0, (int) cd);
        System.arraycopy(block, 0, out, (int) cd, block.length);
        System.arraycopy(zip, (int) cd, out, (int) cd + block.length, zip.length - (int) cd);
        long newCd = cd + block.length;
        int e2 = eocd + block.length;
        out[e2 + 16] = (byte) newCd;
        out[e2 + 17] = (byte) (newCd >> 8);
        out[e2 + 18] = (byte) (newCd >> 16);
        out[e2 + 19] = (byte) (newCd >> 24);
        ApkInspector.Info i = ApkInspector.inspect(write("v2.apk", out));
        assertTrue(i.hasV2OrLaterSignature);
        assertTrue(i.isSigned());
        assertTrue(i.hasAssetsWww);
    }

    // ================================================================ toolchain + local engine

    @Test
    public void toolchainReportsMissingComponentsWithActions() {
        Toolchain t = new Toolchain(null, null, null);
        List<Toolchain.Component> c = t.check(35, "8.7.3", "8.9");
        assertEquals(Toolchain.State.MISSING, c.get(0).state);
        for (Toolchain.Component x : c) {
            if (x.state == Toolchain.State.MISSING) {
                assertTrue(x.name + " has no action", x.action != null && x.action.length() > 0);
            }
        }
        assertTrue(c.get(2).name.contains("35"));
    }

    private Toolchain fakeToolchain(int jdk, int[] platforms, String[] buildTools, String gradle) throws Exception {
        File j = tmp.newFolder("jdk" + System.nanoTime());
        new File(j, "bin").mkdirs();
        IoUtil.writeUtf8Atomic(new File(j, "bin/java"), "#!/bin/sh\n");
        IoUtil.writeUtf8Atomic(new File(j, "release"), "JAVA_VERSION=\"" + (jdk == 8 ? "1.8.0_402" : jdk + ".0.2") + "\"\n");
        File sdk = tmp.newFolder("sdk" + System.nanoTime());
        for (int p : platforms) {
            new File(sdk, "platforms/android-" + p).mkdirs();
            IoUtil.writeUtf8Atomic(new File(sdk, "platforms/android-" + p + "/android.jar"), "x");
        }
        for (String b : buildTools) {
            new File(sdk, "build-tools/" + b).mkdirs();
        }
        new File(sdk, "platform-tools").mkdirs();
        File g = tmp.newFolder("gradle" + System.nanoTime());
        new File(g, "lib").mkdirs();
        new File(g, "bin").mkdirs();
        IoUtil.writeUtf8Atomic(new File(g, "lib/gradle-core-" + gradle + ".jar"), "x");
        return new Toolchain(j, sdk, g);
    }

    @Test
    public void toolchainDetectsInstalledAndIncompatibleComponents() throws Exception {
        Toolchain ok = fakeToolchain(17, new int[]{34, 35}, new String[]{"34.0.0", "35.0.0"}, "8.9");
        for (Toolchain.Component c : ok.check(35, "8.7.3", "8.9")) {
            assertEquals(c.name + ": " + c.detail, Toolchain.State.INSTALLED, c.state);
        }
        assertEquals(17, ok.jdkMajor());
        assertEquals("8.9", ok.gradleVersion());
        assertEquals(java.util.Arrays.asList(34, 35), ok.platforms());

        Toolchain old = fakeToolchain(8, new int[]{33}, new String[]{"30.0.3"}, "7.6");
        Map<String, Toolchain.State> st = new LinkedHashMap<String, Toolchain.State>();
        for (Toolchain.Component c : old.check(35, "8.7.3", "8.9")) {
            st.put(c.name, c.state);
        }
        assertEquals(Toolchain.State.INCOMPATIBLE, st.get("Java Development Kit"));
        assertEquals(Toolchain.State.MISSING, st.get("SDK Platform 35"));
        assertEquals(Toolchain.State.MISSING, st.get("Build Tools 34.0.0+"));
        assertEquals(Toolchain.State.INCOMPATIBLE, st.get("Gradle"));
        assertEquals(8, old.jdkMajor());
    }

    @Test
    public void localEngineReportsUnavailableWithoutToolchain() {
        LocalBuildEngine e = new LocalBuildEngine(new Toolchain(null, null, null), 1000);
        assertFalse(e.status().available);
        assertTrue(e.status().action.length() > 0);
        BuildResult r = e.build(new BuildRequest(project, "debug", tmp.getRoot()), listener, new CancelToken());
        assertFalse(r.success);
    }

    /** Fake toolchain test: proves the process handling, not that real Gradle works. */
    private LocalBuildEngine localWithScript(String script) throws Exception {
        Assume.assumeTrue(new File("/bin/sh").exists());
        Toolchain t = fakeToolchain(17, new int[]{35}, new String[]{"34.0.0"}, "8.9");
        File gradle = new File(t.gradleHome, "bin/gradle");
        IoUtil.writeUtf8Atomic(gradle, "#!/bin/sh\n" + script);
        assertTrue(gradle.setExecutable(true));
        return new LocalBuildEngine(t, 20000);
    }

    @Test
    public void localEngineReturnsTheApkTheToolProduced() throws Exception {
        File apk = write("prebuilt.apk", TestSupport.syntheticApk(true));
        LocalBuildEngine e = localWithScript("echo '> Task :app:assembleDebug'\nmkdir -p app/build/outputs/apk/debug\ncp '"
                + apk.getPath() + "' app/build/outputs/apk/debug/app-debug.apk\necho BUILD SUCCESSFUL\n");
        assertTrue(e.status().available);
        BuildResult r = e.build(new BuildRequest(project, "debug", new File(tmp.getRoot(), "out")), listener, new CancelToken());
        assertTrue(r.errorTitle, r.success);
        assertTrue(r.apk.isFile());
        assertTrue(r.log.contains("BUILD SUCCESSFUL"));
    }

    @Test
    public void localEngineSuccessExitWithoutApkIsStillAFailure() throws Exception {
        LocalBuildEngine e = localWithScript("echo BUILD SUCCESSFUL\n");
        BuildResult r = e.build(new BuildRequest(project, "debug", new File(tmp.getRoot(), "out")), listener, new CancelToken());
        assertFalse("exit code 0 alone must not count as success", r.success);
        assertTrue(r.errorTitle.contains("no APK was found"));
    }

    @Test
    public void localEngineReportsGradleFailureWithFindings() throws Exception {
        LocalBuildEngine e = localWithScript("echo '/w/app/src/main/java/com/x/A.java:9: error: cannot find symbol'\nexit 1\n");
        BuildResult r = e.build(new BuildRequest(project, "debug", new File(tmp.getRoot(), "out")), listener, new CancelToken());
        assertFalse(r.success);
        assertEquals(9, r.findings.get(0).line);
    }

    @Test
    public void localEngineCancelStopsTheProcess() throws Exception {
        final CancelToken cancel = new CancelToken();
        LocalBuildEngine e = localWithScript("echo started\nexec sleep 30\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException ignored) {
                    // test thread
                }
                cancel.cancel();
            }
        }).start();
        long t0 = System.currentTimeMillis();
        BuildResult r = e.build(new BuildRequest(project, "debug", new File(tmp.getRoot(), "out")), listener, cancel);
        assertTrue(r.cancelled);
        assertTrue("cancel must not wait for the 30 s sleep", System.currentTimeMillis() - t0 < 10000);
    }
}
