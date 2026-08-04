# boss-microkernel-runtime

The OOP plugin child JVM entry point. BossConsole spawns this JAR as a
classpath alongside each out-of-process plugin's JAR; the runtime hosts the
plugin's state holder, streams its widget tree back over gRPC, and proxies
plugin-context calls back to the kernel.

## Architecture

- `PluginProcessMain` - the `main()` for the child JVM. Loads the plugin's
  `Plugin` impl from the classpath via reflection.
- `RemotePluginContext` - mirror of `PluginContext`; every method delegates
  to a gRPC proxy under `ai.rever.boss.plugin.ipc.*` (the
  `plugin-api-ipc.jar` upstream dependency).
- `PluginStateHolder` - MVI base class for plugin state.
- `PluginUiClient` - the plugin's **client** of the host's `PluginUIService`:
  registers surfaces, streams the widget tree up, reads user events back.
  The plugin dials the kernel here, not the other way round - see below.
- `stateholders/` - concrete state holders for each in-house panel plugin.

## Build

Compile-time deps come from BossConsole's IPC contract jars:

- `boss-ipc-<ipcVersion>.jar` - gRPC + protobuf stubs
- `boss-ui-sdk-<ipcVersion>.jar` - widget-tree primitives
- `plugin-api-core-<ipcVersion>.jar` - `Plugin`, `PluginContext`, manifest
- `plugin-api-ipc-<ipcVersion>.jar` - the 19 gRPC proxies

These are published as release assets by BossConsole's CI.

Plus the plugin API contract itself, which comes from a **different** repo:

- `boss-plugin-api-<version>.jar` - `ai.rever.boss.plugin.api.*`, the provider
  interfaces `RemotePluginContext` implements. Published by
  risa-labs-inc/boss-plugin-api; pinned by the `boss.plugin.api.version`
  Gradle property and resolved from a sibling `boss_plugins/boss-plugin-api`
  checkout, BossConsole's already-fetched copy, or that repo's releases.
  It used to arrive folded into `plugin-api-core`; the plugin-platform
  decoupling stopped publishing it there. **Keep the pin equal to
  BossConsole's `boss-plugin-api` in `libs.versions.toml`** - the host loads
  this contract into its own classloader, and a mismatch is invisible to the
  IPC compat gate.

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
branch and run the `downloadDeps` task instead - that pulls the four
upstream jars from
`https://github.com/risa-labs-inc/BossConsole-Releases/releases/latest/download/`.

To pin a specific BossConsole release, pass `-Pupstream.source=...` or
the `bossconsole_release_tag` workflow input.

## UI transport direction

`ui_protocol.proto` makes the **plugin the client and the kernel the server**,
in both directions: the plugin calls `RegisterUI`, then opens `StreamUI` and
sends `WidgetUpdate`s up while reading `UIEvent`s back off the same call.

Do not reintroduce a `PluginUIService` implementation in this process. This repo
used to have one, and when BossConsole was corrected to serve the contract
(BossConsole #50) both ends sat waiting to be dialled - no plugin UI connected at
all, with no error anywhere. `PluginUiClientTest` stands up the kernel's half and
asserts the plugin dials it, which is the only way that failure is visible from
this side.

Two `StreamUI` details the client has to respect:

- The call carries no surface id - it binds to the surface of its **first**
  `WidgetUpdate`, so there is one call per surface and the first message must be
  a real tree (an update with neither `oneof` arm set is treated as malformed).
- The call **ending** unregisters the surface. Recovery is register-then-reopen,
  never a bare reconnect.

## Versioning

Two independent versions:

- **Runtime version** (`plugin.json#version`, `runtime.version` Gradle
  property): bumped per release.
- **IPC version** (`gradle.properties#ipc.version`, `plugin.json#minIpcVersion`):
  matches BossConsole's `IpcVersion.CURRENT` - the wire-format contract.
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
