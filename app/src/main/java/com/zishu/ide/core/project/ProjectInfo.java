package com.zishu.ide.core.project;

import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.Properties;

/** A project on disk plus the small amount of metadata Zishu IDE keeps in .zishu/project.properties. */
public final class ProjectInfo {
    public static final String META_DIR = ".zishu";
    public static final String META_FILE = "project.properties";

    public final File dir;
    public final String name;
    public final ProjectType type;
    public final Language language;
    public final String appName;
    public final String applicationId;
    public final String webUrl;
    public final long created;
    public final long lastOpened;

    public ProjectInfo(File dir, String name, ProjectType type, Language language, String appName,
                       String applicationId, String webUrl, long created, long lastOpened) {
        this.dir = dir;
        this.name = name;
        this.type = type;
        this.language = language;
        this.appName = appName;
        this.applicationId = applicationId;
        this.webUrl = webUrl;
        this.created = created;
        this.lastOpened = lastOpened;
    }

    public ProjectInfo withLastOpened(long time) {
        return new ProjectInfo(dir, name, type, language, appName, applicationId, webUrl, created, time);
    }

    public ProjectInfo withName(String newName) {
        return new ProjectInfo(dir, newName, type, language, appName, applicationId, webUrl, created, lastOpened);
    }

    public static File metaFile(File projectDir) {
        return new File(new File(projectDir, META_DIR), META_FILE);
    }

    /** Reads metadata; for directories without any, infers an imported project when Gradle files exist. */
    public static ProjectInfo read(File dir) {
        File meta = metaFile(dir);
        if (meta.isFile()) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(meta);
                Properties p = new Properties();
                p.load(new InputStreamReader(in, IoUtil.UTF8));
                return new ProjectInfo(dir,
                        p.getProperty("name", dir.getName()),
                        ProjectType.fromName(p.getProperty("type"), ProjectType.IMPORTED),
                        Language.fromName(p.getProperty("language"), Language.JAVA),
                        p.getProperty("appName", ""),
                        p.getProperty("applicationId", ""),
                        p.getProperty("webUrl", ""),
                        parseLong(p.getProperty("created"), dir.lastModified()),
                        parseLong(p.getProperty("lastOpened"), dir.lastModified()));
            } catch (IOException ignored) {
                // fall through to inference
            } finally {
                IoUtil.closeQuietly(in);
            }
        }
        if (ProjectLocator.looksLikeGradleProject(dir)) {
            long t = dir.lastModified();
            return new ProjectInfo(dir, dir.getName(), ProjectType.IMPORTED, Language.JAVA, "", "", "", t, t);
        }
        return null;
    }

    public void write() throws IOException {
        Properties p = new Properties();
        p.setProperty("name", name);
        p.setProperty("type", type.name());
        p.setProperty("language", language.name());
        p.setProperty("appName", appName == null ? "" : appName);
        p.setProperty("applicationId", applicationId == null ? "" : applicationId);
        p.setProperty("webUrl", webUrl == null ? "" : webUrl);
        p.setProperty("created", String.valueOf(created));
        p.setProperty("lastOpened", String.valueOf(lastOpened));
        StringWriter w = new StringWriter();
        p.store(w, "Zishu IDE project metadata");
        File meta = metaFile(dir);
        IoUtil.writeUtf8Atomic(meta, w.toString());
    }

    private static long parseLong(String s, long dflt) {
        try {
            return s == null ? dflt : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
