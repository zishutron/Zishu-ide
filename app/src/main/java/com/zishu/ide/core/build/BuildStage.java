package com.zishu.ide.core.build;

/** The stages a build moves through. Not every engine uses every stage. */
public enum BuildStage {
    VALIDATE("Validating project"),
    PACKAGE("Packaging project"),
    UPLOAD("Uploading to your repository"),
    QUEUE("Waiting for a runner"),
    BUILD("Building"),
    DOWNLOAD("Downloading APK"),
    VERIFY("Verifying APK"),
    CLEANUP("Cleaning up"),
    DONE("Finished");

    public final String label;

    BuildStage(String label) {
        this.label = label;
    }
}
