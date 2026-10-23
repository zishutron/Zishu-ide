package com.zishu.ide.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;

import com.zishu.ide.core.project.GitHubDownloader;
import com.zishu.ide.core.project.GitHubRepo;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.project.ProjectManager;
import com.zishu.ide.core.project.SafeZip;
import com.zishu.ide.core.signing.KeyStores;
import com.zishu.ide.core.terminal.IdeCommands.Level;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.signing.ApkSigning;
import com.zishu.ide.terminal.TerminalState;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Android side of the terminal's APK, keystore, signing and clone commands. Passwords are only ever
 * read from masked dialogs, kept in char arrays that are wiped after use, and never printed, saved or uploaded.
 */
final class TerminalActions {
    static final int REQ_SAVE_APK = 9101;
    static final int REQ_PICK_KEYSTORE = 9102;

    private interface PasswordResult {
        void done(char[] password);
    }

    private final Activity act;
    private final Palette pal;
    private final TerminalState state;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cloning = new AtomicBoolean(false);
    private final Runnable promptRefresh;
    private File pendingSave;

    TerminalActions(Activity act, Palette pal, TerminalState state, Runnable promptRefresh) {
        this.act = act;
        this.pal = pal;
        this.state = state;
        this.promptRefresh = promptRefresh;
    }

    private void say(final Level level, final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                state.println(level, text);
            }
        });
    }

    private ProjectInfo project() {
        return state.project();
    }

    // ------------------------------------------------------------------ download

    String downloadApk(final File apk) {
        if (StorageAccess.granted(act)) {
            @SuppressWarnings("deprecation")
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if ((dl.isDirectory() || dl.mkdirs()) && dl.canWrite()) {
                File target = uniqueFile(dl, apk.getName());
                try {
                    IoUtil.writeAtomic(target, IoUtil.readFile(apk, 1024L * 1024 * 1024));
                    state.println(Level.OK, "Saved to " + target.getPath());
                    return null;
                } catch (IOException e) {
                    state.println(Level.WARN, "Could not write to Downloads (" + e.getMessage() + "); choose a place instead.");
                }
            }
        }
        pendingSave = apk;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_TITLE, apk.getName());
        try {
            act.startActivityForResult(i, REQ_SAVE_APK);
        } catch (RuntimeException e) {
            return "No file picker is available on this device: " + e.getMessage();
        }
        state.println(Level.DIM, "Choose where to save " + apk.getName() + " (for example the Download folder).");
        return null;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int i = 1;
        String stem = name.endsWith(".apk") ? name.substring(0, name.length() - 4) : name;
        String ext = name.endsWith(".apk") ? ".apk" : "";
        while (f.exists()) {
            f = new File(dir, stem + "-" + i++ + ext);
        }
        return f;
    }

    // ------------------------------------------------------------------ activity results

    void onActivityResult(int req, int result, Intent data) {
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (req == REQ_SAVE_APK && pendingSave != null) {
            final File apk = pendingSave;
            pendingSave = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        DocumentIo.copyFileToUri(act.getContentResolver(), apk, uri);
                        say(Level.OK, "Saved " + apk.getName() + " (" + IoUtil.formatSize(apk.length()) + ") to the place you chose.");
                    } catch (IOException e) {
                        say(Level.ERROR, "Could not save the APK: " + e.getMessage());
                    }
                }
            }, "zishu-save").start();
        } else if (req == REQ_PICK_KEYSTORE) {
            final ProjectInfo p = project();
            if (p == null) {
                return;
            }
            final String picked = DocumentIo.displayName(act.getContentResolver(), uri);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    InputStream in = null;
                    try {
                        in = DocumentIo.open(act.getContentResolver(), uri);
                        byte[] data = IoUtil.readAll(in, 4L * 1024 * 1024);
                        if (data.length < 16) {
                            throw new IOException("That file is too small to be a keystore.");
                        }
                        File dir = KeyStores.dir(p.dir);
                        if (!dir.isDirectory() && !dir.mkdirs()) {
                            throw new IOException("Cannot create the keystore folder");
                        }
                        File target = uniqueFile(dir, KeyStores.fileName(picked == null ? "imported" : picked));
                        IoUtil.writeAtomic(target, data);
                        ensureIgnored(p.dir);
                        say(Level.OK, "Copied into the project: keystore/" + target.getName());
                        say(Level.DIM, "Check it with  zishu keystore info " + target.getName() + "  (asks for its password).");
                    } catch (IOException e) {
                        say(Level.ERROR, "Could not import the keystore: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    } finally {
                        IoUtil.closeQuietly(in);
                    }
                }
            }, "zishu-ks-import").start();
        }
    }

    // ------------------------------------------------------------------ keystores

    String importKeystore() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            act.startActivityForResult(i, REQ_PICK_KEYSTORE);
        } catch (RuntimeException e) {
            return "No file picker is available on this device: " + e.getMessage();
        }
        state.println(Level.DIM, "Pick your .p12 / .pfx / .jks / .keystore file. It is copied into this project's keystore/ folder.");
        return null;
    }

    String createKeystore(final String fileName, final String alias, final String cn, final String org,
                          final String country, final int years) {
        final ProjectInfo p = project();
        if (p == null) {
            return "No project is selected.";
        }
        askPassword("New keystore password", "Choose a password for " + fileName
                + " (at least 6 characters). You will need it every time you sign. It is not stored anywhere; "
                + "if you forget it the key cannot be recovered.", true, new PasswordResult() {
            @Override
            public void done(final char[] pw) {
                if (pw == null) {
                    state.println(Level.WARN, "Cancelled. No keystore was created.");
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            say(Level.DIM, "Generating an RSA 2048 key (a few seconds)...");
                            byte[] data = KeyStores.createPkcs12(alias, cn, org, country, years, pw);
                            File dir = KeyStores.dir(p.dir);
                            if (!dir.isDirectory() && !dir.mkdirs()) {
                                throw new IOException("Cannot create the keystore folder");
                            }
                            File target = new File(dir, fileName);
                            if (target.exists()) {
                                throw new IOException(fileName + " already exists");
                            }
                            IoUtil.writeAtomic(target, data);
                            KeyStores.Opened back = KeyStores.open(target, pw);
                            X509Certificate c = back.chain(alias)[0];
                            ensureIgnored(p.dir);
                            say(Level.OK, "Created keystore/" + fileName + "  (alias " + alias + ", valid " + years + " years)");
                            say(Level.PLAIN, "Certificate SHA-256: " + KeyStores.sha256(c));
                            say(Level.WARN, "Back this file up somewhere safe and remember the password. Anyone with both can "
                                    + "sign apps as you; if you lose them you can no longer update apps signed with this key.");
                            say(Level.DIM, "Sign with:  zishu sign --keystore " + fileName);
                        } catch (Exception e) {
                            say(Level.ERROR, "Could not create the keystore: " + msg(e));
                        } finally {
                            KeyStores.wipe(pw);
                        }
                    }
                }, "zishu-ks-create").start();
            }
        });
        return null;
    }

    String keystoreInfo(final File ks) {
        askPassword("Keystore password", "Password of " + ks.getName(), false, new PasswordResult() {
            @Override
            public void done(final char[] pw) {
                if (pw == null) {
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            KeyStores.Opened o = KeyStores.open(ks, pw);
                            say(Level.OK, ks.getName() + "  (" + o.format + ", " + o.keyAliases.size() + " key"
                                    + (o.keyAliases.size() == 1 ? "" : "s") + ")");
                            for (String a : o.keyAliases) {
                                X509Certificate c = o.chain(a)[0];
                                say(Level.PLAIN, "alias    " + a);
                                say(Level.PLAIN, "subject  " + c.getSubjectX500Principal().getName());
                                say(Level.PLAIN, "valid    " + c.getNotBefore() + "  to  " + c.getNotAfter());
                                say(Level.PLAIN, "SHA-256  " + KeyStores.sha256(c));
                            }
                        } catch (Exception e) {
                            say(Level.ERROR, msg(e));
                        } finally {
                            KeyStores.wipe(pw);
                        }
                    }
                }, "zishu-ks-info").start();
            }
        });
        return null;
    }

    String deleteKeystore(final File ks) {
        Ui.dialog(act, pal).setTitle("Delete " + ks.getName() + "?")
                .setMessage("This permanently deletes the key file from this project. If you have no other copy you can "
                        + "never sign updates of apps that were signed with it.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        boolean ok = ks.delete() || !ks.exists();
                        state.println(ok ? Level.OK : Level.ERROR,
                                ok ? "Deleted " + ks.getName() : "Could not delete " + ks.getName());
                    }
                }).show();
        return null;
    }

    // ------------------------------------------------------------------ sign / verify

    String verifyApk(final File apk) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    report(apk, ApkSigning.verify(apk));
                } catch (Exception e) {
                    say(Level.ERROR, "Could not check the APK: " + msg(e));
                }
            }
        }, "zishu-verify").start();
        return null;
    }

    private void report(File apk, ApkSigning.Report r) {
        if (r.verified) {
            say(Level.OK, apk.getName() + ": signature VERIFIED  (v1 " + yn(r.v1) + ", v2 " + yn(r.v2) + ", v3 " + yn(r.v3) + ")");
            say(Level.PLAIN, "signer   " + r.certSubject);
            say(Level.PLAIN, "SHA-256  " + r.sha256);
        } else {
            say(Level.ERROR, apk.getName() + ": signature NOT valid");
            for (String e : r.errors) {
                say(Level.ERROR, "  " + e);
            }
        }
        for (String w : r.warnings) {
            say(Level.WARN, "  " + w);
        }
    }

    private static String yn(boolean b) {
        return b ? "yes" : "no";
    }

    String signApk(final File apk, final File ks, final String alias) {
        final ProjectInfo p = project();
        if (p == null) {
            return "No project is selected.";
        }
        askPassword("Keystore password", "Password of " + ks.getName() + " (used to sign " + apk.getName() + ")", false,
                new PasswordResult() {
                    @Override
                    public void done(final char[] pw) {
                        if (pw == null) {
                            state.println(Level.WARN, "Cancelled. Nothing was signed.");
                            return;
                        }
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                signWithPassword(p, apk, ks, alias, pw);
                            }
                        }, "zishu-sign").start();
                    }
                });
        return null;
    }

    private void signWithPassword(ProjectInfo p, File apk, File ks, String aliasArg, char[] pw) {
        boolean wipe = true;
        try {
            KeyStores.Opened o = KeyStores.open(ks, pw);
            String alias = aliasArg;
            if (alias == null) {
                if (o.keyAliases.size() == 1) {
                    alias = o.keyAliases.get(0);
                } else if (o.keyAliases.isEmpty()) {
                    say(Level.ERROR, ks.getName() + " has no private key to sign with.");
                    return;
                } else {
                    say(Level.ERROR, ks.getName() + " has several keys. Run again with  --alias <name>  (" + o.keyAliases + ")");
                    return;
                }
            } else {
                String found = null;
                for (String a : o.keyAliases) {
                    if (a.equalsIgnoreCase(alias)) {
                        found = a;
                    }
                }
                if (found == null) {
                    say(Level.ERROR, "No key with alias \"" + alias + "\". This keystore has: " + o.keyAliases);
                    return;
                }
                alias = found;
            }
            PrivateKey key;
            try {
                key = o.key(alias, pw);
            } catch (UnrecoverableKeyException e) {
                wipe = false;
                askKeyPassword(p, apk, o, alias, ks);
                return;
            }
            finishSigning(p, apk, o.chain(alias), key, ks);
        } catch (Exception e) {
            say(Level.ERROR, "Signing failed: " + msg(e));
        } finally {
            if (wipe) {
                KeyStores.wipe(pw);
            }
        }
    }

    private void askKeyPassword(final ProjectInfo p, final File apk, final KeyStores.Opened o, final String alias,
                                final File ks) {
        main.post(new Runnable() {
            @Override
            public void run() {
                askPassword("Key password", "The key \"" + alias + "\" has its own password (different from the keystore "
                        + "password).", false, new PasswordResult() {
                    @Override
                    public void done(final char[] kp) {
                        if (kp == null) {
                            state.println(Level.WARN, "Cancelled. Nothing was signed.");
                            return;
                        }
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    finishSigning(p, apk, o.chain(alias), o.key(alias, kp), ks);
                                } catch (Exception e) {
                                    say(Level.ERROR, "Signing failed: " + msg(e));
                                } finally {
                                    KeyStores.wipe(kp);
                                }
                            }
                        }, "zishu-sign").start();
                    }
                });
            }
        });
    }

    private void finishSigning(ProjectInfo p, File apk, X509Certificate[] chain, PrivateKey key, File ks) throws Exception {
        File outDir = new File(p.dir, "apk");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("Cannot create the apk folder");
        }
        String stem = apk.getName().substring(0, apk.getName().length() - 4);
        if (stem.endsWith("-signed")) {
            stem = stem.substring(0, stem.length() - 7);
        }
        File out = new File(outDir, stem + "-signed.apk");
        if (out.getCanonicalFile().equals(apk.getCanonicalFile())) {
            out = new File(outDir, stem + "-resigned.apk");
        }
        say(Level.DIM, "Signing " + apk.getName() + " ...");
        ApkSigning.sign(apk, out, key, chain);
        ApkSigning.Report r = ApkSigning.verify(out);
        if (!r.verified) {
            out.delete();
            say(Level.ERROR, "The signed file was rejected by the APK verifier, so it was deleted:");
            for (String e : r.errors) {
                say(Level.ERROR, "  " + e);
            }
            return;
        }
        say(Level.OK, "SIGNED  " + out.getPath() + "  (" + IoUtil.formatSize(out.length()) + ")");
        report(out, r);
        say(Level.DIM, "Install with  zishu install  or save with  zishu download " + out.getName());
    }

    // ------------------------------------------------------------------ clone

    String cloneRepo(final String repoName, final String ref, final String projectName, boolean useToken) {
        final GitHubRepo parsed = GitHubRepo.parse(repoName);
        if (parsed == null) {
            return "That is not a GitHub repository.";
        }
        final GitHubRepo repo = ref == null ? parsed : parsed.withRef(ref);
        final String token = useToken ? TokenStore.load(act) : null;
        if (useToken && token == null) {
            return "No cloud token is saved. Run  zishu cloud settings  to add one, or clone a public repo without --auth.";
        }
        if (!cloning.compareAndSet(false, true)) {
            return "A clone is already running.";
        }
        state.println(Level.DIM, "Downloading " + repo.displayName() + " from GitHub ...");
        final Context app = act.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File zip = new File(app.getCacheDir(), "clone-" + System.nanoTime() + ".zip");
                File tmp = null;
                try {
                    final ProjectManager pm = App.projects(app);
                    tmp = new File(pm.getRoot(), ".import-" + System.nanoTime());
                    final long[] last = {0};
                    GitHubDownloader.download(repo, token, zip, new GitHubDownloader.Progress() {
                        @Override
                        public void bytes(long done) {
                            if (done - last[0] >= 5L * 1024 * 1024) {
                                last[0] = done;
                                say(Level.DIM, "  " + IoUtil.formatSize(done) + " ...");
                            }
                        }
                    }, null);
                    say(Level.DIM, "Downloaded " + IoUtil.formatSize(zip.length()) + ". Setting up the project ...");
                    InputStream in = new FileInputStream(zip);
                    try {
                        SafeZip.extract(in, tmp, new SafeZip.Limits());
                    } finally {
                        IoUtil.closeQuietly(in);
                    }
                    final ProjectManager.ImportOutcome o = pm.adopt(tmp, projectName);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            state.setProject(o.info);
                            promptRefresh.run();
                            state.println(Level.OK, "Project \"" + o.info.name + "\" is ready and selected.");
                            for (String n : o.notes) {
                                state.println(Level.PLAIN, "  - " + n);
                            }
                            state.println(Level.DIM, "Next:  zishu build");
                        }
                    });
                } catch (Exception e) {
                    say(Level.ERROR, "Clone failed: " + msg(e));
                } finally {
                    zip.delete();
                    try {
                        if (tmp != null) {
                            IoUtil.deleteRecursively(tmp);
                        }
                    } catch (IOException ignored) {
                        // hidden temp folder
                    }
                    cloning.set(false);
                }
            }
        }, "zishu-clone").start();
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** Keeps keystore/ (and built apk/) out of any git repository the project is later pushed to. */
    private static void ensureIgnored(File projectDir) {
        File gi = new File(projectDir, ".gitignore");
        try {
            String text = gi.isFile() ? IoUtil.readUtf8(gi, 1024 * 1024) : "";
            StringBuilder add = new StringBuilder();
            for (String line : new String[]{"keystore/", "*.jks", "*.p12", "*.keystore"}) {
                if (!Arrays.asList(text.split("\r?\n")).contains(line)) {
                    add.append(line).append('\n');
                }
            }
            if (add.length() > 0) {
                IoUtil.writeUtf8Atomic(gi, text + (text.length() == 0 || text.endsWith("\n") ? "" : "\n") + add);
            }
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String msg(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** A masked password dialog. The callback gets a char array (wipe it) or null when cancelled. */
    private void askPassword(String title, String message, final boolean confirm, final PasswordResult result) {
        final int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 20);
        box.setPadding(pad, Ui.dp(act, 8), pad, 0);
        box.addView(Ui.text(act, pal, message, Ui.Style.BODY));
        final Ui.Field f1 = Ui.field(act, pal, "Password", "", "", type);
        box.addView(f1.view);
        final Ui.Field f2 = confirm ? Ui.field(act, pal, "Repeat password", "", "", type) : null;
        if (f2 != null) {
            box.addView(f2.view);
        }
        final boolean[] answered = {false};
        final AlertDialog d = Ui.dialog(act, pal).setTitle(title).setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", null).create();
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface x) {
                if (!answered[0]) {
                    answered[0] = true;
                    result.done(null);
                }
            }
        });
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                char[] a = chars(f1);
                if (a.length == 0) {
                    f1.setError("Enter the password");
                    return;
                }
                if (f2 != null) {
                    char[] b = chars(f2);
                    boolean same = Arrays.equals(a, b);
                    KeyStores.wipe(b);
                    if (!same) {
                        f2.setError("The two passwords differ");
                        KeyStores.wipe(a);
                        return;
                    }
                    if (a.length < 6) {
                        f1.setError("Use at least 6 characters");
                        KeyStores.wipe(a);
                        return;
                    }
                }
                answered[0] = true;
                f1.edit.setText("");
                if (f2 != null) {
                    f2.edit.setText("");
                }
                d.dismiss();
                result.done(a);
            }
        });
    }

    private static char[] chars(Ui.Field f) {
        android.text.Editable e = f.edit.getText();
        char[] c = new char[e.length()];
        e.getChars(0, c.length, c, 0);
        return c;
    }
}
