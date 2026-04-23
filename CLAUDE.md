# boss-microkernel-runtime

The OOP plugin child JVM entry point. BossConsole spawns this JAR as a
classpath alongside each out-of-process plugin's JAR; the runtime hosts the
plugin's state holder, streams its widget tree back over gRPC, and proxies
plugin-context calls back to the kernel.

## Architecture

- `PluginProcessMain` — the `main()` for the child JVM. Loads the plugin's
  `Plugin` impl from the classpath via reflection.
- `RemotePluginContext` — mirror of `PluginContext`; every method delegates
  to a gRPC proxy under `ai.rever.boss.plugin.ipc.*` (the
  `plugin-api-ipc.jar` upstream dependency).
- `PluginStateHolder` — MVI base class for plugin state.
- `PluginUIServiceImpl` — gRPC service that streams the plugin's Compose
  widget tree to the host.
- `stateholders/` — concrete state holders for each in-house panel plugin.

## Build

Compile-time deps come from BossConsole's IPC contract jars:

- `boss-ipc-<ipcVersion>.jar` — gRPC + protobuf stubs
- `boss-ui-sdk-<ipcVersion>.jar` — widget-tree primitives
- `plugin-api-core-<ipcVersion>.jar` — `Plugin`, `PluginContext`, manifest
- `plugin-api-ipc-<ipcVersion>.jar` — the 19 gRPC proxies

These are published as release assets by BossConsole's CI.

### Local development

```bash
# One-time, after cloning. Requires a sibling BossConsole checkout
# (or BOSSCONSOLE_DIR env var pointing at one).
./dev-setup.sh

# Build the fatJar.
./gradlew fatJar

# Drop into ~/.boss/plugins/ to test against your local BossConsole.
cp build/libs/boss-microkernel-runtime-*-all.jar ~/.boss/plugins/
```

### CI

CI sets `CI=true`, which makes `build.gradle.kts` skip the local-deps
branch and run the `downloadDeps` task instead — that pulls the four
upstream jars from
`https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest/download/`.

To pin a specific BossConsole release, pass `-Pupstream.source=...` or
the `bossconsole_release_tag` workflow input.

## Versioning

Two independent versions:

- **Runtime version** (`plugin.json#version`, `runtime.version` Gradle
  property): bumped per release.
- **IPC version** (`gradle.properties#ipc.version`, `plugin.json#minIpcVersion`):
  matches BossConsole's `IpcVersion.CURRENT` — the wire-format contract.
  Bumped only when the proto surface changes. The host refuses to spawn a
  runtime whose `minIpcVersion` is incompatible with its own IPC version.

## Release

`workflow_dispatch` on `.github/workflows/release.yml`:

1. Bumps `plugin.json#version`.
2. Resolves IPC version from `gradle.properties` (overridable).
3. Downloads upstream jars from BossConsole-Releases.
4. Builds the fatJar.
5. Computes SHA-256.
6. Creates a GitHub Release in this repo with the JAR attached.
7. POSTs metadata to the BOSS Plugin Store
   (`/plugin-store/github/metadata`) with `{githubUrl, sha256}`.

The store endpoint extracts the manifest from the JAR server-side and
uses the streaming-computed hash as the integrity anchor; the `sha256`
in the payload is a sanity check.
