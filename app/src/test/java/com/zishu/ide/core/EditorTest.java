package com.zishu.ide.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zishu.ide.core.editor.CodeEditing;
import com.zishu.ide.core.editor.Diagnostic;
import com.zishu.ide.core.editor.FileLanguage;
import com.zishu.ide.core.editor.Highlighter;
import com.zishu.ide.core.editor.SyntaxChecker;
import com.zishu.ide.core.editor.TextSearch;
import com.zishu.ide.core.editor.TokenType;
import com.zishu.ide.core.editor.UndoStack;
import com.zishu.ide.core.project.FileManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;
import java.util.regex.PatternSyntaxException;

public class EditorTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static boolean has(List<Highlighter.Token> tokens, String src, TokenType type, String text) {
        for (Highlighter.Token t : tokens) {
            if (t.type == type && src.substring(t.start, t.end).equals(text)) {
                return true;
            }
        }
        return false;
    }

    // ---- language detection

    @Test
    public void detectsLanguageFromFileName() {
        assertEquals(FileLanguage.JAVA, FileLanguage.fromFileName("Main.java"));
        assertEquals(FileLanguage.KOTLIN, FileLanguage.fromFileName("A.kt"));
        assertEquals(FileLanguage.KOTLIN, FileLanguage.fromFileName("build.gradle.kts"));
        assertEquals(FileLanguage.GRADLE, FileLanguage.fromFileName("build.gradle"));
        assertEquals(FileLanguage.XML, FileLanguage.fromFileName("AndroidManifest.xml"));
        assertEquals(FileLanguage.HTML, FileLanguage.fromFileName("INDEX.HTML"));
        assertEquals(FileLanguage.PROPERTIES, FileLanguage.fromFileName("gradle.properties"));
        assertEquals(FileLanguage.PLAIN, FileLanguage.fromFileName("LICENSE"));
    }

    // ---- highlighting

    @Test
    public void highlightsJava() {
        String src = "public class A { // note\n  String s = \"hi\\\"x\"; int n = 0x1F; @Override void f() {} /* c */ }";
        List<Highlighter.Token> t = Highlighter.highlight(FileLanguage.JAVA, src);
        assertTrue(has(t, src, TokenType.KEYWORD, "public"));
        assertTrue(has(t, src, TokenType.KEYWORD, "class"));
        assertTrue(has(t, src, TokenType.TYPE, "A"));
        assertTrue(has(t, src, TokenType.TYPE, "String"));
        assertTrue(has(t, src, TokenType.COMMENT, "// note"));
        assertTrue(has(t, src, TokenType.STRING, "\"hi\\\"x\""));
        assertTrue(has(t, src, TokenType.NUMBER, "0x1F"));
        assertTrue(has(t, src, TokenType.ANNOTATION, "@Override"));
        assertTrue(has(t, src, TokenType.COMMENT, "/* c */"));
    }

    @Test
    public void keywordsInsideStringsAndCommentsAreNotKeywords() {
        String src = "// class if\nString s = \"public static\";";
        List<Highlighter.Token> t = Highlighter.highlight(FileLanguage.JAVA, src);
        assertFalse(has(t, src, TokenType.KEYWORD, "class"));
        assertFalse(has(t, src, TokenType.KEYWORD, "public"));
        assertFalse(has(t, src, TokenType.KEYWORD, "static"));
    }

    @Test
    public void highlightsKotlinXmlHtmlJsonCssMarkdown() {
        String kt = "fun main() { val x = 1 }";
        assertTrue(has(Highlighter.highlight(FileLanguage.KOTLIN, kt), kt, TokenType.KEYWORD, "fun"));
        assertTrue(has(Highlighter.highlight(FileLanguage.KOTLIN, kt), kt, TokenType.KEYWORD, "val"));

        String xml = "<manifest a=\"b\"><!-- c --><application/></manifest>";
        List<Highlighter.Token> x = Highlighter.highlight(FileLanguage.XML, xml);
        assertTrue(has(x, xml, TokenType.ATTRIBUTE, "a"));
        assertTrue(has(x, xml, TokenType.STRING, "\"b\""));
        assertTrue(has(x, xml, TokenType.COMMENT, "<!-- c -->"));

        String html = "<html><style>p{color:#fff}</style><script>var a = 'x';</script></html>";
        List<Highlighter.Token> h = Highlighter.highlight(FileLanguage.HTML, html);
        assertTrue("css inside <style>", has(h, html, TokenType.NUMBER, "#fff"));
        assertTrue("js inside <script>", has(h, html, TokenType.KEYWORD, "var"));
        assertTrue(has(h, html, TokenType.STRING, "'x'"));

        String json = "{\"k\": [1, true, null, \"v\"]}";
        List<Highlighter.Token> j = Highlighter.highlight(FileLanguage.JSON, json);
        assertTrue(has(j, json, TokenType.PROPERTY, "\"k\""));
        assertTrue(has(j, json, TokenType.KEYWORD, "true"));
        assertTrue(has(j, json, TokenType.STRING, "\"v\""));

        String css = "a.b { margin: 4px; }";
        assertTrue(has(Highlighter.highlight(FileLanguage.CSS, css), css, TokenType.NUMBER, "4px"));

        String md = "# Title\n`code` and **bold**\n- item";
        List<Highlighter.Token> m = Highlighter.highlight(FileLanguage.MARKDOWN, md);
        assertTrue(has(m, md, TokenType.HEADING, "# Title"));
        assertTrue(has(m, md, TokenType.STRING, "`code`"));
    }

    @Test
    public void highlightsUnterminatedConstructsWithoutHanging() {
        String[] samples = {"/* never closed", "\"open string", "<!-- open", "<a href=\"x", "`tpl", "\"\"\"text block"};
        for (FileLanguage l : new FileLanguage[]{FileLanguage.JAVA, FileLanguage.XML, FileLanguage.JAVASCRIPT, FileLanguage.HTML}) {
            for (String s : samples) {
                Highlighter.highlight(l, s); // must terminate and not throw
            }
        }
    }

    @Test
    public void highlightsLargeInputQuickly() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            sb.append("public void method").append(i).append("() { String s = \"x\"; } // c\n");
        }
        long t0 = System.nanoTime();
        List<Highlighter.Token> t = Highlighter.highlight(FileLanguage.JAVA, sb);
        long ms = (System.nanoTime() - t0) / 1000000;
        assertTrue("tokens: " + t.size(), t.size() >= 100000);
        assertTrue("highlighting 20k lines took " + ms + " ms", ms < 5000);
    }

    // ---- syntax checks

    @Test
    public void xmlCheckFindsErrorWithPosition() {
        assertTrue(SyntaxChecker.check(FileLanguage.XML, "<a><b/></a>").isEmpty());
        List<Diagnostic> d = SyntaxChecker.check(FileLanguage.XML, "<a>\n<b>\n</a>");
        assertEquals(1, d.size());
        assertEquals(3, d.get(0).line);
    }

    @Test
    public void xmlCheckDoesNotResolveExternalEntities() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>";
        List<Diagnostic> d = SyntaxChecker.check(FileLanguage.XML, evil);
        // Either rejected outright or parsed without touching the file; it must never throw or hang.
        assertTrue(d.size() <= 1);
    }

    @Test
    public void jsonCheckReportsLineAndColumn() {
        assertTrue(SyntaxChecker.check(FileLanguage.JSON, "{\"a\": [1, 2, {\"b\": null}]}").isEmpty());
        List<Diagnostic> d = SyntaxChecker.check(FileLanguage.JSON, "{\n  \"a\": 1,\n  \"b\" 2\n}");
        assertEquals(1, d.size());
        assertEquals(3, d.get(0).line);
        for (String bad : new String[]{"{", "[1,]", "{\"a\":}", "{'a':1}", "01", "[1 2]", "\"abc", "tru", "{\"a\":1}x"}) {
            assertFalse("should be invalid: " + bad, SyntaxChecker.check(FileLanguage.JSON, bad).isEmpty());
        }
    }

    @Test
    public void bracketCheckIgnoresStringsAndComments() {
        assertTrue(SyntaxChecker.check(FileLanguage.JAVA, "class A { void f() { String s = \"}\"; /* ( */ } }").isEmpty());
        List<Diagnostic> d = SyntaxChecker.check(FileLanguage.JAVA, "class A {\n void f() {\n }\n");
        assertEquals(1, d.size());
        assertEquals(1, d.get(0).line);
        assertTrue(d.get(0).message.contains("Unclosed '{'"));
        d = SyntaxChecker.check(FileLanguage.JAVA, "void f() { ) }");
        assertFalse(d.isEmpty());
        d = SyntaxChecker.check(FileLanguage.JAVA, "int x = (1 + 2];");
        assertFalse(d.isEmpty());
    }

    @Test
    public void unterminatedStringIsReported() {
        List<Diagnostic> d = SyntaxChecker.check(FileLanguage.JAVA, "class A {\n String s = \"oops;\n}");
        boolean found = false;
        for (Diagnostic x : d) {
            if (x.message.contains("Unterminated string")) {
                found = true;
                assertEquals(2, x.line);
            }
        }
        assertTrue(found);
        assertTrue(SyntaxChecker.check(FileLanguage.JAVA, "String s = \"a\\\"b\";").isEmpty());
        assertTrue(SyntaxChecker.check(FileLanguage.JAVA, "char c = '\\'';").isEmpty());
    }

    // ---- search / replace

    @Test
    public void findAllWithOptions() {
        String text = "Foo foo FOO food";
        assertEquals(4, TextSearch.findAll(text, "foo", new TextSearch.Options(false, false, false), 100).size());
        assertEquals("foo and the foo inside food", 2, TextSearch.findAll(text, "foo", new TextSearch.Options(true, false, false), 100).size());
        assertEquals(3, TextSearch.findAll(text, "foo", new TextSearch.Options(false, true, false), 100).size());
        assertEquals(4, TextSearch.findAll(text, "fo+", new TextSearch.Options(false, false, true), 100).size());
        assertEquals(0, TextSearch.findAll(text, "", new TextSearch.Options(), 100).size());
        assertEquals(2, TextSearch.findAll(text, "foo", new TextSearch.Options(false, false, false), 2).size());
    }

    @Test
    public void literalSearchTreatsRegexCharactersAsText() {
        assertEquals(1, TextSearch.findAll("a.b a+b", "a.b", new TextSearch.Options(true, false, false), 10).size());
        assertEquals(1, TextSearch.findAll("a(b", "a(b", new TextSearch.Options(), 10).size());
    }

    @Test(expected = PatternSyntaxException.class)
    public void badRegexThrows() {
        TextSearch.findAll("x", "(", new TextSearch.Options(false, false, true), 10);
    }

    @Test
    public void emptyRegexMatchesDoNotLoop() {
        assertEquals(0, TextSearch.findAll("abc", "x*", new TextSearch.Options(false, false, true), 100).size());
    }

    @Test
    public void replaceAllLiteralAndRegexGroups() {
        assertEquals("b.c b.c", TextSearch.replaceAll("a.c a.c", "a.c", "b.c", new TextSearch.Options(true, false, false)));
        assertEquals("$1 costs", TextSearch.replaceAll("x costs", "x", "$1", new TextSearch.Options(true, false, false)));
        assertEquals("2-1", TextSearch.replaceAll("1-2", "(\\d)-(\\d)", "$2-$1", new TextSearch.Options(true, false, true)));
    }

    @Test
    public void nextAndPreviousWrapAround() {
        List<TextSearch.Match> m = TextSearch.findAll("a b a b a", "a", new TextSearch.Options(), 10);
        assertEquals(0, TextSearch.nextIndex(m, 0));
        assertEquals(1, TextSearch.nextIndex(m, 1));
        assertEquals(0, TextSearch.nextIndex(m, 9));
        assertEquals(2, TextSearch.previousIndex(m, 9));
        assertEquals(2, TextSearch.previousIndex(m, 0));
        assertEquals(-1, TextSearch.nextIndex(TextSearch.findAll("x", "y", new TextSearch.Options(), 10), 0));
    }

    @Test
    public void searchProjectFindsLinesAndSkipsBuildAndBinary() throws Exception {
        java.io.File root = tmp.newFolder("p");
        FileManager fm = new FileManager(root);
        fm.createDirectory("", "src");
        fm.createDirectory("", "build");
        fm.createFile("src", "A.java");
        fm.writeText("src/A.java", "class A {\n  int needle = 1;\n}\n", false);
        fm.createFile("build", "B.java");
        fm.writeText("build/B.java", "needle", false);
        com.zishu.ide.core.util.IoUtil.writeAtomic(new java.io.File(root, "bin.dat"), new byte[]{'n', 'e', 'e', 'd', 'l', 'e', 0});
        List<TextSearch.FileMatch> r = TextSearch.searchProject(fm, "needle", new TextSearch.Options(), 50);
        assertEquals(1, r.size());
        assertEquals("src/A.java", r.get(0).path);
        assertEquals(2, r.get(0).line);
        assertTrue(r.get(0).lineText.contains("needle"));
    }

    // ---- editing helpers

    @Test
    public void autoIndentKeepsAndIncreasesIndent() {
        CodeEditing.Enter e = CodeEditing.newlineWithIndent("    int x = 1;", 14, 4, false);
        assertEquals("\n    ", e.insert);
        e = CodeEditing.newlineWithIndent("  if (a) {", 10, 4, false);
        assertEquals("\n      ", e.insert);
        e = CodeEditing.newlineWithIndent("{}", 1, 4, false);
        assertEquals("\n    \n", e.insert);
        assertEquals(5, e.caretOffset);
        e = CodeEditing.newlineWithIndent("\tx {", 4, 4, true);
        assertEquals("\n\t\t", e.insert);
    }

    @Test
    public void indentAfterNewlineMatchesWhatTheTextWatcherSees() {
        // "    int x = 1;" + Enter at the end
        CodeEditing.Enter e = CodeEditing.indentAfterNewline("    int x = 1;\n", 14, 4, false);
        assertEquals("    ", e.insert);
        assertEquals(4, e.caretOffset);
        // opening brace deepens
        e = CodeEditing.indentAfterNewline("  if (a) {\n", 10, 4, false);
        assertEquals("      ", e.insert);
        // Enter between {} splits onto three lines with the caret on the middle one
        e = CodeEditing.indentAfterNewline("{\n}", 1, 4, false);
        assertEquals("    \n", e.insert);
        assertEquals(4, e.caretOffset);
        // tabs
        e = CodeEditing.indentAfterNewline("\tx {\n", 4, 4, true);
        assertEquals("\t\t", e.insert);
        // first line, nothing to copy
        e = CodeEditing.indentAfterNewline("\n", 0, 4, false);
        assertEquals("", e.insert);
        // trailing spaces before Enter after a brace still count as opening
        e = CodeEditing.indentAfterNewline("a {  \n", 5, 2, false);
        assertEquals("  ", e.insert);
    }

    @Test
    public void bracketMatching() {
        String s = "f(a[1], {b})";
        assertEquals(11, CodeEditing.matchingBracket(s, 1, null));
        assertEquals(1, CodeEditing.matchingBracket(s, 12, null));
        assertEquals(5, CodeEditing.matchingBracket(s, 3, null));
        assertEquals(-1, CodeEditing.matchingBracket("(((", 0, null));
        assertEquals(-1, CodeEditing.matchingBracket("abc", 1, null));
        String j = "x = \"(\"; (y)";
        boolean[] skip = CodeEditing.skipMask(FileLanguage.JAVA, j);
        assertEquals(11, CodeEditing.matchingBracket(j, 10, skip));
    }

    @Test
    public void lineNavigation() {
        String t = "a\nbb\nccc";
        assertEquals(0, CodeEditing.offsetOfLine(t, 1));
        assertEquals(2, CodeEditing.offsetOfLine(t, 2));
        assertEquals(5, CodeEditing.offsetOfLine(t, 3));
        assertEquals(t.length(), CodeEditing.offsetOfLine(t, 99));
        assertEquals(3, CodeEditing.lineCount(t));
        assertEquals(2, CodeEditing.lineOfOffset(t, 3));
        assertEquals(1, CodeEditing.lineCount(""));
    }

    // ---- undo / redo

    @Test
    public void undoRedoRestoreText() {
        UndoStack u = new UndoStack();
        String text = "";
        text = apply(u, text, 0, "", "h", 0);
        text = apply(u, text, 1, "", "i", 10);
        assertEquals("hi", text);
        UndoStack.Result r = u.undo("hi");
        assertEquals("typing is merged into one undo step", "", r.text);
        assertFalse(u.canUndo());
        r = u.redo(r.text);
        assertEquals("hi", r.text);
        assertEquals(2, r.caret);
    }

    private static String apply(UndoStack u, String text, int pos, String removed, String inserted, long now) {
        String out = text.substring(0, pos) + inserted + text.substring(pos + removed.length());
        u.record(pos, removed, inserted, now);
        return out;
    }

    @Test
    public void newEditClearsRedoAndSavePointTracksState() {
        UndoStack u = new UndoStack();
        String t = "abc";
        t = apply(u, t, 3, "", "d", 0);
        u.markSaved();
        assertTrue(u.isAtSavePoint());
        t = apply(u, t, 4, "", "\n", 5000);
        assertFalse(u.isAtSavePoint());
        t = u.undo(t).text;
        assertTrue("undoing back to the saved state is clean again", u.isAtSavePoint());
        assertEquals("abcd", t);
        t = apply(u, t, 0, "a", "X", 9000);
        assertFalse(u.canRedo());
        assertFalse(u.isAtSavePoint());
    }

    @Test
    public void replacementEditsUndoExactly() {
        UndoStack u = new UndoStack();
        String t = "hello world";
        t = apply(u, t, 6, "world", "there", 0);
        assertEquals("hello there", t);
        assertEquals("hello world", u.undo(t).text);
    }

    @Test
    public void backspacesAreMerged() {
        UndoStack u = new UndoStack();
        String t = "abcd";
        t = apply(u, t, 3, "d", "", 0);
        t = apply(u, t, 2, "c", "", 100);
        assertEquals("ab", t);
        assertEquals("abcd", u.undo(t).text);
        assertFalse(u.canUndo());
    }

    @Test
    public void bigRangeEditsAreBounded() {
        UndoStack u = new UndoStack();
        String big = new String(new char[600000]).replace('\0', 'x');
        for (int i = 0; i < 20; i++) {
            u.record(0, big, "", i * 10000L);
        }
        int steps = 0;
        String t = "";
        UndoStack.Result r;
        while ((r = u.undo(t.isEmpty() ? big : t)) != null && steps < 100) {
            t = r.text;
            steps++;
        }
        assertTrue("memory bound must discard old history", steps < 20);
    }
}
