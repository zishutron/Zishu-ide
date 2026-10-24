package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.maven.DownloadMeter;
import com.zishu.ide.core.ondevice.JarSplitter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class DownloadAndSplitTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void meterShowsPercentSpeedAndTimeLeft() {
        DownloadMeter m = new DownloadMeter("big.jar", 0, 2000);
        long total = 50L * 1024 * 1024;
        assertNull("too soon", m.update(1000, 1024 * 1024, total));
        String line = m.update(2000, 4L * 1024 * 1024, total);
        assertNotNull(line);
        assertTrue(line, line.contains("big.jar: 8% (4.0 MB of 50.0 MB)"));
        assertTrue(line, line.contains("2.0 MB/s"));
        assertTrue(line, line.contains("about 23 s left"));
        String unknown = new DownloadMeter("u.jar", 0, 1000).update(1000, 2L * 1024 * 1024, -1);
        assertTrue(unknown, unknown.contains("2.0 MB downloaded") && !unknown.contains("left"));
        String done = m.finish(25000, total);
        assertTrue(done, done.contains("done, 50.0 MB in 25 s"));
        assertEquals("1 min 5 s", DownloadMeter.duration(65));
    }

    @Test
    public void jarIsSplitIntoGroupsAndEveryClassLandsInExactlyOne() throws Exception {
        File jar = tmp.newFile("big.jar");
        Random rnd = new Random(1);
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar));
        for (int i = 0; i < 40; i++) {
            zos.putNextEntry(new ZipEntry("p/C" + String.format("%02d", i) + ".class"));
            byte[] b = new byte[10000];
            rnd.nextBytes(b);
            zos.write(b);
            zos.closeEntry();
        }
        zos.putNextEntry(new ZipEntry("META-INF/extensions/x.xml"));
        zos.write(new byte[]{1});
        zos.closeEntry();
        zos.close();
        List<List<String>> groups = JarSplitter.plan(jar, 100000);
        assertTrue("several groups: " + groups.size(), groups.size() >= 4 && groups.size() <= 6);
        int total = 0;
        for (List<String> g : groups) {
            total += g.size();
            for (String n : g) {
                assertTrue(n, n.endsWith(".class"));
            }
        }
        assertEquals(40, total);
        File part = tmp.newFile("part.jar");
        JarSplitter.write(jar, groups.get(1), part);
        ZipFile z = new ZipFile(part);
        try {
            assertEquals(groups.get(1).size(), java.util.Collections.list(z.entries()).size());
        } finally {
            z.close();
        }
        assertEquals(1, JarSplitter.plan(jar, 100L * 1024 * 1024).size());
    }
}
