package com.zishu.ide.core.maven;

import java.io.File;
import java.io.IOException;

/** One Maven repository (Google, Maven Central ...). */
public interface MavenRepo {
    String name();

    /** Downloads {@code path} (for example androidx/core/core/1.9.0/core-1.9.0.pom) to {@code dest}. @return false when the repository does not have it */
    boolean download(String path, File dest) throws IOException;
}
