package com.zishu.ide.core.project;

import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * File operations confined to one project directory. Every path is a project-relative string
 * using '/' and is resolved through {@link #resolve(String)}, which rejects anything that would
 * leave the project (including through symbolic links). Nothing is ever overwritten silently.
 */
public final class FileManager {
    public static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;

    public static final class FileExistsException extends IOException {
        public FileExistsException(String message) {
            super(message);
        }
    }

    public static final class NotTextException extends IOException {
        public NotTextException(String message) {
            super(message);
        }
    }

    public static final class FileNode {
        public final String path;
        public final String name;
        public final boolean directory;
        public final long size;

        FileNode(String path, String name, boolean directory, long size) {
            this.path = path;
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    /** Text content plus the line-ending style, so saving does not rewrite CRLF files as LF. */
    public static final class TextFile {
        public final String text;
        public final boolean crlf;

        public TextFile(String text, boolean crlf) {
            this.text = text;
            this.crlf = crlf;
        }
    }

    private final File root;
    private final String rootCanonical;

    public FileManager(File root) throws IOException {
        this.root = root;
        this.rootCanonical = root.getCanonicalPath();
    }

    public File getRoot() {
        return root;
    }

    public File resolve(String rel) throws IOException {
        if (rel == null) {
            throw new IOException("No path given");
        }
        if (rel.indexOf('\0') >= 0 || rel.startsWith("/") || rel.indexOf('\\') >= 0) {
            throw new IOException("Invalid path: " + rel);
        }
        for (String seg : rel.split("/", -1)) {
            if (seg.equals("..")) {
                throw new IOException("Path leaves the project: " + rel);
            }
        }
        File f = rel.length() == 0 ? root : new File(root, rel);
        String canonical = f.getCanonicalPath();
        if (!canonical.equals(rootCanonical) && !canonical.startsWith(rootCanonical + File.separator)) {
            throw new IOException("Path leaves the project: " + rel);
        }
        return f;
    }

    public static void validateName(String name) throws IOException {
        if (name == null || name.trim().length() == 0) {
            throw new IOException("The name is empty");
        }
        if (name.equals(".") || name.equals("..") || name.length() > 200) {
            throw new IOException("Invalid name: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || "/\\:*?\"<>|".indexOf(c) >= 0) {
                throw new IOException("The name contains a character that is not allowed: '" + c + "'");
            }
        }
    }

    /** Throws if any folder on the way to {@code rel} is a link: an import never goes through links. */
    private void requireNoLinks(String rel) throws IOException {
        String acc = "";
        if (rel.length() == 0) {
            return;
        }
        for (String seg : rel.split("/")) {
            acc = acc.length() == 0 ? seg : acc + "/" + seg;
            if (IoUtil.isSymlink(resolveNoFollow(acc))) {
                throw new IOException("\"" + acc + "\" is a link. Choose a real folder.");
            }
        }
    }

    /** A new temporary folder for extracting a ZIP, inside the project's own metadata folder (which must be a real folder). */
    public File newImportTempDir() throws IOException {
        File meta = new File(root, METADATA_DIR);
        if (IoUtil.isSymlink(meta)) {
            throw new IOException("The project's .zishu folder is a link, so nothing can be imported safely.");
        }
        File tmp = resolve(METADATA_DIR + "/import-" + System.nanoTime());
        return tmp;
    }

    private File resolveNoFollow(String rel) {
        return new File(root, rel.replace('/', File.separatorChar));
    }

    private String relOf(File f) throws IOException {
        String canonical = f.getCanonicalPath();
        if (canonical.equals(rootCanonical)) {
            return "";
        }
        return canonical.substring(rootCanonical.length() + 1).replace(File.separatorChar, '/');
    }

    private static String childPath(String parent, String name) {
        return parent.length() == 0 ? name : parent + "/" + name;
    }

    public List<FileNode> list(String relDir) throws IOException {
        File dir = resolve(relDir);
        File[] children = dir.listFiles();
        List<FileNode> out = new ArrayList<FileNode>();
        if (children == null) {
            return out;
        }
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File c : children) {
            if (c.getName().equals(".zishu")) {
                continue; // IDE metadata, not part of the user's project
            }
            out.add(new FileNode(childPath(relDir, c.getName()), c.getName(), c.isDirectory(), c.length()));
        }
        return out;
    }

    public String createFile(String relDir, String name) throws IOException {
        validateName(name);
        File dir = resolve(relDir);
        if (!dir.isDirectory()) {
            throw new IOException("Not a folder: " + relDir);
        }
        File f = resolve(childPath(relDir, name));
        if (f.exists()) {
            throw new FileExistsException("\"" + name + "\" already exists");
        }
        if (!f.createNewFile()) {
            throw new IOException("Could not create " + name);
        }
        return childPath(relDir, name);
    }

    public String createDirectory(String relDir, String name) throws IOException {
        validateName(name);
        File dir = resolve(relDir);
        if (!dir.isDirectory()) {
            throw new IOException("Not a folder: " + relDir);
        }
        File f = resolve(childPath(relDir, name));
        if (f.exists()) {
            throw new FileExistsException("\"" + name + "\" already exists");
        }
        if (!f.mkdir()) {
            throw new IOException("Could not create folder " + name);
        }
        return childPath(relDir, name);
    }

    public String rename(String rel, String newName) throws IOException {
        validateName(newName);
        if (rel.length() == 0) {
            throw new IOException("The project root cannot be renamed here");
        }
        File src = resolve(rel);
        if (!src.exists()) {
            throw new IOException("Not found: " + rel);
        }
        File parent = src.getParentFile();
        File dst = new File(parent, newName);
        if (dst.exists()) {
            throw new FileExistsException("\"" + newName + "\" already exists");
        }
        if (!src.renameTo(dst)) {
            throw new IOException("Could not rename " + src.getName());
        }
        return relOf(dst);
    }

    public void delete(String rel) throws IOException {
        if (rel.length() == 0) {
            throw new IOException("The project root cannot be deleted here");
        }
        File f = resolve(rel);
        IoUtil.deleteRecursively(f);
    }

    public String copy(String rel, String destDirRel) throws IOException {
        File src = resolve(rel);
        File destDir = resolve(destDirRel);
        if (!src.exists()) {
            throw new IOException("Not found: " + rel);
        }
        if (!destDir.isDirectory()) {
            throw new IOException("Not a folder: " + destDirRel);
        }
        File dst = new File(destDir, src.getName());
        if (dst.exists()) {
            throw new FileExistsException("\"" + src.getName() + "\" already exists in the target folder");
        }
        if (src.isDirectory() && (dst.getCanonicalPath() + File.separator).startsWith(src.getCanonicalPath() + File.separator)) {
            throw new IOException("A folder cannot be copied into itself");
        }
        copyTree(src, dst);
        return relOf(dst);
    }

    private void copyTree(File src, File dst) throws IOException {
        if (IoUtil.isSymlink(src)) {
            return;
        }
        if (src.isDirectory()) {
            if (!dst.mkdir()) {
                throw new IOException("Could not create folder " + dst.getName());
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) {
                    copyTree(c, new File(dst, c.getName()));
                }
            }
        } else {
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(dst);
                IoUtil.copy(in, out);
            } finally {
                IoUtil.closeQuietly(in);
                IoUtil.closeQuietly(out);
            }
        }
    }

    public String move(String rel, String destDirRel) throws IOException {
        if (rel.length() == 0) {
            throw new IOException("The project root cannot be moved");
        }
        File src = resolve(rel);
        File destDir = resolve(destDirRel);
        if (!src.exists()) {
            throw new IOException("Not found: " + rel);
        }
        if (!destDir.isDirectory()) {
            throw new IOException("Not a folder: " + destDirRel);
        }
        File dst = new File(destDir, src.getName());
        if (dst.exists()) {
            throw new FileExistsException("\"" + src.getName() + "\" already exists in the target folder");
        }
        if (src.isDirectory() && (dst.getCanonicalPath() + File.separator).startsWith(src.getCanonicalPath() + File.separator)) {
            throw new IOException("A folder cannot be moved into itself");
        }
        if (!src.renameTo(dst)) {
            copyTree(src, dst);
            IoUtil.deleteRecursively(src);
        }
        return relOf(dst);
    }

    /** All directories (project-relative, "" for the root), used by the copy/move folder picker. */
    public List<String> listAllDirectories() throws IOException {
        List<String> out = new ArrayList<String>();
        out.add("");
        collectDirs("", out, 0);
        return out;
    }

    private void collectDirs(String rel, List<String> out, int depth) throws IOException {
        if (depth > 12 || out.size() > 2000) {
            return;
        }
        for (FileNode n : list(rel)) {
            if (n.directory && !IoUtil.isSymlink(resolve(n.path))) {
                out.add(n.path);
                collectDirs(n.path, out, depth + 1);
            }
        }
    }

    /** Imports bytes as a new file (never overwrites). */
    public String importFile(String relDir, String name, java.io.InputStream in, long maxBytes) throws IOException {
        validateName(name);
        File dir = resolve(relDir);
        if (!dir.isDirectory()) {
            throw new IOException("Not a folder: " + relDir);
        }
        File f = resolve(childPath(relDir, name));
        if (f.exists()) {
            throw new FileExistsException("\"" + name + "\" already exists");
        }
        byte[] data = IoUtil.readAll(in, maxBytes);
        IoUtil.writeAtomic(f, data);
        return childPath(relDir, name);
    }

    // ---- text access ----

    public static boolean looksBinary(byte[] data, int length) {
        int n = Math.min(length, 8000);
        for (int i = 0; i < n; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    public TextFile readText(String rel) throws IOException {
        File f = resolve(rel);
        if (!f.isFile()) {
            throw new IOException("Not a file: " + rel);
        }
        if (f.length() > MAX_TEXT_BYTES) {
            throw new NotTextException("The file is larger than " + IoUtil.formatSize(MAX_TEXT_BYTES)
                    + " and cannot be opened in the editor.");
        }
        byte[] data = IoUtil.readFile(f, MAX_TEXT_BYTES);
        if (looksBinary(data, data.length)) {
            throw new NotTextException("This looks like a binary file and cannot be edited as text.");
        }
        String text = new String(data, IoUtil.UTF8);
        boolean crlf = text.indexOf("\r\n") >= 0;
        if (text.indexOf('\r') >= 0) {
            text = text.replace("\r\n", "\n").replace('\r', '\n');
        }
        return new TextFile(text, crlf);
    }

    public void writeText(String rel, String text, boolean crlf) throws IOException {
        File f = resolve(rel);
        if (f.isDirectory()) {
            throw new IOException("Not a file: " + rel);
        }
        String out = crlf ? text.replace("\n", "\r\n") : text;
        IoUtil.writeAtomic(f, out.getBytes(IoUtil.UTF8));
    }

    // ---- import and search ----

    /** What to do when an imported file has the name of a file that is already in the project. */
    public enum Conflict {
        /** The new file gets a free name: "Main (1).java". */
        KEEP_BOTH,
        /** The old file is replaced by the new one. */
        REPLACE,
        /** The new file is not added. */
        SKIP
    }

    /** Result of {@link #mergeDirectory}. */
    public static final class MergeResult {
        public int added;
        /** Added under a new name because the name was taken (KEEP_BOTH). */
        public int renamed;
        /** Old files that were replaced (REPLACE); their project-relative paths are in {@link #replacedPaths}. */
        public int replaced;
        public int skipped;
        public final List<String> replacedPaths = new ArrayList<String>();
    }

    /** Replaces characters a file name cannot have, so a name supplied by another app can be used. */
    public static String safeName(String raw) {
        String n = raw == null ? "" : raw.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            sb.append(c < 0x20 || "/\\:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String r = sb.toString();
        if (r.length() == 0 || r.equals(".") || r.equals("..")) {
            return "file";
        }
        return r.length() > 200 ? r.substring(r.length() - 200) : r;
    }

    /** "photo.png" becomes "photo (1).png", "photo (2).png" ... until the name is free in {@code dir}. */
    public static String freeName(File dir, String name) {
        if (!new File(dir, name).exists()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String c = base + " (" + i + ")" + ext;
            if (!new File(dir, c).exists()) {
                return c;
            }
        }
        return base + " (" + System.nanoTime() + ")" + ext;
    }

    public String importStream(String relDir, String rawName, java.io.InputStream in, long maxBytes) throws IOException {
        return importStream(relDir, rawName, in, maxBytes, Conflict.KEEP_BOTH);
    }

    /**
     * Copies a stream (any file type, any size up to maxBytes) into a folder of the project without holding it in memory.
     * If the name is taken, {@code onConflict} decides: a free name, replacing the old file, or nothing (returns null).
     * Returns the project-relative path of the new file.
     */
    public String importStream(String relDir, String rawName, java.io.InputStream in, long maxBytes, Conflict onConflict) throws IOException {
        File dir = resolve(relDir);
        if (!dir.isDirectory()) {
            throw new IOException("Not a folder: " + relDir);
        }
        requireNoLinks(relDir);
        String name = safeName(rawName);
        if (name.equals(METADATA_DIR)) {
            name = "zishu-file";
        }
        File existing = new File(dir, name);
        if (existing.exists()) {
            if (onConflict == Conflict.SKIP) {
                return null;
            }
            if (onConflict == Conflict.KEEP_BOTH || existing.isDirectory()) {
                name = freeName(dir, name);
            }
        }
        File dest = resolve(childPath(relDir, name));
        File tmp = new File(dir, ".zishu-import-" + System.nanoTime() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        boolean ok = false;
        try {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IOException("\"" + name + "\" is larger than " + IoUtil.formatSize(maxBytes));
                }
                out.write(buf, 0, n);
            }
            out.close();
            if (dest.isFile()) {
                replaceFile(tmp, dest);
            } else if (!tmp.renameTo(dest)) {
                throw new IOException("Could not save " + name);
            }
            ok = true;
        } finally {
            IoUtil.closeQuietly(out);
            if (!ok) {
                tmp.delete();
            }
        }
        return childPath(relDir, name);
    }

    /** True when a file (not a folder) with this name is already in the project folder. */
    public boolean fileExists(String relDir, String rawName) throws IOException {
        File f = resolve(childPath(relDir, safeName(rawName)));
        return f.isFile();
    }

    /** Project-relative paths of the files in {@code srcDir} that would land on a file the project already has. */
    public List<String> conflicts(File srcDir, String relDestDir) throws IOException {
        requireNoLinks(relDestDir);
        List<String> out = new ArrayList<String>();
        collectConflicts(srcDir, relDestDir, out, 0);
        return out;
    }

    private void collectConflicts(File src, String relDir, List<String> out, int depth) throws IOException {
        File[] kids = src.listFiles();
        if (kids == null || depth > 40) {
            return;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            if (IoUtil.isSymlink(k) || k.getName().equals(METADATA_DIR)) {
                continue;
            }
            String rel = childPath(relDir, k.getName());
            if (IoUtil.isSymlink(resolveNoFollow(rel))) {
                continue;
            }
            if (k.isDirectory()) {
                collectConflicts(k, rel, out, depth + 1);
            } else if (resolve(rel).isFile()) {
                out.add(rel);
            }
        }
    }

    private static final String METADATA_DIR = ".zishu";

    private static void moveFile(File from, File to) throws IOException {
        if (from.renameTo(to)) {
            return;
        }
        FileInputStream fin = new FileInputStream(from);
        try {
            importStreamTo(to, fin);
        } catch (IOException e) {
            to.delete();
            throw e;
        } finally {
            IoUtil.closeQuietly(fin);
        }
        from.delete();
    }

    /** Puts {@code from} in place of the existing file {@code to}; if that fails the old file is put back. */
    public static void replaceFile(File from, File to) throws IOException {
        File backup = new File(to.getParentFile(), ".zishu-old-" + System.nanoTime() + ".tmp");
        if (!to.renameTo(backup)) {
            throw new IOException("Could not replace " + to.getName());
        }
        try {
            moveFile(from, to);
        } catch (IOException e) {
            to.delete();
            backup.renameTo(to);
            throw e;
        } catch (RuntimeException e) {
            to.delete();
            backup.renameTo(to);
            throw e;
        }
        backup.delete();
    }

    private static void importStreamTo(File target, java.io.InputStream in) throws IOException {
        FileOutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            IoUtil.closeQuietly(out);
        }
    }

    private static final java.util.Set<String> PROJECT_FOLDERS = new java.util.HashSet<String>(Arrays.asList(
            "app", "src", "main", "java", "kotlin", "res", "assets", "libs", "jnilibs", "gradle", "build", "test", "androidtest"));

    /**
     * A ZIP made by zipping a folder holds just that folder ("ZishuOS/..."). The files should land in the project as if the
     * ZIP had been opened: when the only entry is one folder, its contents are used instead. A folder that is itself a project
     * folder (app, src, res ...) is kept, because then its name is part of the path the files belong to.
     */
    public static File unwrapSingleFolder(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null || kids.length != 1 || !kids[0].isDirectory()
                || PROJECT_FOLDERS.contains(kids[0].getName().toLowerCase(java.util.Locale.US))) {
            return dir;
        }
        try {
            if (IoUtil.isSymlink(kids[0])) {
                return dir;
            }
        } catch (IOException e) {
            return dir;
        }
        return kids[0];
    }

    public MergeResult mergeDirectory(File srcDir, String relDestDir) throws IOException {
        return mergeDirectory(srcDir, relDestDir, Conflict.KEEP_BOTH);
    }

    /**
     * Moves everything inside {@code srcDir} (a folder outside the project, for example a ZIP extracted to a temporary place)
     * into a project folder, keeping its sub-folders. When a file with the same name exists, {@code onConflict} decides:
     * the new file is added as "Main (1).java", replaces the old one, or is skipped.
     */
    public MergeResult mergeDirectory(File srcDir, String relDestDir, Conflict onConflict) throws IOException {
        File dest = resolve(relDestDir);
        if (!dest.isDirectory()) {
            throw new IOException("Not a folder: " + relDestDir);
        }
        requireNoLinks(relDestDir);
        MergeResult r = new MergeResult();
        mergeInto(srcDir, dest, relDestDir, r, onConflict, 0);
        return r;
    }

    private void mergeInto(File src, File dest, String rel, MergeResult r, Conflict onConflict, int depth) throws IOException {
        if (depth > 40) {
            throw new IOException("The folders are nested too deeply");
        }
        File[] kids = src.listFiles();
        if (kids == null) {
            return;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            if (IoUtil.isSymlink(k) || k.getName().equals(METADATA_DIR)) {
                continue; // links are never imported, and a ZIP cannot touch the IDE's own project data
            }
            File target = new File(dest, k.getName());
            String targetRel = childPath(rel, k.getName());
            if (IoUtil.isSymlink(target)) {
                r.skipped++; // a link inside the project is never followed or replaced
                continue;
            }
            resolve(targetRel); // must stay inside the project
            if (k.isDirectory()) {
                if (target.exists() && !target.isDirectory()) {
                    if (onConflict == Conflict.SKIP) {
                        r.skipped++;
                        continue;
                    }
                    target = new File(dest, freeName(dest, k.getName()));
                    targetRel = childPath(rel, target.getName());
                    r.renamed++;
                }
                if (!target.isDirectory() && !target.mkdirs()) {
                    throw new IOException("Cannot create " + target.getName());
                }
                mergeInto(k, target, targetRel, r, onConflict, depth + 1);
                continue;
            }
            boolean replacing = false;
            if (target.exists()) {
                if (onConflict == Conflict.SKIP) {
                    r.skipped++;
                    continue;
                }
                if (onConflict == Conflict.REPLACE && target.isFile()) {
                    replacing = true;
                } else {
                    target = new File(dest, freeName(dest, k.getName()));
                    targetRel = childPath(rel, target.getName());
                    r.renamed++;
                }
            }
            if (replacing) {
                replaceFile(k, target);
            } else {
                moveFile(k, target);
            }
            r.added++;
            if (replacing) {
                r.replaced++;
                r.replacedPaths.add(targetRel);
            }
        }
    }

    /** Files and folders whose name contains {@code query} (any case), anywhere in the project, at most {@code max} of them. */
    public List<FileNode> search(String query, int max) throws IOException {
        List<FileNode> out = new ArrayList<FileNode>();
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.US);
        if (q.length() == 0) {
            return out;
        }
        searchIn("", q, max, out, 0);
        return out;
    }

    private void searchIn(String rel, String q, int max, List<FileNode> out, int depth) throws IOException {
        if (depth > 25) {
            return;
        }
        for (FileNode n : list(rel)) {
            if (out.size() >= max) {
                return;
            }
            if (n.name.toLowerCase(java.util.Locale.US).contains(q)) {
                out.add(n);
            }
            if (n.directory && !IoUtil.isSymlink(resolve(n.path))) {
                searchIn(n.path, q, max, out, depth + 1);
            }
        }
    }

    public boolean exists(String rel) throws IOException {
        return resolve(rel).exists();
    }

    public static List<String> sortedCopy(List<String> in) {
        List<String> c = new ArrayList<String>(in);
        Collections.sort(c);
        return c;
    }
}
