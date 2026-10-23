# Known limitations

- No on-device compilation. Builds need the GitHub Actions cloud route, a private repository and a token.
  The `LocalBuildEngine` really runs Gradle but reports "unavailable" on Android; it is for a desktop JVM.
- No managed SDK/JDK downloads. The Build tools screen reports facts; it does not install anything.
- Only debug builds; no release signing for user projects; no Gradle task choice; no APK splits/AAB.
- Build state lives in the app process. If Android kills the app, the build screen cannot reattach; the
  remote run may continue on GitHub and the temporary branch may remain (delete branches named `zishu-job-*`).
- Editor: lexical highlighting and structural checks only (brackets, quotes, XML well-formedness), no
  semantic errors, completion, refactoring or code folding. Files over 2 MB are refused; very large files
  are not checked live. Project search is read-only (no replace across files). Unsaved edits are lost if the
  process is killed before a save (auto-save writes on pause when enabled).
- Open tabs are not restored after the editor is closed.
- Imported ZIP/folder limits apply (500 MB, 20,000 files, depth 30).
- Kotlin projects need the Kotlin Gradle plugin from Maven/Google in the cloud runner; plugin versions are
  checked against known-compatible ranges only.
- WebView projects: HTTPS is the default; cleartext HTTP is enabled only if the user enters an http:// URL (the wizard warns). No JavaScript bridge is exposed; file access is disabled except bundled assets.

## On-phone build (new, unverified on a device)

See docs/ONDEVICE.md. It supports simple Java projects only, needs Android 8+ and an arm64 phone, and its native parts
have not been run by the author yet; run the self-test in Build tools. Kotlin and dependency-based projects still need the cloud build.

## Import, clone and signing

- Generated Gradle files come from the manifest only. Dependencies from Maven are not guessed; add them to `app/build.gradle`.
- Clone downloads a ZIP of the default branch (or `--branch`); there is no git history and no push. Private repositories need `--auth` and a token that can read them.
- Repositories with several apps import one app (the one with a launcher activity) and list the others.
- The JKS reader and the signing code were verified with keytool-made keystores in unit tests; signing/verification with apksig and the dialogs are verified only by the CI build and on a phone.
