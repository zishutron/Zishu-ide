# Privacy Policy for Zishu IDE

Effective date: 25 October 2026

Zishu IDE (package `com.zishu.ide`) is an Android app made by zishutron for writing, building, signing and installing
Android apps on your phone. This policy explains what the app does with your data. The short version: **Zishu IDE does
not have user accounts, does not collect personal data on any server of ours, and contains no advertising or analytics.**

## What stays on your phone

Your projects, source code, build logs, built APKs, downloaded libraries, the prepared Kotlin compiler, keystores you
import or create, and your settings are stored on your phone only. They are not sent to us. Projects are kept in a
visible `ZishuIDE` folder on shared storage, so other apps that have storage access can also see them.

The debug key used to sign on-phone builds is generated on your phone and stored encrypted by the Android Keystore.

## What the app connects to

Zishu IDE connects to the internet only when you ask it to do something that needs it:

- **Google Maven (`dl.google.com`) and Maven Central (`repo1.maven.org`)**: to download libraries and the Kotlin compiler
  for your builds, and the setup resources from the first-launch popup or `zishu setup`. These servers receive the
  request, including your IP address, as any website would. Downloads use HTTPS and are checked against checksums.
- **GitHub (`github.com`, `api.github.com`)**: only if you clone a repository (`zishu clone`) or use the optional cloud
  build. If you enter a GitHub token, it is stored encrypted on your phone and sent only to GitHub. Cloud builds upload
  your project to a private repository that you own; keystores and the `apk/` folder are never uploaded.

We do not run a server that receives your data from the app, and we do not see which projects you build or which
libraries you download.

## Permissions

- **Internet**: for the downloads and GitHub features above.
- **All files access / storage**: to keep your projects and APKs in the visible `ZishuIDE` folder and to import or save
  files. The app uses this only for your own projects and the files you choose.
- **Install packages**: to install the APKs you build. Android asks you to confirm every installation.

## Signing keys and passwords

Keystore passwords are asked for in a dialog when needed and are never saved, logged or uploaded. No signing key or password
is shipped inside the app.

## Third-party services and components

Zishu IDE uses open-source and third-party tools (listed in `THIRD_PARTY_NOTICES.md`) that run on your phone. They do not
send data anywhere. The only third-party services involved are the ones named above, and only when you use those features.

## Apps you create

Apps you build with Zishu IDE belong to you. This policy covers Zishu IDE only. If your own app collects data, you are
responsible for its privacy policy.

## Children

Zishu IDE is a developer tool and is not directed at children under 13. It does not knowingly collect data from anyone.

## Deleting your data

Uninstalling Zishu IDE removes its private data, including downloaded libraries, the prepared Kotlin compiler and the
debug key. Your projects in the `ZishuIDE` folder are normal files; delete them with any file manager if you want them gone.

## Changes

If this policy changes, the new version will be published in this repository with a new effective date.

## Contact

Questions about this policy: open an issue at <https://github.com/zishutron/Zishu-ide/issues>.
