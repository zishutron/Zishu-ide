package com.zishu.ide.core.ondevice;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildLog;

import java.io.File;
import java.util.List;

/**
 * The four real tools the on-device pipeline needs. The Android app provides implementations that run
 * aapt2, ECJ, D8 and apksig; tests provide fakes. Each method must really do the work or throw.
 */
public interface OnDeviceTools {

    /** Resources of one library (AAR): compiled once into {@code compiledZip}, which is reused by later builds. */
    final class ResLib {
        public final String name;
        public final File resDir;
        public final File compiledZip;

        public ResLib(String name, File resDir, File compiledZip) {
            this.name = name;
            this.resDir = resDir;
            this.compiledZip = compiledZip;
        }
    }

    /** Input of the resource step. {@code resDir} and {@code assetsDir} may be null when the project has none. */
    final class ResourceJob {
        public File manifest;
        public File resDir;
        public File assetsDir;
        public File androidJar;
        public File genDir;
        public File outApk;
        public File workDir;
        public int minSdk;
        public int targetSdk;
        public int versionCode;
        public String versionName;
        /** Final package id of the installed app (applicationId); R.java stays in the manifest package. */
        public String applicationId;
        /** Library resources in dependency order (a library's own dependencies first); the app's resources override them. */
        public List<ResLib> libs = new java.util.ArrayList<ResLib>();
        public List<File> extraAssetsDirs = new java.util.ArrayList<File>();
        /** Library packages that need their own R class (generated with aapt2 --extra-packages). */
        public List<String> extraPackages = new java.util.ArrayList<String>();
    }

    final class JavaJob {
        public List<File> sourceFiles;
        public List<File> classpath;
        public File outDir;
        /** Java language level (5..21) used for -source and -target. */
        public int javaLevel = JavaLevels.DEFAULT;
    }

    final class DexJob {
        public File classesDir;
        public List<File> libraryJars;
        public File androidJar;
        public int minSdk;
        public File outDir;
    }

    BuildEngine.Status status();

    /** The platform android.jar every project is compiled against. */
    File androidJar();

    /** API level of {@link #androidJar()} (for example 34). */
    int androidJarApi();

    /** aapt2 compile + link: writes {@code outApk} (manifest, resources.arsc, res, assets) and R.java into genDir. */
    void compileResources(ResourceJob job, BuildLog log) throws ToolException;

    /** Compiles Java to class files in {@code outDir}. */
    void compileJava(JavaJob job, BuildLog log) throws ToolException;

    /** Converts class files and library jars to one or more classes*.dex in {@code outDir}. */
    void dex(DexJob job, BuildLog log) throws ToolException;

    /** Signs {@code in} with the debug key and writes {@code out}; the result must pass APK verification. */
    void sign(File in, File out, BuildLog log) throws ToolException;
}
