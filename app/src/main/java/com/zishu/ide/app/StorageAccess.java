package com.zishu.ide.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;

/**
 * Optional access to a visible project folder on the phone (ZishuIDE). Android 11+ needs the
 * "All files access" switch; Android 6-10 needs the storage permission; Android 5 needs nothing.
 * Nothing here is requested unless the user turns on "Phone storage" in Settings.
 */
public final class StorageAccess {
    public static final int REQUEST_LEGACY = 77;

    private StorageAccess() {
    }

    @SuppressWarnings("deprecation")
    public static File sharedDir() {
        return new File(Environment.getExternalStorageDirectory(), "ZishuIDE");
    }

    public static boolean granted(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Opens the Android screen (or permission prompt) where the user can allow access. */
    public static void request(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName()));
                a.startActivity(i);
            } catch (RuntimeException e) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_LEGACY);
        }
    }
}
