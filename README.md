# Zishu IDE

**Build real Android apps on your Android phone.** Zishu IDE is a mobile IDE for creating, editing, building, signing and
installing Android projects (Java, Kotlin, XML layouts, programmatic UI, WebView apps) with **no computer and no Android Studio**.
Builds run on the phone itself; a GitHub Actions cloud build is available as an optional fallback.

- Download size: about 38.6 MB (libraries and the Kotlin compiler are downloaded later, only if you need them)
- Package: `com.zishu.ide` · Java · minSdk 21 · targetSdk 35 · the IDE itself uses no AndroidX (your apps can)
- Code editor with syntax highlighting, tabs, undo/redo, find and replace, file explorer and problems list
- Built-in **Zishu Terminal** with IDE commands and an Android shell
- Projects live in a visible `ZishuIDE` folder, so you can also manage them from a file manager
- Builds on the phone: **Java 5 to 15, AndroidX / Maven libraries, Kotlin 2.0**
- Import a ZIP or **clone a GitHub repository**; the project is laid out for you
- Sign APKs with **your own keystore**; keep the built APK in a visible `apk/` folder

## What builds on the phone

| | On this phone | Cloud (GitHub Actions) |
|---|---|---|
| Account / internet | Not needed for plain Java; internet once for libraries and the Kotlin compiler | Your private GitHub repo + token |
| Java projects | Yes (Java 15 by default, 5 to 15 supported) | Yes |
| AndroidX and other Maven libraries (Google, Maven Central) | Yes: downloaded once, then kept on the phone | Yes |
| Kotlin | Yes (Kotlin 2.0.21, see below) | Yes |
| kapt / ksp / annotation processors, Compose, multi-module Gradle, NDK | No | Gradle decides |
| Phone requirement | Android 8+, 64-bit ARM | Any supported phone |

### On-phone pipeline

`aapt2` (resources, also for AAR libraries) → *Kotlin compiler, if there are `.kt` files* → ECJ (Java) → D8 (dex, multi-dex) →
package and align → apksig (v1 + v2 signature). Details: [docs/ONDEVICE.md](docs/ONDEVICE.md).

- **Libraries.** `implementation` / `api` / `compileOnly` / `runtimeOnly` / `debugImplementation` with Maven coordinates, version
  catalogs (`libs.versions.toml`), BOMs (`platform(...)`), exclusions, `files()` / `fileTree`. The resolver picks the highest
  version, merges library manifests and compiles library resources. Unsupported things (`project(':x')`, `kapt`, `ksp`,
  `annotationProcessor`, dynamic versions such as `1.+`) are refused with a clear message instead of being guessed.
- **Java levels.** 5 to 15 are compiled; a higher level in `build.gradle` is lowered to 15 and the build log says so. New projects
  and projects whose `build.gradle` sets no level use Java 15.
- **Kotlin.** The official `kotlin-compiler-embeddable` (2.0.21 by default, 1.9.24 for projects that declare a Kotlin 1.x plugin) is downloaded from Maven Central, converted to dex
  with D8 on the phone (once; it is large) and run in-process. It compiles `.kt` files together with your Java files, including
  projects with several Kotlin files and AndroidX/KTX libraries. Language features newer than
  Kotlin 2.0, kapt/ksp and the Compose compiler plugin are not supported.
- **First-run setup.** When the app opens it checks what is really on the phone. If the AndroidX starter libraries or the prepared
  Kotlin compiler are missing, it asks once whether to download them (**Download** opens the terminal and runs `zishu setup all`
  with percent, speed and time left; **Later** asks again next time). Nothing is asked when both are present.
- **Signing.** Builds are signed with a debug key generated on first use, unique to your install and stored encrypted by the
  Android Keystore. For your own releases, sign an APK with your own keystore (`zishu sign`, password asked in a masked dialog,
  never saved, logged or uploaded). No key or password is stored in the repository or shipped in the app.

### Cloud build (optional)

The project is uploaded to a private repository you own, a GitHub Actions workflow runs Gradle, and the APK is downloaded.
Details: [docs/BUILD.md](docs/BUILD.md). Keystores and the `apk/` folder are never uploaded.

## Zishu Terminal

Open it from the editor or the home screen. IDE commands (`zishu help` lists them):

`projects` · `open` · `status` · `build [--device|--cloud]` · `log` · `apk` · `apks` · `install` · `download` ·
`keystore list|create|import|info|delete` · `sign` · `verify` · `clone <owner/repo>` · `setup [androidx|kotlin|status]` ·
`engine` · `cloud` · `history` · `editor` · `selftest`. Everything else goes to the Android shell (`ls`, `cat`, `cp` ...).
Details: [docs/TERMINAL.md](docs/TERMINAL.md).

## Import and clone

- **Import ZIP / clone a GitHub repo** (`zishu clone owner/repo [--branch b] [--name n] [--auth]`): a flat layout, a module without
  Gradle files or a wrapper folder is turned into the standard `app/src/main/...` layout, and `settings.gradle`, `build.gradle`
  and `app/build.gradle` are generated from the manifest when missing. Repositories with several apps import the one with a
  launcher activity and list the others. Clone downloads a ZIP of the branch (no git history, no push).

## What has been verified

Verified by running it on real phones (reported by the author while testing):
- The IDE's own CI: unit tests and `assembleDebug` on GitHub Actions; the unit test suite has 256 tests at the time of writing.
- On-phone build, install and launch of generated Java projects and of a larger WebView app.
- AndroidX / Maven libraries on the phone: annotation, core and AppCompat projects build (aapt2 with real AAR resources, D8 with
  real AndroidX); Java levels up to 15 build.
- Kotlin 1.9.24 on the phone: native Kotlin projects build, install and run, including a multi-file Kotlin app (a calculator) using
  AndroidX and KTX libraries (62 libraries resolved, about 900 dex inputs, Java 11); a Kotlin compile error is reported with
  file, line and message in the build log.
- The first-run download popup appears when resources are missing, **Download** fetches them in the terminal, and the popup does not return afterwards; the build log scrolls on its own (Android 8 fix); Java 15 is the default.
- Terminal, visible `apk/` folder and download, signing with your own keystore, GitHub clone, ZIP import without Gradle files.
- A stable CI signing key: new IDE APKs install over the previous one.

Not verified yet:
- Kotlin 2.0.21 on a phone (added after the Kotlin 1.9.24 tests), Kotlin mixed with hand-written Java files, coroutines used directly in app code, kapt/ksp and Compose (not supported).
- Lambdas and newer Java syntax across many projects; phones other than the ones tested; release signing of user apps.
- Annotation processors, Compose, multi-module Gradle projects and NDK (not supported yet).

See [docs/STATUS.md](docs/STATUS.md) and [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

## Download

Download the latest APK from the **Releases** page of this repository:

**[zishu.ide_v0.1.0.apk (v0.1.0)](https://github.com/zishutron/Zishu-ide/releases/download/v0.1.0/zishu.ide_v0.1.0.apk)**
· [all releases](https://github.com/zishutron/Zishu-ide/releases)

1. Open the link on your phone and download the APK.
2. Allow "Install unknown apps" for your browser or file manager when Android asks, then install.
3. The APK is about 38.6 MB. Needs Android 8+ and a 64-bit ARM phone (the cloud build also works on other phones).
4. Later versions install over this one, because every release is signed with the same key.

Zishu IDE is an early release (v0.1.0): see "Not verified yet" above and [docs/STATUS.md](docs/STATUS.md).

## Build from source (developers)

Locally (JDK 17, Android SDK platforms 34 and 35, build-tools 34.0.0):
`sh scripts/fetch-toolchain.sh && ./gradlew testDebugUnitTest assembleDebug`

## Set up cloud builds (optional)

1. Create a **private** repository, e.g. `yourname/zishu-builds`.
2. GitHub → Settings → Developer settings → Fine-grained tokens: only that repository; Contents, Actions and Workflows = Read and write; short expiry.
3. In the app: Settings → Cloud build. Enter the repository and token, enable "Install or update the build workflow", then Save and test.

The token is stored encrypted (Android Keystore) and sent only to `api.github.com`.

## Repository layout

- `app/src/main/java/com/zishu/ide/core` – pure-Java logic: templates, validation, project import, ZIP safety, signing, terminal commands, editor algorithms, build engines, GitHub client
- `app/src/main/java/com/zishu/ide/core/maven` – dependency resolution: coordinates, POMs, BOMs, version catalogs, AAR unpacking, download cache
- `app/src/main/java/com/zishu/ide/core/ondevice` – on-phone build pipeline (planning, manifest merge, Java levels, Kotlin support, resource packs)
- `app/src/main/java/com/zishu/ide/ondevice` – Android implementations of the build tools (aapt2, ECJ, D8, apksig, Kotlin runner)
- `app/src/main/java/com/zishu/ide/app` – Android UI (built in code) and services
- `app/src/main/assets/cloud/zishu-build.yml` – workflow installed into your build repository
- `scripts/fetch-toolchain.sh` – fetches aapt2 and android.jar for the on-phone build
- `.github/workflows` – CI and manual signed release
- `docs/` – build, on-phone engine, terminal, status, limitations, troubleshooting

## License

Copyright (c) 2026 zishutron. **All rights reserved.** This is proprietary software: you may not copy, modify,
redistribute or present it as your own work without written permission. See [LICENSE](LICENSE).
Third-party components keep their own licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Apps you create with Zishu IDE belong to you.
