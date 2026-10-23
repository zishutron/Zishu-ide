# On-phone build engine

Zishu IDE can build simple Java Android projects **on the phone**, with no GitHub account and no internet.

## Pipeline

1. **Check** the project (single module, Java only, no Maven dependencies; `.jar` files in `app/libs` are allowed).
2. **aapt2** compile + link: resources, manifest, `R.java`. The manifest gets `package` from `namespace` in build.gradle.
3. **ECJ** compiles the Java sources against the bundled `android.jar` (Java 8 language level).
4. **D8** converts class files to `classes.dex`.
5. **Package** the APK; uncompressed entries are 4-byte aligned.
6. **Sign** with apksig (v1 + v2). The key is generated on first use, unique to this install, and stored encrypted by the Android Keystore. No password exists and no key ships inside the app.
7. **Verify** the APK structure before reporting success.

## What is bundled (by `scripts/fetch-toolchain.sh`, run in CI)

| File | Source |
|---|---|
| `lib/arm64-v8a/libaapt2.so` | aapt2 34.0.3 for Android, third-party build from github.com/lzhiyong/android-sdk-tools. CI prints its SHA-256; pin it with `AAPT2_SHA256`. |
| `assets/toolchain/android.jar` | `platforms;android-34` from the CI runner's Android SDK |
| ECJ 3.25.0, R8/D8 8.7.18, apksig 8.7.3, BouncyCastle 1.78.1 | Maven dependencies |

## Requirements and limits

- Android 8.0+ (API 26) and a 64-bit ARM phone. The IDE APK is arm64-only because of the bundled aapt2.
- Java only. No Kotlin yet, no multi-module projects (`project(':x')`), no NDK code, no annotation processors (kapt/ksp/annotationProcessor are refused by name).
- Projects are compiled against API 34. APIs newer than that will not compile.
- A debug-signed APK cannot update an app installed with a different key. Uninstalling Zishu IDE deletes its signing key.
- Java lambdas / method references are untested with this ECJ + android.jar combination.
- Cancel takes effect between stages, not inside a running tool.

## Verification status

Pure-Java logic (manifest patching, Gradle value parsing, APK alignment, pipeline control flow) is unit tested.
The native/Android parts (running aapt2 from the native library folder, ECJ and D8 on ART, apksig, the Keystore-wrapped key)
have **not** been run by the author. Use **Build tools → Run toolchain self-test** on a phone: it builds a generated sample
project with the real tools and prints each stage and any tool output.

## Libraries (AndroidX and other Maven dependencies)

`implementation`, `api`, `compileOnly`, `runtimeOnly` and `debugImplementation` lines with `group:artifact:version`,
`group:/name:/version:` maps, `platform(...)` BOMs, `libs.*` accessors from `gradle/libs.versions.toml`, simple `def`/`ext`
version variables, `files('libs/x.jar')` and `fileTree(libs)` are read from build.gradle(.kts).

- Libraries come from `google()` and `mavenCentral()` over HTTPS, are checked against the repository's `.sha1` and are kept
  in the app's private storage, so they are downloaded once. The first build with a new library needs internet.
- POMs are read for transitive dependencies (parents, properties, BOM imports, exclusions, `runtime` scope, optional). When two
  versions of a library are requested the higher one is used. Dynamic versions (`1.+`, ranges) are refused.
- AARs: classes and `libs/*.jar` go to the compiler and D8; resources are compiled with aapt2 (cached per library) and linked
  with the app's, each library package gets its own `R` class; assets are merged; `jni/arm64-v8a` and `armeabi-v7a` libraries
  and `src/main/jniLibs` are packaged; library manifests are merged (permissions, features, activities, services, providers,
  receivers, meta-data, `${applicationId}`); a library that needs a higher minSdk than the app stops the build with that message.
- Java language level comes from `sourceCompatibility` / `jvmToolchain` (5 to 21 accepted); the bundled ECJ decides what it can
  really compile and the build says so when it rejects the level.

Verified by unit tests only with fake repositories and fake tools. Real downloads, aapt2 with real AARs, D8 with real AndroidX
and installing the result have to be checked on a phone. Not supported: Gradle module metadata, version ranges, tools:node merge
rules, resource shrinking, R8, annotation processing, Kotlin.
