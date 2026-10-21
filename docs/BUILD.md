# Cloud build: how it works and what it needs

Flow (all calls go to `https://api.github.com` with the token in the `Authorization` header only):

1. Validate the project locally (manifest, XML, Gradle/AGP compatibility, applicationId, web index).
2. Zip it (max 40 MB; build output and `.zishu` metadata are excluded).
3. Check the repository: refuse public repositories unless you allowed it; check the token can push.
4. Check `.github/workflows/zishu-build.yml`; install or update it only if you enabled that option.
5. Create a temporary branch `zishu-job-<id>` and commit `zishu-jobs/<id>/project.zip` to it.
6. Dispatch the workflow (`job_id`, `job_ref`, `variant`); find the run by its name `Zishu build <id>`.
7. Poll jobs and steps, show them as stages; cancel stops the run.
8. Download the artifact `zishu-apk-<id>` (retention 1 day), check the APK (manifest, resources, dex,
   signature), save it with the build history and log.
9. Delete the temporary branch (also after failure or cancel).

The workflow has `contents: read`, validates its inputs, unpacks the zip with path-traversal and symlink
checks, installs the compileSdk platform and build-tools 34.0.0, builds `assembleDebug` with the project's
wrapper (or Gradle 8.9) on JDK 17, verifies the APK with `aapt2` and `apksigner`, and uploads it.

Notes
- Debug builds are signed with the Android Gradle Plugin's debug key. They are for testing and cannot update
  an app installed with a different key. Release signing for user projects is not implemented.
- Your project code runs on a GitHub runner in your repository; GitHub Actions minutes are billed to you
  according to your plan (private repositories have a monthly free allowance).
- Third-party actions are referenced by major-version tag (`actions/checkout@v4`, etc.), not pinned to commit
  SHAs. Pin them yourself if your threat model requires it.
