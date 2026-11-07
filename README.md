# Ala Logger

Share a Minecraft log or crash report with one command.

```
/alalogger
[Ala Logger] Looking for the log file...
[Ala Logger] Uploading latest.log (23 KB)...
[Ala Logger] Log uploaded: alacraft.day/en/logs/aB3xY9kM [open] [delete]
[Ala Logger] Found 2 known problems:
  1. Java 17 is installed, but this version needs Java 25
  2. Port 25565 is already in use by another process
```

The file is stripped of IP addresses, your operating-system account name and —
critically — your Minecraft session token **before it leaves the machine**, then
uploaded to [alacraft.day](https://alacraft.day/en/logs), which returns a short
link and whatever it recognised, in the player's own language.

## What you get

- **Twelve languages, chosen per player.** Messages, detected problems and links
  follow each player's client language, and a player on a vanilla client gets
  theirs too, because the text is resolved on the server side.
- **Problems in chat, right after the upload.** The site reads the file and names
  what it found. Often nobody needs to open the link at all.
- **It says what it is doing.** Looking for the file, reading it, uploading, done.
  A command that sits silent for four seconds looks broken.
- **Errors you can act on.** Offline, rate limited, file missing and token rejected
  are four different messages, because they need four different responses.
- **An oversized `latest.log` keeps its tail.** The failure is written at the
  bottom of a live log, so cutting from the top throws away the answer.
- **JVM crashes are collected too.** `hs_err_pid*.log` carries the session token
  in its launch arguments, which is why sharing one raw is dangerous and why this
  removes the token first.
- **Deleting your own upload survives a restart.** The delete token is kept on
  disk, so the link you shared this morning is still yours tonight.
- **`history` finds the link you already sent.** Past uploads are listed newest
  first, with what was uploaded and when, so a link that scrolled out of chat is
  not lost with it.
- **After a crash, the title screen offers the log.** The next launch shows a
  button for the files the crash left - a crash report, the JVM's fatal error
  log, or the log of a session that was killed and wrote nothing. One click
  uploads one, and the link can be copied, opened, or copied as a ready message
  for a support chat. Nothing is uploaded until you click, and a crash is
  offered once.

## Commands

`/alalogger` (alias `/alog`) needs operator level 2. In singleplayer that means
cheats on; the crash offer on the title screen needs no command at all.

| Command | What it does |
|---|---|
| `/alalogger` | Upload `logs/latest.log` |
| `/alalogger share <file>` | Upload a log or crash report by name |
| `/alalogger crash` | Upload the newest crash report or JVM fatal error log |
| `/alalogger list [filter]` | List the files that can be shared, ten a page |
| `/alalogger insights <id>` | Show the detected problems of an upload again |
| `/alalogger history` | Your recent uploads, newest first |
| `/alalogger delete <id>` | Delete an upload from the site, after a confirmation |

## Status

Minecraft **26.3 and 26.2**, for **Fabric, NeoForge and Forge** — six jars from
one source and one version number, all six verified on a running server.

**Quilt** runs the Fabric jar: there is no separate Quilt build, and the Fabric
files are published for Quilt as well. It needs Quilt Loader 0.30.2 or newer and
the regular Fabric API (QFAPI and QSL are not made for Minecraft 26.x). The
Fabric jar is checked on a real Quilt server for both game versions, and a
repository check refuses a Fabric Loader requirement newer than the one Quilt
provides.

The shared code is split so that adding a platform is a thin adapter rather than
a rewrite: everything a player interacts with lives in `common` and `common-mc`,
compiled from the same source by each loader, and a loader module is an
entrypoint plus a handful of lines. The startup sequence is shared too, so the
three entrypoints are about fifty lines each — and `checkLoaderParity` fails the
build if they ever stop matching, their client entrypoints included.

## Layout

| Module | Contains | Minecraft on the classpath |
|---|---|---|
| `common` | HTTP, redaction, file discovery, config, history, translations, the startup sequence, the crash check | no |
| `common-mc` | the Brigadier command tree, chat components, the title-screen crash offer | yes, via NeoForm |
| `fabric` | entrypoints and loader hooks | yes |
| `neoforge` | entrypoints and loader hooks | yes |
| `forge` | entrypoint and loader hooks | yes |

`common` and `common-mc` are consumed as **source**, so every loader recompiles
them with its own toolchain — one body of logic, no shading, no intermediate
artifact.

## Building

Requires JDK 25.

```bash
./gradlew :fabric:build :neoforge:build :forge:build            # three jars for Minecraft 26.3
./gradlew :fabric:build :neoforge:build :forge:build -Pmc=26.2  # the same for Minecraft 26.2
./gradlew :common:test                             # unit tests, no game needed
./gradlew :fabric:runClient                        # dev client, Fabric
./gradlew :neoforge:runClient                      # dev client, NeoForge
./gradlew :forge:runClient                         # dev client, Forge
```

Build every loader, and both game versions. The shared modules are compiled
separately by each loader against each Minecraft, so code that compiles under
one can fail under another. What differs between game versions — Minecraft,
Fabric API, the loaders — is one file per version in `versions/`; the source is
the same.

More in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md); API signatures verified
against the game jars are in [docs/MC-26.2-API.md](docs/MC-26.2-API.md).

## Configuration

`config/alalogger.json`, created on first run:

| Key | Default | What it does |
|---|---|---|
| `apiBaseUrl` | `https://alacraft.day/api/v1` | Point it at a self-hosted Log Checker if you run one |
| `apiToken` | empty | Personal token from [alacraft.day/profile](https://alacraft.day/profile): attaches uploads to your account and raises the rate limit |
| `language` | `auto` | `auto` follows each player's client language, or pin `en_us`/`ru_ru`/`uk_ua`/`de_de`/`fr_fr`/`es_es`/`ja_jp`/`pt_br`/`zh_cn`/`pl_pl`/`tr_tr`/`ko_kr` |
| `insightsInChat` | `3` | How many detected problems to print. `0` turns it off |
| `crashWatch` | `true` | Notice new crash reports on startup and offer to upload them — offer, never upload. On a dedicated server in the console, in the game on the title screen |
| `persistHistory` | `true` | Remember upload ids and delete tokens across restarts |
| `broadcastToAdmins` | `true` | Show successful uploads to other online admins |

Uploads never happen on their own. A log leaves the machine when someone runs
the command or clicks Upload, and not before.

Files the mod keeps, next to the config: `alalogger/history.json` (your uploads
and their delete tokens), `alalogger/crash-marker.json` (which crashes were
already offered) and, on a client, `alalogger/session.json` (the last session's
process id, to tell its crash apart from another program's). The config file is
rewritten on startup with every known key; keys it does not know are dropped.

There is one request nobody triggers, and it is easier to name it than to let you
find it yourself: on startup the mod calls `GET /logs/limits` once, to learn the
current maximum upload size rather than discovering it during the first upload of
the session. It sends no log content and no identifier — the User-Agent carries the
mod version, the Minecraft version and the loader name — and, like any HTTP request,
it shows the server where it came from. `apiBaseUrl` redirects this request too, so
a self-hosted instance keeps it in-house.

## Licence

MIT — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
An independent project by AlaCraft, not affiliated with Mojang Studios or Microsoft.
