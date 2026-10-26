package com.zishu.ide.core.maven;

import com.zishu.ide.core.ondevice.ManifestPatcher;
import com.zishu.ide.core.project.SafeZip;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** An unpacked AAR (Android library): classes, resources, manifest, native libraries. */
public final class Aar {
    public final File dir;
    /** classes.jar or null. */
    public final File classesJar;
    /** libs/*.jar inside the AAR. */
    public final List<File> extraJars = new ArrayList<File>();
    public final File resDir;
    public final File assetsDir;
    public final File manifest;
    /** jni/ folder with one sub folder per ABI, or null. */
    public final File jniDir;
    public final String packageName;
    /** R.txt (the library's own resource symbols) or null. */
    public File rTxt;

    private Aar(File dir, File classes, File res, File assets, File manifest, File jni, String pkg) {
        this.dir = dir;
        this.classesJar = classes;
        this.resDir = res;
        this.assetsDir = assets;
        this.manifest = manifest;
        this.jniDir = jni;
        this.packageName = pkg;
    }

    /** Unpacks the AAR into {@code dir} (reusing an earlier unpack) and describes it. */
    public static Aar open(File aarFile, File dir) throws IOException {
        File done = new File(dir, ".unpacked");
        if (!done.isFile()) {
            if (dir.exists()) {
                IoUtil.deleteRecursively(dir);
            }
            InputStream in = new FileInputStream(aarFile);
            try {
                SafeZip.Limits lim = new SafeZip.Limits();
                lim.maxTotalBytes = 400L * 1024 * 1024;
                SafeZip.extract(in, dir, lim);
            } finally {
                IoUtil.closeQuietly(in);
            }
            IoUtil.writeUtf8Atomic(done, "ok");
        }
        File classes = new File(dir, "classes.jar");
        File res = new File(dir, "res");
        File assets = new File(dir, "assets");
        File manifest = new File(dir, "AndroidManifest.xml");
        File jni = new File(dir, "jni");
        String pkg = null;
        if (manifest.isFile()) {
            pkg = ManifestPatcher.declaredPackage(IoUtil.readUtf8(manifest, 2 * 1024 * 1024));
        }
        Aar a = new Aar(dir, classes.isFile() ? classes : null, res.isDirectory() && hasFiles(res) ? res : null,
                assets.isDirectory() ? assets : null, manifest.isFile() ? manifest : null,
                jni.isDirectory() ? jni : null, pkg);
        File rt = new File(dir, "R.txt");
        if (rt.isFile()) {
            a.rTxt = rt;
        }
        File[] libs = new File(dir, "libs").listFiles();
        if (libs != null) {
            Arrays.sort(libs);
            for (File j : libs) {
                if (j.isFile() && j.getName().endsWith(".jar")) {
                    a.extraJars.add(j);
                }
            }
        }
        return a;
    }

    private static boolean hasFiles(File dir) {
        File[] k = dir.listFiles();
        if (k == null) {
            return false;
        }
        for (File f : k) {
            if (f.isFile() || hasFiles(f)) {
                return true;
            }
        }
        return false;
    }
}
