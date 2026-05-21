# unidrive

A cloud-sync core. Today it ships as a Linux daemon syncing **Internxt Drive** and **OneDrive** through a single config, a single CLI, and a single SQLite state DB per profile. Tomorrow it powers a Windows desktop client, an Android app, and a Linux UI as separate platform surfaces. No telemetry, no hidden control plane. Apache-2.0.

The Linux daemon is the only currently-shipping surface. See [docs/adr/multi-platform.md](docs/adr/multi-platform.md) for the scope this repo operates under — "multi-platform core" is the direction, not a current capability claim.

## Quickstart (Linux daemon)

Build the fat JAR and install:

```bash
git clone https://github.com/gkrost/unidrive
cd unidrive
( cd core && ./gradlew :app:cli:shadowJar )
bash dist/install.sh
```

Drops the fat JAR into `~/.local/lib/unidrive/`, a wrapper into `~/.local/bin/unidrive`, and a systemd-user unit.

Create a profile (interactive wizard prompts for provider type, credentials), authenticate, sync:

```bash
unidrive profile add                                # wizard: pick onedrive or internxt
unidrive -p <profile-name> auth                     # OAuth (browser) or JWT (Internxt)
unidrive -p <profile-name> sync --watch             # one-shot foreground sync
systemctl --user enable --now unidrive.service      # auto-start on login (background)
journalctl --user -u unidrive.service -f            # follow logs
```

Config lives at `~/.config/unidrive/config.toml`. Per-profile state (SQLite, OAuth tokens, conflict log) lives at `~/.config/unidrive/<profile>/`. The daemon advertises sync progress over a Unix-domain socket per profile.

## Requirements (current ship: Linux daemon)

- Linux. x86_64 or aarch64.
- Java 21+ runtime on `$PATH`. JRE is enough; JDK only required to build.
- systemd-user instance (standard on Ubuntu, Fedora, Arch, Debian; absent on minimal containers).

Windows and macOS are not currently supported by the daemon. A Windows desktop client (Cloud Files API placeholders, Explorer overlay) and a Linux UI (FUSE + Dolphin context menus) are planned as separate platform surfaces; neither has shipping code yet. The Android app similarly consumes the core but is not part of this repo's current build.

## Uninstall

```bash
bash dist/uninstall.sh
```

Removes the binary, wrapper, JAR, and systemd unit. Keeps `~/.config/unidrive/` (profiles + tokens) and `~/.local/share/unidrive/` (logs) — delete them manually for a full wipe.

## Hacking on it / running an agent against it

Read [AGENTS.md](AGENTS.md). It is the rulebook for every change to this repo — human or LLM.

## License

Apache-2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE). The author also builds a non-OSS Android app on top of this codebase; Apache 2.0's permissive grant explicitly allows that, and any contributor patches are licensed permissively under the same grant.

Maintainer: Gernot Krost — `unidrive@krost.org`.
