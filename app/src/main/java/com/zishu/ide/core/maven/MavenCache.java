package com.zishu.ide.core.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** A local folder that keeps everything downloaded from the repositories, so a library is fetched once. */
public final class MavenCache implements Resolver.Fetcher {
    /** Told before a library file (not a POM) is downloaded. */
    public interface Listener {
        void downloading(String path);
    }

    private Listener listener;
    private DownloadProgress progress;
    private final File dir;
    private final List<MavenRepo> repos;

    public MavenCache(File dir, List<MavenRepo> repos) {
        this.dir = dir;
        this.repos = new ArrayList<MavenRepo>(repos);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Progress of every library download over HTTPS (percent, size); optional. */
    public void setProgress(DownloadProgress p) {
        this.progress = p;
    }

    public File dir() {
        return dir;
    }

    public String repoNames() {
        StringBuilder sb = new StringBuilder();
        for (MavenRepo r : repos) {
            sb.append(sb.length() == 0 ? "" : ", ").append(r.name());
        }
        return sb.toString();
    }

    @Override
    public File fetch(String path) throws IOException {
        if (path.contains("..") || path.startsWith("/")) {
            throw new IOException("Refusing an unsafe repository path");
        }
        File local = new File(dir, path);
        if (local.isFile() && local.length() > 0) {
            return local;
        }
        File parent = local.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        File part = new File(parent, local.getName() + ".part");
        if (listener != null && (path.endsWith(".aar") || path.endsWith(".jar"))) {
            listener.downloading(path);
        }
        IOException last = null;
        for (MavenRepo r : repos) {
            try {
                boolean got = progress != null && r instanceof HttpMavenRepo && (path.endsWith(".aar") || path.endsWith(".jar"))
                        ? ((HttpMavenRepo) r).download(path, part, progress) : r.download(path, part);
                if (got) {
                    if (!part.renameTo(local)) {
                        throw new IOException("Cannot store " + local.getName());
                    }
                    return local;
                }
            } catch (IOException e) {
                last = e;
                part.delete();
            }
        }
        part.delete();
        if (last != null) {
            throw last;
        }
        return null;
    }
}
