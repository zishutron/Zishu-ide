package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.maven.DownloadReporter;
import com.zishu.ide.core.maven.MavenCache;
import com.zishu.ide.core.maven.MavenDependencies;
import com.zishu.ide.core.maven.MavenRepo;
import com.zishu.ide.core.ondevice.ResourcePacks;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SetupTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, byte[]> repoFiles = new HashMap<String, byte[]>();

    private void publishAar(String group, String artifact, String version) {
        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        Map<String, byte[]> aar = new LinkedHashMap<String, byte[]>();
        aar.put("AndroidManifest.xml", TestSupport.utf8("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\""
                + group + "." + artifact.replace('-', '_') + "\"/>"));
        aar.put("classes.jar", TestSupport.utf8("classes"));
        try {
            repoFiles.put(base + ".aar", TestSupport.zip(aar));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        repoFiles.put(base + ".pom", TestSupport.utf8("<project><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version><packaging>aar</packaging></project>"));
    }

    private MavenDependencies deps(final List<String> downloaded) throws IOException {
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
                downloaded.add(path);
                IoUtil.writeAtomic(dest, b);
                return true;
            }
        };
        return new MavenDependencies(new MavenCache(tmp.newFolder("cache"), Arrays.asList(repo)), tmp.newFolder("unpacked"));
    }

    @Test
    public void androidxIsReadyOnlyWhenEverythingIsOnThePhone() throws Exception {
        publishAar("androidx.appcompat", "appcompat", "1.7.0");
        publishAar("androidx.core", "core-ktx", "1.13.1");
        publishAar("androidx.constraintlayout", "constraintlayout", "2.1.4");
        publishAar("com.google.android.material", "material", "1.12.0");
        List<String> downloaded = new ArrayList<String>();
        MavenDependencies d = deps(downloaded);
        assertFalse("nothing downloaded yet", ResourcePacks.androidxReady(d));
        assertTrue("checking must not download anything", downloaded.isEmpty());

        final List<String> lines = new ArrayList<String>();
        DownloadReporter.attach(d.cache(), new DownloadReporter.Sink() {
            @Override
            public void line(String text) {
                lines.add(text);
            }
        });
        ResourcePacks.Done done = ResourcePacks.downloadAndroidx(d);
        assertEquals(4, done.libraries);
        assertEquals(4, done.aars);
        assertTrue(lines.toString(), lines.contains("Downloading appcompat-1.7.0.aar"));
        assertTrue(ResourcePacks.androidxReady(d));
    }

    @Test
    public void aMissingLibraryKeepsAndroidxNotReady() throws Exception {
        publishAar("androidx.appcompat", "appcompat", "1.7.0");
        publishAar("androidx.core", "core-ktx", "1.13.1");
        publishAar("androidx.constraintlayout", "constraintlayout", "2.1.4");
        // material is not published: the download fails and the check stays false
        MavenDependencies d = deps(new ArrayList<String>());
        try {
            ResourcePacks.downloadAndroidx(d);
            assertTrue("expected an error", false);
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("material"));
        }
        assertFalse(ResourcePacks.androidxReady(d));
    }
}
