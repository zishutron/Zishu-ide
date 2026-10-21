package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectManager;
import com.zishu.ide.core.project.WorkspaceMover;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class WorkspaceMoverTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void movesProjectsWithTheirEditsAndRemovesTheOriginals() throws Exception {
        File from = new File(tmp.getRoot(), "private");
        File to = new File(tmp.getRoot(), "shared");
        ProjectManager pm = new ProjectManager(from);
        ProjectInfo p = pm.create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        File edited = new File(p.dir, "app/src/main/java/com/example/myapp/MainActivity.java");
        IoUtil.writeUtf8Atomic(edited, "// my edit");

        WorkspaceMover.Result r = WorkspaceMover.moveAll(from, to);

        assertEquals(1, r.moved);
        assertTrue(r.failed.isEmpty());
        assertFalse(p.dir.exists());
        ProjectManager moved = new ProjectManager(to);
        assertEquals(1, moved.list().size());
        File again = new File(moved.list().get(0).dir, "app/src/main/java/com/example/myapp/MainActivity.java");
        assertEquals("// my edit", IoUtil.readUtf8(again, 1000));
    }

    @Test
    public void neverOverwritesAProjectWithTheSameNameInTheTarget() throws Exception {
        File from = new File(tmp.getRoot(), "a");
        File to = new File(tmp.getRoot(), "b");
        new ProjectManager(from).create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        ProjectInfo existing = new ProjectManager(to).create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        IoUtil.writeUtf8Atomic(new File(existing.dir, "keep.txt"), "keep");

        WorkspaceMover.Result r = WorkspaceMover.moveAll(from, to);

        assertEquals(1, r.moved);
        assertEquals("keep", IoUtil.readUtf8(new File(existing.dir, "keep.txt"), 100));
        assertEquals(2, new ProjectManager(to).list().size());
    }

    @Test
    public void ignoresFoldersThatAreNotProjectsAndSameFolder() throws Exception {
        File from = new File(tmp.getRoot(), "w");
        new ProjectManager(from).create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        new File(from, "random").mkdirs();
        assertEquals(0, WorkspaceMover.moveAll(from, from).moved);
        assertEquals(1, new ProjectManager(from).list().size());
        assertTrue(new File(from, "random").isDirectory());
    }

    @Test
    public void refusesNestedFolders() throws Exception {
        File from = new File(tmp.getRoot(), "w");
        new ProjectManager(from).create(ProjectConfig.forType(ProjectType.NATIVE_JAVA));
        try {
            WorkspaceMover.moveAll(from, new File(from, "inner"));
            org.junit.Assert.fail();
        } catch (java.io.IOException expected) {
            assertEquals(1, new ProjectManager(from).list().size());
        }
    }
}
