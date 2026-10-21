package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.project.FileManager;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class FileManagerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private FileManager fm;
    private File root;

    @Before
    public void setUp() throws Exception {
        root = tmp.newFolder("project");
        fm = new FileManager(root);
    }

    @Test
    public void createsFilesAndDirectories() throws Exception {
        assertEquals("src", fm.createDirectory("", "src"));
        assertEquals("src/Main.java", fm.createFile("src", "Main.java"));
        assertTrue(new File(root, "src/Main.java").isFile());
    }

    @Test
    public void neverOverwritesOnCreate() throws Exception {
        fm.createFile("", "a.txt");
        fm.writeText("a.txt", "precious", false);
        try {
            fm.createFile("", "a.txt");
            fail();
        } catch (FileManager.FileExistsException expected) {
            assertEquals("precious", fm.readText("a.txt").text);
        }
        try {
            fm.createDirectory("", "a.txt");
            fail();
        } catch (FileManager.FileExistsException expected) {
            // ok
        }
    }

    @Test
    public void rejectsPathsOutsideTheProject() throws Exception {
        for (String bad : new String[]{"../x", "a/../../x", "/etc/passwd", "a\\b", "a/../.."}) {
            try {
                fm.resolve(bad);
                fail("should reject " + bad);
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void rejectsInvalidNames() throws Exception {
        for (String bad : new String[]{"", " ", "..", ".", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a|b", "a\nb"}) {
            try {
                fm.createFile("", bad);
                fail("should reject name '" + bad + "'");
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void symlinkCannotEscapeTheProject() throws Exception {
        File outside = tmp.newFolder("outside");
        IoUtil.writeUtf8Atomic(new File(outside, "secret.txt"), "secret");
        File link = new File(root, "link");
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlinks: nothing to test here
        }
        try {
            fm.readText("link/secret.txt");
            fail("reading through an escaping symlink must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("leaves the project"));
        }
    }

    @Test
    public void deletingASymlinkDoesNotDeleteItsTarget() throws Exception {
        File outside = tmp.newFolder("outside2");
        File keep = new File(outside, "keep.txt");
        IoUtil.writeUtf8Atomic(keep, "keep");
        File link = new File(root, "link2");
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        IoUtil.deleteRecursively(link);
        assertTrue("target of the link must survive", keep.isFile());
        assertFalse(link.exists());
    }

    @Test
    public void renameMoveCopyDelete() throws Exception {
        fm.createDirectory("", "a");
        fm.createDirectory("", "b");
        fm.createFile("a", "f.txt");
        fm.writeText("a/f.txt", "hello", false);

        assertEquals("a/g.txt", fm.rename("a/f.txt", "g.txt"));
        assertEquals("b/g.txt", fm.copy("a/g.txt", "b"));
        assertEquals("hello", fm.readText("b/g.txt").text);
        try {
            fm.copy("a/g.txt", "b");
            fail("copy must not overwrite");
        } catch (FileManager.FileExistsException expected) {
            // ok
        }
        fm.createDirectory("", "c");
        assertEquals("c/g.txt", fm.move("a/g.txt", "c"));
        assertFalse(fm.exists("a/g.txt"));
        fm.delete("c");
        assertFalse(fm.exists("c"));
        assertTrue("copy must be independent of the moved file", fm.exists("b/g.txt"));
    }

    @Test
    public void renameOntoExistingNameIsRefused() throws Exception {
        fm.createFile("", "x.txt");
        fm.createFile("", "y.txt");
        try {
            fm.rename("x.txt", "y.txt");
            fail();
        } catch (FileManager.FileExistsException expected) {
            assertTrue(fm.exists("x.txt") && fm.exists("y.txt"));
        }
    }

    @Test
    public void cannotMoveOrCopyFolderIntoItself() throws Exception {
        fm.createDirectory("", "d");
        fm.createDirectory("d", "sub");
        for (String op : new String[]{"move", "copy"}) {
            try {
                if (op.equals("move")) {
                    fm.move("d", "d/sub");
                } else {
                    fm.copy("d", "d/sub");
                }
                fail(op + " into itself must fail");
            } catch (IOException expected) {
                assertTrue(fm.exists("d/sub"));
            }
        }
    }

    @Test
    public void rootCannotBeDeletedRenamedOrMoved() throws Exception {
        try {
            fm.delete("");
            fail();
        } catch (IOException expected) {
            assertTrue(root.isDirectory());
        }
        try {
            fm.rename("", "x");
            fail();
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void keepsCrlfLineEndingsRoundTrip() throws Exception {
        IoUtil.writeAtomic(new File(root, "win.txt"), "a\r\nb\r\n".getBytes("UTF-8"));
        FileManager.TextFile tf = fm.readText("win.txt");
        assertTrue(tf.crlf);
        assertEquals("a\nb\n", tf.text);
        fm.writeText("win.txt", tf.text + "c\n", tf.crlf);
        assertEquals("a\r\nb\r\nc\r\n", new String(IoUtil.readFile(new File(root, "win.txt"), 100), "UTF-8"));
    }

    @Test
    public void unicodeSurvivesSave() throws Exception {
        fm.createFile("", "u.txt");
        String s = "héllo – 日本語 – \uD83D\uDE00";
        fm.writeText("u.txt", s, false);
        assertEquals(s, fm.readText("u.txt").text);
    }

    @Test
    public void refusesBinaryAndHugeFiles() throws Exception {
        IoUtil.writeAtomic(new File(root, "b.bin"), new byte[]{1, 2, 0, 3});
        try {
            fm.readText("b.bin");
            fail();
        } catch (FileManager.NotTextException expected) {
            // ok
        }
        IoUtil.writeAtomic(new File(root, "huge.txt"), new byte[(int) FileManager.MAX_TEXT_BYTES + 1]);
        try {
            fm.readText("huge.txt");
            fail();
        } catch (FileManager.NotTextException expected) {
            // ok
        }
    }

    @Test
    public void saveIsAtomicAndLeavesNoTempFiles() throws Exception {
        fm.createFile("", "s.txt");
        fm.writeText("s.txt", "one", false);
        fm.writeText("s.txt", "two", false);
        String[] names = root.list();
        assertEquals(1, names.length);
        assertEquals("two", fm.readText("s.txt").text);
    }

    @Test
    public void importFileDoesNotOverwriteAndEnforcesLimit() throws Exception {
        fm.importFile("", "i.png", new ByteArrayInputStream(new byte[]{1, 2, 3}), 10);
        try {
            fm.importFile("", "i.png", new ByteArrayInputStream(new byte[]{9}), 10);
            fail();
        } catch (FileManager.FileExistsException expected) {
            // ok
        }
        try {
            fm.importFile("", "big.bin", new ByteArrayInputStream(new byte[100]), 10);
            fail();
        } catch (IOException expected) {
            assertFalse("a rejected import must not leave a file", fm.exists("big.bin"));
        }
    }

    @Test
    public void listsFoldersFirstAndHidesIdeMetadata() throws Exception {
        fm.createFile("", "b.txt");
        fm.createDirectory("", "z");
        fm.createDirectory("", ".zishu");
        fm.createFile("", "A.txt");
        List<FileManager.FileNode> l = fm.list("");
        assertEquals("z", l.get(0).name);
        assertEquals("A.txt", l.get(1).name);
        assertEquals(3, l.size());
    }
}
