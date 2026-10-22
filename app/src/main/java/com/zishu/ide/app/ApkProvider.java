package com.zishu.ide.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands a finished APK to the system package installer. Only read access to *.apk files inside
 * {workspace}/{project}/.zishu/builds/ is possible; everything else is refused, so other apps cannot
 * use this provider to read project sources or settings.
 */
public final class ApkProvider extends ContentProvider {
    public static final String AUTHORITY = "com.zishu.ide.apk";

    public static Uri uriFor(Context c, File apk) throws IOException {
        String root = App.workspaceDir(c).getCanonicalPath() + File.separator;
        String path = apk.getCanonicalPath();
        if (!path.startsWith(root)) {
            throw new IOException("APK is not inside the workspace");
        }
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTHORITY);
        for (String seg : path.substring(root.length()).split(java.util.regex.Pattern.quote(File.separator))) {
            b.appendPath(seg);
        }
        return b.build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        if (c == null) {
            throw new FileNotFoundException();
        }
        try {
            java.util.List<String> segs = uri.getPathSegments();
            if (segs.size() < 3) {
                throw new FileNotFoundException("Unknown path");
            }
            File f = App.workspaceDir(c);
            for (String s : segs) {
                if (s.equals("..") || s.equals(".") || s.length() == 0 || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0) {
                    throw new FileNotFoundException("Invalid path");
                }
                f = new File(f, s);
            }
            String root = App.workspaceDir(c).getCanonicalPath() + File.separator;
            String canonical = f.getCanonicalPath();
            String marker = File.separator + ".zishu" + File.separator + "builds" + File.separator;
            boolean visibleApkDir = canonical.startsWith(root)
                    && canonical.substring(root.length()).matches("[^/\\\\]+[/\\\\]apk[/\\\\][^/\\\\]+");
            if (!canonical.startsWith(root) || !(canonical.contains(marker) || visibleApkDir)
                    || !canonical.endsWith(".apk") || !f.isFile()) {
                throw new FileNotFoundException("Not a build output");
            }
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException("Cannot resolve path");
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new SecurityException("Read-only provider");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{f.getName(), Long.valueOf(f.length())});
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
