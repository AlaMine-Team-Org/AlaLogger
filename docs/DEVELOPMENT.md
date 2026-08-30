# Development

Requires **JDK 25** — Minecraft 26.x will not start on anything older.

## Build

```bash
./gradlew :fabric:build :neoforge:build   # jars in <loader>/build/libs/
./gradlew :common:test                    # unit tests, no game required
```

Build both. The shared modules are compiled separately by each loader, so code
that compiles under one can fail under the other.

## Run the game

```bash
./gradlew :fabric:runClient   # dev client, game dir: fabric/runs/client/
./gradlew :fabric:runServer   # dev server, game dir: fabric/runs/server/
```

`:neoforge:runClient` and `:neoforge:runServer` are the same for the other
loader, with their own `neoforge/runs/` directories.

The dev environment is self-contained: worlds, configs and logs live under
`fabric/runs/` and never touch a real Minecraft installation.

Two things that will otherwise cost you an afternoon:

- **The command needs operator rights.** In singleplayer that means creating the
  world with cheats enabled — otherwise `/alalogger` simply is not there.
- **Do not pipe commands into `runServer`.** Gradle mangles the server's stdin
  and every command fails, vanilla ones included, with a message that points at
  your mod. Use RCON instead (`enable-rcon=true` in `server.properties`).

## Point the mod somewhere else

`fabric/runs/client/config/alalogger.json` is created on first run. Set
`apiBaseUrl` to a local instance of the Log Checker while developing:

```json
{ "apiBaseUrl": "http://127.0.0.1:8123/api/v1", "language": "auto" }
```

## Project layout

| Module | Contains | Minecraft on the classpath |
|---|---|---|
| `common` | HTTP, redaction, file discovery, config, history, translations, the startup sequence | no |
| `common-mc` | the Brigadier command tree and chat components | yes, via NeoForm |
| `fabric`, `neoforge` | entrypoint and loader hooks | yes |

`common` and `common-mc` are consumed as **source**, so every loader recompiles
them with its own toolchain. Adding Paper means writing one thin adapter, not
forking the logic.

## A loader module is an adapter

`AlaLoggerBootstrap` in `common` holds the whole startup sequence: read the
config, build the API client, assemble the service, warm the limits cache,
announce crash files, print the startup lines. An entrypoint hands it the two
directories its loader uses and connects three events to it. That is all it does,
and it is checked rather than trusted:

```bash
./gradlew checkLoaderParity     # also runs as part of :fabric:build
```

The check fails when the two entrypoints stop performing the same shared steps in
the same order, when either grows logic that belongs in `common`, or when one
declares a method the other does not. It self-tests against a known-drifted pair
before it reads a real file, because a gate that cannot fail reports success.

## The store description

`mod_description.txt` at the repository root, read as UTF-8 and folded to a
single line, becomes the description in `fabric.mod.json` and
`neoforge.mods.toml`. It is a file rather than a key in `gradle.properties`
because Gradle reads that file as ISO-8859-1, and a non-ASCII character there
reaches the jar double-encoded — first seen, in practice, on a store page.

API signatures verified against the 26.2 jar are in [MC-26.2-API.md](MC-26.2-API.md).
