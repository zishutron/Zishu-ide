package com.zishu.ide.core.project;

import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.template.ProjectTemplates;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.validation.Issue;
import com.zishu.ide.core.validation.ValidationException;
import com.zishu.ide.core.validation.ValidationReport;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Creates, imports, lists and deletes projects below one workspace directory. A new or imported
 * project always gets its own fresh directory; an existing directory is never reused or overwritten.
 */
public final class ProjectManager {
    public static final long MAX_WEB_IMPORT_BYTES = 50L * 1024 * 1024;

    public static final class NotAProjectException extends IOException {
        public NotAProjectException(String message) {
            super(message);
        }
    }

    private final File root;

    public ProjectManager(File root) throws IOException {
        this.root = root;
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("Cannot create workspace " + root);
        }
    }

    public File getRoot() {
        return root;
    }

    public List<ProjectInfo> list() {
        List<ProjectInfo> out = new ArrayList<ProjectInfo>();
        File[] children = root.listFiles();
        if (children != null) {
            for (File c : children) {
                if (!c.isDirectory() || c.getName().startsWith(".")) {
                    continue;
                }
                ProjectInfo info = ProjectInfo.read(c);
                if (info != null) {
                    out.add(info);
                }
            }
        }
        Collections.sort(out, new Comparator<ProjectInfo>() {
            @Override
            public int compare(ProjectInfo a, ProjectInfo b) {
                return a.lastOpened == b.lastOpened ? a.name.compareToIgnoreCase(b.name)
                        : (a.lastOpened > b.lastOpened ? -1 : 1);
            }
        });
        return out;
    }

    public ProjectInfo find(String dirName) {
        File d = new File(root, dirName);
        return d.isDirectory() ? ProjectInfo.read(d) : null;
    }

    /** A directory name derived from desired that does not exist yet ("name", "name-2", ...). */
    public File uniqueDir(String desired) {
        String base = sanitizeDirName(desired);
        File d = new File(root, base);
        int i = 2;
        while (d.exists()) {
            d = new File(root, base + "-" + i++);
        }
        return d;
    }

    static String sanitizeDirName(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == ' ' ? c : '_');
        }
        String t = sb.toString().trim();
        while (t.startsWith(".")) {
            t = t.substring(1);
        }
        if (t.length() == 0) {
            t = "project";
        }
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    // ------------------------------------------------------------------ create

    public ProjectInfo create(ProjectConfig config) throws IOException, ValidationException {
        Map<String, byte[]> files = ProjectTemplates.generate(config);
        return writeNewProject(config, files);
    }

    /**
     * Creates a local WebView project whose pages come from a ZIP of the user's own HTML/CSS/JS.
     * The archive must contain an index.html (at its top level or inside one wrapper folder).
     */
    public ProjectInfo createLocalWebFromZip(ProjectConfig config, InputStream zip)
            throws IOException, ValidationException {
        if (config.type != ProjectType.WEBVIEW_LOCAL) {
            throw new IllegalArgumentException("Only local WebView projects can import web files");
        }
        Map<String, byte[]> files = ProjectTemplates.generate(config);
        File tmp = new File(root, ".import-" + System.nanoTime());
        try {
            SafeZip.Limits limits = new SafeZip.Limits();
            limits.maxTotalBytes = MAX_WEB_IMPORT_BYTES;
            SafeZip.extract(zip, tmp, limits);
            File webRoot = ProjectLocator.findWebRoot(tmp);
            if (webRoot == null) {
                ValidationReport r = new ValidationReport();
                r.add(Issue.error("web-entry", "The archive has no index.html at its top level.",
                        "Put index.html in the root of the ZIP (or inside a single folder) and try again."));
                throw new ValidationException(r);
            }
            // Keep the template's error.html unless the archive provides its own.
            byte[] templateError = files.get(ProjectTemplates.WEB_ROOT + "error.html");
            List<String> remove = new ArrayList<String>();
            for (String key : files.keySet()) {
                if (key.startsWith(ProjectTemplates.WEB_ROOT)) {
                    remove.add(key);
                }
            }
            for (String key : remove) {
                files.remove(key);
            }
            collectFiles(webRoot, "", ProjectTemplates.WEB_ROOT, files, new long[]{0});
            if (!files.containsKey(ProjectTemplates.WEB_ROOT + "error.html")) {
                files.put(ProjectTemplates.WEB_ROOT + "error.html", templateError);
            }
        } finally {
            try {
                IoUtil.deleteRecursively(tmp);
            } catch (IOException ignored) {
                // leftover hidden temp folder is harmless
            }
        }
        return writeNewProject(config, files);
    }

    private void collectFiles(File dir, String rel, String targetPrefix, Map<String, byte[]> out, long[] total)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            String r = rel.length() == 0 ? c.getName() : rel + "/" + c.getName();
            if (c.isDirectory()) {
                collectFiles(c, r, targetPrefix, out, total);
            } else if (c.isFile()) {
                total[0] += c.length();
                if (total[0] > MAX_WEB_IMPORT_BYTES) {
                    throw new IOException("Web files are larger than " + IoUtil.formatSize(MAX_WEB_IMPORT_BYTES));
                }
                out.put(targetPrefix + r, IoUtil.readFile(c, MAX_WEB_IMPORT_BYTES));
            }
        }
    }

    private ProjectInfo writeNewProject(ProjectConfig config, Map<String, byte[]> files) throws IOException {
        File dir = uniqueDir(config.projectName);
        if (!dir.mkdir()) {
            throw new IOException("Cannot create project folder " + dir.getName());
        }
        try {
            FileManager fm = new FileManager(dir);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                File target = fm.resolve(e.getKey());
                IoUtil.writeAtomic(target, e.getValue());
            }
            long now = System.currentTimeMillis();
            ProjectInfo info = new ProjectInfo(dir, config.projectName.trim(), config.type, config.language,
                    config.appName.trim(), config.applicationId,
                    config.type == ProjectType.WEBVIEW_SITE ? config.webUrl.trim() : "", now, now);
            info.write();
            return info;
        } catch (IOException e) {
            deleteQuietly(dir);
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(dir);
            throw e;
        }
    }

    // ------------------------------------------------------------------ import

    /** What an import produced: the registered project and everything the importer decided on the way. */
    public static final class ImportOutcome {
        public final ProjectInfo info;
        public final java.util.List<String> notes;

        ImportOutcome(ProjectInfo info, java.util.List<String> notes) {
            this.info = info;
            this.notes = notes;
        }
    }

    /** Imports a ZIP containing an Android project, with or without Gradle files. */
    public ProjectInfo importZip(InputStream zip, String suggestedName) throws IOException {
        return importZipDetailed(zip, suggestedName).info;
    }

    public ImportOutcome importZipDetailed(InputStream zip, String suggestedName) throws IOException {
        File tmp = new File(root, ".import-" + System.nanoTime());
        try {
            SafeZip.extract(zip, tmp, new SafeZip.Limits());
            return adopt(tmp, suggestedName);
        } finally {
            try {
                IoUtil.deleteRecursively(tmp);
            } catch (IOException ignored) {
                // hidden temp folder, harmless
            }
        }
    }

    /**
     * Normalises an extracted tree (ZIP, cloned repository) and moves it into the workspace as a new
     * project. The extracted directory is consumed.
     */
    public ImportOutcome adopt(File extracted, String suggestedName) throws IOException {
        String base = suggestedName == null || suggestedName.trim().length() == 0 ? "imported" : suggestedName.trim();
        if (base.toLowerCase(java.util.Locale.US).endsWith(".zip")) {
            base = base.substring(0, base.length() - 4);
        }
        ProjectImporter.Result r;
        try {
            r = ProjectImporter.normalize(extracted, base);
        } catch (ProjectImporter.NoAppFoundException e) {
            if (ProjectLocator.findWebRoot(extracted) != null) {
                throw new NotAProjectException("This archive holds web files (it has an index.html) but no Android "
                        + "app. Create a Local WebView project and choose \"Use web files from a ZIP\".");
            }
            throw new NotAProjectException(e.getMessage());
        }
        File dest = uniqueDir(base);
        if (!r.root.renameTo(dest)) {
            throw new IOException("Could not move the imported project into the workspace");
        }
        return new ImportOutcome(writeImportedInfo(dest, dest.getName(), r), r.notes);
    }

    /** Registers a directory that was copied into the workspace (e.g. from a document tree). */
    public ProjectInfo registerImported(File dir, String name) throws IOException {
        return registerImportedDetailed(dir, name).info;
    }

    public ImportOutcome registerImportedDetailed(File dir, String name) throws IOException {
        ProjectImporter.Result r;
        try {
            r = ProjectImporter.normalize(dir, name);
        } catch (ProjectImporter.NoAppFoundException e) {
            throw new NotAProjectException(e.getMessage());
        }
        File finalDir = dir;
        if (!r.root.equals(dir)) {
            finalDir = uniqueDir(name);
            if (!r.root.renameTo(finalDir)) {
                throw new IOException("Could not move the project folder");
            }
            deleteQuietly(dir);
        }
        return new ImportOutcome(writeImportedInfo(finalDir, name, r), r.notes);
    }

    private ProjectInfo writeImportedInfo(File dir, String name, ProjectImporter.Result r) throws IOException {
        long now = System.currentTimeMillis();
        ProjectInfo info = new ProjectInfo(dir, name, ProjectType.IMPORTED, r.language, r.appName, r.applicationId,
                "", now, now);
        info.write();
        return info;
    }

    // ------------------------------------------------------------------ misc

    public ProjectInfo touch(ProjectInfo info) throws IOException {
        ProjectInfo updated = info.withLastOpened(System.currentTimeMillis());
        updated.write();
        return updated;
    }

    public void delete(ProjectInfo info) throws IOException {
        File dir = info.dir.getCanonicalFile();
        if (!dir.getParentFile().equals(root.getCanonicalFile())) {
            throw new IOException("Refusing to delete a folder outside the workspace");
        }
        IoUtil.deleteRecursively(dir);
    }

    public void exportZip(ProjectInfo info, OutputStream out) throws IOException {
        // Exports keep everything except build output and IDE metadata.
        SafeZip.zipDirectory(info.dir, out, SafeZip.DEFAULT_EXCLUDES, 1024L * 1024 * 1024);
    }

    private static void deleteQuietly(File dir) {
        try {
            IoUtil.deleteRecursively(dir);
        } catch (IOException ignored) {
            // best effort cleanup after a failed create
        }
    }

}
