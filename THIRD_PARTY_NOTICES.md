# Third-party notices

Zishu IDE's own code is covered by [LICENSE](LICENSE). The components below are not written by the Zishu IDE
author and remain under their own licenses. Check each project for the current license text and your obligations
before redistributing the application.

| Component | Used for | License (as published by the project) | Source |
|---|---|---|---|
| Eclipse JDT Core Batch Compiler (ECJ) 3.25.0 | Java compilation on the phone | Eclipse Public License 2.0 | https://www.eclipse.org/jdt/core/ |
| R8 / D8 8.7.18 | Converting classes to DEX on the phone | BSD 3-Clause | https://r8.googlesource.com/r8 |
| apksig 8.7.3 | Signing APKs on the phone | Apache License 2.0 | https://android.googlesource.com/platform/tools/apksig |
| Bouncy Castle (bcpkix, bcprov, bcutil) 1.78.1 | Generating the debug signing certificate | Bouncy Castle License (MIT-style) | https://www.bouncycastle.org/ |
| aapt2 34.0.3 for Android (aarch64) | Compiling resources on the phone | Apache License 2.0 (AOSP), binary built by the third-party project below | https://github.com/lzhiyong/android-sdk-tools |
| Android platform `android.jar` (API 34) | Compiling projects against the Android API | Android SDK License Agreement | https://developer.android.com/studio/terms |
| JUnit 4.13.2 (tests only, not shipped) | Unit tests | Eclipse Public License 1.0 | https://junit.org/junit4/ |

`javax.lang.model.SourceVersion` in `app/src/main/java/javax/lang/model/` is a minimal stand-in written for Zishu IDE
so that ECJ can start on Android; it is not the JDK class.

This table is a good-faith summary, not legal advice. If you distribute the application publicly, review the
license texts of the components above.
