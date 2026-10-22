package com.zishu.ide.app;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.IOException;

/** Opens Android's package installer for an APK built by Zishu IDE. */
final class ApkInstaller {
    private ApkInstaller() {
    }

    /** @return null when the installer was opened, otherwise a message for the user */
    static String install(final Activity a, Palette pal, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
                Ui.dialog(a, pal).setTitle("Allow installing apps")
                        .setMessage("Android needs your permission before Zishu IDE can open the installer. "
                                + "Enable \"Allow from this source\", then run the install again.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + a.getPackageName())));
                            }
                        }).show();
                return "Allow installing apps for Zishu IDE first, then run  zishu install  again.";
            }
            Uri uri = ApkProvider.uriFor(a, apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return null;
        } catch (IOException e) {
            return "Cannot install: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Cannot install: no installer is available on this device (" + e.getMessage() + ")";
        }
    }
}
