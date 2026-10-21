package com.zishu.ide.core.build;

import com.zishu.ide.core.model.Defaults;
import com.zishu.ide.core.project.SafeZip;
import com.zishu.ide.core.util.IoUtil;
import com.zishu.ide.core.util.MiniJson;
import com.zishu.ide.core.validation.ProjectValidator;
import com.zishu.ide.core.validation.ValidationReport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds a project on GitHub Actions in a repository the user owns.
 *
 * Flow: validate, zip the project, push the zip to a temporary branch, start the
 * {@code zishu-build.yml} workflow with workflow_dispatch, poll the run, download the APK artifact,
 * verify it and delete the temporary branch. The build itself runs on GitHub's isolated, ephemeral
 * runners; this class never executes project code.
 *
 * A successful result is only produced after an APK file has been downloaded from the finished
 * run's artifact and passed {@link ApkInspector}.
 */
public final class CloudBuildEngine implements BuildEngine {
    public static final String ID = "github-actions";
    public static final String WORKFLOW_FILE = "zishu-build.yml";
    public static final String WORKFLOW_PATH = ".github/workflows/" + WORKFLOW_FILE;
    public static final long MAX_PROJECT_ZIP_BYTES = 40L * 1024 * 1024;

    /** Options chosen by the user in the cloud-build settings. */
    public static final class Config {
        public String token;
        public String ownerRepo;
        /** Text of the bundled workflow file. */
        public String workflowYaml;
        /** Allow committing the workflow file to the repository when it is missing or outdated. */
        public boolean installWorkflow;
        /** Allow building in a public repository (the project's source becomes public). */
        public boolean allowPublicRepo;
        public long pollIntervalMs = 5000;
        public long runDiscoveryTimeoutMs = 120000;
        public long buildTimeoutMs = 45L * 60 * 1000;
    }

    private final HttpTransport http;
    private final Config config;

    public CloudBuildEngine(HttpTransport http, Config config) {
        this.http = http;
        this.config = config;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "GitHub Actions (cloud)";
    }

    @Override
    public Status status() {
        if (config.token == null || config.token.trim().length() == 0) {
            return Status.unavailable("No GitHub access token is set.",
                    "Open Cloud build settings and enter a fine-grained token for your build repository.");
        }
        if (GitHubApi.parseRepo(config.ownerRepo) == null) {
            return Status.unavailable("No build repository is set.",
                    "Open Cloud build settings and enter a repository such as yourname/zishu-builds.");
        }
        if (config.workflowYaml == null || config.workflowYaml.length() == 0) {
            return Status.unavailable("The bundled build workflow is missing from this app.", "Reinstall Zishu IDE.");
        }
        return Status.ok("Builds run on GitHub-hosted runners in " + config.ownerRepo.trim() + ".");
    }

    private static String newJobId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    @Override
    public BuildResult build(BuildRequest request, BuildListener listener, CancelToken cancel) {
        long start = System.currentTimeMillis();
        BuildLog log = new BuildLog(listener, config.token);
        String variant = request.variant;
        Status st = status();
        if (!st.available) {
            log.line(st.reason + " " + st.action);
            return BuildResult.failure(st.reason, 0, ID, variant, log.text(), null, null);
        }
        if (!"debug".equals(variant)) {
            return BuildResult.failure("Only debug builds are supported in this release.", 0, ID, variant,
                    log.text(), null, null);
        }

        String[] or = GitHubApi.parseRepo(config.ownerRepo);
        GitHubApi api = new GitHubApi(http, config.token.trim(), or[0], or[1]);
        String jobId = newJobId();
        String branch = "zishu-job-" + jobId;
        boolean branchCreated = false;
        String runUrl = null;
        GitHubApi.Run run = null;
        try {
            // 1. validate
            listener.onStage(BuildStage.VALIDATE, request.project.name);
            log.line("Build job " + jobId + " for \"" + request.project.name + "\"");
            ValidationReport report = ProjectValidator.validate(request.project);
            for (com.zishu.ide.core.validation.Issue i : report.all()) {
                log.line(i.severity.name() + ": " + i.message);
            }
            if (report.hasErrors()) {
                return BuildResult.invalid(report, elapsed(start), ID, variant);
            }
            cancel.throwIfCancelled();

            // 2. package
            listener.onStage(BuildStage.PACKAGE, null);
            ByteArrayOutputStream zipOut = new ByteArrayOutputStream();
            SafeZip.zipDirectory(request.project.dir, zipOut, SafeZip.DEFAULT_EXCLUDES, MAX_PROJECT_ZIP_BYTES * 3);
            byte[] zipBytes = zipOut.toByteArray();
            if (zipBytes.length > MAX_PROJECT_ZIP_BYTES) {
                throw new IOException("The packaged project is " + IoUtil.formatSize(zipBytes.length)
                        + ", above the " + IoUtil.formatSize(MAX_PROJECT_ZIP_BYTES) + " limit for cloud builds. "
                        + "Remove large files (videos, archives) from the project.");
            }
            log.line("Packaged project: " + IoUtil.formatSize(zipBytes.length));
            listener.onProgress(10);
            cancel.throwIfCancelled();

            // 3. repository checks
            listener.onStage(BuildStage.UPLOAD, or[0] + "/" + or[1]);
            GitHubApi.RepoInfo repo = api.getRepo();
            if (!repo.canPush) {
                throw new IOException("The token cannot write to " + config.ownerRepo.trim() + ". "
                        + GitHubApi.permissionHelp());
            }
            if (!repo.isPrivate && !config.allowPublicRepo) {
                throw new IOException(config.ownerRepo.trim() + " is a public repository: uploading would make your "
                        + "project's source code public. Use a private repository, or explicitly allow public repositories "
                        + "in Cloud build settings.");
            }
            String defaultBranch = repo.defaultBranch;
            ensureWorkflow(api, defaultBranch, log);
            cancel.throwIfCancelled();

            // 4. upload on a temporary branch
            String sha = api.getBranchSha(defaultBranch);
            api.createBranch(branch, sha);
            branchCreated = true;
            api.putFile("zishu-jobs/" + jobId + "/project.zip", branch, zipBytes,
                    "Zishu IDE build input " + jobId, null);
            log.line("Uploaded project to temporary branch " + branch);
            listener.onProgress(25);
            cancel.throwIfCancelled();

            // 5. start the workflow
            Map<String, Object> inputs = new LinkedHashMap<String, Object>();
            inputs.put("job_id", jobId);
            inputs.put("job_ref", branch);
            inputs.put("variant", variant);
            dispatch(api, defaultBranch, inputs, cancel, log);

            // 6. wait for the run to appear
            listener.onStage(BuildStage.QUEUE, null);
            long deadline = System.currentTimeMillis() + config.runDiscoveryTimeoutMs;
            while (run == null) {
                cancel.sleep(config.pollIntervalMs);
                run = api.findRunByTitle(WORKFLOW_FILE, jobId);
                if (run == null && System.currentTimeMillis() > deadline) {
                    throw new IOException("GitHub did not start the build within "
                            + config.runDiscoveryTimeoutMs / 1000 + " seconds. Check that Actions are enabled "
                            + "for " + config.ownerRepo.trim() + ".");
                }
            }
            runUrl = run.htmlUrl;
            log.line("GitHub run: " + runUrl);
            listener.onProgress(30);

            // 7. poll until done
            long buildDeadline = System.currentTimeMillis() + config.buildTimeoutMs;
            String lastStep = null;
            while (!run.isCompleted()) {
                try {
                    cancel.sleep(config.pollIntervalMs);
                } catch (CancelToken.CancelledException e) {
                    requestCancel(api, run, log);
                    throw e;
                }
                run = api.getRun(run.id);
                List<GitHubApi.Step> steps = api.getSteps(run.id);
                int done = 0;
                String current = null;
                for (GitHubApi.Step s : steps) {
                    if ("completed".equals(s.status)) {
                        done++;
                    } else if ("in_progress".equals(s.status) && current == null) {
                        current = s.name;
                    }
                }
                if (current != null && !current.equals(lastStep)) {
                    lastStep = current;
                    log.line("Step: " + current);
                    listener.onStage(BuildStage.BUILD, current);
                }
                if (!steps.isEmpty()) {
                    listener.onProgress(30 + (int) (55L * done / steps.size()));
                }
                if (System.currentTimeMillis() > buildDeadline) {
                    requestCancel(api, run, log);
                    throw new IOException("The build did not finish within "
                            + config.buildTimeoutMs / 60000 + " minutes and was cancelled.");
                }
            }
            listener.onProgress(88);

            // 8. logs
            String remoteLog = "";
            try {
                remoteLog = api.downloadLogs(run.id, 400000);
            } catch (IOException e) {
                log.line("Could not download the run log: " + e.getMessage());
            }
            if (remoteLog.length() > 0) {
                log.line("---- GitHub Actions log ----");
                log.block(remoteLog);
                log.line("---- end of GitHub Actions log ----");
            }
            if ("cancelled".equals(run.conclusion)) {
                return BuildResult.cancelled(elapsed(start), ID, variant, log.text(), runUrl);
            }
            if (!"success".equals(run.conclusion)) {
                List<BuildLogAnalyzer.Finding> findings = BuildLogAnalyzer.analyze(log.redact(remoteLog));
                String title = findings.isEmpty()
                        ? "The cloud build failed (" + run.conclusion + "). See the log for details."
                        : "The build failed: " + findings.get(0).message;
                return BuildResult.failure(title, elapsed(start), ID, variant, log.text(), findings, runUrl);
            }

            // 9. fetch and verify the APK
            listener.onStage(BuildStage.DOWNLOAD, null);
            String wanted = "zishu-apk-" + jobId;
            GitHubApi.Artifact art = null;
            for (GitHubApi.Artifact a : api.listArtifacts(run.id)) {
                if (wanted.equals(a.name) && !a.expired) {
                    art = a;
                }
            }
            if (art == null) {
                throw new IOException("The run succeeded but produced no APK artifact named " + wanted + ".");
            }
            byte[] artifactZip = api.downloadArtifactZip(art.id);
            File apkOut = extractApk(artifactZip, request.outputDir, log);
            listener.onProgress(96);

            listener.onStage(BuildStage.VERIFY, apkOut.getName());
            ApkInspector.Info info = ApkInspector.inspect(apkOut);
            log.line("APK verified: " + IoUtil.formatSize(info.sizeBytes) + ", " + info.dexCount + " dex file(s), "
                    + (info.isSigned() ? "signed" : "NOT signed"));
            if (!info.isSigned()) {
                throw new IOException("The APK is not signed and cannot be installed.");
            }
            listener.onProgress(100);
            return BuildResult.success(apkOut, info, elapsed(start), ID, variant, log.text(), runUrl,
                    "Debug key (generated by the Android Gradle Plugin on the runner)");
        } catch (CancelToken.CancelledException e) {
            log.line("Build cancelled.");
            return BuildResult.cancelled(elapsed(start), ID, variant, log.text(), runUrl);
        } catch (GitHubApi.ApiException e) {
            log.line("ERROR: " + e.getMessage());
            return BuildResult.failure(e.getMessage(), elapsed(start), ID, variant, log.text(), null, runUrl);
        } catch (IOException e) {
            String msg = log.redact(e.getMessage() == null ? e.toString() : e.getMessage());
            log.line("ERROR: " + msg);
            return BuildResult.failure(msg, elapsed(start), ID, variant, log.text(), null, runUrl);
        } catch (MiniJson.JsonException e) {
            log.line("ERROR: GitHub sent an unreadable response: " + e.getMessage());
            return BuildResult.failure("GitHub sent an unreadable response.", elapsed(start), ID, variant,
                    log.text(), null, runUrl);
        } finally {
            if (branchCreated) {
                listener.onStage(BuildStage.CLEANUP, null);
                try {
                    api.deleteBranch(branch);
                    log.line("Deleted temporary branch " + branch);
                } catch (IOException e) {
                    log.line("Could not delete temporary branch " + branch + " (delete it manually): " + e.getMessage());
                }
            }
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private void requestCancel(GitHubApi api, GitHubApi.Run run, BuildLog log) {
        try {
            api.cancelRun(run.id);
            log.line("Asked GitHub to cancel the run.");
        } catch (IOException e) {
            log.line("Could not cancel the GitHub run: " + e.getMessage());
        }
    }

    private void ensureWorkflow(GitHubApi api, String defaultBranch, BuildLog log)
            throws IOException, MiniJson.JsonException {
        String existing = api.getFileText(WORKFLOW_PATH, defaultBranch);
        String wanted = config.workflowYaml;
        if (existing != null && normalize(existing).equals(normalize(wanted))) {
            log.line("Build workflow is installed and up to date.");
            return;
        }
        if (!config.installWorkflow) {
            throw new IOException(existing == null
                    ? "The repository has no Zishu build workflow (" + WORKFLOW_PATH + "). Allow Zishu IDE to install it in Cloud build settings."
                    : "The repository's Zishu build workflow is out of date. Allow Zishu IDE to update it in Cloud build settings.");
        }
        String sha = existing == null ? null : api.getFileSha(WORKFLOW_PATH, defaultBranch);
        api.putFile(WORKFLOW_PATH, defaultBranch, wanted.getBytes(IoUtil.UTF8),
                (existing == null ? "Add" : "Update") + " Zishu IDE build workflow (version " + Defaults.CLOUD_WORKFLOW_VERSION + ")", sha);
        log.line((existing == null ? "Installed" : "Updated") + " " + WORKFLOW_PATH + " on " + defaultBranch);
    }

    private static String normalize(String s) {
        return s.replace("\r\n", "\n").trim();
    }

    /** A just-installed workflow can take a few seconds to become dispatchable. */
    private void dispatch(GitHubApi api, String ref, Map<String, Object> inputs, CancelToken cancel, BuildLog log)
            throws IOException, CancelToken.CancelledException {
        int attempts = 0;
        while (true) {
            try {
                api.dispatchWorkflow(WORKFLOW_FILE, ref, inputs);
                return;
            } catch (GitHubApi.ApiException e) {
                if (e.status == 404 && ++attempts < 8) {
                    log.line("Workflow not registered yet, retrying (" + attempts + "/8)...");
                    cancel.sleep(Math.max(config.pollIntervalMs, 1));
                } else {
                    throw e;
                }
            }
        }
    }

    /** Extracts the single .apk from an artifact zip into outputDir, never overwriting an existing file. */
    static File extractApk(byte[] artifactZip, File outputDir, BuildLog log) throws IOException {
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IOException("Cannot create the output folder " + outputDir);
        }
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(artifactZip));
        try {
            ZipEntry e;
            File result = null;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) {
                    continue;
                }
                String safe = SafeZip.sanitizeEntryName(name);
                if (safe == null) {
                    continue;
                }
                String base = safe.substring(safe.lastIndexOf('/') + 1);
                if (base.endsWith(".apk")) {
                    if (result != null) {
                        throw new IOException("The artifact contains more than one APK.");
                    }
                    File target = uniqueFile(outputDir, base);
                    FileOutputStream out = new FileOutputStream(target);
                    try {
                        byte[] data = IoUtil.readAll(zin, GitHubApi.MAX_DOWNLOAD);
                        out.write(data);
                    } finally {
                        IoUtil.closeQuietly(out);
                    }
                    result = target;
                } else if (base.equals("apk-info.txt")) {
                    log.line("---- apk-info (from aapt2 on the runner) ----");
                    log.block(new String(IoUtil.readAll(zin, 64 * 1024), IoUtil.UTF8));
                }
            }
            if (result == null) {
                throw new IOException("The artifact contains no .apk file.");
            }
            return result;
        } finally {
            IoUtil.closeQuietly(zin);
        }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int i = 2;
        String stem = name.endsWith(".apk") ? name.substring(0, name.length() - 4) : name;
        while (f.exists()) {
            f = new File(dir, stem + "-" + i++ + ".apk");
        }
        return f;
    }
}
