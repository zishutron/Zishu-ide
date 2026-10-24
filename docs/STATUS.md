# Status: what is verified and what is not

Last updated after on-phone testing by the author. "Verified" means it was run, not only written.

## Verified in CI and in the development environment
- Unit tests run on every push in GitHub Actions; the suite has 256 tests (templates, ZIP safety, validation, editor
  algorithms, Maven resolution and POM/BOM handling, version catalogs, manifest merge, Java levels, class stubs, download
  meter, JAR splitting, terminal commands, setup, on-device build planning with fake tools).
- `assembleDebug` of the IDE itself runs in CI; the workflow checks package, minSdk, targetSdk and signature and prints the
  measured APK size.
- Stable CI signing: with the four signing secrets set, new IDE APKs install over the previous one.

## Verified on real phones (reported by the author)
- On-phone build, install and launch of generated Java projects and of a larger WebView app.
- AndroidX / Maven libraries: annotation, core and AppCompat projects build; aapt2 compiles real AAR resources and D8 dexes
  real AndroidX libraries.
- Java levels up to 15 build; Java 15 is the default for new projects and for projects whose `build.gradle` sets no level.
- Kotlin 1.9.24 on the phone: native Kotlin projects build, install and run, including a multi-file Kotlin calculator using
  AndroidX and KTX libraries (62 libraries resolved, about 900 dex inputs, Java 11). Kotlin compile errors are reported with
  file, line and message.
- First launch: the "download build resources" popup appears when resources are missing; **Download** opens the terminal and
  downloads everything there (`zishu setup all`); after that the popup does not appear again.
- Build log scrolls on its own on the phone that had the Android 8 problem (the whole build screen no longer scrolls).
- Terminal, visible `apk/` folder and download, signing with your own keystore, GitHub clone, ZIP import without Gradle files.

## Not verified yet
- Kotlin mixed with hand-written Java files, and coroutines used directly in app code.
- Lambdas and newer Java syntax across many projects; phones other than the ones tested.
- Release signing of user apps; the cloud build against a real repository after the latest changes.

## Not supported on the phone (use the cloud build)
Annotation processors (kapt, ksp, `annotationProcessor`), Compose, multi-module Gradle (`project(':x')`), NDK, dynamic
versions such as `1.+`. These are refused with a clear message. See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

## Notes
- Maven, Google and GitHub could not be reached from the development environment, so everything that needs the network was
  confirmed only on real phones or in CI, never simulated.
- Android-layer Java is compiled only by CI; locally it is syntax-checked.
