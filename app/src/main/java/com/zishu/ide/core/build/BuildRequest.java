package com.zishu.ide.core.build;

import com.zishu.ide.core.project.ProjectInfo;

import java.io.File;

public final class BuildRequest {
    public final ProjectInfo project;
    /** Only "debug" is supported in this release. */
    public final String variant;
    /** Directory the APK is saved into (created by the engine). */
    public final File outputDir;

    public BuildRequest(ProjectInfo project, String variant, File outputDir) {
        this.project = project;
        this.variant = variant;
        this.outputDir = outputDir;
    }
}
