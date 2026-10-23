package com.zishu.ide.core.ondevice;

/** Java language levels for the on-device compiler (ECJ): 5 to 21 are accepted, the bundled ECJ decides what it can really compile. */
public final class JavaLevels {
    public static final int DEFAULT = 8;
    public static final int MIN = 5;
    public static final int MAX = 21;

    private JavaLevels() {
    }

    /** The value for ECJ's -source / -target: 1.5 .. 1.8, then 9, 10, 11 ... */
    public static String option(int level) {
        if (level < MIN || level > MAX) {
            throw new IllegalArgumentException("Unsupported Java level " + level);
        }
        return level <= 8 ? "1." + level : String.valueOf(level);
    }

    /** What to tell the user when ECJ rejects the level. */
    public static String unsupportedHint(int level) {
        return "The Java compiler bundled in this IDE does not accept Java " + level + ". Lower sourceCompatibility in "
                + "build.gradle (for example to 11 or 8), or use the cloud build.";
    }

    public static boolean looksLikeLevelRejection(String compilerOutput) {
        String o = compilerOutput == null ? "" : compilerOutput.toLowerCase(java.util.Locale.US);
        return o.contains("invalid source release") || o.contains("invalid target release")
                || o.contains("unsupported source") || o.contains("unsupported target")
                || o.contains("unknown option") && o.contains("source");
    }
}
