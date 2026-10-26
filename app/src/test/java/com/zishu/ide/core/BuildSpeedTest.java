package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.ondevice.DexCache;
import com.zishu.ide.core.ondevice.StageTimer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BuildSpeedTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void timerListsEveryStageAndTheTotal() {
        StageTimer t = new StageTimer(1000);
        t.mark("libraries", 1400);
        t.mark("resources", 6400);
        t.mark("Kotlin", 86400);
        t.mark("D8", 86900);
        String s = t.summary();
        assertEquals("Time: libraries <1 s, resources 5 s, Kotlin 1 min 20 s, D8 <1 s (total 1 min 26 s)", s);
    }

    @Test
    public void cacheDirectoryChangesWhenTheJarOrTheSettingsChange() throws Exception {
        File root = tmp.newFolder("cache");
        File jar = tmp.newFile("lib-1.0.jar");
        File a = DexCache.dirFor(root, jar, 21, "android-34.jar");
        assertEquals(a, DexCache.dirFor(root, jar, 21, "android-34.jar"));
        assertNotEquals("minSdk", a, DexCache.dirFor(root, jar, 26, "android-34.jar"));
        assertNotEquals("platform", a, DexCache.dirFor(root, jar, 21, "android-35.jar"));
        assertTrue(a.getName().startsWith("lib-1.0.jar-"));
        FileOutputStream out = new FileOutputStream(jar);
        out.write(new byte[]{1, 2, 3});
        out.close();
        assertNotEquals("size", a, DexCache.dirFor(root, jar, 21, "android-34.jar"));
        assertTrue(DexCache.key(jar, 21, "p").startsWith(DexCache.ID));
    }

    @Test
    public void entryIsCompleteOnlyAfterItIsMarked() throws Exception {
        File dir = tmp.newFolder("entry");
        new FileOutputStream(new File(dir, "classes2.dex")).close();
        new FileOutputStream(new File(dir, "classes.dex")).close();
        new FileOutputStream(new File(dir, "classes10.dex")).close();
        new FileOutputStream(new File(dir, "notes.txt")).close();
        assertFalse(DexCache.isComplete(dir));
        DexCache.markComplete(dir);
        assertTrue(DexCache.isComplete(dir));
        List<File> files = DexCache.dexFiles(dir);
        assertEquals(3, files.size());
        assertEquals("classes.dex", files.get(0).getName());
        assertEquals("classes2.dex", files.get(1).getName());
        assertEquals("classes10.dex", files.get(2).getName());
    }

    @Test
    public void jarsWithoutClassesAreRecognised() throws Exception {
        File res = tmp.newFile("res.jar");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(res));
        z.putNextEntry(new ZipEntry("META-INF/x.properties"));
        z.write(1);
        z.closeEntry();
        z.putNextEntry(new ZipEntry("module-info.class"));
        z.write(1);
        z.closeEntry();
        z.close();
        assertFalse(DexCache.hasClasses(res));
        File code = tmp.newFile("code.jar");
        z = new ZipOutputStream(new FileOutputStream(code));
        z.putNextEntry(new ZipEntry("a/B.class"));
        z.write(1);
        z.closeEntry();
        z.close();
        assertTrue(DexCache.hasClasses(code));
    }
}
