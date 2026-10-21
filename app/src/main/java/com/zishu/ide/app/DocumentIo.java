package com.zishu.ide.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import com.zishu.ide.core.project.FileManager;
import com.zishu.ide.core.project.SafeZip;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Storage Access Framework helpers: no storage permission is ever requested. */
public final class DocumentIo {
    private static final Set<String> SKIP_DIRS = new HashSet<String>(Arrays.asList(
            "build", ".gradle", ".git", ".idea", ".cxx", "node_modules"));

    private DocumentIo() {
    }

    public static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.length() > 0) {
                    return n.replace('/', '_').replace('\\', '_');
                }
            }
        } catch (RuntimeException ignored) {
            // provider refused the query; fall back to the last path segment
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last.substring(last.lastIndexOf('/') + 1);
    }

    public static InputStream open(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) {
            throw new FileNotFoundException("Cannot open the selected file");
        }
        return in;
    }

    public static void copyFileToUri(ContentResolver cr, File src, Uri dest) throws IOException {
        OutputStream out = cr.openOutputStream(dest, "wt");
        if (out == null) {
            throw new FileNotFoundException("Cannot write to the selected location");
        }
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            IoUtil.copy(in, out);
            out.flush();
        } finally {
            IoUtil.closeQuietly(in);
            IoUtil.closeQuietly(out);
        }
    }

    public static final class Limits {
        public long maxBytes = 500L * 1024 * 1024;
        public int maxFiles = 20000;
        public int maxDepth = 30;
    }

    private static final class Counter {
        long bytes;
        int files;
    }

    /**
     * Copies a document tree (a folder the user picked) into destDir, which must not exist. Entry
     * names supplied by the document provider are validated exactly like ZIP entries: anything with a
     * path separator or "." / ".." is skipped, so a hostile provider cannot write outside destDir.
     * On failure the partial copy is deleted.
     */
    public static int importTree(ContentResolver cr, Uri treeUri, File destDir, Limits limits) throws IOException {
        if (destDir.exists()) {
            throw new FileManager.FileExistsException("Target folder already exists");
        }
        if (!destDir.mkdirs()) {
            throw new IOException("Cannot create " + destDir);
        }
        Counter counter = new Counter();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            copyChildren(cr, treeUri, rootId, destDir, destDir.getCanonicalPath(), limits, counter, 0);
            return counter.files;
        } catch (IOException e) {
            IoUtil.deleteRecursively(destDir);
            throw e;
        } catch (RuntimeException e) {
            IoUtil.deleteRecursively(destDir);
            throw new IOException("Could not read the selected folder: " + e.getMessage());
        }
    }

    private static void copyChildren(ContentResolver cr, Uri tree, String parentId, File dir, String rootCanonical,
                                     Limits limits, Counter counter, int depth) throws IOException {
        if (depth > limits.maxDepth) {
            throw new SafeZip.ZipSecurityException("The folder is nested more than " + limits.maxDepth + " levels deep");
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        Cursor c = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
        if (c == null) {
            throw new IOException("The folder cannot be listed");
        }
        // Collect first so the cursor is closed before recursing (providers limit open cursors).
        java.util.List<String[]> entries = new java.util.ArrayList<String[]>();
        try {
            while (c.moveToNext()) {
                entries.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
            }
        } finally {
            c.close();
        }
        for (String[] e : entries) {
            String id = e[0];
            String name = e[1];
            boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(e[2]);
            if (name == null || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                    || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0 || name.length() == 0) {
                continue;
            }
            File target = new File(dir, name);
            String canonical = target.getCanonicalPath();
            if (!canonical.startsWith(rootCanonical + File.separator)) {
                throw new SafeZip.ZipSecurityException("An entry would be written outside the project folder: " + name);
            }
            if (isDir) {
                if (SKIP_DIRS.contains(name)) {
                    continue;
                }
                if (!target.mkdir()) {
                    throw new IOException("Cannot create folder " + name);
                }
                copyChildren(cr, tree, id, target, rootCanonical, limits, counter, depth + 1);
            } else {
                if (++counter.files > limits.maxFiles) {
                    throw new SafeZip.ZipSecurityException("The folder has more than " + limits.maxFiles + " files");
                }
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                InputStream in = cr.openInputStream(doc);
                if (in == null) {
                    throw new IOException("Cannot read " + name);
                }
                FileOutputStream out = new FileOutputStream(target);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        counter.bytes += n;
                        if (counter.bytes > limits.maxBytes) {
                            throw new SafeZip.ZipSecurityException("The folder is larger than " + IoUtil.formatSize(limits.maxBytes));
                        }
                        out.write(buf, 0, n);
                    }
                } finally {
                    IoUtil.closeQuietly(in);
                    IoUtil.closeQuietly(out);
                }
            }
        }
    }
}
