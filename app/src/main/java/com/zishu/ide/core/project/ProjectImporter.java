package com.zishu.ide.core.project;

import com.zishu.ide.core.model.Defaults;
import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.template.Escape;
import com.zishu.ide.core.template.ProjectTemplates;
import com.zishu.ide.core.util.IoUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an extracted ZIP, a cloned repository or a copied folder into a project laid out the
 * way the build engines expect: {@code settings.gradle}, root {@code build.gradle} and an
 * {@code app/} module with {@code src/main/{AndroidManifest.xml,java,res,assets}}.
 *
 * <ul>
 *   <li>A Gradle project is kept as it is (wrapper folders such as GitHub's {@code repo-main/} are stripped).</li>
 *   <li>A module that already has {@code src/main} but no Gradle files gets the Gradle files it lacks.</li>
 *   <li>A flat layout (AndroidManifest.xml next to {@code src/}, {@code res/}, {@code assets/}, {@code libs/}) is
 *       moved into the standard layout and the Gradle files are generated from the manifest.</li>
 * </ul>
 * Everything the importer decides is reported in {@link Result#notes}; nothing is silently guessed.
 */
public final class ProjectImporter {
    private static final int MAX_DEPTH = 7;
    private static final Set<String> SKIP_DIRS = new HashSet<String>(Arrays.asList(
            "build", ".git", ".gradle", ".idea", ".zishu", ".cxx", "node_modules", "__MACOSX"));

    private ProjectImporter() {
    }

    public static final class Result {
        /** The directory that is now the project root (may be the extracted directory itself or a folder inside it). */
        public final File root;
        public final List<String> notes;
        /** True when the importer created Gradle files or moved files into the standard layout. */
        public final boolean restructured;
        public final String applicationId;
        public final String appName;
        public final Language language;

        Result(File root, List<String> notes, boolean restructured, String applicationId, String appName,
               Language language) {
            this.root = root;
            this.notes = Collections.unmodifiableList(notes);
            this.restructured = restructured;
            this.applicationId = applicationId == null ? "" : applicationId;
            this.appName = appName == null ? "" : appName;
            this.language = language;
        }
    }

    /** Thrown when no Android app could be found at all. */
    public static final class NoAppFoundException extends IOException {
        public NoAppFoundException(String message) {
            super(message);
        }
    }

    /**
     * Normalises {@code extracted} in place and returns where the project root is. Throws
     * {@link NoAppFoundException} (and changes nothing) when there is no AndroidManifest.xml and no Gradle project.
     */
    public static Result normalize(File extracted, String projectName) throws IOException {
        List<String> notes = new ArrayList<String>();
        File gradleRoot = ProjectLocator.findGradleRoot(extracted);
        if (gradleRoot == null) {
            gradleRoot = findSettingsRoot(extracted, 0);
            if (gradleRoot != null) {
                notes.add("Gradle project found in the folder \"" + relative(extracted, gradleRoot) + "\".");
            }
        }
        if (gradleRoot != null) {
            return keepGradle(gradleRoot, notes);
        }

        List<Candidate> candidates = new ArrayList<Candidate>();
        collectManifests(extracted, extracted, 0, candidates);
        if (candidates.isEmpty()) {
            throw new NoAppFoundException("No AndroidManifest.xml and no Gradle files were found in this archive, so "
                    + "there is no Android app to set up. (Web files alone are not an Android project.)");
        }
        Collections.sort(candidates, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                if (a.launcher != b.launcher) {
                    return a.launcher ? -1 : 1;
                }
                if (a.module != b.module) {
                    return a.module ? -1 : 1;
                }
                if (a.depth != b.depth) {
                    return a.depth < b.depth ? -1 : 1;
                }
                return a.manifest.getPath().compareTo(b.manifest.getPath());
            }
        });
        Candidate pick = candidates.get(0);
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder("The archive holds " + candidates.size()
                    + " Android modules. Using \"" + relative(extracted, pick.dir) + "\". Others: ");
            for (int i = 1; i < candidates.size() && i < 6; i++) {
                sb.append(i > 1 ? ", " : "").append('"').append(relative(extracted, candidates.get(i).dir)).append('"');
            }
            notes.add(sb.toString());
        }
        File stripped = stripWrappers(extracted);
        return pick.module ? scaffoldModule(extracted, stripped, pick, projectName, notes)
                : scaffoldFlat(pick, projectName, notes);
    }

    // ------------------------------------------------------------------ gradle projects

    private static Result keepGradle(File root, List<String> notes) throws IOException {
        boolean changed = false;
        boolean hasSettings = new File(root, "settings.gradle").isFile() || new File(root, "settings.gradle.kts").isFile();
        if (!hasSettings) {
            File[] kids = root.listFiles();
            List<String> modules = new ArrayList<String>();
            if (kids != null) {
                Arrays.sort(kids);
                for (File k : kids) {
                    if (k.isDirectory() && !k.getName().startsWith(".") && !SKIP_DIRS.contains(k.getName())
                            && (new File(k, "build.gradle").isFile() || new File(k, "build.gradle.kts").isFile())) {
                        modules.add(k.getName());
                    }
                }
            }
            if (!modules.isEmpty()) {
                StringBuilder sb = new StringBuilder("rootProject.name = '")
                        .append(Escape.groovyString(root.getName().startsWith(".") ? "project" : root.getName()))
                        .append("'\n");
                for (String m : modules) {
                    sb.append("include ':").append(m).append("'\n");
                }
                IoUtil.writeUtf8Atomic(new File(root, "settings.gradle"), sb.toString());
                notes.add("settings.gradle was missing; created it with " + modules + ".");
                changed = true;
            }
        }
        Info info = null;
        File manifest = new File(root, "app/src/main/AndroidManifest.xml");
        if (manifest.isFile()) {
            info = Info.parse(IoUtil.readUtf8(manifest, 2 * 1024 * 1024));
        }
        return new Result(root, notes, changed, info == null ? "" : info.packageName, info == null ? "" : info.label,
                hasKotlin(new File(root, "app/src")) ? Language.KOTLIN : Language.JAVA);
    }

    private static File findSettingsRoot(File dir, int depth) {
        if (new File(dir, "settings.gradle").isFile() || new File(dir, "settings.gradle.kts").isFile()) {
            return dir;
        }
        if (depth >= 4) {
            return null;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return null;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            if (k.isDirectory() && !k.getName().startsWith(".") && !SKIP_DIRS.contains(k.getName())) {
                File r = findSettingsRoot(k, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ manifests

    private static final class Candidate {
        File manifest;
        File dir;
        boolean module;
        boolean launcher;
        int depth;
    }

    private static void collectManifests(File root, File dir, int depth, List<Candidate> out) {
        if (depth > MAX_DEPTH) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                String n = k.getName();
                if (n.startsWith(".") || SKIP_DIRS.contains(n)) {
                    continue;
                }
                if (dir.getName().equals("src") && (n.equals("test") || n.equals("androidTest"))) {
                    continue;
                }
                collectManifests(root, k, depth + 1, out);
            } else if (k.getName().equals("AndroidManifest.xml") && k.isFile()) {
                Candidate c = new Candidate();
                c.manifest = k;
                c.depth = depth;
                File parent = dir.getParentFile();
                if (dir.getName().equals("main") && parent != null && parent.getName().equals("src")) {
                    c.module = true;
                    c.dir = parent.getParentFile();
                } else if (parent != null && parent.getName().equals("src")) {
                    continue; // src/debug, src/release ...: variant manifests, not an app
                } else {
                    c.module = false;
                    c.dir = dir;
                }
                try {
                    String text = IoUtil.readUtf8(k, 2 * 1024 * 1024);
                    c.launcher = text.contains("android.intent.category.LAUNCHER");
                } catch (IOException e) {
                    c.launcher = false;
                }
                out.add(c);
            }
        }
    }

    private static final class Info {
        String packageName = "";
        int versionCode = 1;
        String versionName = "1.0";
        int minSdk = Defaults.MIN_SDK;
        int targetSdk = -1;
        String label = "";

        static Info parse(String xml) {
            Info i = new Info();
            Matcher m = Pattern.compile("<manifest\\b[^>]*>", Pattern.DOTALL).matcher(xml);
            if (m.find()) {
                String tag = m.group();
                i.packageName = attr(tag, "package");
                i.versionCode = parseInt(attr(tag, "android:versionCode"), 1);
                String vn = attr(tag, "android:versionName");
                if (vn.length() > 0 && !vn.startsWith("@")) {
                    i.versionName = vn;
                }
            }
            Matcher u = Pattern.compile("<uses-sdk\\b[^>]*>", Pattern.DOTALL).matcher(xml);
            if (u.find()) {
                i.minSdk = parseInt(attr(u.group(), "android:minSdkVersion"), Defaults.MIN_SDK);
                i.targetSdk = parseInt(attr(u.group(), "android:targetSdkVersion"), -1);
            }
            Matcher a = Pattern.compile("<application\\b[^>]*>", Pattern.DOTALL).matcher(xml);
            if (a.find()) {
                String l = attr(a.group(), "android:label");
                if (l.length() > 0 && !l.startsWith("@")) {
                    i.label = l;
                }
            }
            return i;
        }

        private static String attr(String tag, String name) {
            Matcher m = Pattern.compile("(?<![\\w:.-])" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
            return m.find() ? m.group(1) : "";
        }

        private static int parseInt(String s, int fallback) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /** Removes what Gradle now owns (package, versions, uses-sdk) and fixes missing android:exported. */
    static String cleanManifest(String xml, int targetSdk, List<String> notes) {
        Matcher m = Pattern.compile("<manifest\\b[^>]*>", Pattern.DOTALL).matcher(xml);
        if (m.find()) {
            String tag = m.group();
            String t = tag.replaceAll("\\s+package\\s*=\\s*\"[^\"]*\"", "")
                    .replaceAll("\\s+android:versionCode\\s*=\\s*\"[^\"]*\"", "")
                    .replaceAll("\\s+android:versionName\\s*=\\s*\"[^\"]*\"", "");
            xml = xml.substring(0, m.start()) + t + xml.substring(m.end());
        }
        xml = xml.replaceAll("[ \\t]*<uses-sdk\\b[^>]*/>[ \\t]*\\r?\\n?", "");
        xml = xml.replaceAll("[ \\t]*<uses-sdk\\b[^>]*>\\s*</uses-sdk>[ \\t]*\\r?\\n?", "");
        if (targetSdk >= 31) {
            Pattern p = Pattern.compile("<(activity-alias|activity|service|receiver)\\b([^>]*?)(/?)>", Pattern.DOTALL);
            StringBuilder out = new StringBuilder();
            int last = 0;
            int fixed = 0;
            Matcher em = p.matcher(xml);
            while (em.find()) {
                if (em.group(3).length() > 0 || em.group(2).contains("android:exported")) {
                    continue;
                }
                int end = xml.indexOf("</" + em.group(1) + ">", em.end());
                if (end < 0 || xml.substring(em.end(), end).indexOf("<intent-filter") < 0) {
                    continue;
                }
                out.append(xml, last, em.start() + 1 + em.group(1).length());
                out.append(" android:exported=\"true\"");
                last = em.start() + 1 + em.group(1).length();
                fixed++;
            }
            if (fixed > 0) {
                out.append(xml, last, xml.length());
                xml = out.toString();
                notes.add("Added android:exported=\"true\" to " + fixed + " component(s) with an intent-filter "
                        + "(required when targetSdk is 31 or higher).");
            }
        }
        return xml;
    }

    // ------------------------------------------------------------------ scaffolding

    private static Result scaffoldFlat(Candidate pick, String projectName, List<String> notes) throws IOException {
        File base = pick.dir;
        File main = new File(base, "app/src/main");
        if (new File(base, "app").exists()) {
            throw new IOException("This project already has an \"app\" folder next to AndroidManifest.xml; "
                    + "cannot restructure it automatically.");
        }
        String manifestText = IoUtil.readUtf8(pick.manifest, 2 * 1024 * 1024);
        Info info = Info.parse(manifestText);
        File srcSrc = firstDir(base, "src", "java");
        File tmpHold = new File(base, ".zishu-move");
        mkdirs(tmpHold);
        // Park everything that moves, because the targets live inside the base folder.
        File pManifest = park(pick.manifest, tmpHold, "AndroidManifest.xml");
        File pSrc = srcSrc == null ? null : park(srcSrc, tmpHold, "src");
        File pRes = parkIfDir(base, "res", tmpHold);
        File pAssets = parkIfDir(base, "assets", tmpHold);
        File pLibs = parkIfDir(base, "libs", tmpHold);
        File pJni = parkIfDir(base, "jniLibs", tmpHold);
        mkdirs(main);
        boolean kotlin = pSrc != null && hasKotlin(pSrc);
        String cleaned = cleanManifest(manifestText, info.targetSdk < 0 ? info.minSdk : info.targetSdk, notes);
        IoUtil.writeUtf8Atomic(new File(main, "AndroidManifest.xml"), cleaned);
        pManifest.delete();
        place(pSrc, new File(main, "java"));
        place(pRes, new File(main, "res"));
        place(pAssets, new File(main, "assets"));
        place(pJni, new File(main, "jniLibs"));
        place(pLibs, new File(base, "app/libs"));
        IoUtil.deleteRecursively(tmpHold);
        for (File f : listFiles(base)) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isFile() && (n.endsWith(".pro") || n.equals("proguard.cfg"))) {
                place(f, new File(base, "app/" + f.getName()));
            }
        }
        if (pSrc == null) {
            notes.add("No src/ or java/ folder was found next to AndroidManifest.xml; the app has no Java sources yet.");
        }
        notes.add("Flat layout detected: moved the manifest, sources, res, assets and libs into app/src/main/.");
        writeGradle(base, info, kotlin, new File(base, "app/libs").isDirectory(), projectName, notes, true);
        return new Result(base, notes, true, effectiveId(info, projectName), info.label,
                kotlin ? Language.KOTLIN : Language.JAVA);
    }

    private static Result scaffoldModule(File extracted, File stripped, Candidate pick, String projectName,
                                         List<String> notes) throws IOException {
        File module = pick.dir;
        File base;
        String moduleName = "app";
        if (module.equals(stripped)) {
            base = stripped;
            File app = new File(base, "app");
            if (app.exists()) {
                throw new IOException("This project already has an \"app\" folder; cannot restructure it automatically.");
            }
            mkdirs(app);
            for (String n : new String[]{"src", "libs", "jniLibs", "build.gradle", "build.gradle.kts"}) {
                File f = new File(base, n);
                if (f.exists()) {
                    place(f, new File(app, n));
                }
            }
            for (File f : listFiles(base)) {
                String n = f.getName().toLowerCase(Locale.US);
                if (f.isFile() && (n.endsWith(".pro") || n.equals("proguard.cfg"))) {
                    place(f, new File(app, f.getName()));
                }
            }
            module = app;
            notes.add("Moved src/ into an app/ module.");
        } else {
            base = module.getParentFile();
            if (module.getName().equals("app")) {
                moduleName = "app";
            } else if (!new File(base, "app").exists()) {
                File app = new File(base, "app");
                if (!module.renameTo(app)) {
                    throw new IOException("Could not rename the module folder to app");
                }
                notes.add("Renamed module folder \"" + module.getName() + "\" to app.");
                module = app;
            } else {
                moduleName = module.getName();
                notes.add("Module \"" + moduleName + "\" kept its name; the on-device engine looks for \"app\" or a "
                        + "root module, so use the cloud build for it.");
            }
        }
        File manifest = new File(module, "src/main/AndroidManifest.xml");
        String manifestText = IoUtil.readUtf8(manifest, 2 * 1024 * 1024);
        Info info = Info.parse(manifestText);
        boolean kotlin = hasKotlin(new File(module, "src"));
        boolean hasModuleGradle = new File(module, "build.gradle").isFile() || new File(module, "build.gradle.kts").isFile();
        if (!hasModuleGradle) {
            IoUtil.writeUtf8Atomic(manifest,
                    cleanManifest(manifestText, info.targetSdk < 0 ? info.minSdk : info.targetSdk, notes));
        } else {
            notes.add("Found " + moduleName + "/build.gradle; kept it and only added the missing top-level files.");
        }
        writeGradle(base, info, kotlin, new File(module, "libs").isDirectory(), projectName, notes, !hasModuleGradle,
                moduleName);
        return new Result(base, notes, true, effectiveId(info, projectName), info.label,
                kotlin ? Language.KOTLIN : Language.JAVA);
    }

    private static void writeGradle(File base, Info info, boolean kotlin, boolean libs, String projectName,
                                    List<String> notes, boolean appGradle) throws IOException {
        writeGradle(base, info, kotlin, libs, projectName, notes, appGradle, "app");
    }

    private static void writeGradle(File base, Info info, boolean kotlin, boolean libs, String projectName,
                                    List<String> notes, boolean appGradle, String moduleName) throws IOException {
        ProjectConfig c = new ProjectConfig();
        c.projectName = projectName == null || projectName.trim().length() == 0 ? "ImportedApp" : projectName.trim();
        c.applicationId = effectiveId(info, projectName);
        c.language = kotlin ? Language.KOTLIN : Language.JAVA;
        int min = Math.max(info.minSdk, Defaults.LOWEST_MIN_SDK);
        if (min != info.minSdk) {
            notes.add("minSdkVersion " + info.minSdk + " raised to " + min + " (the lowest the toolchain supports).");
        }
        int target = info.targetSdk < 0 ? min : Math.max(info.targetSdk, min);
        c.minSdk = min;
        c.targetSdk = target;
        c.compileSdk = Math.max(Defaults.COMPILE_SDK, target);
        c.versionCode = info.versionCode;
        c.versionName = info.versionName;
        c.javaLevel = 8;
        if (info.packageName.length() == 0) {
            notes.add("The manifest has no package name; using " + c.applicationId + ". Change applicationId in "
                    + "app/build.gradle if you need another one.");
        }
        writeIfMissing(new File(base, "settings.gradle"),
                moduleName.equals("app") ? ProjectTemplates.settingsGradle(c)
                        : ProjectTemplates.settingsGradle(c).replace("include ':app'", "include ':" + moduleName + "'"),
                notes, "settings.gradle");
        writeIfMissing(new File(base, "build.gradle"), ProjectTemplates.rootBuildGradle(c, kotlin), notes, "build.gradle");
        writeIfMissing(new File(base, "gradle.properties"),
                "org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\nandroid.useAndroidX=false\n", notes, null);
        writeIfMissing(new File(base, ".gitignore"),
                "build/\n.gradle/\nlocal.properties\n.idea/\n*.iml\n.cxx/\n.zishu/\n", notes, null);
        if (appGradle) {
            String g = ProjectTemplates.appBuildGradle(c, kotlin);
            if (libs) {
                g += "\ndependencies {\n    implementation fileTree(dir: 'libs', include: ['*.jar'])\n}\n";
            }
            writeIfMissing(new File(base, moduleName + "/build.gradle"), g, notes, moduleName + "/build.gradle");
        }
    }

    private static void writeIfMissing(File f, String text, List<String> notes, String label) throws IOException {
        if (f.exists()) {
            return;
        }
        IoUtil.writeUtf8Atomic(f, text);
        if (label != null) {
            notes.add("Created " + label + " from the manifest.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String effectiveId(Info info, String projectName) {
        if (info.packageName.length() > 0 && info.packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            return info.packageName;
        }
        StringBuilder sb = new StringBuilder();
        String n = projectName == null ? "" : projectName.toLowerCase(Locale.US);
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9' && sb.length() > 0)) {
                sb.append(ch);
            }
        }
        return "com.imported." + (sb.length() == 0 ? "app" : sb.toString());
    }

    private static boolean hasKotlin(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return false;
        }
        for (File k : kids) {
            if (k.isDirectory() ? hasKotlin(k) : k.getName().endsWith(".kt")) {
                return true;
            }
        }
        return false;
    }

    /** Descends through single wrapper folders (a GitHub zipball has exactly one). */
    private static File stripWrappers(File dir) {
        for (int depth = 0; depth < 3; depth++) {
            File[] kids = dir.listFiles();
            if (kids == null) {
                return dir;
            }
            File only = null;
            int count = 0;
            for (File k : kids) {
                if (k.getName().equals("__MACOSX")) {
                    continue;
                }
                count++;
                only = k;
            }
            if (count == 1 && only.isDirectory() && !only.getName().equals("src")) {
                dir = only;
            } else {
                return dir;
            }
        }
        return dir;
    }

    private static File firstDir(File base, String... names) {
        for (String n : names) {
            File f = new File(base, n);
            if (f.isDirectory()) {
                return f;
            }
        }
        return null;
    }

    private static File parkIfDir(File base, String name, File hold) throws IOException {
        File f = new File(base, name);
        return f.isDirectory() ? park(f, hold, name) : null;
    }

    private static File park(File f, File hold, String name) throws IOException {
        File to = new File(hold, name);
        if (!f.renameTo(to)) {
            throw new IOException("Could not move " + f.getName());
        }
        return to;
    }

    private static void place(File from, File to) throws IOException {
        if (from == null) {
            return;
        }
        mkdirs(to.getParentFile());
        if (!from.renameTo(to)) {
            throw new IOException("Could not move " + from.getName() + " to " + to.getName());
        }
    }

    private static void mkdirs(File d) throws IOException {
        if (!d.isDirectory() && !d.mkdirs()) {
            throw new IOException("Could not create folder " + d.getName());
        }
    }

    private static File[] listFiles(File d) {
        File[] f = d.listFiles();
        return f == null ? new File[0] : f;
    }

    private static String relative(File root, File f) {
        String r = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(r)) {
            return ".";
        }
        return p.startsWith(r) ? p.substring(r.length() + 1) : p;
    }
}
