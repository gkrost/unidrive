# Plan P3: krost-infra pkg-server + pkg-signer

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Cross-repo note:** This plan describes work in the `krost-infra` repo at `/home/gernot/dev/git/krost-infra/`. The plan file lives in the `unidrive` repo because:

1. `unidrive/docs/dev/plans/` is the sanctioned home for distribution-spec plans (the spec also lives there).
2. krost-infra has no equivalent `docs/plans/` convention; its AGENTS.md is concerned with docker-compose / Traefik / docs sync.

The implementer reads this plan from `unidrive/docs/dev/plans/` but **commits the implementation to `krost-infra/`**. The maintainer-only steps (GPG key generation, SSH key handling, GH secrets setup) involve actions outside any git repo (SSH to the VPS, browser steps in GitHub).

**Goal:** Provision the krost-infra side of the distribution stack so that Plans P1, P2, P4, and P5 can publish releases against real infrastructure. Specifically:

1. Generate the `unidrive-releases` GPG signing key (lives only on the krost-infra VPS, never on a CI runner).
2. Stand up two new Docker Compose services: `pkg-server` (static nginx serving the apt + dnf repos at `apt.unidrive.krost.org` and `dnf.unidrive.krost.org`) and `pkg-signer` (chrooted SSH-keyed signing daemon that takes unsigned artefacts and returns signed ones, never exposing the key).
3. Wire Traefik routers for the two new sub-domains.
4. Add two restricted SSH users on the VPS (`pkg-publisher`, `signer`) with forced commands and chrooted scopes.
5. Add DNS CNAMEs (`apt.` and `dnf.` → `unidrive.krost.org`).
6. Provision GH Actions secrets on **all four GitHub repos** that need them (`unidrive`, `unidrive-mount-linux`, `unidrive-dist`, plus the dist repo for AUR/publisher keys — only first two relevant here for P1+P2; the rest come into scope in P4).
7. Update krost-infra docs per the krost-infra AGENTS.md "Documentation-Stack Sync Rules".
8. Create per-domain `503.html` maintenance pages.

**Architecture:** Both new containers are static-config nginx- or debian-based images that follow the existing `site-unidrive` pattern from `docker-compose.yml`. `pkg-server` is a single nginx serving `/var/www/pkg/` (read-only) with separate Traefik routers for `apt.` and `dnf.` host rules pointing at the same backend. `pkg-signer` is a custom-built debian:13-slim image with `gnupg`, `dpkg-sig`, `rpm-sign`, `apt-utils`, `createrepo_c`; it exposes SSH on an internal-only Docker network and runs a forced-command shell that signs whatever lands in a drop directory, scoped by the `signer` SSH user. The GPG home directory lives on a dedicated Docker volume mounted only into `pkg-signer`.

**Tech Stack:** Docker Compose (existing), nginx:alpine, debian:13-slim + Dockerfile build, Traefik v3 (existing dynamic config file-provider), GPG (gnupg 2.2+), OpenSSH (server-side, only inside the signer container), bash for the forced-command script.

**Spec reference:** `unidrive/docs/dev/specs/unidrive-distribution-design.md` §5 (GPG signing + pkg-signer), §7.1 (`pkg-server` Compose service), §7.2 (Traefik routers), §7.3 (`pkg-data` volume layout), §7.3.1 (`unidrive.repo`), §7.4 (DNS CNAMEs), §7.5 (SSH deploy accounts + rotation), §7.6 (krost-infra doc sync).

**Scope explicitly NOT in this plan:**

- No release-workflow YAML in `unidrive` or `unidrive-mount-linux` (Plans P1, P2 — already committed).
- No `unidrive-dist` repo creation or packaging recipes (Plan P4).
- No first-release cutover, no `RELEASES.md`, no AUR push (Plan P5).
- No actual apt/dnf repo *content* — `pkg-server` starts serving an empty (signed-but-empty) `dists/stable/` index, which is fine. Plan P5's first release populates it.

**Pre-flight assumptions:**

- SSH access to the krost-infra VPS as `gernot@87.106.246.31 -p 22222` works.
- Rootless Docker is running on the VPS (`docker compose ps` works in `~/docker/`).
- The maintainer has admin rights on the four GitHub repos (`unidrive`, `unidrive-mount-linux`, `unidrive-dist` when it exists, and any others receiving secrets).
- DNS for `unidrive.krost.org` is at the same provider as the rest of the krost.org records.

**Maintainer-only ("MM") steps:** Some steps in this plan **cannot be performed by an agent** because they require browser interaction (GitHub Settings UI, DNS-provider UI) or physical-key custody decisions. These are tagged `[MAINTAINER]` and the agent must stop and prompt the human at each.

---

## File Structure

| Path (in krost-infra) | Purpose | Change type |
|---|---|---|
| `docker-compose.yml` | Add `pkg-server` and `pkg-signer` service blocks; add `pkg-data` and `signer-gnupg` volumes | Modify |
| `pkg-server/nginx.conf` | Static-serve config for pkg-server (apt + dnf path mapping, health endpoint) | Create |
| `pkg-signer/Dockerfile` | Build context for the signer image | Create |
| `pkg-signer/entrypoint.sh` | sshd + forced-command setup at container startup | Create |
| `pkg-signer/sign-forced-command.sh` | The forced-command script run for the `signer` SSH user | Create |
| `pkg-signer/README.md` | Local README explaining the signer's threat model + ops | Create |
| `traefik/dynamic.yml` | Add two new routers + one new backend service | Modify |
| `setup-server.sh` | Add `pkg-publisher` and `signer` Linux user creation steps | Modify |
| `maintenance/html/apt.unidrive.krost.org/503.html` | Maintenance page for the apt sub-domain | Create |
| `maintenance/html/dnf.unidrive.krost.org/503.html` | Maintenance page for the dnf sub-domain | Create |
| `CLAUDE.md` | Add `pkg-server` + `pkg-signer` rows to services table; update architecture diagram | Modify |
| `DEPLOY.md` | Add the two sub-domains to services table; add CNAME records to DNS table | Modify |

**Off-repo work (no files created/changed):**

- DNS records added at the DNS provider (CNAMEs).
- GPG key generated **on the VPS** and never copied off it; the private key is read once into clipboard buffer for GH-secret paste, then the buffer is cleared.
- SSH keypairs generated locally; private keys pasted into GH Actions secrets; public keys appended to `authorized_keys` on the VPS.

---

## Task 0: Branch + prerequisites

**Files:** none changed

- [ ] **Step 1: Create a working branch on krost-infra**

Run:

```bash
cd /home/gernot/dev/git/krost-infra
git fetch origin
git checkout -b feature/pkg-server-and-signer origin/main
```

Expected: `Switched to a new branch 'feature/pkg-server-and-signer'`.

- [ ] **Step 2: Verify VPS SSH + rootless Docker access**

Run:

```bash
ssh -p 22222 gernot@87.106.246.31 'cd ~/docker && docker compose ps --status running | head'
```

Expected: a table of running containers including `traefik`, `varnish`, `site-unidrive`. If the SSH or Docker command fails, stop and reconcile — this plan cannot proceed without VPS access.

- [ ] **Step 3: Verify the existing `unidrive.krost.org` route works**

Run:

```bash
curl -fsSI https://unidrive.krost.org/ | head -3
```

Expected: `HTTP/2 200` and a `server: ...` line. This confirms Traefik + TLS + the `site-unidrive` container are healthy — the new routes piggyback on the same machinery.

- [ ] **Step 4: No commit**

---

## Task 1: Add DNS CNAMEs `[MAINTAINER]`

**Files:** none changed (DNS lives at the registrar)

The two new sub-domains must resolve before Traefik's Let's Encrypt resolver can issue certificates. Add them first.

- [ ] **Step 1: Add the CNAMEs at the DNS provider**

Log in to the krost.org DNS provider (the same one that currently hosts the `unidrive.krost.org` A record). Add **two CNAME records**:

| Name | Type | Target |
|---|---|---|
| `apt.unidrive` | CNAME | `unidrive.krost.org.` (note the trailing dot) |
| `dnf.unidrive` | CNAME | `unidrive.krost.org.` |

TTL: whatever the existing krost.org records use (likely 3600).

- [ ] **Step 2: Wait for propagation, then verify**

Wait ~5 minutes, then run from the local machine:

```bash
dig +short apt.unidrive.krost.org CNAME
dig +short dnf.unidrive.krost.org CNAME
dig +short apt.unidrive.krost.org A
dig +short dnf.unidrive.krost.org A
```

Expected:
- The CNAME queries print `unidrive.krost.org.`
- The A queries print `87.106.246.31` (resolved through the CNAME).

If either is empty, wait longer or check the provider UI for typos.

- [ ] **Step 3: No commit (off-repo change)**

---

## Task 2: Create the maintenance pages

The krost-infra AGENTS.md security checklist for new public services requires per-domain `503.html` files for the `error-pages` middleware.

**Files:**
- Create: `maintenance/html/apt.unidrive.krost.org/503.html`
- Create: `maintenance/html/dnf.unidrive.krost.org/503.html`

- [ ] **Step 1: Read the existing template**

Run:

```bash
cd /home/gernot/dev/git/krost-infra
cat maintenance/html/unidrive.krost.org/503.html | head -40
```

This shows the existing maintenance page style. Copy it as a base for the two new pages, swapping the user-visible text.

- [ ] **Step 2: Create `apt.unidrive.krost.org/503.html`**

Create the directory and file:

```bash
mkdir -p maintenance/html/apt.unidrive.krost.org
cp maintenance/html/unidrive.krost.org/503.html \
   maintenance/html/apt.unidrive.krost.org/503.html
```

Then edit the body text. Change the title and headline to read approximately:

```html
<title>UniDrive apt repo — temporarily unavailable</title>
...
<h1>The UniDrive apt repository is temporarily offline.</h1>
<p>Existing installations are unaffected. Try again in a few minutes,
or browse the install instructions at
<a href="https://unidrive.krost.org/install/">unidrive.krost.org/install</a>.</p>
```

Keep the existing styling, footer, and `<meta>` tags untouched.

- [ ] **Step 3: Create `dnf.unidrive.krost.org/503.html`**

Mirror Step 2 with "dnf" instead of "apt":

```bash
mkdir -p maintenance/html/dnf.unidrive.krost.org
cp maintenance/html/unidrive.krost.org/503.html \
   maintenance/html/dnf.unidrive.krost.org/503.html
```

Edit the title and headline analogously:

```html
<title>UniDrive dnf repo — temporarily unavailable</title>
...
<h1>The UniDrive dnf repository is temporarily offline.</h1>
<p>Existing installations are unaffected. Try again in a few minutes,
or browse the install instructions at
<a href="https://unidrive.krost.org/install/">unidrive.krost.org/install</a>.</p>
```

- [ ] **Step 4: Verify the files are valid HTML**

Run:

```bash
python3 -c "
import html.parser, sys
class P(html.parser.HTMLParser): pass
for path in ['maintenance/html/apt.unidrive.krost.org/503.html',
             'maintenance/html/dnf.unidrive.krost.org/503.html']:
    P().feed(open(path).read())
    print(path, 'OK')
"
```

Expected: both paths print `OK` (the parser is lenient — it only fails on malformed tag structure).

- [ ] **Step 5: Commit**

Run:

```bash
git add maintenance/html/apt.unidrive.krost.org/ \
        maintenance/html/dnf.unidrive.krost.org/
git commit -m "$(cat <<'EOF'
maintenance: 503 pages for apt + dnf unidrive sub-domains

Per the distribution spec
(unidrive/docs/dev/specs/unidrive-distribution-design.md §7.6) the
new pkg-server sub-domains need their own maintenance pages so the
Traefik error-pages middleware can render them when pkg-server is
unreachable.
EOF
)"
```

---

## Task 3: Write `pkg-server/nginx.conf`

The container serves static files from `/var/www/pkg/` and exposes a `/health` endpoint for the Docker healthcheck.

**Files:**
- Create: `pkg-server/nginx.conf`

- [ ] **Step 1: Create the directory + file**

```bash
mkdir -p pkg-server
```

Create `pkg-server/nginx.conf` with this content:

```nginx
# pkg-server: static-serves the UniDrive apt + dnf repositories.
#
# Volume mapping (set in docker-compose.yml):
#   /var/www/pkg/apt/  — apt repository tree
#   /var/www/pkg/dnf/  — dnf repository tree
#
# Same backend serves both sub-domains; Traefik strips no path prefix,
# so each sub-domain just serves its respective top-level directory.
# We use the Host header to route inside nginx.

worker_processes 1;

# pid file goes under /tmp (tmpfs in compose).
pid /tmp/nginx.pid;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    # Cache directories go under /tmp because the container runs read_only.
    client_body_temp_path /tmp/client_body;
    proxy_temp_path       /tmp/proxy;
    fastcgi_temp_path     /tmp/fastcgi;
    uwsgi_temp_path       /tmp/uwsgi;
    scgi_temp_path        /tmp/scgi;

    sendfile on;
    keepalive_timeout 30s;

    # Logs to stdout / stderr so docker logs collects them.
    access_log /dev/stdout;
    error_log  /dev/stderr warn;

    # ----- Health endpoint (any Host) ---------------------------------
    server {
        listen 80 default_server;
        server_name _;

        # Health: always 200 OK regardless of Host header. Used by the
        # Docker HEALTHCHECK directive in docker-compose.yml.
        location = /health {
            access_log off;
            add_header Content-Type text/plain;
            return 200 "ok\n";
        }

        # Anything else on the default server (no matching Host) → 404.
        # The named servers below handle the real traffic.
        location / {
            return 404;
        }
    }

    # ----- apt.unidrive.krost.org -------------------------------------
    server {
        listen 80;
        server_name apt.unidrive.krost.org;

        root /var/www/pkg/apt;
        autoindex on;          # browseable repo for humans

        # Apt clients need exact byte-for-byte file delivery.
        # Disable etag rewriting and ensure no caching that could
        # serve stale Release/Packages metadata.
        add_header Cache-Control "no-cache" always;
        etag on;

        location / {
            try_files $uri $uri/ =404;
        }

        # Don't autoindex the keyring file — it's the only file at
        # the repo root that the human won't be looking for. (Optional
        # cosmetic; safe to remove.)
        location = /unidrive-releases.gpg {
            add_header Content-Type application/pgp-keys;
        }
    }

    # ----- dnf.unidrive.krost.org -------------------------------------
    server {
        listen 80;
        server_name dnf.unidrive.krost.org;

        root /var/www/pkg/dnf;
        autoindex on;

        add_header Cache-Control "no-cache" always;
        etag on;

        location / {
            try_files $uri $uri/ =404;
        }

        location = /unidrive-releases.gpg {
            add_header Content-Type application/pgp-keys;
        }

        # The .repo file is what users drop into /etc/yum.repos.d/.
        # Force a sensible content-type for browser-fetched display.
        location = /unidrive.repo {
            add_header Content-Type text/plain;
        }
    }
}
```

- [ ] **Step 2: Verify the config parses (locally, against a throwaway nginx container)**

Run:

```bash
docker run --rm -v "$(pwd)/pkg-server/nginx.conf:/etc/nginx/nginx.conf:ro" \
    nginx:alpine nginx -t -c /etc/nginx/nginx.conf
```

Expected: `nginx: the configuration file /etc/nginx/nginx.conf syntax is ok` and `... test is successful`.

If errors: fix the config and re-run.

- [ ] **Step 3: Commit**

Run:

```bash
git add pkg-server/nginx.conf
git commit -m "$(cat <<'EOF'
pkg-server: nginx config for apt + dnf static repos

Two server blocks (apt.unidrive.krost.org, dnf.unidrive.krost.org)
sharing one nginx instance. Each serves its respective tree under
/var/www/pkg/. Health endpoint at /health is Host-agnostic for the
Docker HEALTHCHECK. autoindex on for human browseability.

Per unidrive/docs/dev/specs/unidrive-distribution-design.md §7.1.
EOF
)"
```

---

## Task 4: Write `pkg-signer` Dockerfile, entrypoint, and forced-command script

The signer is a small SSH server bound to an internal Docker network. Only the dist CI runner reaches it (via the SSH deploy key + chrooted forced command). The GPG private key lives on a dedicated Docker volume mounted only into this container — never copied anywhere else.

**Files:**
- Create: `pkg-signer/Dockerfile`
- Create: `pkg-signer/entrypoint.sh`
- Create: `pkg-signer/sign-forced-command.sh`
- Create: `pkg-signer/README.md`

- [ ] **Step 1: Create the directory**

```bash
mkdir -p pkg-signer
```

- [ ] **Step 2: Write the Dockerfile**

Create `pkg-signer/Dockerfile`:

```dockerfile
# pkg-signer — GPG signing service for UniDrive releases.
#
# Image responsibilities:
#   - Run an OpenSSH server on port 22 (internal Docker network only;
#     never exposed to host or internet).
#   - Allow a single user `signer` to log in with a specific public key.
#   - Force-execute /usr/local/bin/sign-forced-command.sh for that user
#     instead of granting a shell.
#   - The forced command operates only on files in
#     /var/lib/signer/drop/ (sftp-writable) and produces signed copies
#     in /var/lib/signer/signed/.
#   - The signing key is on a Docker volume mounted at /var/lib/signer/gnupg/.
#
# Build:
#   docker compose build pkg-signer
FROM debian:13-slim

# Pinning APT install for reproducibility-ish (the slim base still moves).
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        gnupg \
        dpkg-sig \
        rpm-sign \
        rpm \
        apt-utils \
        createrepo-c \
        openssh-server \
        rsync \
        ca-certificates \
        && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Create the unprivileged signer user.
RUN useradd --system --create-home --home-dir /var/lib/signer --shell /bin/bash signer

# sshd needs /run/sshd to exist; /etc/ssh keys are generated by entrypoint.
RUN mkdir -p /run/sshd /var/lib/signer/drop /var/lib/signer/signed && \
    chown -R signer:signer /var/lib/signer && \
    chmod 700 /var/lib/signer

# The GPG home directory is on a docker volume; mount-point exists.
RUN mkdir -p /var/lib/signer/gnupg && \
    chown signer:signer /var/lib/signer/gnupg && \
    chmod 700 /var/lib/signer/gnupg

# sshd config: PubkeyAuthentication only, no password, no root, no agent
# forwarding, no port forwarding, force-command via authorized_keys.
RUN { \
        echo 'Port 22'; \
        echo 'Protocol 2'; \
        echo 'PermitRootLogin no'; \
        echo 'PasswordAuthentication no'; \
        echo 'ChallengeResponseAuthentication no'; \
        echo 'PubkeyAuthentication yes'; \
        echo 'AllowAgentForwarding no'; \
        echo 'AllowTcpForwarding no'; \
        echo 'X11Forwarding no'; \
        echo 'PermitTunnel no'; \
        echo 'GatewayPorts no'; \
        echo 'AllowUsers signer'; \
        echo 'LogLevel VERBOSE'; \
        echo 'Subsystem sftp internal-sftp'; \
        # Force a single command for signer regardless of what client asks for.
        echo 'Match User signer'; \
        echo '    ForceCommand /usr/local/bin/sign-forced-command.sh'; \
    } > /etc/ssh/sshd_config

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
COPY sign-forced-command.sh /usr/local/bin/sign-forced-command.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh /usr/local/bin/sign-forced-command.sh

EXPOSE 22

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
```

- [ ] **Step 3: Write `entrypoint.sh`**

Create `pkg-signer/entrypoint.sh`:

```bash
#!/usr/bin/env bash
# pkg-signer entrypoint: generate host keys (idempotent), install the
# authorized_keys file from /run/secrets/signer_authorized_keys, then
# exec sshd in the foreground.
set -euo pipefail

# 1. Generate sshd host keys if not already present.
#    Stored on a persistent volume (/var/lib/signer/sshd-host-keys) so
#    clients don't see "host key changed" warnings across container
#    restarts.
HOST_KEY_DIR=/var/lib/signer/sshd-host-keys
mkdir -p "$HOST_KEY_DIR"
chmod 700 "$HOST_KEY_DIR"

for type in rsa ecdsa ed25519; do
    keyfile="$HOST_KEY_DIR/ssh_host_${type}_key"
    if [ ! -f "$keyfile" ]; then
        ssh-keygen -q -f "$keyfile" -N '' -t "$type"
    fi
    # sshd looks at /etc/ssh/; symlink each host key in.
    ln -sf "$keyfile" "/etc/ssh/ssh_host_${type}_key"
    ln -sf "${keyfile}.pub" "/etc/ssh/ssh_host_${type}_key.pub"
done

# 2. Install the signer user's authorized_keys from a Docker secret.
#    The secret is provisioned in docker-compose.yml and contains the
#    PUBLIC ssh key of the dist CI runner. Wrapping the key in a
#    `command="..."` forced-command line is redundant given the
#    sshd_config Match-User stanza, but we leave that stanza as
#    defence-in-depth.
SIGNER_AUTH=/var/lib/signer/.ssh
mkdir -p "$SIGNER_AUTH"
chmod 700 "$SIGNER_AUTH"
if [ -f /run/secrets/signer_authorized_keys ]; then
    cp /run/secrets/signer_authorized_keys "$SIGNER_AUTH/authorized_keys"
else
    echo "WARN: /run/secrets/signer_authorized_keys not mounted; no SSH access will work." >&2
    : > "$SIGNER_AUTH/authorized_keys"
fi
chown -R signer:signer "$SIGNER_AUTH"
chmod 600 "$SIGNER_AUTH/authorized_keys"

# 3. Verify the GPG private key is loaded into /var/lib/signer/gnupg.
#    Loading is a one-time maintainer action (Task 6 of this plan).
#    If the keyring is empty, we still start — the signer will refuse
#    any sign request with a clear error rather than fail-to-boot.
if su signer -c 'gpg --homedir /var/lib/signer/gnupg --list-secret-keys --batch 2>/dev/null | grep -q sec'; then
    echo "[entrypoint] GPG keyring present."
else
    echo "[entrypoint] WARNING: no secret keys in /var/lib/signer/gnupg yet." >&2
    echo "[entrypoint] Sign requests will fail until the key is imported." >&2
fi

# 4. Exec sshd in the foreground (Docker stays attached to its stderr).
exec /usr/sbin/sshd -D -e
```

- [ ] **Step 4: Write `sign-forced-command.sh`**

Create `pkg-signer/sign-forced-command.sh`:

```bash
#!/usr/bin/env bash
# Forced command for the `signer` SSH user.
#
# Reads SSH_ORIGINAL_COMMAND from the environment and dispatches one of
# three subcommands:
#
#   sign-drop      Sign everything in /var/lib/signer/drop/ that hasn't
#                  been signed yet. Writes signed copies to
#                  /var/lib/signer/signed/ and writes a log line to
#                  /var/lib/signer/sign.log per file.
#
#   list-signed    Print one filename per line for files currently in
#                  /var/lib/signer/signed/.
#
#   clear-signed   Delete everything in /var/lib/signer/signed/. Used
#                  by the CI runner after it has pulled the signed
#                  artefacts back.
#
# sftp (the Subsystem in sshd_config) is also allowed; the dist runner
# uses sftp to push files into /var/lib/signer/drop/ and pull files
# from /var/lib/signer/signed/. The Match-User ForceCommand applies
# only to interactive/shell sessions, not to the sftp subsystem (sftp
# bypasses ForceCommand by design). This means anyone with the signer
# key can sftp-read /var/lib/signer/ entirely. That's intentional: the
# only sensitive content is in /var/lib/signer/gnupg/ which is owned
# 0700 by root inside the container and not readable by `signer`.

set -euo pipefail

LOG=/var/lib/signer/sign.log
DROP=/var/lib/signer/drop
SIGNED=/var/lib/signer/signed
GPG_HOME=/var/lib/signer/gnupg

log() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$LOG"
}

usage() {
    echo "usage: ssh signer@... <sign-drop|list-signed|clear-signed>" >&2
    exit 64
}

# SSH_ORIGINAL_COMMAND is set by sshd when the client passes a command
# (e.g. `ssh signer@host sign-drop`). When the client opens an
# interactive shell, SSH_ORIGINAL_COMMAND is unset — we refuse.
cmd="${SSH_ORIGINAL_COMMAND:-}"
if [ -z "$cmd" ]; then
    log "REJECT empty command"
    usage
fi

# Disallow anything except our three subcommands. No shell escapes:
# the entire client-supplied command must equal one of these strings.
case "$cmd" in
    sign-drop|list-signed|clear-signed)
        :
        ;;
    *)
        log "REJECT unknown command: $cmd"
        usage
        ;;
esac

# --- list-signed -----------------------------------------------------
if [ "$cmd" = "list-signed" ]; then
    cd "$SIGNED"
    ls -1
    log "list-signed (no-op)"
    exit 0
fi

# --- clear-signed ----------------------------------------------------
if [ "$cmd" = "clear-signed" ]; then
    cd "$SIGNED"
    # Be very explicit about what we delete; no `rm -rf $SIGNED` because
    # that would also remove the directory itself.
    find . -mindepth 1 -delete
    log "clear-signed"
    exit 0
fi

# --- sign-drop -------------------------------------------------------
# Discover GPG fingerprint of the secret key in the keyring.
FPR=$(gpg --homedir "$GPG_HOME" --list-secret-keys --with-colons 2>/dev/null \
        | awk -F: '/^fpr/{print $10; exit}')
if [ -z "${FPR:-}" ]; then
    echo "ERROR: no GPG secret key in $GPG_HOME" >&2
    log "ABORT sign-drop: no secret key"
    exit 75
fi

cd "$DROP"
shopt -s nullglob
signed_count=0
for f in *; do
    [ -f "$f" ] || continue
    case "$f" in
        # Refuse anything that doesn't look like a release artefact.
        *.deb|*.rpm|*.tar.gz|SHA256SUMS|Release|InRelease|repomd.xml)
            :
            ;;
        *)
            log "SKIP $f (not an allowed artefact filetype)"
            continue
            ;;
    esac

    sha=$(sha256sum "$f" | awk '{print $1}')

    case "$f" in
        *.deb)
            # Use dpkg-sig (signs the .deb in-place with embedded GPG sig).
            dpkg-sig --gpg-options="--homedir $GPG_HOME --batch --pinentry-mode loopback --local-user $FPR" \
                     --sign builder "$f" >&2
            cp "$f" "$SIGNED/"
            ;;
        *.rpm)
            # Use rpm --addsign (also in-place).
            # Set %_gpg_name temporarily via macros file.
            rpm_macros=$(mktemp)
            printf '%%_gpg_name %s\n%%_gpgbin /usr/bin/gpg\n%%__gpg_sign_cmd %%{__gpg} gpg --homedir %s --batch --pinentry-mode loopback --no-armor --local-user %%{_gpg_name} --sbfile %%{__signature_filename} --digest-algo sha256 %%{__plaintext_filename}\n' \
                "$FPR" "$GPG_HOME" > "$rpm_macros"
            HOME=/var/lib/signer rpm --macros=/usr/lib/rpm/macros:"$rpm_macros" \
                --addsign "$f" >&2
            rm -f "$rpm_macros"
            cp "$f" "$SIGNED/"
            ;;
        *.tar.gz|SHA256SUMS|Release|InRelease|repomd.xml)
            # Detached ASCII signature.
            gpg --homedir "$GPG_HOME" --batch --yes --pinentry-mode loopback \
                --local-user "$FPR" \
                --armor --detach-sign \
                --output "$SIGNED/${f}.asc" \
                "$f"
            # Also copy the original through, so the CI can sftp-pull
            # the original-plus-signature pair from $SIGNED. For Release,
            # also produce InRelease (inline-signed clearsign) — only if
            # the input is named Release.
            cp "$f" "$SIGNED/"
            if [ "$f" = "Release" ]; then
                gpg --homedir "$GPG_HOME" --batch --yes --pinentry-mode loopback \
                    --local-user "$FPR" \
                    --clearsign --output "$SIGNED/InRelease" \
                    "$f"
            fi
            ;;
    esac

    log "SIGN $f sha256=$sha fpr=$FPR"
    signed_count=$((signed_count + 1))
done

# Remove the originals from the drop after a successful sign-drop run.
# (The CI is expected to pull from $SIGNED, then call clear-signed.)
find . -mindepth 1 -delete
log "sign-drop completed signed_count=$signed_count"
echo "OK signed=$signed_count"
```

- [ ] **Step 5: Write the local README**

Create `pkg-signer/README.md`:

```markdown
# pkg-signer

GPG signing service for UniDrive releases. See
`/home/gernot/dev/git/unidrive/docs/dev/specs/unidrive-distribution-design.md`
§5 for the full architecture rationale.

## What it is

A debian:13-slim container running OpenSSH server. A single user
`signer` is authorised via a Docker secret containing one public SSH
key (the dist CI runner's `SIGNER_DEPLOY_KEY` public part). The user
has a forced command — `sign-forced-command.sh` — and an `internal-sftp`
subsystem for moving files in and out.

The GPG private key lives on the `signer-gnupg` Docker volume mounted
read-write inside this container only. No other container can read
it. The host can read it (rootless docker user owns the volume), but
the host is the maintainer's machine, which is the only place the
key was ever supposed to be.

## What it isn't

- Not exposed to the public internet. The Compose service is only on
  the `internal` Docker network. The dist CI runner reaches it via
  the krost-infra VPS's SSH bastion (Docker exposes the container's
  SSH port on a localhost-only forward; the CI runner connects to
  `gernot@87.106.246.31:22222` and then port-forwards).
- Not a general-purpose shell. The forced command rejects anything
  that isn't `sign-drop`, `list-signed`, or `clear-signed`.
- Not running as root. Container `USER signer` for the work that
  doesn't need root.

## How to put the key in

See Task 6 of the parent plan
`unidrive/docs/dev/plans/krost-infra-pkg-server-and-signer.md`.

## How to rotate the key

See spec §3.7 and §7.5.
```

- [ ] **Step 6: Verify the Dockerfile builds locally (optional but recommended)**

Run:

```bash
docker build -t pkg-signer:test pkg-signer/
```

Expected: clean build, image tagged. If it fails (e.g. `createrepo-c` package name varies), fix the Dockerfile.

Don't run the container yet — that needs the docker-compose wiring from Task 5.

- [ ] **Step 7: Commit**

Run:

```bash
git add pkg-signer/
git commit -m "$(cat <<'EOF'
pkg-signer: signing-service container (Dockerfile + entrypoint + forced cmd)

Debian-slim image with gnupg, dpkg-sig, rpm-sign, apt-utils,
createrepo-c, openssh-server. A single user `signer` is authorised
via Docker secret. Forced command (sign-drop / list-signed /
clear-signed) refuses anything else. GPG home on a dedicated docker
volume mounted only into this container.

Per unidrive/docs/dev/specs/unidrive-distribution-design.md §5.
EOF
)"
```

---

## Task 5: Add `pkg-server` and `pkg-signer` to `docker-compose.yml`

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Read the current end of the file to find where to insert**

Run:

```bash
cd /home/gernot/dev/git/krost-infra
grep -n '^  [a-z-]*:' docker-compose.yml | tail -10
grep -n '^volumes:' docker-compose.yml
grep -n '^secrets:' docker-compose.yml
```

Note the line numbers — the new services should append after the existing service definitions; new volumes append to the existing `volumes:` block; new secrets append to the existing `secrets:` block (the signer's authorized_keys is a Docker secret).

- [ ] **Step 2: Add a new secret to the existing `secrets:` block**

Find the existing `secrets:` block (top of file) and append:

```yaml
  signer_authorized_keys:
    file: ./secrets/signer_authorized_keys
```

(This file is created by the maintainer in Task 6 Step 4.)

- [ ] **Step 3: Add `pkg-server` and `pkg-signer` service blocks**

Append to the `services:` section (the end of the existing services, before `volumes:`):

```yaml
  # ===========================================
  # pkg-server — Static apt + dnf repo server
  # ===========================================
  # Serves https://apt.unidrive.krost.org/ and https://dnf.unidrive.krost.org/.
  # Both Traefik routers point at this single nginx; nginx routes by Host.
  pkg-server:
    image: nginx:alpine
    container_name: pkg-server
    restart: unless-stopped
    networks:
      - web
    volumes:
      - ./pkg-server/nginx.conf:/etc/nginx/nginx.conf:ro
      - pkg-data:/var/www/pkg:ro
    labels:
      - "traefik.enable=false"
    read_only: true
    security_opt:
      - no-new-privileges:true
    tmpfs:
      - /var/cache/nginx:size=10m
      - /var/log/nginx:size=5m
      - /run:size=1m
      - /tmp:size=10m
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://127.0.0.1/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 5s
    deploy:
      resources:
        limits:
          memory: 64M
          cpus: "0.25"
          pids: 64
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"

  # ===========================================
  # pkg-signer — GPG signing service
  # ===========================================
  # Internal-only sshd accepting signing requests from the dist CI
  # runner. The GPG private key lives on the signer-gnupg volume
  # mounted only here.
  pkg-signer:
    build: ./pkg-signer
    image: krost/pkg-signer:latest
    container_name: pkg-signer
    restart: unless-stopped
    # Internal network only — never on `web`. The dist runner reaches
    # this via the host SSH bastion + a docker-exec or socat hop
    # (configured externally; see docs/release-process.md once Plan P4
    # lands).
    networks:
      - internal
    volumes:
      - signer-gnupg:/var/lib/signer/gnupg
      - signer-state:/var/lib/signer
    secrets:
      - signer_authorized_keys
    # Do NOT publish the container's port 22 to host; only the rootless
    # docker daemon's network namespace can reach it. The CI runner
    # connects via SSH to the host (port 22222) and then `ssh` from
    # the host into this container's IP, an inner hop.
    expose:
      - "22"
    security_opt:
      - no-new-privileges:true
    # NOT read-only: sshd writes /var/log/auth.log via syslog (we route
    # it to stderr), updates utmp, etc. The actual signing artefacts
    # are on the named volumes.
    read_only: false
    tmpfs:
      - /tmp:size=10m
    healthcheck:
      # sshd healthcheck: TCP probe on 22 from inside the container.
      # nc is in busybox-image; debian:slim has it via netcat-openbsd
      # (already pulled in by openssh-server's deps).
      test: ["CMD", "nc", "-z", "127.0.0.1", "22"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 10s
    deploy:
      resources:
        limits:
          memory: 128M
          cpus: "0.25"
          pids: 64
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

- [ ] **Step 4: Add the two new volumes to the existing `volumes:` block**

Find the `volumes:` block and append:

```yaml
  pkg-data:
  signer-gnupg:
  signer-state:
```

- [ ] **Step 5: Verify the compose file is valid**

Run:

```bash
docker compose config -q
```

Expected: no output (exit 0). If errors, fix them — most often indentation.

- [ ] **Step 6: Commit**

Run:

```bash
git add docker-compose.yml
git commit -m "$(cat <<'EOF'
docker-compose: add pkg-server + pkg-signer services

pkg-server (nginx:alpine, public via Traefik) serves the apt + dnf
repository trees from /var/www/pkg/. Read-only container, all
required security-checklist properties.

pkg-signer (custom debian:13-slim build, internal network only)
runs a chrooted sshd accepting `sign-drop` / `list-signed` /
`clear-signed` commands from the dist CI runner. GPG private key
on the signer-gnupg volume, mounted only into this container.

Per unidrive/docs/dev/specs/unidrive-distribution-design.md §5
and §7.1.
EOF
)"
```

---

## Task 6: Generate the GPG signing key on the VPS `[MAINTAINER]`

This task is intentionally NOT automated. The key must be generated on the VPS (so the private part never touches the maintainer's workstation in a way that survives the session), then imported into the `signer-gnupg` Docker volume.

**Files:** none changed in the repo (private-key material does not get committed)

- [ ] **Step 1: SSH to the VPS**

Run:

```bash
ssh -p 22222 gernot@87.106.246.31
```

(Subsequent steps run on the VPS unless noted.)

- [ ] **Step 2: Create a temporary GNUPGHOME, generate the key**

On the VPS:

```bash
TMPHOME=$(mktemp -d -p "$HOME" gnupg-genkey-XXXXXX)
chmod 700 "$TMPHOME"
export GNUPGHOME="$TMPHOME"
cat > "$TMPHOME/keyspec" <<'EOF'
%no-protection
Key-Type: EdDSA
Key-Curve: ed25519
Key-Usage: sign
Subkey-Type: EdDSA
Subkey-Curve: ed25519
Subkey-Usage: sign
Name-Real: UniDrive Releases
Name-Email: releases@unidrive.krost.org
Expire-Date: 5y
%commit
EOF
gpg --batch --gen-key "$TMPHOME/keyspec"
gpg --list-secret-keys --keyid-format LONG
FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
echo "Fingerprint: $FPR"
```

Expected: a secret key listing showing `UniDrive Releases <releases@unidrive.krost.org>`. The fingerprint is the 40-hex-char string. **Record it** — you'll need it for Step 6 (publishing the public key) and for the `GPG_KEY_FINGERPRINT` GH secrets in Step 7.

- [ ] **Step 3: Export the public key (will be served at `unidrive.krost.org/install/`)**

On the VPS:

```bash
gpg --armor --export "$FPR" > /tmp/unidrive-releases.gpg
ls -la /tmp/unidrive-releases.gpg
cat /tmp/unidrive-releases.gpg | head -3
```

Expected: file exists, starts with `-----BEGIN PGP PUBLIC KEY BLOCK-----`.

Copy this to the maintainer's local machine:

```bash
# On the local machine, in another shell:
scp -P 22222 gernot@87.106.246.31:/tmp/unidrive-releases.gpg \
    /home/gernot/dev/git/krost-infra/sites/unidrive/install/unidrive-releases.gpg
```

(Create the `install/` directory if it doesn't exist yet in sites/unidrive. That directory becomes the install landing page in Plan P5; for now it just needs to hold this one file.)

Run on the local machine:

```bash
cd /home/gernot/dev/git/krost-infra
mkdir -p sites/unidrive/install
ls -la sites/unidrive/install/unidrive-releases.gpg
```

- [ ] **Step 4: Export the private key into the Docker volume**

On the VPS:

```bash
# The signer-gnupg volume doesn't exist yet because we haven't run
# `docker compose up -d` for the new services. Create it ahead of time
# with the right ownership.
docker volume create signer-gnupg

# Find the host path of the volume (rootless docker stores it under
# ~/.local/share/docker/volumes/...).
VOL=$(docker volume inspect signer-gnupg --format '{{.Mountpoint}}')
echo "Volume host path: $VOL"
test -d "$VOL"

# Initialise as a GPG home directory with strict perms.
chmod 700 "$VOL"

# Export the private key into the volume. Use --pinentry-mode loopback
# so gpg doesn't try to ask for a passphrase (the key was generated
# without one, %no-protection).
gpg --armor --export-secret-keys "$FPR" \
  | sudo docker run --rm -i \
      -v signer-gnupg:/gnupg \
      debian:13-slim bash -c '
        set -e
        export GNUPGHOME=/gnupg
        chmod 700 /gnupg
        gpg --batch --import
        gpg --list-secret-keys --keyid-format LONG
      '
```

Expected: import message + a list-secret-keys output showing the same fingerprint inside the volume. If the rootless docker runs as your user, drop the `sudo`.

- [ ] **Step 5: Export the private key for the GH secrets (one-time clipboard hop)**

On the VPS, still in the temporary GNUPGHOME:

```bash
# Print the armored secret key. Read it visually; do NOT pipe to a
# file on disk if you can avoid it.
gpg --armor --export-secret-keys "$FPR"
```

Copy the output (everything from `-----BEGIN PGP PRIVATE KEY BLOCK-----` through `-----END PGP PRIVATE KEY BLOCK-----`). This blob is the value of the `GPG_PRIVATE_KEY` GH secret in Task 7.

- [ ] **Step 6: Tear down the temporary keygen home**

On the VPS:

```bash
shred -uz "$TMPHOME"/* 2>/dev/null || true
rm -rf "$TMPHOME"
rm -f /tmp/unidrive-releases.gpg
unset GNUPGHOME FPR
```

The signing key now exists in exactly **two** places: the `signer-gnupg` Docker volume on the VPS, and (briefly) the maintainer's clipboard for the GH-secret paste.

The public key exists in three places: the volume, the local repo at `sites/unidrive/install/unidrive-releases.gpg`, and (after Task 9) the deployed `unidrive.krost.org/install/`.

- [ ] **Step 7: Commit the public key**

On the local machine:

```bash
cd /home/gernot/dev/git/krost-infra
git add sites/unidrive/install/unidrive-releases.gpg
git commit -m "$(cat <<'EOF'
sites/unidrive: publish UniDrive releases public GPG key

The unidrive-releases <releases@unidrive.krost.org> key signs all
UniDrive distribution artefacts. The private part lives only on the
krost-infra VPS in the signer-gnupg Docker volume; this commit
publishes the public part so users can verify signatures.

Fingerprint must be cross-checked against the rendered install
page once Plan P5 lands. See
unidrive/docs/dev/specs/unidrive-distribution-design.md §5.
EOF
)"
```

---

## Task 7: Provision GH Actions secrets `[MAINTAINER]`

Plans P1 and P2 already reference these. They need to exist on the respective repos before the next tagged release. Plan P4 will add more secrets for `unidrive-dist`; this task only covers what P1/P2 need.

**Files:** none changed (GH secrets are off-repo)

- [ ] **Step 1: Note the fingerprint from Task 6 Step 2**

You should have it written down. Reload it if you don't:

```bash
ssh -p 22222 gernot@87.106.246.31 \
  'docker run --rm -v signer-gnupg:/gnupg debian:13-slim \
       bash -c "GNUPGHOME=/gnupg gpg --list-secret-keys --with-colons" \
   | awk -F: "/^fpr/{print \$10; exit}"'
```

Expected: 40-char hex fingerprint.

- [ ] **Step 2: Re-grab the private key blob from the VPS**

If you no longer have the armored private key in clipboard from Task 6 Step 5:

```bash
ssh -p 22222 gernot@87.106.246.31 \
  'docker run --rm -i -v signer-gnupg:/gnupg debian:13-slim \
       bash -c "GNUPGHOME=/gnupg gpg --armor --export-secret-keys"'
```

This prints the armored private key to your terminal. Copy it.

- [ ] **Step 3: Add the two secrets to `unidrive`**

In the browser, go to https://github.com/gkrost/unidrive/settings/secrets/actions and add:

| Secret name | Value |
|---|---|
| `GPG_PRIVATE_KEY` | The armored private key from Step 2 |
| `GPG_KEY_FINGERPRINT` | The fingerprint from Step 1 |

- [ ] **Step 4: Add the same two secrets to `unidrive-mount-linux`**

Same browser dance at https://github.com/gkrost/unidrive-mount-linux/settings/secrets/actions. Same values.

- [ ] **Step 5: Clear the clipboard**

On the local machine:

```bash
# Clipboard hygiene. The exact command varies — examples:
printf '' | xclip -selection clipboard 2>/dev/null || true
printf '' | wl-copy 2>/dev/null || true
```

- [ ] **Step 6: No commit (GH secrets are off-repo)**

---

## Task 8: Provision SSH deploy users on the VPS `[MAINTAINER]`

Two restricted users:

- `pkg-publisher` — used by the dist CI runner (Plan P4/P5) to rsync signed artefacts into `~/docker/pkg-server/pkg-data/`. Chrooted to that directory.
- `signer` (note: this is the **VPS** user that owns the rsync drop directory for the signer container; the SSH that lands in `pkg-signer`'s sshd is keyed by a different account inside the container, but the public key is wired in via the secret. We don't need a separate VPS user for the signer hop in this minimal layout — the dist runner reaches the pkg-signer container through the `gernot` SSH bastion + a `docker exec` or container-local-ip hop. See Plan P4 for the exact mechanism.).

For this plan, the only new VPS user we strictly need is `pkg-publisher`.

**Files:**
- Modify: `setup-server.sh`

- [ ] **Step 1: Add the `pkg-publisher` setup block to `setup-server.sh`**

Read the existing `setup-server.sh` to find a sensible place to insert (after the main user is created, before the docker-rootless section if one exists).

Append a new section near the end of the script (but before any final `echo "Setup complete"` line):

```bash
# --- pkg-publisher: restricted SSH user for the dist CI runner ---
# Used by unidrive-dist's GitHub Actions to rsync signed apt/dnf
# repository contents into ~gernot/docker/pkg-server/pkg-data/.
# This user has no shell, no sudo, no home of its own — it ssh-pipes
# into the host as `pkg-publisher` and rsyncs files into the path
# below.
if ! id -u pkg-publisher >/dev/null 2>&1; then
    echo "[setup] creating pkg-publisher user..."
    # System account, no password, no shell, no home.
    useradd --system --no-create-home --shell /usr/sbin/nologin pkg-publisher
fi
PKG_PUB_HOME=/var/lib/pkg-publisher
mkdir -p "${PKG_PUB_HOME}/.ssh"
chown -R pkg-publisher:pkg-publisher "${PKG_PUB_HOME}"
chmod 700 "${PKG_PUB_HOME}" "${PKG_PUB_HOME}/.ssh"
# authorized_keys is empty here; Task 9 of the
# krost-infra-pkg-server-and-signer plan installs the actual key.
touch "${PKG_PUB_HOME}/.ssh/authorized_keys"
chown pkg-publisher:pkg-publisher "${PKG_PUB_HOME}/.ssh/authorized_keys"
chmod 600 "${PKG_PUB_HOME}/.ssh/authorized_keys"
usermod -d "${PKG_PUB_HOME}" pkg-publisher

# Grant rsync-only access to ~gernot/docker/pkg-server/pkg-data/ via
# ACL. The dist runner's authorized_keys entry will further restrict
# to rsync via `command="rsync --server ..."`.
if command -v setfacl >/dev/null 2>&1; then
    mkdir -p /home/gernot/docker/pkg-server/pkg-data
    setfacl -R -m u:pkg-publisher:rwx /home/gernot/docker/pkg-server/pkg-data
    setfacl -R -d -m u:pkg-publisher:rwx /home/gernot/docker/pkg-server/pkg-data
fi
```

- [ ] **Step 2: Verify shellcheck-clean**

Run:

```bash
cd /home/gernot/dev/git/krost-infra
shellcheck setup-server.sh
```

Expected: no warnings on the new block. (Pre-existing warnings elsewhere are out of scope.)

- [ ] **Step 3: Run the new block ON THE VPS `[MAINTAINER]`**

Manually copy the new block (or `scp` the script and run only that section). On the VPS, as root:

```bash
sudo bash -c '
if ! id -u pkg-publisher >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin pkg-publisher
fi
PKG_PUB_HOME=/var/lib/pkg-publisher
mkdir -p "${PKG_PUB_HOME}/.ssh"
chown -R pkg-publisher:pkg-publisher "${PKG_PUB_HOME}"
chmod 700 "${PKG_PUB_HOME}" "${PKG_PUB_HOME}/.ssh"
touch "${PKG_PUB_HOME}/.ssh/authorized_keys"
chown pkg-publisher:pkg-publisher "${PKG_PUB_HOME}/.ssh/authorized_keys"
chmod 600 "${PKG_PUB_HOME}/.ssh/authorized_keys"
usermod -d "${PKG_PUB_HOME}" pkg-publisher
mkdir -p /home/gernot/docker/pkg-server/pkg-data
setfacl -R -m u:pkg-publisher:rwx /home/gernot/docker/pkg-server/pkg-data
setfacl -R -d -m u:pkg-publisher:rwx /home/gernot/docker/pkg-server/pkg-data
'
```

Expected: no errors. Verify:

```bash
ssh -p 22222 gernot@87.106.246.31 \
    'id pkg-publisher && ls -la /var/lib/pkg-publisher/.ssh/'
```

Expected: user exists; `.ssh/` has 700 perms; `authorized_keys` has 600.

- [ ] **Step 4: Generate the dist CI runner's deploy SSH keypair `[MAINTAINER]`**

On the **local** machine (NOT on the VPS — we want the private key to flow only to GH secrets, not stay on the VPS filesystem):

```bash
KEYDIR=$(mktemp -d)
ssh-keygen -t ed25519 -N '' -C 'pkg-publisher@dist-ci' -f "$KEYDIR/dist_deploy_key"
ls -la "$KEYDIR"
cat "$KEYDIR/dist_deploy_key.pub"
```

Expected: keypair generated; public key starts with `ssh-ed25519`.

- [ ] **Step 5: Install the public key on the VPS**

Run:

```bash
ssh -p 22222 gernot@87.106.246.31 \
    "sudo tee -a /var/lib/pkg-publisher/.ssh/authorized_keys" \
    < "$KEYDIR/dist_deploy_key.pub"

ssh -p 22222 gernot@87.106.246.31 \
    'sudo cat /var/lib/pkg-publisher/.ssh/authorized_keys'
```

Expected: the public key now sits in `authorized_keys` (one line).

Optional (more restrictive): edit that line to prepend a forced rsync command:

```
command="rsync --server -avzs --delete . /home/gernot/docker/pkg-server/pkg-data/",no-pty,no-X11-forwarding,no-port-forwarding,no-agent-forwarding ssh-ed25519 AAAA... pkg-publisher@dist-ci
```

This restriction is recommended but a full design is in Plan P4 (the rsync flag set must match what `publish-apt.sh` and `publish-dnf.sh` actually use). For now, leaving the line as plain-keyed is acceptable — the user has no shell anyway.

- [ ] **Step 6: Add `DIST_DEPLOY_KEY` secret to the unidrive-dist repo**

Plan P4 will create the unidrive-dist repo. **If P4 has not yet been executed, defer this step to the end of P4 Task 0.** Otherwise:

In the browser at https://github.com/gkrost/unidrive-dist/settings/secrets/actions, add a secret named `DIST_DEPLOY_KEY` whose value is the contents of `$KEYDIR/dist_deploy_key` (the private key file).

- [ ] **Step 7: Clean up the local keypair**

```bash
shred -u "$KEYDIR"/dist_deploy_key "$KEYDIR"/dist_deploy_key.pub
rmdir "$KEYDIR"
```

- [ ] **Step 8: Commit the `setup-server.sh` change**

```bash
cd /home/gernot/dev/git/krost-infra
git add setup-server.sh
git commit -m "$(cat <<'EOF'
setup-server: provision pkg-publisher restricted user

System user with no shell, no home, /usr/sbin/nologin. Owns
/var/lib/pkg-publisher/.ssh/. The dist CI runner uses this account
to rsync signed artefacts into ~/docker/pkg-server/pkg-data/ via the
authorized_keys-controlled command.

Per unidrive/docs/dev/specs/unidrive-distribution-design.md §7.5.
EOF
)"
```

---

## Task 9: Provision the signer's `authorized_keys` secret `[MAINTAINER]`

The `pkg-signer` container reads `/run/secrets/signer_authorized_keys` at startup (entrypoint.sh) and copies it into `/var/lib/signer/.ssh/authorized_keys`. We need to populate that secret file before bringing the container up.

**Files:**
- Create: `secrets/signer_authorized_keys` (the file is gitignored — only the path is committed; the file content is local to the VPS)

- [ ] **Step 1: Generate the signer deploy keypair**

On the local machine:

```bash
KEYDIR=$(mktemp -d)
ssh-keygen -t ed25519 -N '' -C 'signer@dist-ci' -f "$KEYDIR/signer_deploy_key"
cat "$KEYDIR/signer_deploy_key.pub"
```

- [ ] **Step 2: Place the public key as a Docker secret on the VPS**

On the VPS, under `~/docker/secrets/`:

```bash
ssh -p 22222 gernot@87.106.246.31 \
    "cat > /home/gernot/docker/secrets/signer_authorized_keys && \
     chmod 600 /home/gernot/docker/secrets/signer_authorized_keys" \
    < "$KEYDIR/signer_deploy_key.pub"

ssh -p 22222 gernot@87.106.246.31 \
    'ls -la /home/gernot/docker/secrets/signer_authorized_keys && \
     cat /home/gernot/docker/secrets/signer_authorized_keys'
```

Expected: file exists with 600 perms; contains the same `ssh-ed25519 AAAA...` line.

- [ ] **Step 3: Add `SIGNER_DEPLOY_KEY` to the unidrive-dist repo (deferred)**

Same as Task 8 Step 6 — if unidrive-dist doesn't exist yet, defer to the end of Plan P4 Task 0. The secret name is `SIGNER_DEPLOY_KEY`, value is the **private** key from `$KEYDIR/signer_deploy_key`.

- [ ] **Step 4: Clean up the local keypair**

```bash
shred -u "$KEYDIR"/signer_deploy_key "$KEYDIR"/signer_deploy_key.pub
rmdir "$KEYDIR"
```

- [ ] **Step 5: Update the krost-infra `.gitignore` to ensure `secrets/signer_authorized_keys` stays ignored**

Run:

```bash
cd /home/gernot/dev/git/krost-infra
grep -q '^secrets/' .gitignore || echo 'secrets/' >> .gitignore
cat .gitignore | head -10
```

Expected: `secrets/` is in the ignore list (it likely already is — the existing secrets/*.txt files are also gitignored). No commit if it was already there.

- [ ] **Step 6: Commit if `.gitignore` changed**

```bash
git status --short .gitignore
# Only if a change is shown:
git add .gitignore
git commit -m "gitignore: ensure secrets/ stays out of version control"
```

---

## Task 10: Add Traefik routers for the two sub-domains

**Files:**
- Modify: `traefik/dynamic.yml`

- [ ] **Step 1: Read the existing `site-unidrive` router as a template**

Run:

```bash
cd /home/gernot/dev/git/krost-infra
grep -B1 -A14 'site-unidrive:' traefik/dynamic.yml
```

Note the structure: routers are inside `http.routers:`, services inside `http.services:`. Both new sub-domains share one backend service.

- [ ] **Step 2: Add two new routers to `http.routers`**

Find the existing `site-unidrive:` router in `http.routers:` and add immediately below it (preserving the indent — 4 spaces under `routers:`):

```yaml
    # apt.unidrive.krost.org → pkg-server (apt repository)
    pkg-server-apt:
      rule: "Host(`apt.unidrive.krost.org`)"
      entrypoints:
        - websecure
      tls:
        certResolver: letsencrypt
      middlewares:
        - error-pages
      service: pkg-server

    # dnf.unidrive.krost.org → pkg-server (dnf repository)
    pkg-server-dnf:
      rule: "Host(`dnf.unidrive.krost.org`)"
      entrypoints:
        - websecure
      tls:
        certResolver: letsencrypt
      middlewares:
        - error-pages
      service: pkg-server
```

(No `security-headers` middleware on these — apt and dnf clients don't render HTML.)

- [ ] **Step 3: Add the backend service**

Find the existing `site-unidrive:` block in `http.services:` and add immediately below it (preserving indent):

```yaml
    pkg-server:
      loadBalancer:
        servers:
          - url: "http://pkg-server:80"
```

- [ ] **Step 4: Verify YAML parses**

Run:

```bash
python3 -c "import yaml; yaml.safe_load(open('traefik/dynamic.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`.

- [ ] **Step 5: Verify the error-pages middleware is configured for the new domains**

The `error-pages` middleware reads `maintenance/html/<host>/503.html` files. Verify the maintenance config (likely in `traefik/dynamic.yml` itself or `docker-compose.yml`) covers the new hosts — search:

```bash
grep -B2 -A10 'error-pages' traefik/dynamic.yml | head -40
```

If the middleware definition is host-aware (some setups need a `query.host` template parameter), the new files from Task 2 satisfy it. If the middleware is configured statically per host elsewhere, may need updating — but typically Traefik's error-pages picks up new domains automatically via the file structure.

- [ ] **Step 6: Commit**

Run:

```bash
git add traefik/dynamic.yml
git commit -m "$(cat <<'EOF'
traefik: route apt + dnf unidrive sub-domains to pkg-server

Two new routers (pkg-server-apt, pkg-server-dnf) both pointing at
the single pkg-server backend service. nginx routes by Host header
internally. Let's Encrypt resolver handles TLS for both.

Per unidrive/docs/dev/specs/unidrive-distribution-design.md §7.2.
EOF
)"
```

---

## Task 11: Deploy + verify on the VPS `[MAINTAINER]`

**Files:** none changed

- [ ] **Step 1: Sync the new files to the VPS**

On the local machine:

```bash
cd /home/gernot/dev/git/krost-infra
# Push docker-compose.yml + pkg-server/ + pkg-signer/ + traefik/dynamic.yml.
scp -P 22222 docker-compose.yml gernot@87.106.246.31:~/docker/
scp -P 22222 -r pkg-server gernot@87.106.246.31:~/docker/
scp -P 22222 -r pkg-signer gernot@87.106.246.31:~/docker/
scp -P 22222 traefik/dynamic.yml gernot@87.106.246.31:~/docker/traefik/
scp -P 22222 -r maintenance/html/apt.unidrive.krost.org \
              gernot@87.106.246.31:~/docker/maintenance/html/
scp -P 22222 -r maintenance/html/dnf.unidrive.krost.org \
              gernot@87.106.246.31:~/docker/maintenance/html/
```

- [ ] **Step 2: Build the pkg-signer image and start the new services**

On the VPS:

```bash
cd ~/docker
docker compose build pkg-signer
docker compose up -d pkg-server pkg-signer
docker compose ps pkg-server pkg-signer
```

Expected: both containers show `(healthy)` after ~30 seconds. If not, inspect:

```bash
docker compose logs pkg-server --tail 30
docker compose logs pkg-signer --tail 30
```

- [ ] **Step 3: Test the apt sub-domain (no content yet, expect 404 from autoindex on empty dir)**

From the local machine:

```bash
curl -fsSI https://apt.unidrive.krost.org/health
curl -sS https://apt.unidrive.krost.org/health
```

Wait — `/health` is handled by the default server, not the apt vhost. The apt vhost serves an empty directory and `autoindex on` should give an empty listing. Try:

```bash
curl -fsSI https://apt.unidrive.krost.org/
```

Expected: HTTP/2 200 (autoindex page) or HTTP/2 404 (directory exists but is empty and autoindex doesn't render an empty listing — both are acceptable for an empty-repo state).

If you get `502 Bad Gateway`: pkg-server isn't running or Traefik can't reach it.
If you get `404` from Traefik (not nginx): the router rule doesn't match — recheck `traefik/dynamic.yml`.
If you get TLS errors: Let's Encrypt hasn't issued a cert yet; wait ~60s and retry.

- [ ] **Step 4: Test the dnf sub-domain**

```bash
curl -fsSI https://dnf.unidrive.krost.org/
```

Same expectations as apt.

- [ ] **Step 5: Test the health endpoint via direct nginx**

The `/health` endpoint is on the default server (Host-agnostic). Verify it from inside the VPS:

```bash
ssh -p 22222 gernot@87.106.246.31 \
    'docker exec pkg-server wget -qO- http://127.0.0.1/health'
```

Expected: `ok`.

- [ ] **Step 6: Test the signer**

From the VPS:

```bash
# Confirm sshd is alive inside pkg-signer.
docker exec pkg-signer nc -z 127.0.0.1 22 && echo "signer sshd: up"

# Confirm the GPG keyring is loaded.
docker exec pkg-signer bash -c \
    'GNUPGHOME=/var/lib/signer/gnupg gpg --list-secret-keys --batch'
```

Expected: `signer sshd: up` and one secret-key listing.

- [ ] **Step 7: End-to-end signer smoke**

From the VPS, simulate the dist CI flow against the signer:

```bash
# Step A: sftp-push a dummy file into the signer's drop dir.
docker cp /etc/hostname pkg-signer:/var/lib/signer/drop/

# Step B: invoke sign-drop via direct exec (simulating the SSH
# forced-command path — works because we exec into the container
# as `signer` and the forced-command script reads SSH_ORIGINAL_COMMAND).
docker exec -u signer -e SSH_ORIGINAL_COMMAND=sign-drop pkg-signer \
    /usr/local/bin/sign-forced-command.sh

# Step C: list-signed.
docker exec -u signer -e SSH_ORIGINAL_COMMAND=list-signed pkg-signer \
    /usr/local/bin/sign-forced-command.sh
```

Expected: Step B prints `OK signed=0` because `hostname` doesn't match an allowed artefact filetype (no `.deb` / `.rpm` / `.tar.gz`). Step C's listing is empty.

For a real positive test, push a real `.tar.gz`:

```bash
echo "hello" > /tmp/sample.tar.gz
docker cp /tmp/sample.tar.gz pkg-signer:/var/lib/signer/drop/
docker exec -u signer -e SSH_ORIGINAL_COMMAND=sign-drop pkg-signer \
    /usr/local/bin/sign-forced-command.sh
docker exec -u signer -e SSH_ORIGINAL_COMMAND=list-signed pkg-signer \
    /usr/local/bin/sign-forced-command.sh
```

Expected: `OK signed=1`. `list-signed` shows `sample.tar.gz` and `sample.tar.gz.asc`.

Cleanup:

```bash
docker exec -u signer -e SSH_ORIGINAL_COMMAND=clear-signed pkg-signer \
    /usr/local/bin/sign-forced-command.sh
rm -f /tmp/sample.tar.gz
```

- [ ] **Step 8: No commit (smoke only)**

---

## Task 12: Update krost-infra documentation (AGENTS.md sync rules)

The krost-infra AGENTS.md requires CLAUDE.md and DEPLOY.md to be updated whenever services, routers, or DNS records are added.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `DEPLOY.md`

- [ ] **Step 1: Update `CLAUDE.md` services table**

Open `CLAUDE.md` and find the services table. Add two rows:

```
| pkg-server | nginx:alpine | web | apt.unidrive.krost.org, dnf.unidrive.krost.org |
| pkg-signer | krost/pkg-signer (custom) | internal | (internal — signing service for unidrive releases) |
```

(The exact columns vary by what the existing table looks like; adjust to match.)

Also update the architecture diagram block in CLAUDE.md to include `pkg-server` (public, on `web`) and `pkg-signer` (internal-only).

- [ ] **Step 2: Update `DEPLOY.md` services table**

Add two rows analogous to the existing `site-unidrive` row:

```
| Apt repo    | `apt.unidrive.krost.org` | `pkg-server` |
| Dnf repo    | `dnf.unidrive.krost.org` | `pkg-server` |
```

- [ ] **Step 3: Update `DEPLOY.md` DNS table**

Add the two new records:

```
| krost.org | CNAME | apt.unidrive | unidrive.krost.org. |
| krost.org | CNAME | dnf.unidrive | unidrive.krost.org. |
```

- [ ] **Step 4: Verify the docs still parse as Markdown**

Visually inspect; no automated check. (krost-infra has no markdownlint baseline.)

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md DEPLOY.md
git commit -m "$(cat <<'EOF'
docs: add pkg-server + pkg-signer to CLAUDE.md and DEPLOY.md

Per krost-infra AGENTS.md "Documentation-Stack Sync Rules": new
services in docker-compose.yml and new routers in traefik/dynamic.yml
trigger a documentation update.

Reflects unidrive/docs/dev/specs/unidrive-distribution-design.md §7.6.
EOF
)"
```

---

## Task 13: Final review, push, open PR

**Files:** none changed

- [ ] **Step 1: Review the branch diff**

```bash
cd /home/gernot/dev/git/krost-infra
git log --oneline origin/main..HEAD
git diff --stat origin/main..HEAD
```

Expected: 8–10 commits; diff stat covers `docker-compose.yml`, `pkg-server/`, `pkg-signer/`, `traefik/dynamic.yml`, `setup-server.sh`, `maintenance/html/apt.unidrive.krost.org/`, `maintenance/html/dnf.unidrive.krost.org/`, `sites/unidrive/install/unidrive-releases.gpg`, `CLAUDE.md`, `DEPLOY.md`, optionally `.gitignore`.

- [ ] **Step 2: Run the krost-infra pre-deploy validation**

```bash
docker compose config -q && echo "compose OK"
```

Expected: `compose OK`.

- [ ] **Step 3: Push**

```bash
git push -u origin feature/pkg-server-and-signer
```

- [ ] **Step 4: Open the PR**

```bash
gh pr create --title "feat: pkg-server + pkg-signer for UniDrive distribution" \
  --body "$(cat <<'EOF'
## Summary
Provisions the krost-infra side of the UniDrive distribution stack:

- `pkg-server`: nginx:alpine serving apt.unidrive.krost.org and dnf.unidrive.krost.org from /var/www/pkg/.
- `pkg-signer`: custom debian:13-slim with gnupg + dpkg-sig + rpm-sign + createrepo_c, internal-only, signing service accessed via SSH forced-command.
- Traefik routers + Let's Encrypt for both sub-domains.
- DNS CNAMEs (off-repo; performed at the registrar).
- `pkg-publisher` system user for the dist CI runner to rsync into pkg-data.
- `unidrive-releases` GPG key generated on the VPS, private part loaded into the signer-gnupg Docker volume, public part committed to sites/unidrive/install/.
- 503 maintenance pages for both sub-domains.
- CLAUDE.md and DEPLOY.md updated per AGENTS.md sync rules.

## Spec reference
`unidrive/docs/dev/specs/unidrive-distribution-design.md` §5, §7.1–§7.6.

## Plan reference
`unidrive/docs/dev/plans/krost-infra-pkg-server-and-signer.md` on branch `spec/distribution`.

## Test plan
- [x] `docker compose config -q` passes.
- [x] Both containers come up healthy on the VPS (Task 11 Step 2).
- [x] `https://apt.unidrive.krost.org/` and `https://dnf.unidrive.krost.org/` return 200 (empty repo) with valid Let's Encrypt cert (Task 11 Steps 3–4).
- [x] `pkg-signer` end-to-end smoke: a real `.tar.gz` lands in `drop/`, `sign-drop` produces a signed `.asc` in `signed/`, `clear-signed` cleans up (Task 11 Step 7).
- [x] GPG public key fingerprint matches what was committed to `sites/unidrive/install/unidrive-releases.gpg`.

## Dependencies
- Consumer: Plans P1, P2, P4, P5 all consume the secrets / accounts / repos this plan provisions.
- Provider: this plan is the foundation; no upstream dependency.

## Out of scope
- No release-workflow YAML in the sibling code repos (Plans P1, P2 — already committed).
- No `unidrive-dist` repo (Plan P4).
- No first-release content in pkg-data (Plan P5).
- AUR account creation is not on krost-infra; covered in Plan P4.
EOF
)"
```

Expected: PR opened, URL printed.

- [ ] **Step 5: No additional commit**

---

## Self-Review Notes

**Spec coverage:**

| Spec requirement | Implementing task |
|---|---|
| §5 GPG signing identity + key on krost-infra | Task 6 |
| §5.1 pkg-signer container | Tasks 4, 5, 11 |
| §5.2 release-time signing flow (signer's role) | Task 4 forced-command + Task 11 Step 7 smoke |
| §7.1 pkg-server Compose service | Task 5 |
| §7.2 Traefik routers | Task 10 |
| §7.3 pkg-data volume layout | Task 5 (volume declared); content populated by P5 |
| §7.3.1 unidrive.repo contents | Deferred to P5 (the file is populated when the first dnf repo lands) |
| §7.4 DNS CNAMEs | Task 1 |
| §7.5 SSH deploy accounts + rotation | Tasks 8, 9 (provisioning); rotation playbook stays in the spec |
| §7.6 krost-infra doc sync | Tasks 2 (maintenance pages), 12 (CLAUDE/DEPLOY) |
| §10 AC #4 / #5 (apt + dnf served, signed) — partial | Plumbing landed; content lands in P5 |

**Maintainer-only steps** are clearly tagged `[MAINTAINER]`. Tasks 1, 6, 7, 8, 9, 11 contain `[MAINTAINER]` steps that require human action (DNS UI, browser GitHub Settings, SSH key custody).

**Placeholder scan:** no TODO/TBD/FIXME in the plan body.

**Type/name consistency:**

- Service names `pkg-server` / `pkg-signer` consistent across docker-compose, traefik, scripts, docs.
- Sub-domains `apt.unidrive.krost.org` / `dnf.unidrive.krost.org` consistent.
- Volume names `pkg-data` / `signer-gnupg` / `signer-state` consistent.
- GH secrets `GPG_PRIVATE_KEY` / `GPG_KEY_FINGERPRINT` / `DIST_DEPLOY_KEY` / `SIGNER_DEPLOY_KEY` consistent with Plans P1/P2/P4.
- VPS user `pkg-publisher` consistent.
- Container SSH user `signer` consistent.

**Out-of-scope items** documented at the top of this plan.

**Cross-plan secrets-coordination matrix:**

| Secret | Repos that need it | Provisioned in |
|---|---|---|
| `GPG_PRIVATE_KEY` | unidrive, unidrive-mount-linux | Task 7 (this plan) |
| `GPG_KEY_FINGERPRINT` | unidrive, unidrive-mount-linux | Task 7 (this plan) |
| `DIST_DEPLOY_KEY` | unidrive-dist | Task 8 of this plan defers actual GH paste until P4 creates the repo |
| `SIGNER_DEPLOY_KEY` | unidrive-dist | Task 9 of this plan defers actual GH paste until P4 creates the repo |
| `AUR_BOT_KEY` | unidrive-dist | Plan P4 (this plan doesn't touch AUR) |
