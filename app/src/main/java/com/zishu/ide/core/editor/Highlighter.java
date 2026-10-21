package com.zishu.ide.core.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical syntax highlighting. This is a tokenizer, not a parser: it colours keywords, strings,
 * comments and so on from the text alone and knows nothing about types or symbols.
 *
 * Regex based languages are compiled into one alternation per language where every rule is a
 * numbered group (no named groups, for portability to older Android regex engines). XML and HTML
 * use a small hand written scanner because attribute values and text need tag context.
 */
public final class Highlighter {

    public static final class Token {
        public final int start;
        public final int end;
        public final TokenType type;

        public Token(int start, int end, TokenType type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }

    private static final class RuleSet {
        final Pattern pattern;
        final TokenType[] types;

        RuleSet(Pattern pattern, TokenType[] types) {
            this.pattern = pattern;
            this.types = types;
        }
    }

    private static final Map<FileLanguage, RuleSet> CACHE = new EnumMap<FileLanguage, RuleSet>(FileLanguage.class);

    private static final String C_COMMENT = "/\\*[\\s\\S]*?(?:\\*/|$)";
    private static final String LINE_COMMENT = "//[^\\n]*";
    private static final String DQ_STRING = "\"(?:\\\\.|[^\"\\\\\\n])*\"?";
    private static final String SQ_STRING = "'(?:\\\\.|[^'\\\\\\n])*'?";
    private static final String NUMBER = "\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?)[lLfFdDuU]?\\b";
    private static final String TYPE_NAME = "\\b[A-Z][A-Za-z0-9_]*\\b";

    private static final String JAVA_KEYWORDS = words("abstract assert boolean break byte case catch char class const "
            + "continue default do double else enum extends final finally float for goto if implements import "
            + "instanceof int interface long native new package private protected public return short static "
            + "strictfp super switch synchronized this throw throws transient try void volatile while true false "
            + "null var record sealed permits yield");
    private static final String KOTLIN_KEYWORDS = words("as break class continue do else false for fun if in interface "
            + "is null object package return super this throw true try typealias val var when while by catch "
            + "constructor finally get import init set where abstract annotation companion const crossinline data "
            + "enum external final infix inline inner internal lateinit noinline open operator out override private "
            + "protected public reified sealed suspend tailrec vararg lazy");
    private static final String JS_KEYWORDS = words("break case catch class const continue debugger default delete do "
            + "else export extends finally for function if import in instanceof let new return super switch this "
            + "throw try typeof var void while with yield async await of static true false null undefined");
    private static final String GRADLE_KEYWORDS = words("def if else for while return new true false null import package "
            + "class extends implements in as plugins android dependencies repositories buildscript allprojects "
            + "subprojects apply plugin id version task ext implementation api compileOnly runtimeOnly "
            + "testImplementation androidTestImplementation namespace compileSdk minSdk targetSdk applicationId "
            + "versionCode versionName buildTypes release debug defaultConfig compileOptions sourceCompatibility "
            + "targetCompatibility kotlinOptions jvmTarget include rootProject pluginManagement "
            + "dependencyResolutionManagement google mavenCentral gradlePluginPortal minifyEnabled");

    private Highlighter() {
    }

    private static String words(String list) {
        StringBuilder sb = new StringBuilder("\\b(?:");
        String[] parts = list.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(parts[i]);
        }
        return sb.append(")\\b").toString();
    }

    public static List<Token> highlight(FileLanguage lang, CharSequence text) {
        if (lang == FileLanguage.PLAIN || text.length() == 0) {
            return Collections.emptyList();
        }
        if (lang == FileLanguage.XML || lang == FileLanguage.HTML) {
            List<Token> out = new ArrayList<Token>();
            scanMarkup(text.toString(), lang == FileLanguage.HTML, out);
            return out;
        }
        RuleSet rs = ruleSet(lang);
        List<Token> out = new ArrayList<Token>();
        Matcher m = rs.pattern.matcher(text);
        while (m.find()) {
            if (m.end() == m.start()) {
                continue;
            }
            for (int i = 0; i < rs.types.length; i++) {
                if (m.start(i + 1) >= 0) {
                    out.add(new Token(m.start(), m.end(), rs.types[i]));
                    break;
                }
            }
        }
        return out;
    }

    private static synchronized RuleSet ruleSet(FileLanguage lang) {
        RuleSet rs = CACHE.get(lang);
        if (rs == null) {
            rs = build(lang);
            CACHE.put(lang, rs);
        }
        return rs;
    }

    private static RuleSet build(FileLanguage lang) {
        List<String> regex = new ArrayList<String>();
        List<TokenType> types = new ArrayList<TokenType>();
        switch (lang) {
            case JAVA:
                add(regex, types, TokenType.COMMENT, C_COMMENT);
                add(regex, types, TokenType.COMMENT, LINE_COMMENT);
                add(regex, types, TokenType.STRING, "\"\"\"[\\s\\S]*?(?:\"\"\"|$)");
                add(regex, types, TokenType.STRING, DQ_STRING);
                add(regex, types, TokenType.STRING, SQ_STRING);
                add(regex, types, TokenType.ANNOTATION, "@[A-Za-z_][A-Za-z0-9_.]*");
                add(regex, types, TokenType.NUMBER, NUMBER);
                add(regex, types, TokenType.KEYWORD, JAVA_KEYWORDS);
                add(regex, types, TokenType.TYPE, TYPE_NAME);
                break;
            case KOTLIN:
                add(regex, types, TokenType.COMMENT, C_COMMENT);
                add(regex, types, TokenType.COMMENT, LINE_COMMENT);
                add(regex, types, TokenType.STRING, "\"\"\"[\\s\\S]*?(?:\"\"\"|$)");
                add(regex, types, TokenType.STRING, DQ_STRING);
                add(regex, types, TokenType.STRING, SQ_STRING);
                add(regex, types, TokenType.ANNOTATION, "@[A-Za-z_][A-Za-z0-9_.]*");
                add(regex, types, TokenType.NUMBER, NUMBER);
                add(regex, types, TokenType.KEYWORD, KOTLIN_KEYWORDS);
                add(regex, types, TokenType.TYPE, TYPE_NAME);
                break;
            case GRADLE:
                add(regex, types, TokenType.COMMENT, C_COMMENT);
                add(regex, types, TokenType.COMMENT, LINE_COMMENT);
                add(regex, types, TokenType.STRING, DQ_STRING);
                add(regex, types, TokenType.STRING, SQ_STRING);
                add(regex, types, TokenType.NUMBER, NUMBER);
                add(regex, types, TokenType.KEYWORD, GRADLE_KEYWORDS);
                add(regex, types, TokenType.TYPE, TYPE_NAME);
                break;
            case JAVASCRIPT:
                add(regex, types, TokenType.COMMENT, C_COMMENT);
                add(regex, types, TokenType.COMMENT, LINE_COMMENT);
                add(regex, types, TokenType.STRING, "`(?:\\\\[\\s\\S]|[^`\\\\])*`?");
                add(regex, types, TokenType.STRING, DQ_STRING);
                add(regex, types, TokenType.STRING, SQ_STRING);
                add(regex, types, TokenType.NUMBER, NUMBER);
                add(regex, types, TokenType.KEYWORD, JS_KEYWORDS);
                add(regex, types, TokenType.TYPE, TYPE_NAME);
                break;
            case CSS:
                add(regex, types, TokenType.COMMENT, C_COMMENT);
                add(regex, types, TokenType.STRING, DQ_STRING);
                add(regex, types, TokenType.STRING, SQ_STRING);
                add(regex, types, TokenType.KEYWORD, "@[A-Za-z-]+");
                add(regex, types, TokenType.NUMBER, "#[0-9A-Fa-f]{3,8}\\b");
                add(regex, types, TokenType.NUMBER, "-?\\b\\d+(?:\\.\\d+)?(?:px|em|rem|%|vh|vw|vmin|vmax|pt|cm|mm|in|ms|s|deg|fr)?");
                add(regex, types, TokenType.TAG, "[.#][A-Za-z_][\\w-]*");
                add(regex, types, TokenType.PROPERTY, "[A-Za-z-]+(?=\\s*:)");
                break;
            case JSON:
                add(regex, types, TokenType.PROPERTY, "\"(?:\\\\.|[^\"\\\\\\n])*\"(?=\\s*:)");
                add(regex, types, TokenType.STRING, DQ_STRING);
                add(regex, types, TokenType.NUMBER, "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b");
                add(regex, types, TokenType.KEYWORD, "\\b(?:true|false|null)\\b");
                break;
            case MARKDOWN:
                add(regex, types, TokenType.STRING, "```[\\s\\S]*?(?:```|$)");
                add(regex, types, TokenType.HEADING, "(?m:^#{1,6}[^\\n]*)");
                add(regex, types, TokenType.STRING, "`[^`\\n]+`");
                add(regex, types, TokenType.KEYWORD, "\\*\\*[^*\\n]+\\*\\*");
                add(regex, types, TokenType.TAG, "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)");
                add(regex, types, TokenType.ANNOTATION, "(?m:^[ \\t]*(?:[-*+]|\\d+\\.)[ \\t])");
                break;
            case PROPERTIES:
                add(regex, types, TokenType.COMMENT, "(?m:^[ \\t]*[#!][^\\n]*)");
                add(regex, types, TokenType.PROPERTY, "(?m:^[ \\t]*[^\\s#!=:][^=:\\n]*(?=[=:]))");
                break;
            default:
                throw new IllegalArgumentException("No rules for " + lang);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < regex.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append('(').append(regex.get(i)).append(')');
        }
        return new RuleSet(Pattern.compile(sb.toString()), types.toArray(new TokenType[0]));
    }

    private static void add(List<String> regex, List<TokenType> types, TokenType t, String r) {
        regex.add(r);
        types.add(t);
    }

    // ------------------------------------------------------------ markup scanner

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '.' || c == '-';
    }

    private static void scanMarkup(String s, boolean html, List<Token> out) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0) {
                return;
            }
            i = lt;
            if (s.startsWith("<!--", i)) {
                int end = s.indexOf("-->", i + 4);
                int stop = end < 0 ? n : end + 3;
                out.add(new Token(i, stop, TokenType.COMMENT));
                i = stop;
            } else if (s.startsWith("<![CDATA[", i)) {
                int end = s.indexOf("]]>", i + 9);
                int stop = end < 0 ? n : end + 3;
                out.add(new Token(i, stop, TokenType.STRING));
                i = stop;
            } else if (s.startsWith("<?", i)) {
                int end = s.indexOf("?>", i + 2);
                int stop = end < 0 ? n : end + 2;
                out.add(new Token(i, stop, TokenType.ANNOTATION));
                i = stop;
            } else if (s.startsWith("<!", i)) {
                int end = s.indexOf('>', i + 2);
                int stop = end < 0 ? n : end + 1;
                out.add(new Token(i, stop, TokenType.ANNOTATION));
                i = stop;
            } else if (i + 1 < n && (Character.isLetter(s.charAt(i + 1)) || s.charAt(i + 1) == '/' || s.charAt(i + 1) == '_')) {
                i = scanTag(s, i, html, out);
            } else {
                i++;
            }
        }
    }

    /** Scans one start or end tag beginning at '<' and returns the index after it. */
    private static int scanTag(String s, int start, boolean html, List<Token> out) {
        int n = s.length();
        int i = start + 1;
        boolean closing = false;
        if (s.charAt(i) == '/') {
            closing = true;
            i++;
        }
        int nameStart = i;
        while (i < n && isNameChar(s.charAt(i))) {
            i++;
        }
        String name = s.substring(nameStart, i).toLowerCase(java.util.Locale.US);
        out.add(new Token(start, i, TokenType.TAG));
        while (i < n) {
            char c = s.charAt(i);
            if (c == '>') {
                out.add(new Token(i, i + 1, TokenType.TAG));
                i++;
                break;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '>') {
                out.add(new Token(i, i + 2, TokenType.TAG));
                i += 2;
                break;
            }
            if (c == '"' || c == '\'') {
                int end = s.indexOf(c, i + 1);
                int stop = end < 0 ? n : end + 1;
                out.add(new Token(i, stop, TokenType.STRING));
                i = stop;
            } else if (isNameChar(c)) {
                int as = i;
                while (i < n && isNameChar(s.charAt(i))) {
                    i++;
                }
                out.add(new Token(as, i, TokenType.ATTRIBUTE));
            } else if (c == '<') {
                return i; // unterminated tag: let the caller resume at the next tag
            } else {
                i++;
            }
        }
        if (html && !closing && (name.equals("script") || name.equals("style"))) {
            String close = "</" + name;
            int end = indexOfIgnoreCase(s, close, i);
            int stop = end < 0 ? n : end;
            if (stop > i) {
                FileLanguage inner = name.equals("script") ? FileLanguage.JAVASCRIPT : FileLanguage.CSS;
                for (Token t : highlight(inner, s.substring(i, stop))) {
                    out.add(new Token(t.start + i, t.end + i, t.type));
                }
            }
            return stop;
        }
        return i;
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int max = s.length() - needle.length();
        for (int i = from; i <= max; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
