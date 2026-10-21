package com.zishu.ide.core.validation;

import com.zishu.ide.core.model.Defaults;
import com.zishu.ide.core.model.Language;
import com.zishu.ide.core.model.ProjectConfig;
import com.zishu.ide.core.model.ProjectType;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates a {@link ProjectConfig} before any file is generated. */
public final class ConfigValidator {
    private static final Pattern PACKAGE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");
    private static final Pattern PROJECT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 _.-]*$");
    private static final Pattern VERSION_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]*$");
    private static final Pattern JAVA_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    public static final int MAX_ICON_BYTES = 1024 * 1024;

    private static final Set<String> JAVA_KEYWORDS = new HashSet<String>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_"));

    private ConfigValidator() {
    }

    /** Returns the problem with an application ID, or null when it is acceptable. */
    public static String checkApplicationId(String id) {
        if (id == null || id.length() == 0) {
            return "The application ID is empty.";
        }
        if (id.length() > 150) {
            return "The application ID is longer than 150 characters.";
        }
        if (!PACKAGE.matcher(id).matches()) {
            return "The application ID must have at least two segments separated by dots, each starting "
                    + "with a letter and containing only letters, digits and underscores (for example com.example.app).";
        }
        for (String seg : id.split("\\.")) {
            if (JAVA_KEYWORDS.contains(seg)) {
                return "'" + seg + "' is a reserved Java word and cannot be used as a package segment.";
            }
        }
        return null;
    }

    public static boolean isJavaIdentifier(String s) {
        return s != null && JAVA_IDENT.matcher(s).matches() && !JAVA_KEYWORDS.contains(s);
    }

    public static boolean isColor(String s) {
        return s != null && COLOR.matcher(s).matches();
    }

    public static boolean isPng(byte[] data) {
        if (data == null || data.length < 24) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    public static ValidationReport validate(ProjectConfig c) {
        ValidationReport r = new ValidationReport();

        if (c.projectName == null || c.projectName.trim().length() == 0) {
            r.add(Issue.error("project-name", "The project name is empty.", "Enter a project name."));
        } else if (c.projectName.length() > 64 || !PROJECT_NAME.matcher(c.projectName).matches()) {
            r.add(Issue.error("project-name",
                    "The project name may only contain letters, digits, spaces, '.', '_' and '-', must start "
                            + "with a letter or digit and must be at most 64 characters.",
                    "Rename the project, for example \"My App\"."));
        }

        if (c.appName == null || c.appName.trim().length() == 0) {
            r.add(Issue.error("app-name", "The app display name is empty.", "Enter the name shown under the launcher icon."));
        } else if (c.appName.length() > 60) {
            r.add(Issue.error("app-name", "The app display name is longer than 60 characters.", "Shorten the name."));
        }

        String idProblem = checkApplicationId(c.applicationId);
        if (idProblem != null) {
            r.add(Issue.error("application-id", idProblem, "Use a name like com.yourname.appname."));
        }

        if (c.type == ProjectType.IMPORTED) {
            r.add(Issue.error("type", "Imported projects are not created from the wizard.",
                    "Use Open Existing Project or Import Project ZIP."));
            return r;
        }

        if (c.type == ProjectType.NATIVE_KOTLIN && c.language != Language.KOTLIN) {
            r.add(Issue.error("language", "A Kotlin project must use the Kotlin language.", "Select Kotlin."));
        }
        if (c.type == ProjectType.NATIVE_JAVA && c.language != Language.JAVA) {
            r.add(Issue.error("language", "A Java project must use the Java language.", "Select Java."));
        }
        if (c.type.isWebView() && c.language != Language.JAVA) {
            r.add(Issue.error("language",
                    "WebView projects are generated in Java in this release.",
                    "Select Java, or create a Native Kotlin project."));
        }

        // SDK levels
        if (c.minSdk < Defaults.LOWEST_MIN_SDK || c.minSdk > Defaults.HIGHEST_COMPILE_SDK) {
            r.add(Issue.error("min-sdk", "minSdk must be between " + Defaults.LOWEST_MIN_SDK + " and "
                    + Defaults.HIGHEST_COMPILE_SDK + ".", "Choose a minSdk in that range."));
        }
        if (c.targetSdk < 21 || c.targetSdk > Defaults.HIGHEST_COMPILE_SDK) {
            r.add(Issue.error("target-sdk", "targetSdk must be between 21 and " + Defaults.HIGHEST_COMPILE_SDK
                    + " for the Android Gradle Plugin " + c.agpVersion + ".", "Choose a targetSdk in that range."));
        }
        if (c.compileSdk < 21 || c.compileSdk > Defaults.HIGHEST_COMPILE_SDK) {
            r.add(Issue.error("compile-sdk", "compileSdk must be between 21 and " + Defaults.HIGHEST_COMPILE_SDK
                    + " for the Android Gradle Plugin " + c.agpVersion + ".", "Choose a compileSdk in that range."));
        }
        if (c.minSdk > c.targetSdk) {
            r.add(Issue.error("sdk-order", "minSdk (" + c.minSdk + ") is higher than targetSdk (" + c.targetSdk + ").",
                    "Lower minSdk or raise targetSdk."));
        }
        if (c.targetSdk > c.compileSdk) {
            r.add(Issue.error("sdk-order", "targetSdk (" + c.targetSdk + ") is higher than compileSdk (" + c.compileSdk + ").",
                    "Raise compileSdk to at least the targetSdk, or lower targetSdk."));
        }

        if (c.versionCode < 1 || c.versionCode > 2100000000) {
            r.add(Issue.error("version-code", "versionCode must be between 1 and 2100000000.", "Enter a positive whole number."));
        }
        if (c.versionName == null || c.versionName.length() > 40 || !VERSION_NAME.matcher(c.versionName).matches()) {
            r.add(Issue.error("version-name",
                    "versionName may only contain letters, digits, '.', '_', '+' and '-' (max 40 characters).",
                    "Use a value like 1.0 or 2.3.1-beta."));
        }

        if (c.type.isNative()) {
            if (!isJavaIdentifier(c.initialActivity)) {
                r.add(Issue.error("activity-name", "The initial activity name is not a valid class name.",
                        "Use letters, digits and underscores, starting with a letter, for example MainActivity."));
            } else if (!Character.isUpperCase(c.initialActivity.charAt(0))) {
                r.add(Issue.warning("activity-name-style", "Class names conventionally start with an upper-case letter.",
                        "Consider renaming the activity to " + Character.toUpperCase(c.initialActivity.charAt(0))
                                + c.initialActivity.substring(1) + "."));
            }
        }

        if (c.javaLevel != 8 && c.javaLevel != 11 && c.javaLevel != 17) {
            r.add(Issue.error("java-level", "Java level " + c.javaLevel + " is not supported. Supported levels: 8, 11, 17.",
                    "Select Java 8, 11 or 17."));
        } else if (c.javaLevel > 8) {
            r.add(Issue.info("java-level-note",
                    "Java " + c.javaLevel + " language syntax is compiled and desugared by the Android Gradle Plugin. "
                            + "That does not make every JDK " + c.javaLevel + " library API available on devices: "
                            + "APIs missing from the device's Android version fail at runtime unless core library "
                            + "desugaring is configured."));
        }

        if (c.type == ProjectType.WEBVIEW_SITE) {
            validateUrl(c.webUrl, r);
        }

        if (!isColor(c.themeColor)) {
            r.add(Issue.error("theme-color", "The theme colour must look like #RRGGBB.", "Enter a colour such as #5B3FD9."));
        }
        if (!isColor(c.iconColor)) {
            r.add(Issue.error("icon-color", "The icon colour must look like #RRGGBB.", "Enter a colour such as #5B3FD9."));
        }
        if (c.iconPng != null) {
            if (!isPng(c.iconPng)) {
                r.add(Issue.error("icon-png", "The selected icon is not a valid PNG file.", "Pick a PNG image or clear the icon."));
            } else if (c.iconPng.length > MAX_ICON_BYTES) {
                r.add(Issue.error("icon-png", "The icon is larger than 1 MB.", "Pick a smaller PNG (512x512 is plenty)."));
            }
        }

        GradleCompat.check(c.agpVersion, c.gradleVersion, c.compileSdk, r);
        return r;
    }

    private static void validateUrl(String url, ValidationReport r) {
        if (url == null || url.trim().length() == 0) {
            r.add(Issue.error("web-url", "The website URL is empty.", "Enter an address such as https://example.com."));
            return;
        }
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                r.add(Issue.error("web-url", "The URL must start with https:// or http://.", "Enter a full web address."));
            } else if (u.getHost() == null || u.getHost().length() == 0) {
                r.add(Issue.error("web-url", "The URL has no host name.", "Enter a full web address."));
            } else if (u.getUserInfo() != null) {
                r.add(Issue.error("web-url", "URLs containing a user name or password are not allowed.",
                        "Remove the credentials from the URL."));
            } else if (scheme.equalsIgnoreCase("http")) {
                r.add(Issue.warning("web-url-cleartext",
                        "http:// traffic is not encrypted. The app will be allowed to use cleartext traffic.",
                        "Use https:// if the site supports it."));
            }
        } catch (URISyntaxException e) {
            r.add(Issue.error("web-url", "The URL is not valid: " + e.getReason() + ".", "Check the address for spaces or illegal characters."));
        }
    }
}
