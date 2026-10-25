# Known limitations

## On-phone build (see [ONDEVICE.md](ONDEVICE.md))
- Needs Android 8+ and a 64-bit ARM phone. Other phones use the cloud build.
- **Supported:** Java projects (Java 5 to 15, default 15), Kotlin projects (2.0.21 by default, 1.9.24 for projects that ask for Kotlin 1.x), Maven/Google libraries including AndroidX,
  version catalogs, BOMs, `files()` / `fileTree`. Verified on real phones, including a multi-file Kotlin app with AndroidX and
  KTX libraries.
- **Not supported (refused with a clear message, use the cloud build):**
  - `project(':x')` modules, `com.android.library` modules, build types and flavors beyond the default debug build
  - `kapt`, `ksp`, `annotationProcessor` and other annotation processors (so no Room/Dagger/Hilt/Glide compilers)
  - Jetpack Compose (needs the Compose compiler plugin)
  - NDK / native code (CMake, ndk-build)
  - dynamic versions such as `1.+`, custom Gradle plugins and tasks
- **Java level:** the bundled compiler (ECJ 3.25.0) compiles up to Java 15. A higher level in `build.gradle` is lowered to 15 and
  the build log says so. Newer language features (records finalised after 15, pattern matching for switch, and so on) are not
  available. Lambdas and newer syntax have been tested on a few projects only.
- **Kotlin:** two fixed compiler versions: 2.0.21 (K2, default) and 1.9.24 (used when the project declares a Kotlin 1.x plugin). Language features newer than 2.0 are not supported. 2.0.21 is verified on a phone, including a multi-file app with AndroidX libraries. Coroutines used
  directly in app code and Kotlin mixed with your own Java files are not tested yet. The compiler runs in-process on the
  phone, so a very large Kotlin project may be slow or run out of memory.
- **First use needs internet and time.** Libraries and the Kotlin compiler are downloaded once and kept on the phone; the Kotlin
  compiler is also converted to dex once (this can take many minutes on a slow phone). The first-run popup or `zishu setup`
  does this ahead of time. Uninstalling the IDE removes all of it. Download is over HTTPS from Google Maven and Maven Central only.
- **Build time:** a project with many libraries (for example about 900 dex inputs) can take several minutes the first time.
- **Signing:** on-phone builds are signed with a debug key unique to your install. An APK signed with a different key cannot be
  installed over an app that was signed with another key. Signing with your own keystore (`zishu sign`)
  works and was verified on a phone with a PKCS12 keystore. No AAB, no APK splits, no ProGuard/R8 shrinking (D8 only).
- The D8 warnings about `java.lang.instrument` / `sun.misc` types from kotlinx-coroutines debug classes are expected and harmless.

## Cloud build
- Needs a private GitHub repository you own and a fine-grained token. Only debug builds; no Gradle task choice.
- Build state lives in the app process. If Android kills the app, the build screen cannot reattach; the remote run may continue
  on GitHub and the temporary branch may remain (delete branches named `zishu-job-*`).
- Kotlin projects need the Kotlin Gradle plugin from Maven/Google in the cloud runner; plugin versions are checked against
  known-compatible ranges only.
- The `LocalBuildEngine` really runs Gradle but reports "unavailable" on Android; it is for a desktop JVM.
- No managed SDK/JDK downloads. The Build tools screen reports facts; it does not install anything.

## Editor
- Lexical highlighting and structural checks only (brackets, quotes, XML well-formedness); no semantic errors, completion,
  refactoring or code folding. Compile errors appear only after a build.
- Files over 2 MB are refused; very large files are not checked live.
- Project search is read-only (no replace across files).
- Unsaved edits are lost if the process is killed before a save (auto-save writes on pause when enabled).
- Open tabs are not restored after the editor is closed.

## Import, clone and signing
- Imported ZIP/folder limits apply (500 MB, 20,000 files, depth 30).
- Generated Gradle files come from the manifest only. Dependencies from Maven are not guessed; add them to `app/build.gradle`.
- Clone downloads a ZIP of the default branch (or `--branch`); there is no git history and no push. Private repositories need
  `--auth` and a token that can read them.
- Repositories with several apps import one app (the one with a launcher activity) and list the others.
- The JKS reader and the signing code were verified with keytool-made keystores in unit tests; signing and verification with
  apksig are verified by the CI build and on phones.

## WebView projects
- HTTPS is the default; cleartext HTTP is enabled only if the user enters an http:// URL (the wizard warns). No JavaScript
  bridge is exposed; file access is disabled except bundled assets.

## The IDE's own CI
- Without the four signing secrets (see the README) every CI build is signed with a fresh random key, and Android will not
  install it over the previous one.
