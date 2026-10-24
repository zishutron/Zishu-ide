package com.zishu.ide.core.maven;

import java.io.File;

/** Where the on-device build gets libraries from (repositories + local cache) and where it unpacks AARs. */
public final class MavenDependencies {
    private final MavenCache cache;
    private final File unpackRoot;

    public MavenDependencies(MavenCache cache, File unpackRoot) {
        this.cache = cache;
        this.unpackRoot = unpackRoot;
    }

    public MavenCache cache() {
        return cache;
    }

    public File unpackDir(Resolver.Artifact a) {
        return new File(unpackRoot, a.group.replace('.', '_') + "__" + a.artifact + "__" + a.version);
    }

    /** The same cache and unpack folder but no repositories: anything not already downloaded is simply missing. */
    public MavenDependencies offline() {
        return new MavenDependencies(new MavenCache(cache.dir(), new java.util.ArrayList<MavenRepo>()), unpackRoot);
    }

    public Resolver resolver() {
        return new Resolver(cache, cache.repoNames());
    }
}
