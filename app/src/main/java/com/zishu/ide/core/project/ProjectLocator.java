package com.zishu.ide.core.project;

import java.io.File;

/** Finds the real project root inside an extracted archive or copied folder. */
public final class ProjectLocator {
    private ProjectLocator() {
    }

    public static boolean looksLikeGradleProject(File dir) {
        return new File(dir, "settings.gradle").isFile() || new File(dir, "settings.gradle.kts").isFile()
                || new File(dir, "build.gradle").isFile() || new File(dir, "build.gradle.kts").isFile();
    }

    /** Returns the directory that contains the Gradle settings/build files, descending through single wrapper folders. */
    public static File findGradleRoot(File dir) {
        return find(dir, 0, false);
    }

    /** Returns the directory that holds index.html (web files), descending through single wrapper folders. */
    public static File findWebRoot(File dir) {
        return find(dir, 0, true);
    }

    private static File find(File dir, int depth, boolean web) {
        if (web ? new File(dir, "index.html").isFile() : looksLikeGradleProject(dir)) {
            return dir;
        }
        if (depth >= 3) {
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        File onlyDir = null;
        for (File c : children) {
            if (c.getName().startsWith(".") || c.getName().equals("__MACOSX")) {
                continue;
            }
            if (c.isDirectory()) {
                if (onlyDir != null) {
                    return null; // more than one candidate folder: ambiguous
                }
                onlyDir = c;
            } else {
                return null; // loose files next to a folder: this level is the root and it did not match
            }
        }
        return onlyDir == null ? null : find(onlyDir, depth + 1, web);
    }
}
