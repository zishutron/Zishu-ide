package com.zishu.ide.core.project;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A GitHub repository reference parsed from what a user types after {@code zishu clone}. */
public final class GitHubRepo {
    public final String owner;
    public final String repo;
    /** Branch, tag or commit from a /tree/ URL or --branch; null means the default branch. */
    public final String ref;

    private static final String NAME = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})";
    private static final String REPO = "[A-Za-z0-9._-]{1,100}";
    private static final Pattern SHORT = Pattern.compile("^(" + NAME + ")/(" + REPO + ")$");
    private static final Pattern HTTPS = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/(" + NAME + ")/(" + REPO + ")(?:/(.*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSH = Pattern.compile("^git@github\\.com:(" + NAME + ")/(" + REPO + ")$", Pattern.CASE_INSENSITIVE);

    private GitHubRepo(String owner, String repo, String ref) {
        this.owner = owner;
        this.repo = repo;
        this.ref = ref;
    }

    public GitHubRepo withRef(String newRef) {
        return new GitHubRepo(owner, repo, newRef == null || newRef.length() == 0 ? ref : newRef);
    }

    /** @return the repository, or null when the text is not a GitHub repository reference */
    public static GitHubRepo parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        int cut = t.indexOf('?');
        if (cut >= 0) {
            t = t.substring(0, cut);
        }
        cut = t.indexOf('#');
        if (cut >= 0) {
            t = t.substring(0, cut);
        }
        Matcher m = SSH.matcher(t);
        if (m.matches()) {
            return make(m.group(1), m.group(2), null);
        }
        m = HTTPS.matcher(t);
        if (m.matches()) {
            String ref = null;
            String rest = m.group(3);
            if (rest != null && rest.startsWith("tree/") && rest.length() > 5) {
                ref = rest.substring(5); // branch names may contain slashes; folder paths below are not supported
            }
            return make(m.group(1), m.group(2), ref);
        }
        m = SHORT.matcher(t);
        if (m.matches()) {
            return make(m.group(1), m.group(2), null);
        }
        return null;
    }

    private static GitHubRepo make(String owner, String repo, String ref) {
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        if (repo.length() == 0 || repo.equals(".") || repo.equals("..")) {
            return null;
        }
        if (ref != null && (ref.contains("..") || ref.startsWith("-") || ref.indexOf(' ') >= 0)) {
            ref = null;
        }
        return new GitHubRepo(owner, repo, ref);
    }

    /** The API address that redirects to a ZIP of the repository. */
    public String zipballUrl() {
        return "https://api.github.com/repos/" + owner + "/" + repo + "/zipball" + (ref == null ? "" : "/" + ref);
    }

    public String displayName() {
        return owner + "/" + repo + (ref == null ? "" : "@" + ref);
    }

    /** True for hosts a redirect from GitHub may legitimately lead to. */
    public static boolean trustedRedirectHost(String host) {
        String h = host.toLowerCase(java.util.Locale.US);
        return h.equals("github.com") || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com");
    }
}
