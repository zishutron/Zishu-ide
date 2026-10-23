package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.zishu.ide.core.maven.Aar;
import com.zishu.ide.core.maven.Coordinate;
import com.zishu.ide.core.maven.GradleDeps;
import com.zishu.ide.core.maven.MavenCache;
import com.zishu.ide.core.maven.MavenRepo;
import com.zishu.ide.core.maven.Resolver;
import com.zishu.ide.core.maven.VersionCatalog;
import com.zishu.ide.core.maven.Versions;
import com.zishu.ide.core.util.IoUtil;

import org.junit.Before;
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

public class MavenTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, byte[]> files = new HashMap<String, byte[]>();
    private int downloads;
    private MavenCache cache;

    @Before
    public void setUp() {
        MavenRepo repo = new MavenRepo() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public boolean download(String path, File dest) throws IOException {
                byte[] b = files.get(path);
                if (b == null) {
                    return false;
                }
                downloads++;
                IoUtil.writeAtomic(dest, b);
                return true;
            }
        };
        cache = new MavenCache(new File(tmp.getRoot(), "cache"), Arrays.asList(repo));
    }

    private void pom(String g, String a, String v, String packaging, String extra) {
        String xml = "<project><modelVersion>4.0.0</modelVersion><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version>"
                + (packaging == null ? "" : "<packaging>" + packaging + "</packaging>") + extra + "</project>";
        files.put(g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + ".pom", TestSupport.utf8(xml));
        if (!"pom".equals(packaging)) {
            files.put(g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + ("aar".equals(packaging) ? ".aar" : ".jar"),
                    TestSupport.utf8("bin " + a + v));
        }
    }

    private static String dep(String g, String a, String v, String scope, String exclusions) {
        return "<dependency><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + (scope == null ? "" : "<scope>" + scope + "</scope>")
                + (exclusions == null ? "" : "<exclusions>" + exclusions + "</exclusions>") + "</dependency>";
    }

    private Resolver.Result resolve(Resolver.Root... roots) throws Exception {
        Resolver r = new Resolver(cache, cache.repoNames());
        return r.resolve(Arrays.asList(roots), new HashMap<String, String>());
    }

    private static Resolver.Root root(String c) {
        return new Resolver.Root(Coordinate.parse(c), true, true, null);
    }

    private static List<String> keys(Resolver.Result r) {
        List<String> k = new ArrayList<String>();
        for (Resolver.Artifact a : r.artifacts) {
            k.add(a.key());
        }
        return k;
    }

    @Test
    public void transitiveDependenciesComeFirstAndAarIsDetected() throws Exception {
        pom("x", "b", "1.0", null, "");
        pom("x", "a", "1.0", "aar", "<dependencies>" + dep("x", "b", "1.0", null, null) + "</dependencies>");
        Resolver.Result r = resolve(root("x:a:1.0"));
        assertEquals(Arrays.asList("x:b:1.0", "x:a:1.0"), keys(r));
        assertFalse(r.artifacts.get(0).aar);
        assertTrue(r.artifacts.get(1).aar);
        assertTrue(r.artifacts.get(1).file.getName().endsWith(".aar"));
    }

    @Test
    public void highestVersionWinsAndLosersAreNotUsed() throws Exception {
        pom("x", "c", "1.0", null, "");
        pom("x", "c", "2.0", null, "<dependencies>" + dep("x", "d", "1.0", null, null) + "</dependencies>");
        pom("x", "d", "1.0", null, "");
        pom("x", "a", "1.0", null, "<dependencies>" + dep("x", "c", "1.0", null, null) + "</dependencies>");
        pom("x", "b", "1.0", null, "<dependencies>" + dep("x", "c", "2.0", null, null) + "</dependencies>");
        Resolver.Result r = resolve(root("x:a:1.0"), root("x:b:1.0"));
        List<String> k = keys(r);
        assertTrue(k.toString(), k.contains("x:c:2.0"));
        assertFalse(k.toString(), k.contains("x:c:1.0"));
        assertTrue(k.toString(), k.contains("x:d:1.0"));
        assertTrue(k.indexOf("x:c:2.0") < k.indexOf("x:a:1.0"));
    }

    @Test
    public void parentPomPropertiesAndManagedVersionsAreApplied() throws Exception {
        pom("x", "parent", "5", "pom", "<properties><dv>3.1</dv></properties><dependencyManagement><dependencies>"
                + dep("x", "managed", "${dv}", null, null) + "</dependencies></dependencyManagement>");
        pom("x", "managed", "3.1", null, "");
        files.put("x/child/1.0/child-1.0.pom", TestSupport.utf8("<project><parent><groupId>x</groupId><artifactId>parent</artifactId>"
                + "<version>5</version></parent><artifactId>child</artifactId><version>1.0</version><dependencies>"
                + dep("x", "managed", null, null, null) + "</dependencies></project>"));
        files.put("x/child/1.0/child-1.0.jar", TestSupport.utf8("jar"));
        Resolver.Result r = resolve(root("x:child:1.0"));
        assertEquals(Arrays.asList("x:managed:3.1", "x:child:1.0"), keys(r));
    }

    @Test
    public void platformConstraintsFillMissingVersions() throws Exception {
        pom("x", "bom", "9", "pom", "<dependencyManagement><dependencies>" + dep("x", "lib", "4.2", null, null)
                + "</dependencies></dependencyManagement>");
        pom("x", "lib", "4.2", null, "");
        Resolver r = new Resolver(cache, "fake");
        Map<String, String> cons = r.constraintsFrom(Arrays.asList(Coordinate.parse("x:bom:9")));
        Resolver.Result res = r.resolve(Arrays.asList(new Resolver.Root(Coordinate.parse("x:lib"), true, true, null)), cons);
        assertEquals(Arrays.asList("x:lib:4.2"), keys(res));
    }

    @Test
    public void exclusionsTestScopeOptionalAndRuntimeScopeAreHonoured() throws Exception {
        pom("x", "excl", "1", null, "");
        pom("x", "rt", "1", null, "");
        pom("x", "t", "1", null, "");
        pom("x", "keep", "1", null, "");
        pom("x", "mid", "1", null, "<dependencies>" + dep("x", "excl", "1", null, null) + dep("x", "t", "1", "test", null)
                + dep("x", "rt", "1", "runtime", null) + dep("x", "keep", "1", null, null)
                + "<dependency><groupId>x</groupId><artifactId>opt</artifactId><version>1</version><optional>true</optional></dependency>"
                + "</dependencies>");
        pom("x", "top", "1", null, "<dependencies>" + dep("x", "mid", "1", null,
                "<exclusion><groupId>x</groupId><artifactId>excl</artifactId></exclusion>") + "</dependencies>");
        Resolver.Result r = resolve(root("x:top:1"));
        List<String> k = keys(r);
        assertFalse(k.toString(), k.contains("x:excl:1"));
        assertFalse(k.toString(), k.contains("x:t:1"));
        assertFalse(k.toString(), k.contains("x:opt:1"));
        assertTrue(k.toString(), k.contains("x:keep:1"));
        for (Resolver.Artifact a : r.artifacts) {
            if (a.artifact.equals("rt")) {
                assertTrue(a.runtime);
                assertFalse("runtime-scope dependency must not be on the compile classpath", a.compile);
            }
            if (a.artifact.equals("keep")) {
                assertTrue(a.compile && a.runtime);
            }
        }
    }

    @Test
    public void compileOnlyRootIsNotPackaged() throws Exception {
        pom("x", "api", "1", null, "");
        Resolver.Result r = resolve(new Resolver.Root(Coordinate.parse("x:api:1"), true, false, null));
        assertTrue(r.artifacts.get(0).compile);
        assertFalse(r.artifacts.get(0).runtime);
    }

    @Test
    public void missingLibraryGivesClearMessageAndCacheAvoidsRedownload() throws Exception {
        try {
            resolve(root("x:nothere:1.0"));
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Could not find x:nothere:1.0"));
            assertTrue(e.getMessage(), e.getMessage().contains("fake"));
        }
        pom("x", "one", "1", null, "");
        resolve(root("x:one:1"));
        int first = downloads;
        resolve(root("x:one:1"));
        assertEquals(first, downloads);
    }

    @Test
    public void dynamicVersionsAreRefused() throws Exception {
        try {
            resolve(root("x:one:1.+"));
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Dynamic version"));
        }
    }

    @Test
    public void versionOrderingMatchesExpectations() {
        assertTrue(Versions.compare("1.10.0", "1.9.0") > 0);
        assertTrue(Versions.compare("1.0.0-alpha01", "1.0.0-beta01") < 0);
        assertTrue(Versions.compare("1.0.0-rc01", "1.0.0") < 0);
        assertTrue(Versions.compare("1.0", "1.0.0") == 0);
        assertTrue(Versions.compare("2.0.0", "1.99.99") > 0);
        assertEquals("1.7.0", Versions.max("1.6.1", "1.7.0"));
    }

    @Test
    public void gradleDependencyDeclarationsAreParsed() {
        String g = "ext { kv = '1.9.0' }\ndef lifecycle = \"2.8.0\"\ndependencies {\n"
                + "  implementation 'androidx.core:core:1.13.1'\n"
                + "  implementation(\"androidx.appcompat:appcompat:$kv\")\n"
                + "  api \"androidx.lifecycle:lifecycle-runtime:${lifecycle}\"\n"
                + "  compileOnly group: 'a', name: 'b', version: '2'\n"
                + "  runtimeOnly 'c:d:3'\n"
                + "  implementation platform('androidx.compose:compose-bom:2024.06.00')\n"
                + "  implementation 'androidx.compose.ui:ui'\n"
                + "  implementation('x:y:1') { exclude group: 'g', module: 'm' }\n"
                + "  implementation project(':lib')\n"
                + "  kapt 'room:compiler:1'\n"
                + "  implementation \"a:b:$unknown\"\n"
                + "  testImplementation 'junit:junit:4.13.2'\n"
                + "  implementation fileTree(dir: 'libs', include: ['*.jar'])\n"
                + "  implementation files('libs/x.jar')\n"
                + "  // implementation 'commented:out:1'\n}";
        GradleDeps d = GradleDeps.parse(g, null);
        List<String> found = new ArrayList<String>();
        for (GradleDeps.Dep x : d.deps) {
            found.add(x.configuration + " " + x.coordinate);
        }
        assertEquals(Arrays.asList("implementation androidx.core:core:1.13.1", "implementation androidx.appcompat:appcompat:1.9.0",
                "api androidx.lifecycle:lifecycle-runtime:2.8.0", "compileOnly a:b:2", "runtimeOnly c:d:3",
                "implementation androidx.compose.ui:ui", "implementation x:y:1"), found);
        assertEquals("g:m", d.deps.get(6).excludes.get(0));
        assertEquals(1, d.boms.size());
        assertEquals("androidx.compose:compose-bom:2024.06.00", d.boms.get(0).toString());
        assertTrue(d.fileTree);
        assertEquals(Arrays.asList("libs/x.jar"), d.localJars);
        assertEquals(3, d.unsupported.size());
        assertFalse(d.deps.get(3).runtime());
        assertFalse(d.deps.get(4).compile());
    }

    @Test
    public void versionCatalogAliasesResolveToCoordinates() {
        String toml = "[versions]\ncore = \"1.13.1\"\n[libraries]\nandroidx-core-ktx = { group = \"androidx.core\", name = \"core-ktx\", version.ref = \"core\" }\n"
                + "appcompat = { module = \"androidx.appcompat:appcompat\", version = \"1.7.0\" }\nplain = \"a:b:2\"\n# c\n[plugins]\nx = { id = \"y\" }\n";
        VersionCatalog c = VersionCatalog.parse(toml);
        assertEquals("androidx.core:core-ktx:1.13.1", c.library("androidx.core.ktx").toString());
        assertEquals("androidx.appcompat:appcompat:1.7.0", c.library("appcompat").toString());
        assertEquals("a:b:2", c.library("plain").toString());
        assertNull(c.library("nope"));
        GradleDeps d = GradleDeps.parse("dependencies {\n implementation(libs.androidx.core.ktx)\n implementation libs.missing\n}", c);
        assertEquals(1, d.deps.size());
        assertEquals(1, d.unsupported.size());
    }

    @Test
    public void aarIsUnpackedAndDescribed() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
        m.put("AndroidManifest.xml", TestSupport.utf8("<manifest package=\"x.lib\" xmlns:android=\"http://schemas.android.com/apk/res/android\"/>"));
        m.put("classes.jar", TestSupport.utf8("jar"));
        m.put("res/values/v.xml", TestSupport.utf8("<resources/>"));
        m.put("jni/arm64-v8a/libx.so", TestSupport.utf8("so"));
        m.put("libs/extra.jar", TestSupport.utf8("jar2"));
        File aar = tmp.newFile("lib.aar");
        IoUtil.writeAtomic(aar, TestSupport.zip(m));
        Aar a = Aar.open(aar, new File(tmp.getRoot(), "unpacked"));
        assertEquals("x.lib", a.packageName);
        assertNotNull(a.classesJar);
        assertNotNull(a.resDir);
        assertNull(a.assetsDir);
        assertNotNull(a.jniDir);
        assertEquals(1, a.extraJars.size());
        assertTrue(new File(a.jniDir, "arm64-v8a/libx.so").isFile());
    }
}
