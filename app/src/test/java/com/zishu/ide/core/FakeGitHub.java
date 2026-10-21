package com.zishu.ide.core;

import com.zishu.ide.core.build.CancelToken;
import com.zishu.ide.core.build.HttpTransport;
import com.zishu.ide.core.util.Base64Util;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.util.MiniJson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scripted in-memory stand-in for the parts of GitHub's REST API the cloud build uses.
 *
 * IMPORTANT: this tests Zishu IDE's logic (request sequencing, error handling, security rules).
 * It does not prove that the real GitHub API behaves the same; that is only shown by a real run.
 */
public class FakeGitHub implements HttpTransport {
    public static final String TOKEN = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8";

    public static final class Req {
        public final String method;
        public final String url;
        public final Map<String, String> headers;
        public final byte[] body;

        Req(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    // ---- scenario switches
    public boolean isPrivate = true;
    public boolean canPush = true;
    public boolean workflowInstalled = true;
    public String workflowText;
    public boolean unauthorized;
    public boolean neverStart;
    /** "success", "failure" or "cancelled" */
    public String conclusion = "success";
    public int pollsUntilDone = 3;
    public boolean artifactMissing;
    public boolean artifactCorrupt;
    public boolean artifactUnsigned;
    public boolean leakTokenInLog = true;
    public String failureLog = "2026-10-19T10:00:00.0000000Z /home/runner/work/r/r/proj/app/src/main/java/com/example/myapp/MainActivity.java:12: error: cannot find symbol\n";
    public CancelToken cancelOnDispatch;
    public int dispatchNotFoundTimes;

    // ---- recorded state
    public final List<Req> requests = new ArrayList<Req>();
    public final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>(); // "branch:path" -> bytes
    public final List<String> branchesCreated = new ArrayList<String>();
    public final List<String> branchesDeleted = new ArrayList<String>();
    public boolean cancelRequested;
    public String jobId;
    private int runPolls;
    private boolean dispatched;
    private int discoveryCalls;

    private HttpTransport.Response json(int code, String body) {
        return new HttpTransport.Response(code, new HashMap<String, String>(), body.getBytes(IoUtil.UTF8));
    }

    private HttpTransport.Response redirect(String location) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("location", location);
        return new HttpTransport.Response(302, h, new byte[0]);
    }

    private HttpTransport.Response bytes(int code, byte[] b) {
        return new HttpTransport.Response(code, new HashMap<String, String>(), b);
    }

    @Override
    public synchronized HttpTransport.Response execute(String method, String url, Map<String, String> headers,
                                                       byte[] body, long max) throws IOException {
        requests.add(new Req(method, url, headers == null ? new HashMap<String, String>() : headers, body));
        if (!url.startsWith("https://")) {
            throw new IOException("insecure url " + url);
        }
        if (url.startsWith("https://blob.example.net/")) {
            return blob(url);
        }
        if (unauthorized) {
            return json(401, "{\"message\":\"Bad credentials\"}");
        }
        String path = url.substring("https://api.github.com".length());
        String prefix = "/repos/me/builds";
        if (!path.startsWith(prefix)) {
            return json(404, "{\"message\":\"Not Found\"}");
        }
        String rest = path.substring(prefix.length());
        String query = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            query = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }

        if (rest.isEmpty() && method.equals("GET")) {
            return json(200, "{\"default_branch\":\"main\",\"private\":" + isPrivate + ",\"permissions\":{\"push\":" + canPush + "}}");
        }
        if (rest.equals("/contents/.github/workflows/zishu-build.yml") && method.equals("GET")) {
            if (!workflowInstalled) {
                return json(404, "{\"message\":\"Not Found\"}");
            }
            return json(200, "{\"sha\":\"wfsha\",\"content\":\"" + Base64Util.encode(workflowText.getBytes(IoUtil.UTF8)) + "\"}");
        }
        if (rest.startsWith("/contents/") && method.equals("PUT")) {
            Map<String, Object> m = parse(body);
            String file = rest.substring("/contents/".length());
            String branch = MiniJson.str(m, "branch");
            files.put(branch + ":" + file, Base64Util.decode(MiniJson.str(m, "content")));
            if (file.equals(".github/workflows/zishu-build.yml")) {
                workflowInstalled = true;
                workflowText = new String(files.get(branch + ":" + file), IoUtil.UTF8);
            }
            return json(201, "{}");
        }
        if (rest.equals("/git/ref/heads/main") && method.equals("GET")) {
            return json(200, "{\"object\":{\"sha\":\"abc123\"}}");
        }
        if (rest.equals("/git/refs") && method.equals("POST")) {
            String ref = MiniJson.str(parse(body), "ref");
            branchesCreated.add(ref.substring("refs/heads/".length()));
            return json(201, "{}");
        }
        if (rest.startsWith("/git/refs/heads/") && method.equals("DELETE")) {
            branchesDeleted.add(rest.substring("/git/refs/heads/".length()));
            return bytes(204, new byte[0]);
        }
        if (rest.equals("/actions/workflows/zishu-build.yml/dispatches") && method.equals("POST")) {
            if (dispatchNotFoundTimes > 0) {
                dispatchNotFoundTimes--;
                return json(404, "{\"message\":\"Workflow does not exist\"}");
            }
            Map<String, Object> inputs = MiniJson.asMap(parse(body).get("inputs"));
            jobId = MiniJson.str(inputs, "job_id");
            dispatched = true;
            if (cancelOnDispatch != null) {
                cancelOnDispatch.cancel();
            }
            return bytes(204, new byte[0]);
        }
        if (rest.equals("/actions/workflows/zishu-build.yml/runs") && method.equals("GET")) {
            if (!dispatched || neverStart || ++discoveryCalls < 2) {
                return json(200, "{\"workflow_runs\":[{\"id\":1,\"display_title\":\"Zishu build someone-else\",\"status\":\"completed\"}]}");
            }
            return json(200, "{\"workflow_runs\":[{\"id\":777,\"display_title\":\"Zishu build " + jobId
                    + "\",\"status\":\"queued\",\"html_url\":\"https://github.com/me/builds/actions/runs/777\"}]}");
        }
        if (rest.equals("/actions/runs/777") && method.equals("GET")) {
            runPolls++;
            boolean done = cancelRequested || runPolls >= pollsUntilDone;
            String status = done ? "completed" : (runPolls == 1 ? "queued" : "in_progress");
            String concl = done ? (cancelRequested ? "cancelled" : conclusion) : null;
            return json(200, "{\"id\":777,\"status\":\"" + status + "\",\"conclusion\":" + (concl == null ? "null" : "\"" + concl + "\"")
                    + ",\"display_title\":\"Zishu build " + jobId + "\",\"html_url\":\"https://github.com/me/builds/actions/runs/777\"}");
        }
        if (rest.equals("/actions/runs/777/jobs") && method.equals("GET")) {
            String s2 = runPolls >= pollsUntilDone ? "completed" : "in_progress";
            return json(200, "{\"jobs\":[{\"steps\":[{\"name\":\"Set up job\",\"status\":\"completed\"},"
                    + "{\"name\":\"Build debug APK\",\"status\":\"" + s2 + "\"},{\"name\":\"Upload APK\",\"status\":\"pending\"}]}]}");
        }
        if (rest.equals("/actions/runs/777/cancel") && method.equals("POST")) {
            cancelRequested = true;
            return json(202, "{}");
        }
        if (rest.equals("/actions/runs/777/logs") && method.equals("GET")) {
            return redirect("https://blob.example.net/logs.zip?sig=abc");
        }
        if (rest.equals("/actions/runs/777/artifacts") && method.equals("GET")) {
            if (artifactMissing) {
                return json(200, "{\"artifacts\":[]}");
            }
            return json(200, "{\"artifacts\":[{\"id\":55,\"name\":\"zishu-apk-" + jobId + "\",\"size_in_bytes\":1234,\"expired\":false}]}");
        }
        if (rest.equals("/actions/artifacts/55/zip") && method.equals("GET")) {
            return redirect("https://blob.example.net/artifact.zip?sig=def");
        }
        return json(404, "{\"message\":\"Not Found: " + method + " " + rest + query + "\"}");
    }

    private HttpTransport.Response blob(String url) throws IOException {
        if (url.contains("logs.zip")) {
            String text = conclusion.equals("failure") ? failureLog : "2026-10-19T10:00:00.0000000Z BUILD SUCCESSFUL in 41s\n";
            if (leakTokenInLog) {
                text += "2026-10-19T10:00:01.0000000Z debug: using token " + TOKEN + "\n";
            }
            Map<String, byte[]> z = new LinkedHashMap<String, byte[]>();
            z.put("build.txt", text.getBytes(IoUtil.UTF8));
            z.put("build/1_Step.txt", "nested duplicate".getBytes(IoUtil.UTF8));
            return bytes(200, TestSupport.zip(z));
        }
        if (artifactCorrupt) {
            return bytes(200, "definitely not a zip".getBytes(IoUtil.UTF8));
        }
        Map<String, byte[]> z = new LinkedHashMap<String, byte[]>();
        z.put("app-debug.apk", TestSupport.syntheticApk(!artifactUnsigned));
        z.put("apk-info.txt", "package: name='com.example.myapp' versionCode='1' versionName='1.0'\n".getBytes(IoUtil.UTF8));
        return bytes(200, TestSupport.zip(z));
    }

    private static Map<String, Object> parse(byte[] body) throws IOException {
        try {
            return MiniJson.asMap(MiniJson.parse(new String(body, IoUtil.UTF8)));
        } catch (MiniJson.JsonException e) {
            throw new IOException(e.getMessage());
        }
    }

    public List<Req> requestsTo(String method, String urlPart) {
        List<Req> out = new ArrayList<Req>();
        for (Req r : requests) {
            if (r.method.equals(method) && r.url.contains(urlPart)) {
                out.add(r);
            }
        }
        return out;
    }
}
