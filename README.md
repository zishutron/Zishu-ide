# Zishu IDE

**Build Android apps on your Android phone.** Zishu IDE is a mobile IDE for creating, editing and building
Android projects (Java, XML layouts, programmatic UI, and WebView apps with local files or a website), with no
computer and no Android Studio.

- Package: `com.zishu.ide` · Java · minSdk 21 · targetSdk 35 · no AndroidX
- Code editor with syntax highlighting, tabs, undo/redo, find and replace, file explorer and problems list
- Projects live in a visible `ZishuIDE` folder on the phone, so you can also manage them from a file manager
- Two build engines: **on this phone** (default) and **GitHub Actions cloud build** (optional)

## Build engines

| | On this phone | Cloud (GitHub Actions) |
|---|---|---|
| Account / internet | Not needed | Your private GitHub repo + token |
| Java projects without libraries | Yes | Yes |
| Kotlin, AndroidX, Maven dependencies | No | Yes |
| Phone requirement | Android 8+, 64-bit ARM | Any supported phone |

**On this phone:** aapt2 → ECJ (Java compiler) → D8 (dex) → package and align → apksig (v1 + v2 signature).
The signing key is generated on first use, is unique to your install and is stored encrypted by the Android
Keystore. No key or password is stored in the repository or shipped in the app. Details: [docs/ONDEVICE.md](docs/ONDEVICE.md).

**Cloud:** the project is uploaded to a private repository you own, a GitHub Actions workflow runs Gradle, and the APK
is downloaded. Details: [docs/BUILD.md](docs/BUILD.md).

## What has been verified

Verified by running it:
- The IDE's own CI: unit tests (180) and `assembleDebug` pass on GitHub Actions.
- Cloud build of a project on real GitHub Actions.
- On-phone build, install and launch of a generated project, and of a larger WebView app, on a real Android phone.

Not verified (see [docs/STATUS.md](docs/STATUS.md) and [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md)):
Kotlin on-phone builds (unsupported), lambdas with the on-phone compiler, phones other than the ones tested, release signing of user apps.

## Build the IDE itself (no Android Studio needed)

1. Push this repository to GitHub.
2. Open Actions → **Build Zishu IDE** → Run workflow (it also runs on every push).
3. Download the `zishu-ide-debug-apk` artifact and install it.

The workflow runs the unit tests, downloads the on-phone toolchain (`scripts/fetch-toolchain.sh`), builds the debug APK,
checks package / minSdk / targetSdk / signature, and prints the measured APK size.

Locally (JDK 17, Android SDK platforms 34 and 35, build-tools 34.0.0):
`sh scripts/fetch-toolchain.sh && ./gradlew testDebugUnitTest assembleDebug`

Signed release: add the four secrets listed at the top of `.github/workflows/release-ide.yml`, then run **Release Zishu IDE**.

## Set up cloud builds (optional)

1. Create a **private** repository, e.g. `yourname/zishu-builds`.
2. GitHub → Settings → Developer settings → Fine-grained tokens: only that repository; Contents, Actions and Workflows = Read and write; short expiry.
3. In the app: Settings → Cloud build. Enter the repository and token, enable "Install or update the build workflow", then Save and test.

The token is stored encrypted (Android Keystore) and sent only to `api.github.com`.

## Repository layout

- `app/src/main/java/com/zishu/ide/core` – pure-Java logic: templates, validation, ZIP safety, editor algorithms, build engines, GitHub client
- `app/src/main/java/com/zishu/ide/core/ondevice` – on-phone build pipeline
- `app/src/main/java/com/zishu/ide/ondevice` – Android implementations of the build tools
- `app/src/main/java/com/zishu/ide/app` – Android UI (built in code) and services
- `app/src/main/assets/cloud/zishu-build.yml` – workflow installed into your build repository
- `scripts/fetch-toolchain.sh` – fetches aapt2 and android.jar for the on-phone build
- `.github/workflows` – CI and manual signed release
- `docs/` – build, on-phone engine, status, limitations, troubleshooting

## License

Copyright (c) 2026 zishutron. **All rights reserved.** This is proprietary software: you may not copy, modify,
redistribute or present it as your own work without written permission. See [LICENSE](LICENSE).
Third-party components keep their own licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Apps you create with Zishu IDE belong to you.

## Download 

To use the latest version of Zishu Terminal, download it from the release on GitHub. 
https://github.com/zishutron/zishu-terminal/releases/download/v6.0.0/zishu.terminal_v6.0.0.apk
