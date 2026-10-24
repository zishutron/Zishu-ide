# Zishu Terminal (inside Zishu IDE)

Open it from the home screen (**Terminal**) or from the editor menu (**⋮ → Terminal**, which selects that project).

## Zishu commands

| Command | What it does |
|---|---|
| `zishu projects` | list projects |
| `zishu open <n\|name>` | select a project; the shell moves into its folder |
| `zishu status` | selected project, engine choice, whether on-phone and cloud builds are ready |
| `zishu build [--device\|--cloud]` (or `build`) | start a debug build; progress and the result print here |
| `zishu cancel` | stop the running build |
| `zishu log [n]` | last n lines of the last build log |
| `zishu apk` / `zishu install` | path of the last APK / open the Android installer |
| `zishu history` | recent builds of the project |
| `zishu engine [auto\|device\|cloud]` | show or set the engine (auto = on the phone when ready, else cloud) |
| `zishu cloud status\|repo\|workflow\|public\|settings` | cloud build settings |
| `zishu editor` | open the project in the editor |
| `zishu selftest` | build a sample app with the on-phone toolchain |

The GitHub token is never typed in the terminal (it would end up in history). Use `zishu cloud settings`.

## APKs, signing and cloning

| Command | What it does |
|---|---|
| `zishu apks` | list the APKs in the project's visible `apk/` folder |
| `zishu download [apk]` | copy an APK to Downloads (or open a "save as" picker when storage access is not granted) |
| `zishu keystore create <name> [--alias a] [--cn "Name"] [--org O] [--country IN] [--years 30]` | new PKCS12 keystore (RSA 2048, self-signed) in `keystore/` |
| `zishu keystore import` | copy a `.p12/.pfx/.jks/.keystore` file into `keystore/` |
| `zishu keystore list|info|delete <name>` | list, inspect (asks password), delete (asks to confirm) |
| `zishu sign [apk] [--keystore f] [--alias a]` | sign with v1+v2, save `apk/<name>-signed.apk`, check it with the APK verifier |
| `zishu verify [apk]` | check any APK's signature and show the signer's SHA-256 |
| `zishu clone <owner/repo|url> [--branch b] [--name n] [--auth]` | download a GitHub repository ZIP and set it up as a project |
| `zishu setup [all|androidx|kotlin|status]` | download the AndroidX starter libraries and prepare the Kotlin compiler for on-phone builds |

Built APKs are copied to `<project>/apk/` (a normal, visible folder); the original stays under the hidden `.zishu/` folder.
Keystore passwords are asked in a masked dialog only. They are not typed in the terminal, not saved, not logged and never
uploaded; `keystore/` and `apk/` are never included in a cloud-build upload, and `.gitignore` gets `keystore/`, `*.jks`,
`*.p12`, `*.keystore` added. JCEKS keystores are not supported (convert with keytool).

Importing a ZIP, opening a folder or cloning a repository goes through one importer: Gradle projects are kept as they are;
a flat layout (AndroidManifest.xml next to `src/`, `res/`, `assets/`, `libs/`) or a module without Gradle files is moved into
`app/src/main/...` and `settings.gradle`, `build.gradle`, `app/build.gradle` are generated from the manifest. What it did is listed
after the import. An archive with no AndroidManifest.xml and no Gradle files is rejected.

## Everything else

Other commands go to the Android shell (`/system/bin/sh`): `ls`, `cat`, `cp`, `mkdir`, ... Colours are shown in the app theme.
`^C` cancels a running build, otherwise it stops the shell (and what it runs); a new shell starts with your next command.

## Limits

- No pseudo-terminal: interactive programs (vim, nano, top) and programs that read the keyboard do not work.
- Only what Android ships is available (toybox/mksh); there is no package manager.
- Stopping the shell ends the shell; a long-running child process may keep running in the background.
- History keeps the last 200 commands; lines containing token/password words are not saved.
