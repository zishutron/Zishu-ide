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

    /** Result of {@link #mergeDirectory}. */
    public static final class MergeResult {
        public int added;
        /** Files that kept their name because the name was free: added minus renamed. */
        public int renamed;
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

    /**
     * Copies a stream (any file type, any size up to maxBytes) into a folder of the project without holding it in memory.
     * An existing file is never replaced: the new one gets a free name. Returns the project-relative path.
     */
    public String importStream(String relDir, String rawName, java.io.InputStream in, long maxBytes) throws IOException {
        File dir = resolve(relDir);
        if (!dir.isDirectory()) {
            throw new IOException("Not a folder: " + relDir);
        }
        String name = freeName(dir, safeName(rawName));
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
            if (!tmp.renameTo(dest)) {
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

    /**
     * Moves everything inside {@code srcDir} (a folder outside the project, for example a ZIP extracted to a temporary place)
     * into a project folder, keeping its sub-folders. A file that already exists is kept and the new one is added under a free name ("Main (1).java").
     */
    public MergeResult mergeDirectory(File srcDir, String relDestDir) throws IOException {
        File dest = resolve(relDestDir);
        if (!dest.isDirectory()) {
            throw new IOException("Not a folder: " + relDestDir);
        }
        MergeResult r = new MergeResult();
        mergeInto(srcDir, dest, r, 0);
        return r;
    }

    private void mergeInto(File src, File dest, MergeResult r, int depth) throws IOException {
        if (depth > 40) {
            throw new IOException("The folders are nested too deeply");
        }
        File[] kids = src.listFiles();
        if (kids == null) {
            return;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            if (IoUtil.isSymlink(k)) {
                continue;
            }
            File target = new File(dest, k.getName());
            if (k.isDirectory()) {
                if (target.exists() && !target.isDirectory()) {
                    target = new File(dest, freeName(dest, k.getName()));
                    r.renamed++;
                }
                if (!target.isDirectory() && !target.mkdirs()) {
                    throw new IOException("Cannot create " + target.getName());
                }
                mergeInto(k, target, r, depth + 1);
            } else {
                if (target.exists()) {
                    target = new File(dest, freeName(dest, k.getName()));
                    r.renamed++;
                }
                if (k.renameTo(target)) {
                    r.added++;
                    continue;
                }
                FileInputStream fin = new FileInputStream(k);
                try {
                    importStreamTo(target, fin);
                } finally {
                    IoUtil.closeQuietly(fin);
                }
                k.delete();
                r.added++;
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
