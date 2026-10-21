package com.zishu.ide.core.model;

public enum Language {
    JAVA("Java", "java"),
    KOTLIN("Kotlin", "kt");

    public final String label;
    public final String extension;

    Language(String label, String extension) {
        this.label = label;
        this.extension = extension;
    }

    public static Language fromName(String name, Language fallback) {
        if (name != null) {
            for (Language l : values()) {
                if (l.name().equals(name)) {
                    return l;
                }
            }
        }
        return fallback;
    }
}
