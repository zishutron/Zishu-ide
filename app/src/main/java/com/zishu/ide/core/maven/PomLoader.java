package com.zishu.ide.core.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads POMs through a fetcher and resolves parent POMs, properties and managed versions. */
final class PomLoader {
    static final class Effective {
        String group;
        String artifact;
        String version;
        String packaging;
        final Map<String, String> props = new LinkedHashMap<String, String>();
        /** ga -> managed version (dependencyManagement, including imported BOMs). */
        final Map<String, String> managed = new LinkedHashMap<String, String>();
        final List<Pom.Dep> deps = new ArrayList<Pom.Dep>();
    }

    private final Resolver.Fetcher fetcher;
    private final Map<String, Effective> cache = new HashMap<String, Effective>();

    PomLoader(Resolver.Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    Effective load(String g, String a, String v) throws IOException {
        return load(g, a, v, 0);
    }

    private Effective load(String g, String a, String v, int depth) throws IOException {
        String key = g + ":" + a + ":" + v;
        Effective hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        if (depth > 12) {
            throw new IOException("POM parent chain is too deep at " + key);
        }
        String path = new Coordinate(g, a, v).path("pom");
        File f = fetcher.fetch(path);
        if (f == null) {
            throw new IOException("NOT_FOUND:" + key);
        }
        Pom raw = Pom.parse(com.zishu.ide.core.util.IoUtil.readFile(f, 4 * 1024 * 1024));
        Effective e = new Effective();
        Effective parent = null;
        if (raw.parentGroup != null && raw.parentArtifact != null && raw.parentVersion != null) {
            parent = load(raw.parentGroup, raw.parentArtifact, raw.parentVersion, depth + 1);
            e.props.putAll(parent.props);
            e.managed.putAll(parent.managed);
        }
        e.group = raw.group != null ? raw.group : (raw.parentGroup != null ? raw.parentGroup : g);
        e.artifact = raw.artifact != null ? raw.artifact : a;
        e.version = raw.version != null ? raw.version : (raw.parentVersion != null ? raw.parentVersion : v);
        e.packaging = raw.packaging == null ? "jar" : raw.packaging;
        e.props.putAll(raw.properties);
        e.props.put("project.groupId", e.group);
        e.props.put("project.artifactId", e.artifact);
        e.props.put("project.version", e.version);
        e.props.put("pom.version", e.version);
        e.props.put("version", e.version);
        e.props.put("groupId", e.group);
        if (raw.parentVersion != null) {
            e.props.put("project.parent.version", raw.parentVersion);
            e.props.put("parent.version", raw.parentVersion);
        }
        if (raw.parentGroup != null) {
            e.props.put("project.parent.groupId", raw.parentGroup);
        }
        for (Pom.Dep d : raw.managed) {
            String dg = interpolate(d.group, e.props);
            String da = interpolate(d.artifact, e.props);
            String dv = interpolate(d.version, e.props);
            if (dg == null || da == null || dv == null) {
                continue;
            }
            if ("import".equals(d.scope)) {
                try {
                    Effective bom = load(dg, da, dv, depth + 1);
                    for (Map.Entry<String, String> m : bom.managed.entrySet()) {
                        if (!e.managed.containsKey(m.getKey())) {
                            e.managed.put(m.getKey(), m.getValue());
                        }
                    }
                } catch (IOException notFound) {
                    // an unavailable BOM only loses version hints; real needs fail later with a clear message
                }
            } else {
                e.managed.put(dg + ":" + da, dv);
            }
        }
        for (Pom.Dep d : raw.dependencies) {
            Pom.Dep c = new Pom.Dep();
            c.group = interpolate(d.group, e.props);
            c.artifact = interpolate(d.artifact, e.props);
            c.version = interpolate(d.version, e.props);
            c.scope = d.scope;
            c.type = d.type;
            c.optional = d.optional;
            c.exclusions.addAll(d.exclusions);
            if (c.group == null || c.artifact == null) {
                continue;
            }
            if (c.version == null) {
                c.version = e.managed.get(c.group + ":" + c.artifact);
            }
            e.deps.add(c);
        }
        cache.put(key, e);
        return e;
    }

    static String interpolate(String s, Map<String, String> props) {
        if (s == null) {
            return null;
        }
        String cur = s;
        for (int i = 0; i < 10 && cur.contains("${"); i++) {
            StringBuilder out = new StringBuilder();
            int pos = 0;
            boolean changed = false;
            while (true) {
                int st = cur.indexOf("${", pos);
                if (st < 0) {
                    out.append(cur, pos, cur.length());
                    break;
                }
                int en = cur.indexOf('}', st);
                if (en < 0) {
                    out.append(cur, pos, cur.length());
                    break;
                }
                String name = cur.substring(st + 2, en);
                String val = props.get(name);
                out.append(cur, pos, st);
                if (val != null) {
                    out.append(val);
                    changed = true;
                } else {
                    out.append(cur, st, en + 1);
                }
                pos = en + 1;
            }
            cur = out.toString();
            if (!changed) {
                break;
            }
        }
        return cur;
    }
}
