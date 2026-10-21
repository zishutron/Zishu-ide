# Troubleshooting

| Message | Cause and fix |
|---|---|
| "No GitHub access token is set" / "No build repository is set" | Settings → Cloud build. |
| "The repository has no Zishu build workflow" | Enable "Install or update the build workflow" in Cloud build settings, or copy `app/src/main/assets/cloud/zishu-build.yml` to `.github/workflows/zishu-build.yml` in your build repo. |
| HTTP 401 / 403 / 404 from GitHub | Token expired, wrong repository, or missing permissions: Contents, Actions, Workflows = Read and write, for that repository. Use "Save and test connection". |
| "is a public repository" | Use a private repository, or allow public repositories knowing your source becomes public. |
| "Project is larger than 40 MB" | Remove large assets or build output from the project. |
| Run not found after dispatch | GitHub can be slow to start runs; retry. Check the Actions tab of the build repository. |
| Gradle error shown in the log | The log is saved under Build → History. Common causes: AGP/Gradle mismatch, missing SDK platform, syntax errors. The build screen lists recognised errors with a suggested action. |
| Install button asks for permission | Android requires "Install unknown apps" to be allowed for Zishu IDE. |
| "App not installed" | A different-signature version of the same package is installed; uninstall it first. |
| Token has to be re-entered | Android 5.x cannot keep it encrypted, so it is memory-only there; or a lock-screen change invalidated the Keystore key. |
| Unsaved edits after theme/rotation | Rotation does not recreate the editor. A system dark-mode change is deferred while unsaved edits exist (unless auto-save is on). |
