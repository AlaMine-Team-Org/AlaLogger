# Development

Requires **JDK 25** — Minecraft 26.x will not start on anything older.

## Build

```bash
./gradlew :fabric:build      # jar in fabric/build/libs/
./gradlew :common:test       # unit tests, no game required
```

## Run the game

```bash
./gradlew :fabric:runClient   # dev client, game dir: fabric/runs/client/
./gradlew :fabric:runServer   # dev server, game dir: fabric/runs/server/
```

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
| `common` | HTTP, redaction, file discovery, config, history, translations | no |
| `common-mc` | the Brigadier command tree and chat components | yes, via NeoForm |
| `fabric` | entrypoint and loader hooks | yes |

`common` and `common-mc` are consumed as **source**, so every loader recompiles
them with its own toolchain. Adding NeoForge or Paper means writing one thin
adapter, not forking the logic.

API signatures verified against the 26.2 jar are in [MC-26.2-API.md](MC-26.2-API.md).
