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

## Everything else

Other commands go to the Android shell (`/system/bin/sh`): `ls`, `cat`, `cp`, `mkdir`, ... Colours are shown in the app theme.
`^C` cancels a running build, otherwise it stops the shell (and what it runs); a new shell starts with your next command.

## Limits

- No pseudo-terminal: interactive programs (vim, nano, top) and programs that read the keyboard do not work.
- Only what Android ships is available (toybox/mksh); there is no package manager.
- Stopping the shell ends the shell; a long-running child process may keep running in the background.
- History keeps the last 200 commands; lines containing token/password words are not saved.
