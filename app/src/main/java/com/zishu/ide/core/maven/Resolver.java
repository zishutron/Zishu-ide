package com.zishu.ide.core.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves dependency coordinates to files: reads POMs, follows transitive dependencies, applies BOM
 * constraints and exclusions, and picks the highest requested version of each library (as Gradle does).
 * Results are ordered dependencies-first.
 */
public final class Resolver {

    public interface Fetcher {
        /** @return the local file for a repository path, downloading it if needed, or null when no repository has it */
        File fetch(String path) throws IOException;
    }

    public static final class Root {
        public final Coordinate coordinate;
        /** On the compile classpath (implementation, api, compileOnly). */
        public final boolean compile;
        /** Packaged into the app (implementation, api, runtimeOnly). */
        public final boolean runtime;
        public final List<String> excludes;

        public Root(Coordinate c, boolean compile, boolean runtime, List<String> excludes) {
            this.coordinate = c;
            this.compile = compile;
            this.runtime = runtime;
            this.excludes = excludes == null ? new ArrayList<String>() : excludes;
        }
    }

    public static final class Artifact {
        public final String group;
        public final String artifact;
        public final String version;
        /** The .jar or .aar, or null for a POM-only module (BOM, aggregator). */
        public final File file;
        public final boolean aar;
        public boolean compile;
        public boolean runtime;

        Artifact(String g, String a, String v, File f, boolean aar) {
            this.group = g;
            this.artifact = a;
            this.version = v;
            this.file = f;
            this.aar = aar;
        }

        public String key() {
            return group + ":" + artifact + ":" + version;
        }
    }

    public static final class Result {
        /** Dependencies before the libraries that use them. */
        public final List<Artifact> artifacts = new ArrayList<Artifact>();
        public final List<String> notes = new ArrayList<String>();
    }

    private static final int MAX_PASSES = 8;

    private final Fetcher fetcher;
    private final PomLoader poms;
    private final String where;

    public Resolver(Fetcher fetcher, String repositoryNames) {
        this.fetcher = fetcher;
        this.poms = new PomLoader(fetcher);
        this.where = repositoryNames;
    }

    private static final class Node {
        String group;
        String artifact;
        String version;
        boolean compile;
        boolean runtime;
        final List<String> children = new ArrayList<String>();
    }

    public Result resolve(List<Root> roots, Map<String, String> constraints) throws IOException {
        Map<String, String> minVersions = new LinkedHashMap<String, String>();
        Map<String, Node> nodes = null;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            nodes = new LinkedHashMap<String, Node>();
            for (Root r : roots) {
                String v = r.coordinate.version;
                if (v == null) {
                    v = constraints.get(r.coordinate.ga());
                }
                if (v == null) {
                    throw new IOException("No version for " + r.coordinate.ga() + ". Write it as group:artifact:version "
                            + "or declare a platform(...) BOM that manages it.");
                }
                walk(r.coordinate.group, r.coordinate.artifact, v, r.compile, r.runtime, r.excludes, nodes, minVersions,
                        constraints, new HashSet<String>());
            }
            boolean changed = false;
            for (Node n : nodes.values()) {
                String key = n.group + ":" + n.artifact;
                if (!n.version.equals(minVersions.get(key))) {
                    minVersions.put(key, n.version);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        Result res = new Result();
        Set<String> emitted = new HashSet<String>();
        for (Root r : roots) {
            emit(r.coordinate.group + ":" + r.coordinate.artifact, nodes, emitted, res);
        }
        return res;
    }

    private void emit(String ga, Map<String, Node> nodes, Set<String> emitted, Result res) throws IOException {
        Node n = nodes.get(ga);
        if (n == null || !emitted.add(ga)) {
            return;
        }
        for (String c : n.children) {
            emit(c, nodes, emitted, res);
        }
        PomLoader.Effective e = loadPom(n.group, n.artifact, n.version);
        String pk = e.packaging;
        if ("pom".equals(pk)) {
            return;
        }
        boolean aar = "aar".equals(pk);
        File f = fetcher.fetch(new Coordinate(n.group, n.artifact, n.version).path(aar ? "aar" : "jar"));
        if (f == null && !aar) {
            f = fetcher.fetch(new Coordinate(n.group, n.artifact, n.version).path("aar"));
            aar = f != null;
        }
        if (f == null) {
            throw new IOException("The file for " + n.group + ":" + n.artifact + ":" + n.version
                    + " was not found in " + where + ".");
        }
        Artifact a = new Artifact(n.group, n.artifact, n.version, f, aar);
        a.compile = n.compile;
        a.runtime = n.runtime;
        res.artifacts.add(a);
    }

    private PomLoader.Effective loadPom(String g, String a, String v) throws IOException {
        try {
            return poms.load(g, a, v);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("NOT_FOUND:")) {
                throw new IOException("Could not find " + g + ":" + a + ":" + v + " in " + where
                        + ". Check the name and version, and that the phone is online.");
            }
            throw e;
        }
    }

    private void walk(String g, String a, String requested, boolean compile, boolean runtime, List<String> excludes,
                      Map<String, Node> nodes, Map<String, String> minVersions, Map<String, String> constraints,
                      Set<String> path) throws IOException {
        String ga = g + ":" + a;
        if (isExcluded(ga, excludes)) {
            return;
        }
        if (requested.startsWith("[") || requested.startsWith("(") || requested.endsWith("+")) {
            requested = rangeLowerBound(requested, ga);
        }
        String v = Versions.max(Versions.max(requested, minVersions.get(ga)), constraints.get(ga));
        Node n = nodes.get(ga);
        if (n != null && Versions.compare(n.version, v) >= 0) {
            boolean grow = (compile && !n.compile) || (runtime && !n.runtime);
            if (!grow) {
                return;
            }
            v = n.version;
        }
        if (n == null) {
            n = new Node();
            n.group = g;
            n.artifact = a;
            nodes.put(ga, n);
        }
        n.version = v;
        n.compile |= compile;
        n.runtime |= runtime;
        n.children.clear();
        if (!path.add(ga)) {
            return; // dependency cycle
        }
        try {
            PomLoader.Effective e = loadPom(g, a, v);
            for (Pom.Dep d : e.deps) {
                String scope = d.scope == null ? "compile" : d.scope;
                if (d.optional || "test".equals(scope) || "provided".equals(scope) || "system".equals(scope)
                        || "import".equals(scope)) {
                    continue;
                }
                String cv = d.version;
                if (cv == null) {
                    cv = constraints.get(d.group + ":" + d.artifact);
                }
                if (cv == null) {
                    throw new IOException(g + ":" + a + ":" + v + " needs " + d.group + ":" + d.artifact
                            + " but its POM gives no version.");
                }
                List<String> ex = new ArrayList<String>(excludes);
                ex.addAll(d.exclusions);
                boolean childCompile = compile && !"runtime".equals(scope);
                n.children.add(d.group + ":" + d.artifact);
                walk(d.group, d.artifact, cv, childCompile, runtime, ex, nodes, minVersions, constraints, path);
            }
        } finally {
            path.remove(ga);
        }
    }

    private static String rangeLowerBound(String range, String ga) throws IOException {
        String r = range.trim();
        if (r.endsWith("+")) {
            throw new IOException("Dynamic version \"" + range + "\" of " + ga + " is not supported. Use a fixed version.");
        }
        String inner = r.substring(1, r.length() - 1);
        String first = inner.split(",")[0].trim();
        if (first.length() == 0) {
            first = inner.contains(",") ? inner.split(",")[1].trim() : inner;
        }
        if (first.length() == 0) {
            throw new IOException("Version range \"" + range + "\" of " + ga + " is not supported.");
        }
        return first;
    }

    static boolean isExcluded(String ga, List<String> excludes) {
        for (String x : excludes) {
            int c = x.indexOf(':');
            String xg = c < 0 ? x : x.substring(0, c);
            String xa = c < 0 ? "*" : x.substring(c + 1);
            int gc = ga.indexOf(':');
            String g = ga.substring(0, gc);
            String a = ga.substring(gc + 1);
            if (("*".equals(xg) || xg.equals(g)) && ("*".equals(xa) || xa.equals(a))) {
                return true;
            }
        }
        return false;
    }

    /** Version constraints from BOM (platform) POMs. */
    public Map<String, String> constraintsFrom(List<Coordinate> boms) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Coordinate b : boms) {
            if (b.version == null) {
                throw new IOException("platform(" + b.ga() + ") needs a version.");
            }
            PomLoader.Effective e = loadPom(b.group, b.artifact, b.version);
            for (Map.Entry<String, String> m : e.managed.entrySet()) {
                out.put(m.getKey(), Versions.max(out.get(m.getKey()), m.getValue()));
            }
        }
        return out;
    }
}
