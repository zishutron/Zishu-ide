package com.zishu.ide.core.build;

import com.zishu.ide.core.util.Base64Util;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.util.MiniJson;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The few GitHub REST calls the cloud build needs. The token is only ever placed in the
 * Authorization header of requests to api.github.com; it is never put in a URL, a log line or an
 * exception message, and it is never sent to the download hosts GitHub redirects to.
 */
public final class GitHubApi {
    public static final String API = "https://api.github.com";
    private static final long MAX_JSON = 8L * 1024 * 1024;
    public static final long MAX_DOWNLOAD = 200L * 1024 * 1024;

    /** An error answered by GitHub, with a message that is safe to show. */
    public static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class RepoInfo {
        public final String defaultBranch;
        public final boolean canPush;
        public final boolean isPrivate;

        RepoInfo(String defaultBranch, boolean canPush, boolean isPrivate) {
            this.defaultBranch = defaultBranch;
            this.canPush = canPush;
            this.isPrivate = isPrivate;
        }
    }

    public static final class Run {
        public final long id;
        public final String status;
        public final String conclusion;
        public final String htmlUrl;
        public final String title;

        Run(long id, String status, String conclusion, String htmlUrl, String title) {
            this.id = id;
            this.status = status;
            this.conclusion = conclusion;
            this.htmlUrl = htmlUrl;
            this.title = title;
        }

        public boolean isCompleted() {
            return "completed".equals(status);
        }
    }

    public static final class Step {
        public final String name;
        public final String status;
        public final String conclusion;

        Step(String name, String status, String conclusion) {
            this.name = name;
            this.status = status;
            this.conclusion = conclusion;
        }
    }

    public static final class Artifact {
        public final long id;
        public final String name;
        public final long sizeBytes;
        public final boolean expired;

        Artifact(long id, String name, long sizeBytes, boolean expired) {
            this.id = id;
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.expired = expired;
        }
    }

    private final HttpTransport http;
    private final String token;
    private final String owner;
    private final String repo;

    public GitHubApi(HttpTransport http, String token, String owner, String repo) {
        this.http = http;
        this.token = token;
        this.owner = owner;
        this.repo = repo;
    }

    /** Checks an "owner/repo" string. Returns {owner, repo} or null. */
    public static String[] parseRepo(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.startsWith("https://github.com/")) {
            t = t.substring("https://github.com/".length());
        }
        if (t.endsWith(".git")) {
            t = t.substring(0, t.length() - 4);
        }
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        String[] p = t.split("/");
        if (p.length != 2 || !p[0].matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})") || !p[1].matches("[A-Za-z0-9._-]{1,100}")
                || p[1].equals(".") || p[1].equals("..")) {
            return null;
        }
        return p;
    }

    private String repoUrl(String suffix) {
        return API + "/repos/" + owner + "/" + repo + suffix;
    }

    private Map<String, String> headers(boolean json) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        h.put("User-Agent", "ZishuIDE");
        h.put("Authorization", "Bearer " + token);
        if (json) {
            h.put("Content-Type", "application/json");
        }
        return h;
    }

    private HttpTransport.Response call(String method, String url, Map<String, Object> jsonBody, int... okCodes)
            throws IOException {
        byte[] body = jsonBody == null ? null : MiniJson.encode(jsonBody).getBytes(IoUtil.UTF8);
        HttpTransport.Response r = http.execute(method, url, headers(body != null), body, MAX_JSON);
        for (int ok : okCodes) {
            if (r.code == ok) {
                return r;
            }
        }
        throw toException(r);
    }

    private ApiException toException(HttpTransport.Response r) {
        String apiMessage = "";
        try {
            Map<String, Object> m = MiniJson.asMap(MiniJson.parse(r.text()));
            String s = MiniJson.str(m, "message");
            if (s != null) {
                apiMessage = s;
            }
        } catch (MiniJson.JsonException ignored) {
            // not JSON
        }
        String msg;
        switch (r.code) {
            case 401:
                msg = "GitHub rejected the access token (expired, revoked or mistyped). Create a new token and enter it again.";
                break;
            case 403:
                if ("0".equals(r.header("x-ratelimit-remaining"))) {
                    msg = "GitHub's rate limit was reached. Wait a few minutes and try again.";
                } else {
                    msg = "GitHub refused the request: the token lacks a required permission. " + permissionHelp()
                            + (apiMessage.length() > 0 ? " (GitHub says: " + apiMessage + ")" : "");
                }
                break;
            case 404:
                msg = "GitHub could not find " + owner + "/" + repo + ", or the token has no access to it. "
                        + "Check the repository name and that the token is granted this repository.";
                break;
            case 422:
                msg = "GitHub rejected the request" + (apiMessage.length() > 0 ? ": " + apiMessage : ".");
                break;
            default:
                msg = "GitHub answered HTTP " + r.code + (apiMessage.length() > 0 ? ": " + apiMessage : ".");
        }
        return new ApiException(r.code, msg);
    }

    public static String permissionHelp() {
        return "The fine-grained token needs, for this repository only: Contents (read and write), "
                + "Actions (read and write) and Workflows (read and write).";
    }

    // ------------------------------------------------------------------ repo

    public RepoInfo getRepo() throws IOException, MiniJson.JsonException {
        HttpTransport.Response r = call("GET", repoUrl(""), null, 200);
        Map<String, Object> m = MiniJson.asMap(MiniJson.parse(r.text()));
        Map<String, Object> perms = MiniJson.asMap(m.get("permissions"));
        return new RepoInfo(MiniJson.str(m, "default_branch"), MiniJson.bool(perms, "push", false),
                MiniJson.bool(m, "private", false));
    }

    public String getBranchSha(String branch) throws IOException, MiniJson.JsonException {
        HttpTransport.Response r = call("GET", repoUrl("/git/ref/heads/" + branch), null, 200);
        Map<String, Object> m = MiniJson.asMap(MiniJson.parse(r.text()));
        return MiniJson.str(MiniJson.asMap(m.get("object")), "sha");
    }

    public void createBranch(String name, String sha) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ref", "refs/heads/" + name);
        body.put("sha", sha);
        call("POST", repoUrl("/git/refs"), body, 201);
    }

    public void deleteBranch(String name) throws IOException {
        HttpTransport.Response r = http.execute("DELETE", repoUrl("/git/refs/heads/" + name), headers(false), null, MAX_JSON);
        if (r.code != 204 && r.code != 404 && r.code != 422) {
            throw toException(r);
        }
    }

    /** Blob sha of a file at a ref, or null if the file does not exist. */
    public String getFileSha(String path, String ref) throws IOException, MiniJson.JsonException {
        HttpTransport.Response r = http.execute("GET", repoUrl("/contents/" + path + "?ref=" + ref), headers(false), null, MAX_JSON);
        if (r.code == 404) {
            return null;
        }
        if (r.code != 200) {
            throw toException(r);
        }
        return MiniJson.str(MiniJson.asMap(MiniJson.parse(r.text())), "sha");
    }

    /** Text of a file at a ref, or null if it does not exist. */
    public String getFileText(String path, String ref) throws IOException, MiniJson.JsonException {
        HttpTransport.Response r = http.execute("GET", repoUrl("/contents/" + path + "?ref=" + ref), headers(false), null, MAX_JSON);
        if (r.code == 404) {
            return null;
        }
        if (r.code != 200) {
            throw toException(r);
        }
        String content = MiniJson.str(MiniJson.asMap(MiniJson.parse(r.text())), "content");
        return content == null ? null : new String(Base64Util.decode(content), IoUtil.UTF8);
    }

    /** Creates or updates a file on a branch. existingSha must be given when the file already exists. */
    public void putFile(String path, String branch, byte[] data, String message, String existingSha) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("message", message);
        body.put("content", Base64Util.encode(data));
        body.put("branch", branch);
        if (existingSha != null) {
            body.put("sha", existingSha);
        }
        HttpTransport.Response r = http.execute("PUT", repoUrl("/contents/" + path), headers(true),
                MiniJson.encode(body).getBytes(IoUtil.UTF8), MAX_JSON);
        if (r.code != 200 && r.code != 201) {
            throw toException(r);
        }
    }

    // --------------------------------------------------------------- actions

    public void dispatchWorkflow(String workflowFile, String ref, Map<String, Object> inputs) throws IOException {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ref", ref);
        body.put("inputs", inputs);
        call("POST", repoUrl("/actions/workflows/" + workflowFile + "/dispatches"), body, 204);
    }

    /** Finds the run whose display title contains marker (the job id), newest first; null if not there yet. */
    public Run findRunByTitle(String workflowFile, String marker) throws IOException, MiniJson.JsonException {
        HttpTransport.Response r = call("GET", repoUrl("/actions/workflows/" + workflowFile
                + "/runs?event=workflow_dispatch&per_page=30"), null, 200);
        Map<String, Object> m = MiniJson.asMap(MiniJson.parse(r.text()));
        List<Object> runs = MiniJson.asList(m.get("workflow_runs"));
        if (runs != null) {
            for (Object o : runs) {
                Run run = parseRun(MiniJson.asMap(o));
                if (run.title != null && run.title.contains(marker)) {
                    return run;
                }
            }
        }
        return null;
    }

    public Run getRun(long id) throws IOException, MiniJson.JsonException {
        HttpTransport.Response r = call("GET", repoUrl("/actions/runs/" + id), null, 200);
        return parseRun(MiniJson.asMap(MiniJson.parse(r.text())));
    }

    private static Run parseRun(Map<String, Object> m) {
        String title = MiniJson.str(m, "display_title");
        if (title == null) {
            title = MiniJson.str(m, "name");
        }
        return new Run(MiniJson.num(m, "id", 0), MiniJson.str(m, "status"), MiniJson.str(m, "conclusion"),
                MiniJson.str(m, "html_url"), title);
    }

    /** Steps of the first job of a run (empty before a runner picked the job up). */
    public List<Step> getSteps(long runId) throws IOException, MiniJson.JsonException {
        HttpTransport.Response r = call("GET", repoUrl("/actions/runs/" + runId + "/jobs"), null, 200);
        Map<String, Object> m = MiniJson.asMap(MiniJson.parse(r.text()));
        List<Step> out = new ArrayList<Step>();
        List<Object> jobs = MiniJson.asList(m.get("jobs"));
        if (jobs != null && !jobs.isEmpty()) {
            List<Object> steps = MiniJson.asList(MiniJson.asMap(jobs.get(0)).get("steps"));
            if (steps != null) {
                for (Object o : steps) {
                    Map<String, Object> s = MiniJson.asMap(o);
                    out.add(new Step(MiniJson.str(s, "name"), MiniJson.str(s, "status"), MiniJson.str(s, "conclusion")));
                }
            }
        }
        return out;
    }

    public void cancelRun(long runId) throws IOException {
        call("POST", repoUrl("/actions/runs/" + runId + "/cancel"), null, 202);
    }

    public List<Artifact> listArtifacts(long runId) throws IOException, MiniJson.JsonException {
        HttpTransport.Response r = call("GET", repoUrl("/actions/runs/" + runId + "/artifacts"), null, 200);
        Map<String, Object> m = MiniJson.asMap(MiniJson.parse(r.text()));
        List<Artifact> out = new ArrayList<Artifact>();
        List<Object> arts = MiniJson.asList(m.get("artifacts"));
        if (arts != null) {
            for (Object o : arts) {
                Map<String, Object> a = MiniJson.asMap(o);
                out.add(new Artifact(MiniJson.num(a, "id", 0), MiniJson.str(a, "name"),
                        MiniJson.num(a, "size_in_bytes", 0), MiniJson.bool(a, "expired", false)));
            }
        }
        return out;
    }

    /** Downloads the zip behind a GitHub API URL that answers with a redirect to a signed storage URL. */
    private byte[] downloadViaRedirect(String apiPath) throws IOException {
        HttpTransport.Response r = http.execute("GET", repoUrl(apiPath), headers(false), null, MAX_JSON);
        if (r.code != 302 && r.code != 301 && r.code != 307) {
            if (r.code == 200) {
                return r.body;
            }
            throw toException(r);
        }
        String location = r.header("location");
        if (location == null || !location.startsWith("https://")) {
            throw new IOException("GitHub returned an unusable download location");
        }
        // The signed URL carries its own authorization: send NO headers, in particular not the token.
        HttpTransport.Response d = http.execute("GET", location, new HashMap<String, String>(), null, MAX_DOWNLOAD);
        if (d.code != 200) {
            throw new ApiException(d.code, "The download failed with HTTP " + d.code + ".");
        }
        return d.body;
    }

    public byte[] downloadArtifactZip(long artifactId) throws IOException {
        return downloadViaRedirect("/actions/artifacts/" + artifactId + "/zip");
    }

    /** All job logs of a run as text (truncated to maxChars from the end, where failures are). */
    public String downloadLogs(long runId, int maxChars) throws IOException {
        byte[] zip = downloadViaRedirect("/actions/runs/" + runId + "/logs");
        StringBuilder sb = new StringBuilder();
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip));
        try {
            ZipEntry e;
            List<String> names = new ArrayList<String>();
            List<String> texts = new ArrayList<String>();
            long total = 0;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory() || e.getName().indexOf('/') >= 0) {
                    continue; // top-level files are the per-job logs; nested ones repeat per step
                }
                byte[] b = IoUtil.readAll(zin, 16L * 1024 * 1024);
                total += b.length;
                if (total > 64L * 1024 * 1024) {
                    break;
                }
                names.add(e.getName());
                texts.add(new String(b, IoUtil.UTF8));
            }
            for (int i = 0; i < names.size(); i++) {
                sb.append(texts.get(i));
                if (!texts.get(i).endsWith("\n")) {
                    sb.append('\n');
                }
            }
        } finally {
            IoUtil.closeQuietly(zin);
        }
        String all = sb.toString();
        return all.length() > maxChars ? "[... earlier log omitted ...]\n" + all.substring(all.length() - maxChars) : all;
    }
}
