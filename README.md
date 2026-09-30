# MineHost

MineHost is a lightweight native Android host controller for Minecraft servers. It is built with Kotlin, Jetpack Compose, and Material 3.

## Included

- One-time first-launch walkthrough (shown once, then never again)
- Material 3 light/dark dashboard with server status, players, uptime, live server RAM and CPU load, and quick actions
- Foreground service with persistent status notification plus Stop and Restart actions
- Optional partial CPU wake lock
- Battery-optimization shortcut
- Server workspace with generated `server.properties` and `eula.txt`
- Configurable server name, MOTD, port, player limit, level name, memory, launch arguments, Query, RCON with password, flight, and reboot start
- Server core importer using Android's document picker
- Dynamic Minecraft version catalogue backed by the official Mojang manifest
- Server-platform catalogue for Paper, Folia, Purpur, Leaf, Vanilla, Fabric, Forge, Pufferfish, Spigot, Magma, Arclight, and OptiFine
- Official release-feed lookup with an HTTPS allowlist, 512 MB download limit, JAR archive check, and published checksum verification when available
- Explicit confirmation before downloading; downloaded runnable JARs are selected in Settings
- Online and clearly warned Offline LAN authentication modes
- Player-count/TPS console parsing and performance presets (Battery saver, Balanced, Performance)
- Advanced `server.properties` editor for distances, world settings, resource packs, compression, and server behavior
- World/server-pack ZIP export and restore through Android Files or Google Drive's document picker
- NeoForge catalog entry and custom server-pack import workflow
- Public access hub that works with no extra apps: one-tap UPnP/NAT-PMP port mapping, copyable manual port-forwarding values, public IPv4/IPv6 candidate addresses, reachability checks, and carrier-grade NAT (CGNAT) detection with honest alternatives
- Free in-app public relay tunnel (bore protocol, client implemented natively - zero dependencies, nothing to install): forwards the Minecraft TCP port through a public relay and hands you a shareable address; auto-reconnects with backoff and survives relay hiccups
- Tailscale tailnet sharing: detects the 100.64.0.0/10 tailnet address and shows a copyable endpoint that friends can join over without any port forwarding
- LAN endpoint copying for same-network play
- Built-in console command input for `whitelist`, `op`, `deop`, `save-all`, and other server commands, with quick-command chips and recent-command recall
- Router Check diagnostics for local/public IP, IPv6, CGNAT detection, UPnP/NAT-PMP discovery, and explicit port mapping
- Live process console with an issue filter, log clearing, and one-tap copy of the whole log buffer
- Console uses a bounded ring buffer and coalesced state emission, so heavy world generation cannot flood the UI
- Graceful shutdown: the server is asked to stop and flush the world before it is signalled
- Start-after-reboot receiver
- Restart action in-app and from the notification
- Version-matching guards so e.g. version 1.21.1 can never resolve the 1.21.11 build
- Download retries with backoff, a strict HTTPS host allowlist, and Mojang manifest mirror fallback
- No bundled Minecraft server core or large runtime

## Device support

Runs on Android 8.0 (API 26) and newer, which covers Android 11 through the current release. Edge-to-edge insets, display cutouts, predictive back, foreground-service types and the API 33+ notification permission are all handled per API level.

## Build

Open this directory in Android Studio (Koala or newer), or run:

```bash
./gradlew assembleStandardDebug
```

Unit tests covering the download allowlist and version-matching rules:

```bash
./gradlew testStandardDebugUnitTest
```

The project has two distribution flavors:

- `standard`: modern target SDK 35; Java runtime checking and catalog downloads.
- `local`: personal sideload build with target SDK 28; enables the on-demand Android-native OpenJDK 21 runtime installer in Settings. This is not a Play Store build and should be used only on a device you control.

The local runtime is downloaded on demand from a pinned Android-native GitHub archive, verified by SHA-256, extracted into app-private storage, and self-tested with `java -version`. It is ARM64-only and requires roughly 700 MB of free storage during installation.

## Releasing

Two GitHub Actions workflows handle releases:

- **CI** runs the unit tests and a debug build on every push and pull request to `main`.
- **Release** runs on a `v*` tag: it runs the tests, builds `assembleStandardRelease` signed with the keystore from repository secrets, verifies the signature with `apksigner`, and attaches the APK to a GitHub release. Re-running an already published tag replaces the APK instead of failing.

Publishing a new signed release:

```bash
git tag v0.02 && git push origin v0.02
```

The signing material lives in `keystore/` (gitignored): `release.keystore` plus `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. **Back this directory up — losing the keystore means you can never update the published app again.** The same four values are stored as repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); `./keystore/upload-secrets.sh` uploads them with a token that may manage repository secrets.

## Public access note

MineHost exposes a Java Edition server with what the device can actually do on its own:

1. **Automatic port mapping** - UPnP IGD or NAT-PMP, requested from the router only after you tap *Go public*.
2. **Manual forwarding** - the exact protocol, port and internal address to copy into the router page.
3. **IPv6 direct** - many providers route IPv6 without NAT, so a global address is reachable as-is.
4. **Tailscale tailnet** - friends join your tailnet and connect to the phone's 100.x address.

Tailscale Funnel is deliberately not used. It cannot run inside an Android app (it is a CLI-only feature of a Linux, macOS or Windows node) and it only exposes TLS-terminated HTTPS on ports 443, 8443 and 10000, which the Minecraft Java protocol cannot speak - see tailscale/tailscale#14240. MineHost therefore offers the tailnet path, which does work from the phone, and never pretends a broken tunnel is live.

## Server runtime note

Android does not ship a normal `java` executable. MineHost deliberately does not bundle a JVM or a Minecraft server because both would make the app much larger and introduce licensing/distribution concerns. Import a server core you trust:

- A native server host executable can be launched directly when the Android runtime/device supports it.
- A `.jar` core is launched using the Java executable configured in Settings. You must provide a compatible Java runtime on the device if one is available.

The catalogue resolves builds from official project feeds (including GitHub Releases for Leaf) rather than accepting arbitrary download URLs. Forge is treated as an installer, Spigot uses its official BuildTools workflow, and OptiFine is clearly marked client-only. The standard build cannot execute a downloaded desktop JDK on modern Android; use the `local` flavor for the on-demand Android-native runtime. The EULA switch must be enabled before a Minecraft server core can start.
