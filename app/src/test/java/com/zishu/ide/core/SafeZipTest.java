package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.project.FileManager;
import com.zishu.ide.core.project.SafeZip;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SafeZipTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Map<String, byte[]> one(String name, String content) {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put(name, TestSupport.utf8(content));
        return m;
    }

    private SafeZip.Result extract(byte[] zip, File dest) throws IOException {
        return SafeZip.extract(new ByteArrayInputStream(zip), dest, new SafeZip.Limits());
    }

    private void assertRejected(String entryName) throws Exception {
        File dest = new File(tmp.getRoot(), "out-" + System.nanoTime());
        try {
            extract(TestSupport.zip(one(entryName, "x")), dest);
            fail("entry should have been rejected: " + entryName);
        } catch (SafeZip.ZipSecurityException expected) {
            assertFalse("failed extraction must leave nothing behind", dest.exists());
        }
    }

    @Test
    public void extractsNormalArchive() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("a.txt", TestSupport.utf8("A"));
        m.put("dir/b.txt", TestSupport.utf8("B"));
        File dest = new File(tmp.getRoot(), "ok");
        SafeZip.Result r = extract(TestSupport.zip(m), dest);
        assertEquals(2, r.files);
        assertEquals("B", new String(com.zishu.ide.core.util.IoUtil.readFile(new File(dest, "dir/b.txt"), 100), "UTF-8"));
    }

    @Test
    public void rejectsParentTraversal() throws Exception {
        assertRejected("../evil.txt");
        assertRejected("a/../../evil.txt");
        assertRejected("a/b/../../../evil.txt");
    }

    @Test
    public void rejectsBackslashTraversalAndAbsolutePaths() throws Exception {
        assertRejected("..\\evil.txt");
        assertRejected("a\\..\\..\\evil.txt");
        assertRejected("/etc/passwd");
        assertRejected("\\windows\\evil.txt");
        assertRejected("C:\\evil.txt");
        assertRejected("C:/evil.txt");
    }

    @Test
    public void traversalAttemptDoesNotWriteOutsideTarget() throws Exception {
        File dest = new File(tmp.getRoot(), "inner/target");
        try {
            extract(TestSupport.zip(one("../escaped.txt", "x")), dest);
            fail();
        } catch (SafeZip.ZipSecurityException expected) {
            assertFalse(new File(tmp.getRoot(), "inner/escaped.txt").exists());
        }
    }

    @Test
    public void rejectsDuplicateEntries() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        // java.util.zip refuses duplicate names, so write the second entry under a path that normalises to the same one
        z.putNextEntry(new ZipEntry("same.txt"));
        z.write(1);
        z.closeEntry();
        z.putNextEntry(new ZipEntry("./same.txt"));
        z.write(2);
        z.closeEntry();
        z.close();
        File dest = new File(tmp.getRoot(), "dup");
        try {
            extract(bos.toByteArray(), dest);
            fail();
        } catch (SafeZip.ZipSecurityException expected) {
            assertFalse(dest.exists());
        }
    }

    @Test
    public void enforcesTotalSizeLimitWhileInflating() throws Exception {
        byte[] big = new byte[3 * 1024 * 1024]; // compresses to a few KB: a small "bomb"
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("big.bin", big);
        SafeZip.Limits l = new SafeZip.Limits();
        l.maxTotalBytes = 1024 * 1024;
        File dest = new File(tmp.getRoot(), "bomb");
        try {
            SafeZip.extract(new ByteArrayInputStream(TestSupport.zip(m)), dest, l);
            fail();
        } catch (SafeZip.ZipSecurityException expected) {
            assertFalse(dest.exists());
        }
    }

    @Test
    public void enforcesEntryCountLimit() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 20; i++) {
            m.put("f" + i, new byte[]{1});
        }
        SafeZip.Limits l = new SafeZip.Limits();
        l.maxEntries = 10;
        try {
            SafeZip.extract(new ByteArrayInputStream(TestSupport.zip(m)), new File(tmp.getRoot(), "many"), l);
            fail();
        } catch (SafeZip.ZipSecurityException expected) {
            // ok
        }
    }

    @Test
    public void rejectsNonZipAndEmptyInput() throws Exception {
        try {
            extract("this is not a zip".getBytes("UTF-8"), new File(tmp.getRoot(), "bad"));
            fail();
        } catch (IOException expected) {
            assertFalse(new File(tmp.getRoot(), "bad").exists());
        }
    }

    @Test
    public void refusesToExtractIntoNonEmptyDirectory() throws Exception {
        File dest = tmp.newFolder("full");
        new File(dest, "keep.txt").createNewFile();
        try {
            extract(TestSupport.zip(one("a.txt", "x")), dest);
            fail();
        } catch (FileManager.FileExistsException expected) {
            assertTrue("existing user file must be untouched", new File(dest, "keep.txt").exists());
        }
    }

    @Test
    public void skipsMacMetadata() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("__MACOSX/._a.txt", new byte[]{1});
        m.put("a.txt", new byte[]{1});
        File dest = new File(tmp.getRoot(), "mac");
        SafeZip.Result r = extract(TestSupport.zip(m), dest);
        assertEquals(1, r.files);
        assertFalse(new File(dest, "__MACOSX").exists());
    }

    @Test
    public void sanitizeNormalisesSafeNames() throws Exception {
        assertEquals("a/b.txt", SafeZip.sanitizeEntryName("a//b.txt"));
        assertEquals("a/b.txt", SafeZip.sanitizeEntryName("./a/./b.txt"));
        assertEquals("a/b", SafeZip.sanitizeEntryName("a\\b"));
        assertNull(SafeZip.sanitizeEntryName("./"));
    }

    @Test
    public void zipDirectoryExcludesBuildOutputAndLocalProperties() throws Exception {
        File p = tmp.newFolder("proj");
        new File(p, "app/src").mkdirs();
        new File(p, "app/build/outputs").mkdirs();
        new File(p, ".gradle").mkdirs();
        new File(p, ".zishu").mkdirs();
        com.zishu.ide.core.util.IoUtil.writeUtf8Atomic(new File(p, "app/src/A.java"), "class A{}");
        com.zishu.ide.core.util.IoUtil.writeUtf8Atomic(new File(p, "app/build/outputs/x.apk"), "x");
        com.zishu.ide.core.util.IoUtil.writeUtf8Atomic(new File(p, "local.properties"), "sdk.dir=/home/me/sdk");
        com.zishu.ide.core.util.IoUtil.writeUtf8Atomic(new File(p, ".zishu/project.properties"), "x");
        com.zishu.ide.core.util.IoUtil.writeUtf8Atomic(new File(p, "settings.gradle"), "include ':app'");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SafeZip.zipDirectory(p, out, SafeZip.DEFAULT_EXCLUDES, 1 << 20);
        File dest = new File(tmp.getRoot(), "round");
        extract(out.toByteArray(), dest);
        assertTrue(new File(dest, "app/src/A.java").isFile());
        assertTrue(new File(dest, "settings.gradle").isFile());
        assertFalse(new File(dest, "app/build").exists());
        assertFalse(new File(dest, "local.properties").exists());
        assertFalse(new File(dest, ".zishu").exists());
    }

    @Test
    public void zipDirectoryEnforcesSizeLimit() throws Exception {
        File p = tmp.newFolder("big");
        com.zishu.ide.core.util.IoUtil.writeAtomic(new File(p, "big.bin"), new byte[200000]);
        try {
            SafeZip.zipDirectory(p, new ByteArrayOutputStream(), SafeZip.DEFAULT_EXCLUDES, 1000);
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("larger"));
        }
    }
}
