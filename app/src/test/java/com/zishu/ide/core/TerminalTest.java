package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.build.BuildEngine;
import com.zishu.ide.core.build.BuildHistory;
import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectType;
import com.zishu.ide.core.project.ProjectInfo;
import com.zishu.ide.core.terminal.AnsiStream;
import com.zishu.ide.core.terminal.CommandLine;
import com.zishu.ide.core.terminal.Completer;
import com.zishu.ide.core.terminal.IdeCommands;
import com.zishu.ide.core.terminal.Utf8Chunker;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TerminalTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- CommandLine ----
    @Test
    public void tokenizerHandlesQuotesAndEscapes() {
        assertEquals(Arrays.asList("a", "b c", "d e", "f"), CommandLine.tokenize("a \"b c\" 'd e' f"));
        assertEquals(Arrays.asList("x y"), CommandLine.tokenize("x\\ y"));
        assertEquals(Arrays.asList("", "a"), CommandLine.tokenize("'' a"));
        assertNull(CommandLine.tokenize("echo \"open"));
        assertTrue(CommandLine.tokenize("   ").isEmpty());
        assertEquals("'it'\\''s'", CommandLine.shellQuote("it's"));
    }

    // ---- AnsiStream ----
    private static String text(List<AnsiStream.Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (AnsiStream.Span s : spans) {
            sb.append(s.text);
        }
        return sb.toString();
    }

    @Test
    public void ansiColoursApplyOnlyToTheirText() {
        AnsiStream a = new AnsiStream();
        List<AnsiStream.Span> s = a.feed("\u001b[32mOK\u001b[0m done");
        assertEquals(2, s.size());
        assertEquals("OK", s.get(0).text);
        assertEquals(2, s.get(0).fg);
        assertEquals(" done", s.get(1).text);
        assertEquals(AnsiStream.DEFAULT, s.get(1).fg);
    }

    @Test
    public void ansiStateSurvivesAcrossChunksAndSplitSequences() {
        AnsiStream a = new AnsiStream();
        assertEquals("", text(a.feed("\u001b[3")));
        List<AnsiStream.Span> s = a.feed("1mred");
        assertEquals("red", s.get(0).text);
        assertEquals(1, s.get(0).fg);
        s = a.feed(" still red\n");
        assertEquals(1, s.get(0).fg);
    }

    @Test
    public void ansiExtendedColoursBoldAndStripping() {
        AnsiStream a = new AnsiStream();
        List<AnsiStream.Span> s = a.feed("\u001b[1;38;5;196mX\u001b[38;2;1;2;3mY\u001b[2K\u001b]0;title\u0007Z\u001b[10;5H");
        assertEquals("XYZ", text(s));
        assertTrue(s.get(0).bold);
        assertEquals(0xFFFF0000 & 0xFFFF0000, s.get(0).fg & 0xFFFF0000);
        assertEquals(0xFF010203, s.get(1).fg);
    }

    @Test
    public void ansiCarriageReturnAndCrlf() {
        AnsiStream a = new AnsiStream();
        List<AnsiStream.Span> s = a.feed("one\r\ntwo\rthree");
        assertEquals("one\ntwothree", text(s));
        boolean sawCr = false;
        for (AnsiStream.Span x : s) {
            sawCr |= x.kind == AnsiStream.Kind.CARRIAGE_RETURN;
        }
        assertTrue(sawCr);
        // a CR at the end of a chunk waits for the next chunk to decide whether it is part of CRLF
        AnsiStream b = new AnsiStream();
        assertEquals("x", text(b.feed("x\r")));
        assertEquals("\ny", text(b.feed("\ny")));
    }

    @Test
    public void ansiClearAndControlCharacters() {
        AnsiStream a = new AnsiStream();
        List<AnsiStream.Span> s = a.feed("a\u0007b\u001b[2Jc");
        assertEquals("abc", text(s));
        assertEquals(AnsiStream.Kind.CLEAR, s.get(1).kind);
    }

    // ---- Utf8Chunker ----
    @Test
    public void utf8CharacterSplitAcrossReadsIsNotCorrupted() throws Exception {
        byte[] b = "aनb".getBytes("UTF-8"); // 'a', 3-byte Devanagari letter, 'b'
        Utf8Chunker c = new Utf8Chunker();
        String s = c.decode(b, 0, 2) + c.decode(b, 2, b.length - 2);
        assertEquals("aनb", s);
    }

    // ---- Completer ----
    @Test
    public void completerCompletesCommandsProjectsAndFiles() throws Exception {
        List<String> projects = Arrays.asList("MyApp", "Notes");
        assertEquals("zishu build ", Completer.complete("zishu bu", projects, tmp.getRoot()).line);
        assertEquals("zishu open Notes ", Completer.complete("zishu open No", projects, tmp.getRoot()).line);
        assertEquals("zishu engine cloud ", Completer.complete("zishu engine cl", projects, tmp.getRoot()).line);
        Completer.Result many = Completer.complete("zishu c", projects, tmp.getRoot());
        assertEquals("zishu c", many.line);
        assertTrue(many.options.contains("cancel") && many.options.contains("cloud"));
        new File(tmp.getRoot(), "src").mkdir();
        tmp.newFile("readme.txt");
        assertEquals("cat src/", Completer.complete("cat sr", projects, tmp.getRoot()).line);
        assertEquals("cat readme.txt ", Completer.complete("cat rea", projects, tmp.getRoot()).line);
        assertEquals("zzz", Completer.complete("zzz", projects, tmp.getRoot()).line);
    }

    // ---- IdeCommands ----
    static final class FakeHost implements IdeCommands.Host {
        final List<ProjectInfo> projects = new ArrayList<ProjectInfo>();
        ProjectInfo current;
        String choice = "auto";
        boolean building;
        String lastEngine = "unset";
        String buildError;
        boolean deviceReady = true;
        boolean cloudReady = false;
        String repo = "";
        boolean workflow, pub, token;
        String log;
        File apk;
        boolean opened, cleared, settings, selftest, installed;

        @Override public List<ProjectInfo> projects() { return projects; }
        @Override public ProjectInfo currentProject() { return current; }
        @Override public void setCurrentProject(ProjectInfo p) { current = p; }
        @Override public String engineChoice() { return choice; }
        @Override public void setEngineChoice(String c) { choice = c; }
        @Override public BuildEngine.Status engineStatus(String e) {
            boolean ok = "device".equals(e) ? deviceReady : cloudReady;
            return ok ? BuildEngine.Status.ok("fine") : BuildEngine.Status.unavailable("not set up", "set it up");
        }
        @Override public String startBuild(String engine) { lastEngine = String.valueOf(engine); return buildError; }
        @Override public boolean isBuilding() { return building; }
        @Override public void cancelBuild() { building = false; }
        @Override public String lastLog() { return log; }
        @Override public File lastApk() { return apk; }
        @Override public String installLastApk() { installed = true; return apk == null ? "No APK." : null; }
        @Override public List<BuildHistory.Entry> history() { return new ArrayList<BuildHistory.Entry>(); }
        @Override public String cloudRepo() { return repo; }
        @Override public String setCloudRepo(String r) { repo = r; return null; }
        @Override public boolean cloudWorkflow() { return workflow; }
        @Override public void setCloudWorkflow(boolean on) { workflow = on; }
        @Override public boolean cloudAllowPublic() { return pub; }
        @Override public void setCloudAllowPublic(boolean on) { pub = on; }
        @Override public boolean hasToken() { return token; }
        @Override public void openCloudSettings() { settings = true; }
        @Override public void openEditor() { opened = true; }
        @Override public void runSelfTest() { selftest = true; }
        @Override public void clearScreen() { cleared = true; }
        String last = "";
        @Override public String downloadApk(File a) { last = "download " + a.getName(); return null; }
        @Override public String signApk(File a, File k, String alias) { last = "sign " + a.getName() + " " + k.getName() + " " + alias; return null; }
        @Override public String verifyApk(File a) { last = "verify " + a.getName(); return null; }
        @Override public String createKeystore(String f, String al, String cn, String o, String c, int y) {
            last = "create " + f + " " + al + " " + cn + " " + o + " " + c + " " + y; return null; }
        @Override public String importKeystore() { last = "import"; return null; }
        @Override public String keystoreInfo(File k) { last = "info " + k.getName(); return null; }
        @Override public String deleteKeystore(File k) { last = "delete " + k.getName(); return null; }
        @Override public String cloneRepo(String r, String ref, String n, boolean t) { last = "clone " + r + " " + ref + " " + n + " " + t; return null; }
    }

    private ProjectInfo project(String name) {
        return new ProjectInfo(new File("/p/" + name), name, ProjectType.NATIVE_JAVA, Language.JAVA, name, "a.b", "", 0, 0);
    }

    private String run(IdeCommands c, String line) {
        final StringBuilder sb = new StringBuilder();
        c.run(CommandLine.tokenize(line), new IdeCommands.Out() {
            @Override
            public void line(IdeCommands.Level level, String text) {
                sb.append(level.name()).append(':').append(text).append('\n');
            }
        });
        return sb.toString();
    }

    @Test
    public void handlesOnlyIdeWords() {
        assertTrue(IdeCommands.handles("zishu"));
        assertTrue(IdeCommands.handles("build"));
        assertFalse(IdeCommands.handles("ls"));
        assertFalse(IdeCommands.handles("install")); // left to the system shell
    }

    @Test
    public void openSelectsByNumberNameAndPrefix() {
        FakeHost h = new FakeHost();
        h.projects.add(project("MyApp"));
        h.projects.add(project("Notes"));
        IdeCommands c = new IdeCommands(h);
        run(c, "zishu open 2");
        assertEquals("Notes", h.current.name);
        run(c, "z open myapp");
        assertEquals("MyApp", h.current.name);
        run(c, "zishu open No");
        assertEquals("Notes", h.current.name);
        assertTrue(run(c, "zishu open zzz").contains("ERROR:No project matches"));
        assertTrue(run(c, "zishu open").contains("Usage"));
    }

    @Test
    public void buildNeedsProjectAndPassesEngine() {
        FakeHost h = new FakeHost();
        IdeCommands c = new IdeCommands(h);
        assertTrue(run(c, "build").contains("No project is selected"));
        assertEquals("unset", h.lastEngine);
        h.current = project("MyApp");
        run(c, "build");
        assertEquals("null", h.lastEngine);
        run(c, "zishu build --device");
        assertEquals("device", h.lastEngine);
        run(c, "zishu build --cloud");
        assertEquals("cloud", h.lastEngine);
        assertTrue(run(c, "zishu build --bogus").contains("Unknown option"));
        h.buildError = "Cloud build is not set up";
        assertTrue(run(c, "build --cloud").contains("ERROR:Cloud build is not set up"));
    }

    @Test
    public void engineChoiceIsValidated() {
        FakeHost h = new FakeHost();
        IdeCommands c = new IdeCommands(h);
        run(c, "zishu engine cloud");
        assertEquals("cloud", h.choice);
        assertTrue(run(c, "zishu engine fast").contains("ERROR"));
        assertEquals("cloud", h.choice);
        assertTrue(run(c, "zishu engine").contains("cloud"));
    }

    @Test
    public void cancelAndLogAndApk() throws Exception {
        FakeHost h = new FakeHost();
        IdeCommands c = new IdeCommands(h);
        assertTrue(run(c, "zishu cancel").contains("No build is running"));
        h.building = true;
        assertTrue(run(c, "zishu cancel").contains("Cancel requested"));
        assertFalse(h.building);
        assertTrue(run(c, "zishu log").contains("no build log"));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sb.append("line ").append(i).append('\n');
        }
        h.log = sb.toString();
        String out = run(c, "zishu log 3");
        assertEquals("PLAIN:line 98\nPLAIN:line 99\nPLAIN:line 100\n", out);
        assertTrue(run(c, "zishu apk").contains("No APK"));
        h.apk = tmp.newFile("a.apk");
        assertTrue(run(c, "zishu apk").contains("a.apk"));
        assertTrue(run(c, "zishu log x").contains("Usage"));
    }

    @Test
    public void cloudSettingsAreValidatedAndTokenIsRefused() {
        FakeHost h = new FakeHost();
        IdeCommands c = new IdeCommands(h);
        assertTrue(run(c, "zishu cloud repo notarepo").contains("Usage"));
        assertTrue(run(c, "zishu cloud repo someone/zishu-builds").contains("saved"));
        assertEquals("someone/zishu-builds", h.repo);
        run(c, "zishu cloud workflow on");
        assertTrue(h.workflow);
        run(c, "zishu cloud public on");
        assertTrue(h.pub);
        assertTrue(run(c, "zishu cloud workflow maybe").contains("Usage"));
        String tokenOut = run(c, "zishu cloud token ghp_abcdefghijklmnopqrstuvwxyz0123456789");
        assertTrue(tokenOut.contains("not accepted"));
        assertFalse(tokenOut.contains("ghp_"));
        assertEquals("someone/zishu-builds", h.repo);
        run(c, "zishu cloud settings");
        assertTrue(h.settings);
        String status = run(c, "zishu cloud status");
        assertTrue(status.contains("someone/zishu-builds"));
        assertTrue(status.contains("not saved"));
    }

    @Test
    public void statusReportsRealEngineState() {
        FakeHost h = new FakeHost();
        h.current = project("MyApp");
        IdeCommands c = new IdeCommands(h);
        String s = run(c, "zishu status");
        assertTrue(s.contains("OK:  on this phone : ready"));
        assertTrue(s.contains("WARN:  cloud         : not ready - not set up"));
        assertTrue(s.contains("No build is running."));
    }

    @Test
    public void miscCommandsAndUnknown() {
        FakeHost h = new FakeHost();
        IdeCommands c = new IdeCommands(h);
        assertTrue(run(c, "zishu").contains("Zishu IDE commands"));
        assertTrue(run(c, "help cloud").contains("zishu cloud repo"));
        assertTrue(run(c, "zishu frobnicate").contains("Unknown command"));
        run(c, "clear");
        assertTrue(h.cleared);
        run(c, "zishu selftest");
        assertTrue(h.selftest);
        assertTrue(run(c, "zishu editor").contains("No project"));
        h.current = project("X");
        run(c, "zishu editor");
        assertTrue(h.opened);
        run(c, "zishu install");
        assertTrue(h.installed);
        assertTrue(run(c, "zishu projects").contains("no projects"));
        assertTrue(run(c, "zishu history").contains("no recorded builds"));
    }

    @Test
    public void cloneParsesRepoBranchNameAndAuth() {
        FakeHost h = new FakeHost();
        IdeCommands c = new IdeCommands(h);
        run(c, "zishu clone https://github.com/octocat/Hello-World.git --branch dev --name Demo --auth");
        assertEquals("clone octocat/Hello-World dev Demo true", h.last);
        run(c, "zishu clone octocat/Hello-World");
        assertEquals("clone octocat/Hello-World null Hello-World false", h.last);
        assertTrue(run(c, "zishu clone https://gitlab.com/a/b").contains("not a GitHub repository"));
    }

    @Test
    public void signAndKeystoreResolveFilesInTheProject() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "zishu-cmd-" + System.nanoTime());
        new File(dir, "apk").mkdirs();
        new File(dir, "keystore").mkdirs();
        new File(dir, "apk/app-debug.apk").createNewFile();
        new File(dir, "keystore/mine.p12").createNewFile();
        FakeHost h = new FakeHost();
        h.current = new ProjectInfo(dir, "P", ProjectType.NATIVE_JAVA, Language.JAVA, "P", "a.b", "", 0, 0);
        IdeCommands c = new IdeCommands(h);
        run(c, "zishu sign");
        assertEquals("sign app-debug.apk mine.p12 null", h.last);
        run(c, "zishu sign app-debug.apk --keystore mine.p12 --alias key0");
        assertEquals("sign app-debug.apk mine.p12 key0", h.last);
        run(c, "zishu keystore create rel --alias up --cn \"Zed A\" --years 40");
        assertEquals("create rel.p12 up Zed A null null 40", h.last);
        String again = run(c, "zishu keystore create mine --years 40");
        assertTrue(again, again.contains("already exists"));
        assertTrue(run(c, "zishu keystore create x --years 0").contains("between 1 and 100"));
        run(c, "zishu download");
        assertEquals("download app-debug.apk", h.last);
        assertTrue(run(c, "zishu sign nothing.apk").contains("No APK found"));
        new File(dir, "keystore/other.jks").createNewFile();
        assertTrue(run(c, "zishu sign").contains("more than one keystore"));
        assertTrue(run(c, "zishu apks").contains("app-debug.apk"));
    }
}
