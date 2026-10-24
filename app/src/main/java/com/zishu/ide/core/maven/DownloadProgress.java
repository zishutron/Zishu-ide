package com.zishu.ide.core.maven;

/** Told how far a download has come. {@code total} is -1 when the server did not say how big the file is. */
public interface DownloadProgress {
    void bytes(String path, long done, long total);
}
