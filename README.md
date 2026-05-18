# unidrive

One Linux daemon that syncs **Internxt Drive** and **OneDrive** through a single config, a single CLI, and a single SQLite state DB per profile. No telemetry, no hidden control plane. Apache-2.0.

## Quickstart

```bash
git clone https://github.com/gkrost/unidrive
cd unidrive/core && ./gradlew :app:cli:shadowJar
bash dist/install.sh
```

Drops the fat JAR into `~/.local/lib/unidrive/`, a wrapper into `~/.local/bin/unidrive`, and a systemd-user unit. Then:

```bash
unidrive auth --provider onedrive my-onedrive
unidrive auth --provider internxt my-internxt
unidrive -p my-onedrive sync --watch
systemctl --user enable --now unidrive.service     # auto-start on login
journalctl --user -u unidrive.service -f           # follow logs
```

Config lives at `~/.config/unidrive/config.toml`. Per-profile state (SQLite, OAuth tokens, conflict log) lives at `~/.config/unidrive/<profile>/`. The daemon advertises sync progress over a Unix-domain socket per profile.

## Requirements

- Linux. x86_64 or aarch64. Windows and macOS are out of scope (see [docs/adr/linux-only.md](docs/adr/linux-only.md)).
- Java 21+ runtime on `$PATH`. JRE is enough; JDK only required to build.
- systemd-user instance (standard on Ubuntu, Fedora, Arch, Debian; absent on minimal containers).

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
