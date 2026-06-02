# `dist/` — end-user installer

One-shot installer for UniDrive on Linux. A thin bash wrapper around the fat
shadow JAR produced by `./gradlew :app:cli:shadowJar`.

## What it does

`install.sh` drops the built fat JAR into `~/.local/lib/unidrive/`, generates
a wrapper script at `~/.local/bin/unidrive`, copies the systemd-user unit,
and runs `systemctl --user daemon-reload`. It does **not** enable or start
the service — that step is yours.

## Prerequisites

- Java 21+ runtime on `$PATH` (JRE is enough; JDK only required to build).
- `~/.local/bin` on your `$PATH`.
- For the systemd unit: a systemd-user instance (standard on Ubuntu, Fedora,
  Arch, Debian; absent on minimal containers).

## Usage — local build

```bash
cd core && ./gradlew :app:cli:shadowJar -q && cd ..
bash dist/install.sh
systemctl --user enable --now unidrive.service
journalctl --user -u unidrive.service -f
```

## Usage — released artefact

```bash
bash dist/install.sh ~/Downloads/unidrive-<ver>.jar
```

## Uninstall

```bash
bash dist/uninstall.sh
```

Removes the binary, wrapper, JAR, and systemd unit. Keeps `~/.config/unidrive/`
(profiles + OAuth tokens) and `~/.local/share/unidrive/` (logs) — delete them
manually if you want a full wipe.

## Releases

On any `v*` tag push, `.github/workflows/release.yml` builds the CLI fat
JAR (`unidrive-<version>.jar`) and publishes it as a GitHub Release with
detached GPG signatures and SHA256 checksums.

End users don't consume this artefact directly — it's the upstream input
to `unidrive-dist`, which packages it as `.deb`/`.rpm`/AUR/tarball and
publishes via the channels at https://unidrive.krost.org/install/.

For the local-development install path used today (build + drop under
`~/.local/`), see `install.sh` and `core/app/cli/build.gradle.kts`'s
`deploy` task.
