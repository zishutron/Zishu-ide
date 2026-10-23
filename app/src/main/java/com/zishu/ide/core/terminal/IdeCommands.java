package com.zishu.ide.core.terminal;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildHistory;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The commands that control Zishu IDE from the terminal: projects, on-phone and cloud builds, install, settings.
 * It contains no Android code, so it is unit tested; the app supplies a {@link Host} that does the real work.
 * Every message printed here describes something that really happened or a state that really exists.
 */
public final class IdeCommands {

    public enum Level {PLAIN, HEAD, DIM, OK, WARN, ERROR}

    public interface Out {
        void line(Level level, String text);
    }

    public interface Host {
        List<ProjectInfo> projects();

        ProjectInfo currentProject();

        void setCurrentProject(ProjectInfo p);

        /** "auto", "device" or "cloud". */
        String engineChoice();

        void setEngineChoice(String choice);

        /** Status of "device" or "cloud". */
        BuildEngine.Status engineStatus(String engine);

        /** @param engine "device", "cloud", or null for the saved choice. @return an error text, or null when the build started */
        String startBuild(String engine);

        boolean isBuilding();

        void cancelBuild();

        /** Log of the most recent build, or null. */
        String lastLog();

        /** APK of the most recent successful build, or null. */
        File lastApk();

        /** @return an error text, or null when the installer was opened */
        String installLastApk();

        List<BuildHistory.Entry> history();

        String cloudRepo();

        /** @return an error text, or null when saved */
        String setCloudRepo(String ownerRepo);

        boolean cloudWorkflow();

        void setCloudWorkflow(boolean on);

        boolean cloudAllowPublic();

        void setCloudAllowPublic(boolean on);

        boolean hasToken();

        void openCloudSettings();

        void openEditor();

        void runSelfTest();

        void clearScreen();

        /** Saves an APK to the phone's Downloads folder (or asks where to save it). @return an error text, or null */
        String downloadApk(File apk);

        /** Starts signing (asks for the password in a masked dialog; reports progress itself). @return an error text, or null when started */
        String signApk(File apk, File keystore, String alias);

        /** Checks an APK's signature and reports the result itself. @return an error text, or null when started */
        String verifyApk(File apk);

        /** Asks for a password and creates a PKCS12 keystore in the project's keystore folder. @return an error text, or null when started */
        String createKeystore(String fileName, String alias, String commonName, String organisation, String country,
                              int validYears);

        /** Opens the system file picker to copy a keystore into the project. @return an error text, or null when opened */
        String importKeystore();

        /** Asks for the password and reports the keystore's keys and certificate. @return an error text, or null when started */
        String keystoreInfo(File keystore);

        /** Asks for confirmation, then deletes. @return an error text, or null when the question was shown */
        String deleteKeystore(File keystore);

        /** Downloads a GitHub repository and sets it up as a new project; reports progress itself. @return an error text, or null when started */
        String cloneRepo(String repo, String ref, String projectName, boolean useToken);
    }

    private static final Pattern OWNER_REPO = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}");
    public static final String VERSION = "Zishu Terminal 1.0 for Zishu IDE";

    private final Host host;

    public IdeCommands(Host host) {
        this.host = host;
    }

    /** True when the first word of a line is handled here instead of being passed to the shell. */
    public static boolean handles(String firstWord) {
        return "zishu".equals(firstWord) || "z".equals(firstWord) || "build".equals(firstWord)
                || "clear".equals(firstWord) || "cls".equals(firstWord) || "help".equals(firstWord);
    }

    public static String[] subcommands() {
        return new String[]{"help", "projects", "open", "status", "engine", "build", "cancel", "log", "apk", "install",
                "history", "cloud", "selftest", "editor", "clear", "version", "clone", "download", "apks", "sign", "verify",
                "keystore"};
    }

    public void run(List<String> words, Out out) {
        if (words.isEmpty()) {
            return;
        }
        String first = words.get(0);
        List<String> args = new ArrayList<String>(words.subList(1, words.size()));
        String sub;
        if ("zishu".equals(first) || "z".equals(first)) {
            if (args.isEmpty()) {
                help(out, null);
                return;
            }
            sub = args.remove(0);
        } else {
            sub = first; // build, clear, cls, help
        }
        if ("cls".equals(sub)) {
            sub = "clear";
        }
        if ("projects".equals(sub)) {
            projects(out);
        } else if ("help".equals(sub) || "-h".equals(sub) || "--help".equals(sub)) {
            help(out, args.isEmpty() ? null : args.get(0));
        } else if ("open".equals(sub)) {
            open(args, out);
        } else if ("status".equals(sub)) {
            status(out);
        } else if ("engine".equals(sub)) {
            engine(args, out);
        } else if ("build".equals(sub)) {
            build(args, out);
        } else if ("cancel".equals(sub)) {
            cancel(out);
        } else if ("log".equals(sub)) {
            log(args, out);
        } else if ("apk".equals(sub)) {
            apk(out);
        } else if ("install".equals(sub)) {
            install(out);
        } else if ("history".equals(sub)) {
            history(out);
        } else if ("apks".equals(sub)) {
            apks(out);
        } else if ("download".equals(sub)) {
            download(args, out);
        } else if ("sign".equals(sub)) {
            sign(args, out);
        } else if ("verify".equals(sub)) {
            verify(args, out);
        } else if ("keystore".equals(sub)) {
            keystore(args, out);
        } else if ("clone".equals(sub)) {
            clone(args, out);
        } else if ("cloud".equals(sub)) {
            cloud(args, out);
        } else if ("selftest".equals(sub)) {
            host.runSelfTest();
        } else if ("editor".equals(sub)) {
            if (host.currentProject() == null) {
                noProject(out);
            } else {
                host.openEditor();
            }
        } else if ("clear".equals(sub)) {
            host.clearScreen();
        } else if ("version".equals(sub)) {
            out.line(Level.PLAIN, VERSION);
        } else {
            out.line(Level.ERROR, "Unknown command: " + sub);
            out.line(Level.DIM, "Type  zishu help  to see what is available.");
        }
    }

    private void noProject(Out out) {
        out.line(Level.WARN, "No project is selected. Use  zishu projects  then  zishu open <number or name>.");
    }

    private void help(Out out, String topic) {
        if (topic != null) {
            String t = helpFor(topic);
            if (t != null) {
                out.line(Level.PLAIN, t);
                return;
            }
        }
        out.line(Level.HEAD, "Zishu IDE commands");
        String[][] rows = {
                {"zishu projects", "list your projects"},
                {"zishu open <n|name>", "select a project (the shell moves into its folder)"},
                {"zishu status", "project, engines and whether builds can run"},
                {"zishu build [--device|--cloud]", "build a debug APK (build is a shortcut)"},
                {"zishu cancel", "stop the running build"},
                {"zishu log [lines]", "show the end of the last build log"},
                {"zishu apk", "where the last APK is"},
                {"zishu install", "open the installer for the last APK"},
                {"zishu apks", "list the APKs in this project's apk/ folder"},
                {"zishu download [apk]", "save an APK to your Downloads folder"},
                {"zishu keystore ...", "create/import/list signing keys (zishu help keystore)"},
                {"zishu sign [apk] [--keystore f]", "sign an APK with your own key (zishu help sign)"},
                {"zishu verify [apk]", "check an APK's signature"},
                {"zishu clone <github repo>", "download a GitHub repo as a new project"},
                {"zishu history", "recent builds of this project"},
                {"zishu engine [auto|device|cloud]", "show or choose the build engine"},
                {"zishu cloud ...", "cloud build settings (zishu help cloud)"},
                {"zishu editor", "open the project in the editor"},
                {"zishu selftest", "build a sample app to test the on-phone toolchain"},
                {"clear", "clear the screen"},
        };
        for (String[] r : rows) {
            out.line(Level.PLAIN, pad(r[0], 34) + r[1]);
        }
        out.line(Level.DIM, "Everything else is passed to the Android shell (ls, cat, cp, mkdir ...). "
                + "Interactive programs such as vim or top do not work.");
    }

    private String helpFor(String t) {
        if ("cloud".equals(t)) {
            return "zishu cloud status               show cloud build settings\n"
                    + "zishu cloud repo owner/name      set the private build repository\n"
                    + "zishu cloud workflow on|off      install/update the build workflow automatically\n"
                    + "zishu cloud public on|off        allow a public build repository (not recommended)\n"
                    + "zishu cloud settings             open the screen where you enter the access token\n"
                    + "The token is never typed here, so it cannot end up in the terminal history.";
        }
        if ("keystore".equals(t)) {
            return "zishu keystore list                          keystores in this project's keystore/ folder\n"
                    + "zishu keystore create <name> [--alias a] [--cn \"Your Name\"] [--org O] [--country IN] [--years 30]\n"
                    + "                                            make a new PKCS12 keystore (asks for a password)\n"
                    + "zishu keystore import                       copy a .p12/.pfx/.jks/.keystore file into the project\n"
                    + "zishu keystore info <name>                  show its keys and certificate (asks for the password)\n"
                    + "zishu keystore delete <name>                delete it (asks to confirm)\n"
                    + "You can also put an existing keystore file into the project's keystore/ folder yourself.\n"
                    + "Passwords are asked in a masked dialog. They are never typed here, saved, or uploaded, and\n"
                    + "keystore/ is never sent to a cloud build. Back the file up: a lost key cannot be recovered.";
        }
        if ("sign".equals(t)) {
            return "zishu sign                                  sign the newest APK in apk/ with the only keystore\n"
                    + "zishu sign <apk> --keystore <file> --alias <alias>\n"
                    + "The result is saved next to the original as <name>-signed.apk and is checked with the\n"
                    + "Android APK verifier before it is reported as signed.";
        }
        if ("clone".equals(t)) {
            return "zishu clone <owner/repo | https://github.com/owner/repo> [--branch b] [--name projectName] [--auth]\n"
                    + "Downloads the repository as a ZIP, finds the Android app inside and lays out the project\n"
                    + "(Gradle files are created if the repository has none). Private repositories need --auth,\n"
                    + "which uses the token saved in cloud settings.";
        }
        if ("build".equals(t)) {
            return "zishu build               use the saved engine (zishu engine)\n"
                    + "zishu build --device      build on this phone\n"
                    + "zishu build --cloud       build with GitHub Actions";
        }
        return null;
    }

    private void projects(Out out) {
        List<ProjectInfo> list = host.projects();
        if (list.isEmpty()) {
            out.line(Level.WARN, "There are no projects yet. Create one from the home screen.");
            return;
        }
        ProjectInfo cur = host.currentProject();
        for (int i = 0; i < list.size(); i++) {
            ProjectInfo p = list.get(i);
            boolean isCur = cur != null && cur.dir.equals(p.dir);
            out.line(isCur ? Level.OK : Level.PLAIN,
                    (isCur ? "* " : "  ") + pad(String.valueOf(i + 1) + ".", 4) + pad(p.name, 24) + p.type.name());
        }
    }

    private void open(List<String> args, Out out) {
        if (args.isEmpty()) {
            out.line(Level.ERROR, "Usage: zishu open <number or name>");
            return;
        }
        String q = args.get(0);
        List<ProjectInfo> list = host.projects();
        ProjectInfo found = null;
        try {
            int n = Integer.parseInt(q);
            if (n >= 1 && n <= list.size()) {
                found = list.get(n - 1);
            }
        } catch (NumberFormatException ignored) {
            // not a number: match by name below
        }
        if (found == null) {
            List<ProjectInfo> prefix = new ArrayList<ProjectInfo>();
            for (ProjectInfo p : list) {
                if (p.name.equalsIgnoreCase(q) || p.dir.getName().equalsIgnoreCase(q)) {
                    found = p;
                    break;
                }
                if (p.name.toLowerCase(Locale.ROOT).startsWith(q.toLowerCase(Locale.ROOT))) {
                    prefix.add(p);
                }
            }
            if (found == null && prefix.size() == 1) {
                found = prefix.get(0);
            } else if (found == null && prefix.size() > 1) {
                out.line(Level.ERROR, "More than one project starts with '" + q + "'. Use the number from  zishu projects.");
                return;
            }
        }
        if (found == null) {
            out.line(Level.ERROR, "No project matches '" + q + "'.");
            return;
        }
        host.setCurrentProject(found);
        out.line(Level.OK, "Selected " + found.name + "  (" + found.dir.getPath() + ")");
    }

    private void status(Out out) {
        ProjectInfo p = host.currentProject();
        out.line(Level.HEAD, "Project");
        out.line(Level.PLAIN, p == null ? "  none selected" : "  " + p.name + "  [" + p.type.name() + "]");
        out.line(Level.HEAD, "Build engine choice: " + host.engineChoice());
        BuildEngine.Status d = host.engineStatus("device");
        BuildEngine.Status c = host.engineStatus("cloud");
        out.line(d.available ? Level.OK : Level.WARN, "  on this phone : " + (d.available ? "ready" : "not ready - " + d.reason));
        out.line(c.available ? Level.OK : Level.WARN, "  cloud         : " + (c.available ? "ready" : "not ready - " + c.reason));
        out.line(Level.PLAIN, host.isBuilding() ? "A build is running." : "No build is running.");
    }

    private void engine(List<String> args, Out out) {
        if (args.isEmpty()) {
            out.line(Level.PLAIN, "Engine choice: " + host.engineChoice()
                    + "   (auto = on this phone when ready, otherwise cloud)");
            return;
        }
        String v = args.get(0).toLowerCase(Locale.ROOT);
        if (!"auto".equals(v) && !"device".equals(v) && !"cloud".equals(v)) {
            out.line(Level.ERROR, "Choose one of: auto, device, cloud");
            return;
        }
        host.setEngineChoice(v);
        out.line(Level.OK, "Engine choice saved: " + v);
    }

    private void build(List<String> args, Out out) {
        if (host.currentProject() == null) {
            noProject(out);
            return;
        }
        String engine = null;
        for (String a : args) {
            if ("--device".equals(a) || "--phone".equals(a)) {
                engine = "device";
            } else if ("--cloud".equals(a)) {
                engine = "cloud";
            } else {
                out.line(Level.ERROR, "Unknown option: " + a + "   (use --device or --cloud)");
                return;
            }
        }
        String err = host.startBuild(engine);
        if (err != null) {
            out.line(Level.ERROR, err);
        }
    }

    private void cancel(Out out) {
        if (!host.isBuilding()) {
            out.line(Level.WARN, "No build is running.");
            return;
        }
        host.cancelBuild();
        out.line(Level.OK, "Cancel requested. The build stops at the next safe point.");
    }

    private void log(List<String> args, Out out) {
        String log = host.lastLog();
        if (log == null || log.length() == 0) {
            out.line(Level.WARN, "There is no build log yet in this session.");
            return;
        }
        int n = 40;
        if (!args.isEmpty()) {
            try {
                n = Math.max(1, Math.min(500, Integer.parseInt(args.get(0))));
            } catch (NumberFormatException e) {
                out.line(Level.ERROR, "Usage: zishu log [number of lines]");
                return;
            }
        }
        String[] lines = log.split("\n", -1);
        int from = Math.max(0, lines.length - n - (lines[lines.length - 1].isEmpty() ? 1 : 0));
        for (int i = from; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) {
                break;
            }
            out.line(Level.PLAIN, lines[i]);
        }
    }

    private void apk(Out out) {
        File f = host.lastApk();
        if (f == null || !f.isFile()) {
            out.line(Level.WARN, "No APK from a successful build is available yet.");
            return;
        }
        out.line(Level.OK, f.getPath());
        out.line(Level.DIM, IoUtil.formatSize(f.length()) + "   (zishu install  opens the installer)");
    }

    private void install(Out out) {
        String err = host.installLastApk();
        if (err != null) {
            out.line(Level.ERROR, err);
        } else {
            out.line(Level.OK, "Opened the Android installer.");
        }
    }

    private void history(Out out) {
        if (host.currentProject() == null) {
            noProject(out);
            return;
        }
        List<BuildHistory.Entry> list = host.history();
        if (list.isEmpty()) {
            out.line(Level.WARN, "This project has no recorded builds.");
            return;
        }
        SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm", Locale.US);
        int shown = 0;
        for (BuildHistory.Entry e : list) {
            if (++shown > 10) {
                break;
            }
            String state = e.success ? "ok    " : e.cancelled ? "stop  " : "FAILED";
            out.line(e.success ? Level.OK : e.cancelled ? Level.WARN : Level.ERROR,
                    f.format(new Date(e.time)) + "  " + state + "  " + pad(String.valueOf(e.engine), 10)
                            + (e.success ? IoUtil.formatSize(e.apkSize) : String.valueOf(e.title)));
        }
    }

    private void cloud(List<String> args, Out out) {
        String sub = args.isEmpty() ? "status" : args.remove(0).toLowerCase(Locale.ROOT);
        if ("status".equals(sub)) {
            String repo = host.cloudRepo();
            BuildEngine.Status st = host.engineStatus("cloud");
            out.line(Level.PLAIN, "Repository       : " + (repo == null || repo.isEmpty() ? "(not set)" : repo));
            out.line(host.hasToken() ? Level.OK : Level.WARN,
                    "Access token     : " + (host.hasToken() ? "saved (encrypted)" : "not saved"));
            out.line(Level.PLAIN, "Install workflow : " + (host.cloudWorkflow() ? "on" : "off"));
            out.line(Level.PLAIN, "Allow public repo: " + (host.cloudAllowPublic() ? "yes" : "no"));
            out.line(st.available ? Level.OK : Level.WARN, st.available ? "Cloud build is ready." : "Not ready: " + st.reason
                    + (st.action == null ? "" : "  " + st.action));
        } else if ("repo".equals(sub)) {
            if (args.isEmpty() || !OWNER_REPO.matcher(args.get(0)).matches()) {
                out.line(Level.ERROR, "Usage: zishu cloud repo owner/name");
                return;
            }
            String err = host.setCloudRepo(args.get(0));
            out.line(err == null ? Level.OK : Level.ERROR, err == null ? "Repository saved: " + args.get(0) : err);
        } else if ("workflow".equals(sub) || "public".equals(sub)) {
            if (args.isEmpty() || !("on".equals(args.get(0)) || "off".equals(args.get(0)))) {
                out.line(Level.ERROR, "Usage: zishu cloud " + sub + " on|off");
                return;
            }
            boolean on = "on".equals(args.get(0));
            if ("workflow".equals(sub)) {
                host.setCloudWorkflow(on);
            } else {
                host.setCloudAllowPublic(on);
            }
            out.line(Level.OK, "Saved: " + sub + " " + args.get(0));
        } else if ("settings".equals(sub)) {
            host.openCloudSettings();
        } else if ("token".equals(sub)) {
            out.line(Level.WARN, "For safety the token is not accepted here. Use  zishu cloud settings.");
        } else {
            out.line(Level.ERROR, "Unknown cloud command: " + sub + "   (zishu help cloud)");
        }
    }

    // ------------------------------------------------------------------ apk files, signing, clone

    /** Removes "--name value" from args and returns the value, or null. */
    private static String takeOption(List<String> args, String name) {
        int i = args.indexOf(name);
        if (i < 0 || i + 1 >= args.size()) {
            return null;
        }
        String v = args.get(i + 1);
        args.remove(i + 1);
        args.remove(i);
        return v;
    }

    private static boolean takeFlag(List<String> args, String name) {
        return args.remove(name);
    }

    private File projectDir() {
        ProjectInfo p = host.currentProject();
        return p == null ? null : p.dir;
    }

    private List<File> apkList() {
        File dir = projectDir();
        List<File> out = new ArrayList<File>();
        if (dir == null) {
            return out;
        }
        File[] kids = new File(dir, BuildHistory.VISIBLE_APK_DIR).listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                    out.add(f);
                }
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.lastModified() == b.lastModified() ? a.getName().compareTo(b.getName())
                        : (a.lastModified() > b.lastModified() ? -1 : 1);
            }
        });
        return out;
    }

    /** An APK argument: a path, a path inside the project, or a file name in apk/. Null argument means the newest APK. */
    private File resolveApk(String arg, Out out) {
        File dir = projectDir();
        if (arg == null) {
            List<File> list = apkList();
            File f = list.isEmpty() ? host.lastApk() : list.get(0);
            if (f == null || !f.isFile()) {
                out.line(Level.WARN, "There is no APK yet. Run  zishu build  first, or give the path of an APK.");
                return null;
            }
            return f;
        }
        File[] tries = new File[]{new File(arg), dir == null ? null : new File(dir, arg),
                dir == null ? null : new File(new File(dir, BuildHistory.VISIBLE_APK_DIR), arg)};
        for (File t : tries) {
            if (t != null && t.isFile() && t.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                return t;
            }
        }
        out.line(Level.ERROR, "No APK found for \"" + arg + "\". Use  zishu apks  to list the APKs of this project.");
        return null;
    }

    private void apks(Out out) {
        if (host.currentProject() == null) {
            noProject(out);
            return;
        }
        List<File> list = apkList();
        if (list.isEmpty()) {
            out.line(Level.WARN, "The apk/ folder of this project has no APK yet. Run  zishu build.");
            return;
        }
        out.line(Level.DIM, new File(projectDir(), BuildHistory.VISIBLE_APK_DIR).getPath());
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        for (File a : list) {
            out.line(Level.PLAIN, pad(a.getName(), 34) + pad(IoUtil.formatSize(a.length()), 10) + f.format(new Date(a.lastModified())));
        }
    }

    private void download(List<String> args, Out out) {
        File apk = resolveApk(args.isEmpty() ? null : args.get(0), out);
        if (apk == null) {
            return;
        }
        String err = host.downloadApk(apk);
        if (err != null) {
            out.line(Level.ERROR, err);
        }
    }

    private void verify(List<String> args, Out out) {
        File apk = resolveApk(args.isEmpty() ? null : args.get(0), out);
        if (apk == null) {
            return;
        }
        String err = host.verifyApk(apk);
        if (err != null) {
            out.line(Level.ERROR, err);
        }
    }

    /** The keystore named by the argument, or the only one in the project. Prints why when there is none. */
    private File resolveKeystore(String arg, Out out) {
        File dir = projectDir();
        if (dir == null) {
            noProject(out);
            return null;
        }
        List<File> all = com.zishu.ide.core.signing.KeyStores.list(dir);
        if (arg != null) {
            File f = new File(com.zishu.ide.core.signing.KeyStores.dir(dir), arg);
            if (f.isFile()) {
                return f;
            }
            File abs = new File(arg);
            if (abs.isFile()) {
                return abs;
            }
            out.line(Level.ERROR, "No keystore named \"" + arg + "\" in the keystore/ folder.");
            return null;
        }
        if (all.isEmpty()) {
            out.line(Level.WARN, "This project has no keystore yet. Make one with  zishu keystore create <name>  or copy "
                    + "yours in with  zishu keystore import.");
            return null;
        }
        if (all.size() > 1) {
            out.line(Level.WARN, "There is more than one keystore. Pick one with  --keystore <file>:");
            for (File f : all) {
                out.line(Level.PLAIN, "  " + f.getName());
            }
            return null;
        }
        return all.get(0);
    }

    private void sign(List<String> args, Out out) {
        if (host.currentProject() == null) {
            noProject(out);
            return;
        }
        List<String> a = new ArrayList<String>(args);
        String ks = takeOption(a, "--keystore");
        String alias = takeOption(a, "--alias");
        File apk = resolveApk(a.isEmpty() ? null : a.get(0), out);
        if (apk == null) {
            return;
        }
        File keystore = resolveKeystore(ks, out);
        if (keystore == null) {
            return;
        }
        String err = host.signApk(apk, keystore, alias);
        if (err != null) {
            out.line(Level.ERROR, err);
        }
    }

    private void keystore(List<String> args, Out out) {
        if (host.currentProject() == null) {
            noProject(out);
            return;
        }
        String sub = args.isEmpty() ? "list" : args.get(0);
        List<String> a = new ArrayList<String>(args.isEmpty() ? args : args.subList(1, args.size()));
        if ("list".equals(sub)) {
            List<File> all = com.zishu.ide.core.signing.KeyStores.list(projectDir());
            if (all.isEmpty()) {
                out.line(Level.WARN, "No keystores in this project. Use  zishu keystore create <name>  or  zishu keystore import.");
                out.line(Level.DIM, "Folder: " + com.zishu.ide.core.signing.KeyStores.dir(projectDir()).getPath());
                return;
            }
            out.line(Level.DIM, com.zishu.ide.core.signing.KeyStores.dir(projectDir()).getPath());
            for (File f : all) {
                out.line(Level.PLAIN, pad(f.getName(), 30) + IoUtil.formatSize(f.length()));
            }
        } else if ("create".equals(sub)) {
            String alias = takeOption(a, "--alias");
            String cn = takeOption(a, "--cn");
            String org = takeOption(a, "--org");
            String country = takeOption(a, "--country");
            String yearsText = takeOption(a, "--years");
            int years = 30;
            if (yearsText != null) {
                try {
                    years = Integer.parseInt(yearsText);
                } catch (NumberFormatException e) {
                    out.line(Level.ERROR, "--years must be a number between 1 and 100.");
                    return;
                }
            }
            if (years < 1 || years > 100) {
                out.line(Level.ERROR, "--years must be a number between 1 and 100.");
                return;
            }
            if (a.isEmpty()) {
                out.line(Level.ERROR, "Usage: zishu keystore create <name> [--alias a] [--cn \"Your Name\"] [--org O] "
                        + "[--country IN] [--years 30]");
                return;
            }
            String file = com.zishu.ide.core.signing.KeyStores.fileName(a.get(0));
            if (new File(com.zishu.ide.core.signing.KeyStores.dir(projectDir()), file).exists()) {
                out.line(Level.ERROR, file + " already exists. Choose another name; existing keys are never overwritten.");
                return;
            }
            if (alias == null) {
                alias = "key0";
            }
            if (!alias.matches("[A-Za-z0-9._-]{1,64}")) {
                out.line(Level.ERROR, "The alias may use letters, digits, dot, dash and underscore only.");
                return;
            }
            String err = host.createKeystore(file, alias, cn == null ? alias : cn, org, country, years);
            if (err != null) {
                out.line(Level.ERROR, err);
            }
        } else if ("import".equals(sub)) {
            String err = host.importKeystore();
            if (err != null) {
                out.line(Level.ERROR, err);
            }
        } else if ("info".equals(sub) || "delete".equals(sub)) {
            File f = resolveKeystore(a.isEmpty() ? null : a.get(0), out);
            if (f == null) {
                return;
            }
            String err = "info".equals(sub) ? host.keystoreInfo(f) : host.deleteKeystore(f);
            if (err != null) {
                out.line(Level.ERROR, err);
            }
        } else {
            out.line(Level.ERROR, "Unknown keystore command: " + sub + "   (zishu help keystore)");
        }
    }

    private void clone(List<String> args, Out out) {
        List<String> a = new ArrayList<String>(args);
        String branch = takeOption(a, "--branch");
        String name = takeOption(a, "--name");
        boolean auth = takeFlag(a, "--auth");
        if (a.isEmpty()) {
            out.line(Level.ERROR, "Usage: zishu clone <owner/repo | https://github.com/owner/repo> [--branch b] [--name n] [--auth]");
            return;
        }
        com.zishu.ide.core.project.GitHubRepo repo = com.zishu.ide.core.project.GitHubRepo.parse(a.get(0));
        if (repo == null) {
            out.line(Level.ERROR, "That is not a GitHub repository. Use owner/repo or https://github.com/owner/repo.");
            return;
        }
        if (branch != null) {
            repo = repo.withRef(branch);
        }
        String err = host.cloneRepo(repo.owner + "/" + repo.repo, repo.ref, name == null ? repo.repo : name, auth);
        if (err != null) {
            out.line(Level.ERROR, err);
        }
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
