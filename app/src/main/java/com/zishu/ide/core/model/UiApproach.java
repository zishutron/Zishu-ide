package com.zishu.ide.core.model;

public enum UiApproach {
    XML_LAYOUT("XML layout"),
    PROGRAMMATIC("Programmatic (code only)");

    public final String label;

    UiApproach(String label) {
        this.label = label;
    }

    public static UiApproach fromName(String name, UiApproach fallback) {
        if (name != null) {
            for (UiApproach u : values()) {
                if (u.name().equals(name)) {
                    return u;
                }
            }
        }
        return fallback;
    }
}
