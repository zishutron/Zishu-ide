package com.zishu.ide.core.maven;

/** group:artifact:version (version may be null when a BOM provides it). */
public final class Coordinate {
    public final String group;
    public final String artifact;
    public final String version;

    public Coordinate(String group, String artifact, String version) {
        this.group = group;
        this.artifact = artifact;
        this.version = version;
    }

    public String ga() {
        return group + ":" + artifact;
    }

    /** @return the coordinate, or null when the text is not group:artifact[:version][:classifier][@ext] */
    public static Coordinate parse(String text) {
        String t = text.trim();
        int at = t.indexOf('@');
        if (at >= 0) {
            t = t.substring(0, at);
        }
        String[] p = t.split(":");
        if (p.length < 2 || p.length > 4) {
            return null;
        }
        for (int i = 0; i < p.length; i++) {
            if (p[i].length() == 0 && !(i == 2)) {
                return null;
            }
            if (!p[i].matches("[A-Za-z0-9._+\\-\\[\\],()]*")) {
                return null;
            }
        }
        String v = p.length >= 3 && p[2].length() > 0 ? p[2] : null;
        return new Coordinate(p[0], p[1], v);
    }

    /** Repository path of a file of this coordinate, e.g. androidx/core/core/1.9.0/core-1.9.0.pom. */
    public String path(String extension) {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "." + extension;
    }

    @Override
    public String toString() {
        return group + ":" + artifact + (version == null ? "" : ":" + version);
    }
}
