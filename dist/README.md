# `dist/` — end-user installer

Ad-hoc one-shot installer for UniDrive on Linux. This is **not** a packaged
distro install — it's a thin bash wrapper around the fat shadowJars produced
by `./gradlew shadowJar`. Packaging for `.deb` / `.rpm` / AppImage / flatpak
is tracked under separate backlog items.

Windows community-best-effort launcher templates live under
[`windows/`](windows/) — see UD-718.

## What it does

`install.sh` drops the built fat JAR(s) into `~/.local/lib/unidrive/`,
generates wrapper script(s) at `~/.local/bin/unidrive[-mcp]`, copies the
systemd-user unit, and runs `systemctl --user daemon-reload`. It does **not**
enable or start anything — that step is yours.

## Prerequisites

- Java 21+ runtime on `$PATH` (JRE is enough; JDK only required to build).
- `~/.local/bin` on your `$PATH` (most modern desktops handle this; if not,
  add it to your shell rc).
- For the systemd unit: a systemd-user instance (standard on Ubuntu, Fedora,
  Arch, Debian; absent on minimal containers).

## Usage — local build

```bash
cd core && ./gradlew :app:cli:shadowJar :app:mcp:shadowJar -q && cd ..
bash dist/install.sh
systemctl --user enable --now unidrive.service
journalctl --user -u unidrive.service -f
```

## Usage — released artefact

```bash
bash dist/install.sh ~/Downloads/unidrive-0.0.1.jar
```

The single-arg form skips MCP and installs just the CLI + wrapper + unit.

## Uninstall

```bash
bash dist/uninstall.sh
```

Removes the binaries, wrappers, and systemd unit. Leaves `~/.config/unidrive/`
(tokens, config) and `~/.local/share/unidrive/` (logs) in place — wipe them
by hand if you want a full reset.

## Status

Salvaged from the pre-greenfield `dist/` tree (see UD-761). Smoke-tested with
`bash -n` and `systemd-analyze verify`; not yet exercised on a clean Ubuntu
container. The "packaged installer" roadmap items live in the backlog.
