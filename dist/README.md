# `dist/` — end-user installer

One-shot installer for UniDrive on Linux. A thin bash wrapper around the fat
shadow JAR produced by `./gradlew :app:cli:shadowJar`.

## What it does

`install.sh` drops the built fat JAR into `~/.local/lib/unidrive/`, generates
a wrapper script at `~/.local/bin/unidrive`, copies the systemd-user unit,
and runs `systemctl --user daemon-reload`. It does **not** enable or start
the service — that step is yours.

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

## Per-profile units

Three user units are installed (none is enabled for you):

| Unit | Runs |
|---|---|
| `unidrive.service` | `unidrive autostart` for the default profile |
| `unidrive@<profile>.service` | `unidrive -p <profile> autostart` |
| `unidrive-mount@<profile>.service` | `unidrive -p <profile> mount $UNIDRIVE_MOUNTPOINT` |

```bash
systemctl --user enable --now unidrive@work.service
systemctl --user enable --now unidrive-mount@work.service
```

The mount unit starts after `unidrive@<profile>.service` and mounts at `~/unidrive/<profile>`. To mount
elsewhere, put the path in `~/.config/unidrive/mount-<profile>.env`:

```
UNIDRIVE_MOUNTPOINT=/home/me/Drive
```

Before each start and after each stop it runs `fusermount3 -uz` on the mount point, so a crashed mount does
not block the next one. That detaches whatever is mounted there, so do not point two mounts (or a mount you
started by hand) at the same directory. The JVM exits 143 on a stop and 78 for a permanent refusal (a wrong-mode
or modeless profile, or no `unidrive-mount` binary installed); the units count both as a clean stop instead of
restarting. `fusermount3` is expected at `/usr/bin/fusermount3` (FUSE 3); a system with only FUSE 2's
`fusermount` needs the unit edited.

If the profile's credential vault needs a passphrase, put `UNIDRIVE_VAULT_PASS=...` in
`~/.config/unidrive/vault-env` (read by the sync units if it exists) and keep that file mode `0600`.

The sync units run with `NoNewPrivileges`, a seccomp-based restriction set, and the address families the
daemon needs (Unix sockets, IPv4/IPv6, netlink). They deliberately leave the filesystem unrestricted: the
sync root, hydration cache and mount point can be anywhere under your home or elsewhere. The mount unit
carries no sandboxing at all, because mounting goes through the setuid `fusermount3`, which
`NoNewPrivileges` blocks.

## Usage — released artefact

```bash
bash dist/install.sh ~/Downloads/unidrive-<ver>.jar
```

## Uninstall

```bash
bash dist/uninstall.sh
```

Removes the binary, wrapper, JAR, and systemd units (stopping and disabling any running instances). Keeps `~/.config/unidrive/`
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
