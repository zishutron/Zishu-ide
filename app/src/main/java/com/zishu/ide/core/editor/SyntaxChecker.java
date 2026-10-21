package com.zishu.ide.core.editor;

import com.zishu.ide.core.util.MiniJson;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Real but shallow syntax checks: XML well-formedness, JSON validity and bracket/string balance
 * for brace languages. These are NOT compiler diagnostics: a file that passes can still fail to
 * compile, and the editor labels them as "syntax checks".
 */
public final class SyntaxChecker {
    private static final int MAX_DIAGNOSTICS = 50;

    private SyntaxChecker() {
    }

    public static List<Diagnostic> check(FileLanguage lang, String text) {
        List<Diagnostic> out = new ArrayList<Diagnostic>();
        if (text.length() == 0) {
            return out;
        }
        switch (lang) {
            case XML:
                checkXml(text, out);
                break;
            case JSON:
                checkJson(text, out);
                break;
            default:
                if (lang.usesBrackets()) {
                    checkBrackets(lang, text, out);
                }
        }
        return out;
    }

    /** Returns the first XML error as a diagnostic, or null if the document is well-formed. */
    public static Diagnostic firstXmlError(String text) {
        List<Diagnostic> out = new ArrayList<Diagnostic>();
        checkXml(text, out);
        return out.isEmpty() ? null : out.get(0);
    }

    private static void checkXml(String text, List<Diagnostic> out) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            trySet(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver(new EntityResolver() {
                @Override
                public InputSource resolveEntity(String publicId, String systemId) {
                    return new InputSource(new StringReader("")); // never fetch external entities
                }
            });
            b.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    // ignored
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            b.parse(new InputSource(new StringReader(text)));
        } catch (SAXParseException e) {
            out.add(new Diagnostic(Diagnostic.Severity.ERROR, Math.max(1, e.getLineNumber()),
                    Math.max(1, e.getColumnNumber()), cleanXmlMessage(e.getMessage())));
        } catch (SAXException e) {
            out.add(new Diagnostic(Diagnostic.Severity.ERROR, 1, 1, cleanXmlMessage(e.getMessage())));
        } catch (ParserConfigurationException e) {
            // The platform has no XML parser: report nothing rather than something false.
        } catch (IOException e) {
            // cannot happen for a StringReader
        }
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (ParserConfigurationException ignored) {
            // feature unsupported by this parser implementation
        }
    }

    private static String cleanXmlMessage(String m) {
        if (m == null) {
            return "Invalid XML";
        }
        return m.replace('\n', ' ').trim();
    }

    private static void checkJson(String text, List<Diagnostic> out) {
        try {
            MiniJson.parse(text);
        } catch (MiniJson.JsonException e) {
            out.add(new Diagnostic(Diagnostic.Severity.ERROR, e.line, e.column, e.getMessage()));
        }
    }

    private static void checkBrackets(FileLanguage lang, String text, List<Diagnostic> out) {
        List<Highlighter.Token> tokens = Highlighter.highlight(lang, text);
        int n = text.length();
        boolean[] skip = new boolean[n];
        for (Highlighter.Token t : tokens) {
            if (t.type == TokenType.STRING || t.type == TokenType.COMMENT) {
                for (int i = t.start; i < t.end && i < n; i++) {
                    skip[i] = true;
                }
                if (t.type == TokenType.STRING) {
                    unterminatedString(text, t, out);
                }
            }
        }
        char[] stack = new char[n + 1];
        int[] stackPos = new int[n + 1];
        int sp = 0;
        int line = 1;
        int col = 1;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (!skip[i]) {
                if (c == '(' || c == '[' || c == '{') {
                    stack[sp] = c;
                    stackPos[sp] = i;
                    sp++;
                } else if (c == ')' || c == ']' || c == '}') {
                    if (sp == 0) {
                        add(out, line, col, "Unexpected '" + c + "' with no matching opening bracket");
                    } else if (!matches(stack[sp - 1], c)) {
                        add(out, line, col, "Unexpected '" + c + "': the open '" + stack[sp - 1] + "' expects '"
                                + closer(stack[sp - 1]) + "'");
                        sp--;
                    } else {
                        sp--;
                    }
                }
            }
            if (c == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        while (sp > 0 && out.size() < MAX_DIAGNOSTICS) {
            sp--;
            int pos = stackPos[sp];
            int[] lc = lineCol(text, pos);
            add(out, lc[0], lc[1], "Unclosed '" + stack[sp] + "'");
        }
    }

    private static void unterminatedString(String text, Highlighter.Token t, List<Diagnostic> out) {
        int len = t.end - t.start;
        char q = text.charAt(t.start);
        if (q == '`') {
            if (len < 2 || text.charAt(t.end - 1) != '`') {
                int[] lc = lineCol(text, t.start);
                add(out, lc[0], lc[1], "Unterminated template string");
            }
            return;
        }
        if (text.startsWith("\"\"\"", t.start)) {
            if (len < 6 || !text.startsWith("\"\"\"", t.end - 3)) {
                int[] lc = lineCol(text, t.start);
                add(out, lc[0], lc[1], "Unterminated text block");
            }
            return;
        }
        boolean closed = len >= 2 && text.charAt(t.end - 1) == q && !escapedAt(text, t.end - 1, t.start);
        if (!closed) {
            int[] lc = lineCol(text, t.start);
            add(out, lc[0], lc[1], "Unterminated string literal");
        }
    }

    /** True if the character at pos is preceded by an odd number of backslashes (within the token). */
    private static boolean escapedAt(String text, int pos, int tokenStart) {
        int count = 0;
        int i = pos - 1;
        while (i > tokenStart && text.charAt(i) == '\\') {
            count++;
            i--;
        }
        return count % 2 == 1;
    }

    private static void add(List<Diagnostic> out, int line, int col, String msg) {
        if (out.size() < MAX_DIAGNOSTICS) {
            out.add(new Diagnostic(Diagnostic.Severity.ERROR, line, col, msg));
        }
    }

    private static boolean matches(char open, char close) {
        return (open == '(' && close == ')') || (open == '[' && close == ']') || (open == '{' && close == '}');
    }

    private static char closer(char open) {
        return open == '(' ? ')' : (open == '[' ? ']' : '}');
    }

    /** 1-based {line, column} of an offset. */
    public static int[] lineCol(String text, int pos) {
        int line = 1;
        int col = 1;
        int end = Math.min(pos, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }
}
