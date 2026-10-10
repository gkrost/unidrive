# `dist/` — end-user installer

One-shot installer for UniDrive on Linux. A thin bash wrapper around the fat
shadow JAR produced by `./gradlew :app:cli:shadowJar`.

## What it does

`install.sh` drops the built fat JAR into `~/.local/lib/unidrive/`, generates
a wrapper script at `~/.local/bin/unidrive`, copies the systemd-user unit,
and runs `systemctl --user daemon-reload`. It does **not** enable or start
the service — that step is yours.

An upgrade is safe over a running install: active `unidrive*.service` units are stopped before the jar is
replaced and started again afterwards, and the jar is unlinked and copied to a fresh file rather than
overwritten in place. A daemon or mount started by hand keeps running the previous jar; the installer lists
it so you can restart it. It also warns when no Java 21+ runtime is found.

## Prerequisites

- Java 21+ runtime (JRE is enough; JDK only required to build); Java 25+ recommended
  (Debian/Ubuntu: `sudo apt install openjdk-25-jre-headless`). The launcher uses `java` on
  `$PATH` when it is 25+, else the newest 25+ JDK under `/usr/lib/jvm`, else `java` on `$PATH`;
  `UNIDRIVE_JAVA=/path/to/bin/java` overrides the choice. Long-running commands warn on Java
  21–24, and every command warns while a running daemon still uses a Java runtime that a package
  upgrade has since replaced (restart it with `unidrive -p <profile> daemon stop`).
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

The unit runs `unidrive autostart`, which starts `sync --watch` for a mirror profile or
`daemon run` for a mount profile. It resolves the default profile each time the service starts.

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

## Launcher flags and `--locale`

Every launcher (the Gradle `deploy` task's `unidrive.ps1` / `unidrive`, `install.sh`, the golden-run launcher,
`scripts/dev/unidrive-jfr.sh`) is rendered from `dist/launcher/*.tmpl` and reads its fixed JVM flags from
`dist/launcher/jvm-flags.txt`. Add or remove a flag there, nowhere else (unidrive-windows mirrors the list in
`EngineHost.JvmFlags`; change both together).

`unidrive --locale=xx_YY ...` (or `UNIDRIVE_LOCALE=xx_YY`) passes `-Duser.language=xx -Duser.country=YY` to the JVM and is
consumed by the launcher; `--locale=xx` sets only the language. The command line beats the environment; a malformed value
exits with 2.
