package com.zishu.ide.core.ondevice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle projects keep the package out of AndroidManifest.xml (it lives in {@code namespace}), but aapt2
 * needs it. This adds {@code package="..."} to the root element when it is missing.
 */
public final class ManifestPatcher {
    private static final Pattern PACKAGE_ATTR = Pattern.compile("\\spackage\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private ManifestPatcher() {
    }

    public static boolean isValidPackage(String s) {
        return s != null && PACKAGE_NAME.matcher(s).matches();
    }

    /** Returns the package declared in the manifest, or null when there is none. */
    public static String declaredPackage(String xml) {
        int[] tag = rootTag(xml);
        if (tag == null) {
            return null;
        }
        Matcher m = PACKAGE_ATTR.matcher(xml.substring(tag[0], tag[1]));
        if (!m.find()) {
            return null;
        }
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    /** @throws IllegalArgumentException when the manifest has no root element or the package is invalid */
    public static String ensurePackage(String xml, String pkg) {
        if (!isValidPackage(pkg)) {
            throw new IllegalArgumentException("'" + pkg + "' is not a valid package name.");
        }
        int[] tag = rootTag(xml);
        if (tag == null) {
            throw new IllegalArgumentException("AndroidManifest.xml has no <manifest> element.");
        }
        String start = xml.substring(tag[0], tag[1]);
        if (PACKAGE_ATTR.matcher(start).find()) {
            return xml;
        }
        int insertAt = tag[0] + "<manifest".length();
        return xml.substring(0, insertAt) + " package=\"" + pkg + "\"" + xml.substring(insertAt);
    }

    /** Offsets {start of "<manifest", end of its start tag (exclusive)}, skipping comments and the XML prolog. */
    private static int[] rootTag(String xml) {
        int i = 0;
        int n = xml.length();
        while (i < n) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                return null;
            }
            if (xml.startsWith("<!--", lt)) {
                int end = xml.indexOf("-->", lt + 4);
                if (end < 0) {
                    return null;
                }
                i = end + 3;
                continue;
            }
            if (xml.startsWith("<?", lt)) {
                int end = xml.indexOf("?>", lt + 2);
                if (end < 0) {
                    return null;
                }
                i = end + 2;
                continue;
            }
            if (xml.startsWith("<manifest", lt) && lt + 9 < n
                    && (Character.isWhitespace(xml.charAt(lt + 9)) || xml.charAt(lt + 9) == '>' || xml.charAt(lt + 9) == '/')) {
                int end = tagEnd(xml, lt);
                return end < 0 ? null : new int[]{lt, end};
            }
            return null; // the first element is not <manifest>
        }
        return null;
    }

    private static int tagEnd(String xml, int from) {
        char quote = 0;
        for (int i = from; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i + 1;
            }
        }
        return -1;
    }
}
