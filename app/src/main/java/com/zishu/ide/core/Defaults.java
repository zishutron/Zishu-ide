package com.zishu.ide.core.model;

/**
 * Toolchain versions used by generated projects. AGP 8.7 requires Gradle 8.9 and JDK 17 and
 * supports up to API 35 (see https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes).
 */
public final class Defaults {
    public static final String AGP_VERSION = "8.7.3";
    public static final String GRADLE_VERSION = "8.9";
    public static final String KOTLIN_VERSION = "2.0.21";
    public static final int JDK_VERSION = 17;
    public static final String BUILD_TOOLS_VERSION = "34.0.0";

    public static final int MIN_SDK = 21;
    public static final int TARGET_SDK = 35;
    public static final int COMPILE_SDK = 35;
    /** Lowest minSdk the generated templates support. */
    public static final int LOWEST_MIN_SDK = 21;
    /** Highest API level the pinned AGP is documented to support. */
    public static final int HIGHEST_COMPILE_SDK = 35;

    /** Incremented whenever assets/cloud/zishu-build.yml changes in a way the app depends on. */
    public static final int CLOUD_WORKFLOW_VERSION = 1;

    private Defaults() {
    }
}
