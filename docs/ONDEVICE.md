# On-phone build engine

Zishu IDE can build simple Java Android projects **on the phone**, with no GitHub account and no internet.

## Pipeline

1. **Check** the project (single module, Java only, no Maven dependencies; `.jar` files in `app/libs` are allowed).
2. **aapt2** compile + link: resources, manifest, `R.java`. The manifest gets `package` from `namespace` in build.gradle.
3. **ECJ** compiles the Java sources against the bundled `android.jar` (Java 15 by default; levels 5 to 15 are compiled, a higher level is lowered to 15 with a warning in the log).
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
- Java, plus Kotlin (see below). No multi-module projects (`project(':x')`), no NDK code, no annotation processors (kapt/ksp/annotationProcessor are refused by name).
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

## Kotlin

Two compiler versions are known: **2.0.21** (K2, the default) and **1.9.24** (K1). A project that declares a Kotlin 1.x plugin or `kotlin` version in its Gradle files (root or app `build.gradle`, or `libs.versions.toml`) is compiled with 1.9.24; any other project, including one that declares nothing, uses 2.0.21. The log line `Kotlin: N file(s), compiled with Kotlin X` says which one was used. Each version has its own compiler download and its own converted copy under `files/toolchains/kotlin-<version>`.

Verified on real phones with **1.9.24**: native Kotlin projects with one and with several `.kt` files, AndroidX/KTX libraries used from Kotlin, compile errors reported with file, line and message. **2.0.21 is verified on a phone**, including a multi-file app with AndroidX libraries. The compiler needs `sun.misc.Unsafe` methods that Android 9 lacks (`copyMemory` with an object base). For 2.x the IDE therefore compiles a small forwarding class named `sun.misc.Unsafe` (`assets/kotlin/UnsafeShim.java.txt`, built with the on-phone Java compiler and D8, once) and gives only the compiler that class; every call is forwarded to the phone's real Unsafe and the missing methods are emulated. A failure is reported in the build log. Not verified yet: Kotlin mixed with hand-written Java, coroutines in app code.

If the module has `.kt` files in `src/main/java` or `src/main/kotlin`, the build:

1. adds `org.jetbrains.kotlin:kotlin-stdlib:<compiler version>` as an `implementation` dependency (unless declared),
2. downloads `kotlin-compiler-embeddable:<version>` and its dependencies from Maven Central (once),
3. converts those jars to dex with D8 (once, kept in `files/toolchains/kotlin-<version>`) and loads them with a `DexClassLoader`,
4. runs `K2JVMCompiler` with `-no-jdk -no-stdlib -no-reflect` against android.jar + your libraries; the Java files and the generated `R.java` are passed too (read for symbols),
5. compiles the Java files with ECJ with the Kotlin classes on the classpath, then dexes everything as usual.

Limits: the compiler versions are fixed (2.0.21 and 1.9.24; language features newer than 2.0 will not compile, newer libraries are read with the metadata check skipped), no kapt/ksp, no Compose compiler plugin, a lot of RAM and time on the first build. If a stage fails, the build log names the stage; send it so the cause can be fixed. Cloud build remains the fallback.

The compiler needs more than its POM lists: `stax-api` (javax.xml.stream), `kotlin-reflect` and `kotlinx-coroutines-core-jvm` are added explicitly, and empty stand-in classes are generated for JDK classes Android lacks (javax.swing.Icon and about 180 others). The compiler is told where its configuration is with a folder unpacked from its jar (`plugin-root`) and a class loader that points the compiler's own classes there.

## First-run setup (AndroidX + Kotlin)

When the app opens it looks at what is really on the phone. If the AndroidX starter libraries (AppCompat, Core KTX, ConstraintLayout, Material) or the converted Kotlin compiler are missing, it asks once whether to download them. **Download** opens the terminal and runs `zishu setup all`; **Later** asks again the next time the app is opened. Nothing is asked when both are present (for example after a build already downloaded them). `zishu setup status` shows the state, `zishu setup androidx` / `zishu setup kotlin` do one part. Progress shows percent, size, speed and time left; the screen is kept awake while it runs.

## Build speed

- **Stage times.** After every successful build the log ends with a line such as `Time: libraries 3 s, resources 5 s, Kotlin 1 min 20 s, Java 12 s, D8 40 s, packaging and signing 2 s (total ...)`, so it is clear where the time went.
- **Library dex cache.** D8 used to convert every library jar again in every build. Now each library jar is converted once (D8 intermediate output) and stored under `files/dexcache/<jar name>-<hash>/`; later builds merge the stored dex files with your own classes. The key holds the jar's path, size and modification time, the minSdk, the platform jar and the D8 version (`DexCache.ID`), so a changed library or a new D8 gets a new entry. The first build with new libraries is not faster (the libraries are converted then, one run each); the following builds are. If the cache cannot be used for any reason, the log says so and the build converts the libraries together with your code as before.
- aapt2 already keeps the compiled resources of each library (`compiled-res.zip` next to the unpacked AAR), so library resources are not recompiled.
