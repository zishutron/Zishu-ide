package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.build.ApkInspector;
import com.zishu.ide.core.build.BuildHistory;
import com.zishu.ide.core.build.BuildResult;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

public class BuildHistoryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private File project;
    private BuildHistory h;

    @Before
    public void setUp() throws Exception {
        project = tmp.newFolder("p");
        h = new BuildHistory(project);
    }

    private BuildResult success(String id) throws Exception {
        File apk = new File(h.apkDir(id), "app-debug.apk");
        IoUtil.writeAtomic(apk, TestSupport.syntheticApk(true));
        return BuildResult.success(apk, ApkInspector.inspect(apk), 1234, "github-actions", "debug", "log of " + id, "https://x/run", "Debug key");
    }

    @Test
    public void emptyHistoryListsNothing() {
        assertTrue(h.list().isEmpty());
    }

    @Test
    public void recordsSuccessWithApkAndLog() throws Exception {
        BuildHistory.Entry e = h.record("a1", success("a1"));
        assertTrue(e.success);
        assertEquals(".zishu/builds/a1/app-debug.apk", e.apkPath);
        assertTrue(e.apkSize > 0);
        assertEquals("log of a1", h.readLog("a1"));
        BuildHistory.Entry back = h.list().get(0);
        assertEquals("a1", back.id);
        assertEquals(1234, back.durationMs);
        assertEquals("https://x/run", back.runUrl);
        assertNotNull(h.apkFile(back));
    }

    @Test
    public void failedBuildKeepsItsLogForDiagnosis() throws Exception {
        BuildResult r = BuildResult.failure("cannot find symbol", 50, "github-actions", "debug", "full failing log\nline 2", null, null);
        BuildHistory.Entry e = h.record("f1", r);
        assertFalse(e.success);
        assertNull(e.apkPath);
        assertEquals("full failing log\nline 2", h.readLog("f1"));
        assertEquals("cannot find symbol", h.list().get(0).title);
    }

    @Test
    public void newestFirstAndBoundedAndOldLogsDeleted() throws Exception {
        for (int i = 0; i < BuildHistory.MAX_ENTRIES + 5; i++) {
            h.record("b" + i, BuildResult.failure("t" + i, 1, "e", "debug", "log" + i, null, null));
        }
        List<BuildHistory.Entry> l = h.list();
        assertEquals(BuildHistory.MAX_ENTRIES, l.size());
        assertEquals("b" + (BuildHistory.MAX_ENTRIES + 4), l.get(0).id);
        assertFalse("evicted build's folder must be removed", h.buildDir("b0").exists());
        assertTrue(h.buildDir("b5").exists());
    }

    @Test
    public void deleteRemovesEntryAndFiles() throws Exception {
        h.record("d1", success("d1"));
        h.record("d2", success("d2"));
        h.delete("d1");
        assertEquals(1, h.list().size());
        assertFalse(h.buildDir("d1").exists());
    }

    @Test
    public void corruptIndexIsTreatedAsEmpty() throws Exception {
        IoUtil.writeUtf8Atomic(new File(project, ".zishu/builds.json"), "{not json");
        assertTrue(h.list().isEmpty());
        h.record("x", BuildResult.failure("t", 1, "e", "debug", "l", null, null));
        assertEquals(1, h.list().size());
    }

    @Test
    public void missingApkFileIsReportedAsNull() throws Exception {
        BuildHistory.Entry e = h.record("m", success("m"));
        assertTrue(new File(project, e.apkPath).delete());
        assertNull(h.apkFile(h.list().get(0)));
    }
}
