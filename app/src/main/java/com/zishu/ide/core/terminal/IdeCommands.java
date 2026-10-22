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
                "history", "cloud", "selftest", "editor", "clear", "version"};
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

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
