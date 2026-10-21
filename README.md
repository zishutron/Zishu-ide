# Zishu IDE

An Android app (`com.zishu.ide`, Java, minSdk 21, targetSdk 35, no AndroidX, no lambdas) for creating and
editing Android projects (Java, Kotlin, XML layouts, programmatic UI, WebView with local assets or a website)
on a phone, and building them into real APKs.

**Read this first: how builds work.** A stock Android phone cannot run a JDK, Gradle or the Android SDK's
native tools. Zishu IDE therefore builds in the cloud: it uploads the project to a *private repository you own*,
runs a GitHub Actions workflow there, and downloads the APK. Without that setup you can create and edit projects
but not build them. See [docs/BUILD.md](docs/BUILD.md). Everything verified and not verified is listed in
[docs/STATUS.md](docs/STATUS.md) and [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

## Build the IDE itself (no Android Studio needed)

1. Push this folder to a GitHub repository.
2. Open Actions → **Build Zishu IDE** → Run workflow (it also runs on every push).
3. Download the `zishu-ide-debug-apk` artifact and install it. The workflow runs the unit tests, builds the
   debug APK, checks package/minSdk/targetSdk and the signature, and prints the measured APK size.

Locally with JDK 17 and the Android SDK (platform 35, build-tools 34.0.0):
`./gradlew testDebugUnitTest assembleDebug`.

Signed release: add the four secrets listed at the top of `.github/workflows/release-ide.yml`, then run
**Release Zishu IDE**. Passwords come only from secrets; no key is stored in the repository or the app.

## Set up cloud builds of your own projects

1. Create a **private** repository, e.g. `yourname/zishu-builds`.
2. GitHub → Settings → Developer settings → Fine-grained tokens: select only that repository; Contents,
   Actions and Workflows = Read and write; short expiry.
3. In the app: Settings → Cloud build. Enter the repository and token, enable "Install or update the build
   workflow", Save and test connection.
4. Open a project → Build. The APK appears with Install / Save buttons.

The token is stored encrypted (Android Keystore, Android 6+) and sent only to `api.github.com`; it is not in the APK.

## Project layout

- `app/src/main/java/com/zishu/ide/core` – pure-Java logic (templates, validation, ZIP safety, editor
  algorithms, build engines, GitHub client). Covered by 160 JUnit tests.
- `app/src/main/java/com/zishu/ide/app` – Android UI (built in code, no layout XML) and services.
- `app/src/main/assets/cloud/zishu-build.yml` – the workflow installed into your build repository.
- `.github/workflows` – `build-ide.yml` (CI) and `release-ide.yml` (manual signed release).

## Tests

`app/src/test` (JUnit 4). Run with `./gradlew testDebugUnitTest`.
