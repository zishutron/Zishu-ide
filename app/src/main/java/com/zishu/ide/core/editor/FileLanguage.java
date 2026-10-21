package com.zishu.ide.core.editor;

import java.util.Locale;

/** Languages the editor knows how to highlight and check. */
public enum FileLanguage {
    JAVA("Java", 4),
    KOTLIN("Kotlin", 4),
    XML("XML", 4),
    GRADLE("Gradle", 4),
    HTML("HTML", 2),
    CSS("CSS", 2),
    JAVASCRIPT("JavaScript", 2),
    JSON("JSON", 2),
    MARKDOWN("Markdown", 2),
    PROPERTIES("Properties", 4),
    PLAIN("Plain text", 4);

    public final String label;
    public final int indentSize;

    FileLanguage(String label, int indentSize) {
        this.label = label;
        this.indentSize = indentSize;
    }

    public static FileLanguage fromFileName(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        if (ext.equals("java")) {
            return JAVA;
        }
        if (ext.equals("kt") || ext.equals("kts")) {
            return KOTLIN;
        }
        if (ext.equals("xml") || ext.equals("svg")) {
            return XML;
        }
        if (ext.equals("gradle")) {
            return GRADLE;
        }
        if (ext.equals("html") || ext.equals("htm")) {
            return HTML;
        }
        if (ext.equals("css")) {
            return CSS;
        }
        if (ext.equals("js") || ext.equals("mjs")) {
            return JAVASCRIPT;
        }
        if (ext.equals("json")) {
            return JSON;
        }
        if (ext.equals("md") || ext.equals("markdown")) {
            return MARKDOWN;
        }
        if (ext.equals("properties") || n.equals("gradle.properties")) {
            return PROPERTIES;
        }
        return PLAIN;
    }

    /** Languages whose syntax uses {} () [] and where bracket balance is a meaningful check. */
    public boolean usesBrackets() {
        return this == JAVA || this == KOTLIN || this == GRADLE || this == JAVASCRIPT || this == CSS;
    }
}
