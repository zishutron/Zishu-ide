# Status: what is verified and what is not

## Verified by running it (in the development environment)
- Core logic compiles with `javac --release 8` and **160 JUnit tests pass** (templates incl. syntax-parsing
  every generated Java file, ZIP safety, file manager, validation, editor algorithms, undo, search, JSON,
  GitHub client against a scripted fake transport, build history, log analysis, APK inspection).
- The cloud workflow YAML parses; its shell steps pass `bash -n`; its unzip and compileSdk-detection Python
  were run against good and hostile inputs.
- All three workflow YAML files parse. All app-layer Java files pass a syntax parse and use no lambdas; the
  helper APIs, drawables and palette fields they reference exist.

## NOT verified (no Android SDK, JDK-Gradle build or network access was available)
- **The Android UI layer has never been compiled against android.jar or run on a device or emulator.**
  Expect compile errors and layout bugs on the first CI run; fix them from the build log.
- No real APK was produced; **APK size is unmeasured** (the CI workflow measures and prints it).
- The GitHub client has only been tested against a fake; real API behaviour is unconfirmed.
- Building a project with SDK 35, and the Kotlin template, are unconfirmed until a real cloud build runs.
- Gradle wrapper + AGP 8.7.3 for the IDE itself: configured, not executed here.

## Feature list
Complete (code written, core tested): project model and templates; creation wizard validation; ZIP
import/export with traversal protection; file manager; syntax highlighting, undo/redo, find/replace;
build engine abstraction, cloud engine, history, cancellation; secure token storage; CI workflows.

Written but untested on a device: dashboard, new-project screen, editor, build, build tools, cloud
settings and settings screens; SAF folder import; APK install/save.

Not implemented: on-device builds, SDK downloads, release signing of user projects, multi-file replace,
tab restore, code completion.
