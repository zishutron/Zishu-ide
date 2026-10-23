package com.zishu.ide.core.ondevice;

import com.zishu.ide.core.build.BuildLog;
import com.zishu.ide.core.maven.Aar;
import com.zishu.ide.core.maven.Coordinate;
import com.zishu.ide.core.maven.GradleDeps;
import com.zishu.ide.core.maven.MavenDependencies;
import com.zishu.ide.core.maven.Resolver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Libraries (Maven jars and AARs) resolved and unpacked for one build, split into what each build step needs. */
public final class LibraryPlan {
    /** Jars the Java compiler sees besides android.jar. */
    public final List<File> compileJars = new ArrayList<File>();
    /** Jars packaged into the app (inputs of D8). */
    public final List<File> dexJars = new ArrayList<File>();
    public final List<OnDeviceTools.ResLib> resLibs = new ArrayList<OnDeviceTools.ResLib>();
    public final List<File> assetDirs = new ArrayList<File>();
    public final List<String> extraPackages = new ArrayList<String>();
    public final List<ManifestMerger.Lib> manifests = new ArrayList<ManifestMerger.Lib>();
    /** APK entry name (lib/arm64-v8a/libx.so) to file. */
    public final Map<String, File> nativeLibs = new LinkedHashMap<String, File>();
    public final List<String> notes = new ArrayList<String>();

    /** ABIs copied into the APK from libraries. */
    static final List<String> ABIS = Arrays.asList("arm64-v8a", "armeabi-v7a");

    public static LibraryPlan build(GradleDeps gd, MavenDependencies deps, File moduleDir, String appPackage, BuildLog log)
            throws IOException {
        LibraryPlan lp = new LibraryPlan();
        List<Resolver.Root> roots = new ArrayList<Resolver.Root>();
        for (GradleDeps.Dep d : gd.deps) {
            roots.add(new Resolver.Root(d.coordinate, d.compile(), d.runtime(), d.excludes));
        }
        Resolver.Result res = new Resolver.Result();
        if (!roots.isEmpty()) try {
            Resolver r = deps.resolver();
            Map<String, String> constraints = r.constraintsFrom(gd.boms.isEmpty() ? new ArrayList<Coordinate>() : gd.boms);
            res = r.resolve(roots, constraints);
        } catch (java.net.UnknownHostException e) {
            throw new IOException("Could not reach the library repositories (" + e.getMessage()
                    + "). Check the internet connection; libraries are downloaded once and then kept on the phone.");
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("The library download timed out. Check the internet connection and try again.");
        } catch (java.net.ConnectException e) {
            throw new IOException("Could not connect to the library repositories: " + e.getMessage());
        }
        Set<String> packages = new LinkedHashSet<String>();
        int aars = 0;
        int jars = 0;
        for (Resolver.Artifact a : res.artifacts) {
            if (a.file == null) {
                continue;
            }
            List<File> classes = new ArrayList<File>();
            if (a.aar) {
                aars++;
                Aar aar = Aar.open(a.file, deps.unpackDir(a));
                if (aar.classesJar != null) {
                    classes.add(aar.classesJar);
                }
                classes.addAll(aar.extraJars);
                if (a.runtime) {
                    if (aar.resDir != null) {
                        lp.resLibs.add(new OnDeviceTools.ResLib(a.artifact, aar.resDir, new File(aar.dir, "compiled-res.zip")));
                    }
                    if (aar.assetsDir != null) {
                        lp.assetDirs.add(aar.assetsDir);
                    }
                    if (aar.packageName != null && !aar.packageName.equals(appPackage)) {
                        packages.add(aar.packageName);
                    }
                    if (aar.manifest != null) {
                        lp.manifests.add(new ManifestMerger.Lib(a.artifact + ":" + a.version,
                                com.zishu.ide.core.util.IoUtil.readUtf8(aar.manifest, 2 * 1024 * 1024)));
                    }
                    addNative(lp, aar);
                }
            } else {
                jars++;
                classes.add(a.file);
            }
            if (a.compile) {
                lp.compileJars.addAll(classes);
            }
            if (a.runtime) {
                lp.dexJars.addAll(classes);
            }
        }
        for (String j : gd.localJars) {
            File f = new File(moduleDir, j);
            if (!f.isFile()) {
                throw new IOException("files('" + j + "') does not exist in the project.");
            }
            lp.compileJars.add(f);
            lp.dexJars.add(f);
        }
        lp.extraPackages.addAll(packages);
        log.line("Libraries: " + res.artifacts.size() + " resolved (" + aars + " AAR, " + jars + " JAR)");
        return lp;
    }

    private static void addNative(LibraryPlan lp, Aar aar) {
        if (aar.jniDir == null) {
            return;
        }
        File[] abis = aar.jniDir.listFiles();
        if (abis == null) {
            return;
        }
        Arrays.sort(abis);
        for (File abi : abis) {
            if (!abi.isDirectory()) {
                continue;
            }
            if (!ABIS.contains(abi.getName())) {
                lp.notes.add("Skipped native libraries for " + abi.getName() + " (only arm64-v8a and armeabi-v7a are packaged).");
                continue;
            }
            File[] so = abi.listFiles();
            if (so == null) {
                continue;
            }
            Arrays.sort(so);
            for (File f : so) {
                if (f.isFile() && f.getName().endsWith(".so")) {
                    lp.nativeLibs.put("lib/" + abi.getName() + "/" + f.getName(), f);
                }
            }
        }
    }
}
