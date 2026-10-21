package javax.lang.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Android has no javax.lang.model. The Eclipse Java compiler (ECJ) probes this class at start-up to find out
 * which Java release it runs on. This minimal stand-in reports Java 8, which is what the on-device build targets.
 * It exists only so ECJ can load; it is not a general implementation of the JDK class.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6, RELEASE_7, RELEASE_8;

    private static final Set<String> KEYWORDS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "abstract", "continue", "for", "new", "switch", "assert", "default", "if", "package", "synchronized",
            "boolean", "do", "goto", "private", "this", "break", "double", "implements", "protected", "throw",
            "byte", "else", "import", "public", "throws", "case", "enum", "instanceof", "return", "transient",
            "catch", "extends", "int", "short", "try", "char", "final", "interface", "static", "void",
            "class", "finally", "long", "strictfp", "volatile", "const", "float", "native", "super", "while",
            "true", "false", "null")));

    public static SourceVersion latest() {
        return RELEASE_8;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_8;
    }

    public static boolean isIdentifier(CharSequence name) {
        String id = name.toString();
        if (id.length() == 0 || !Character.isJavaIdentifierStart(id.codePointAt(0))) {
            return false;
        }
        for (int i = Character.charCount(id.codePointAt(0)); i < id.length(); ) {
            int cp = id.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        return KEYWORDS.contains(s.toString());
    }

    public static boolean isName(CharSequence name) {
        String[] parts = name.toString().split("\\.", -1);
        for (String p : parts) {
            if (!isIdentifier(p) || isKeyword(p)) {
                return false;
            }
        }
        return true;
    }
}
