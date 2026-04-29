# SFTP — client notes

Research input for UD-230 (feeding UD-228 cross-provider robustness audit).

## Vendor recommendations

There is no single "SFTP vendor"; SFTP is a wire protocol (IETF draft
`draft-ietf-secsh-filexfer`, most deployments implement v3) running over
SSH-2 (RFC 4251–4254). Real-world guidance comes from OpenSSH (the de-facto
server) and the SFTP library we use (Apache MINA SSHD).

- **Server concurrency bounds**: OpenSSH's `sshd_config` uses
  `MaxStartups 10:30:100` (default — after 10 unauthenticated connections
  start dropping with p=30 %; hard cap 100) and `MaxSessions 10` (max
  subsystem/shell sessions per authenticated connection). Well-behaved
  clients must stay comfortably below both, ideally ≤ 4 concurrent SFTP
  subsystems per session.
  ([sshd_config(5)](https://man7.org/linux/man-pages/man5/sshd_config.5.html),
  [Microsoft troubleshooting MaxStartups/MaxSessions](https://learn.microsoft.com/en-us/troubleshoot/windows-server/system-management-components/troubleshoot-openssh-connection-issues-maxstartups-maxsessions))
- **Channel windowing (RFC 4254 §5.2)**: every SFTP read/write is bounded
  by the SSH channel window; `SSH_MSG_CHANNEL_WINDOW_ADJUST` governs back-pressure. Clients must not
  issue writes larger than `remote_maximum_packet_size` (typically 32 KB).
- **Host-key algorithm negotiation (RFC 4253 §7)**: clients MUST verify
  the server host key against a persistent trusted-hosts database
  (`known_hosts`). `StrictHostKeyChecking=no` is explicitly deprecated.
  OpenSSH 8+ hashes `known_hosts` entries by default (`HashKnownHosts yes`).
- **Rekeying (RFC 4253 §9)**: SSH must rekey every 1 GB or 1 hour.
  Long-lived sessions must survive a rekey transparently.
- **Apache MINA SSHD guidance**: the MINA FAQ warns that "uploading and
  downloading files with MINA-sshd is about 2× slower than JSCH" unless
  `SftpModuleProperties.POOL_SIZE` is sized to at least the number of
  concurrent threads using the SFTP filesystem, and window sizes
  (`CHANNEL_WINDOW_SIZE`, `CHANNEL_PACKET_SIZE`) are tuned up from
  defaults.
  ([mina-sshd docs/sftp.md](https://github.com/apache/mina-sshd/blob/master/docs/sftp.md),
  [SSHD-1067](https://issues.apache.org/jira/browse/SSHD-1067))
- **SFTP v3 error codes**: `SSH_FX_NO_SUCH_FILE (2)`, `SSH_FX_FAILURE (4)`,
  `SSH_FX_FILE_ALREADY_EXISTS (11)` are the load-bearing ones; OpenSSH
  returns `SSH_FX_FAILURE` for both "mkdir on existing dir" and generic
  errors — clients must treat (4) as ambiguous and probe further.
- **Atomic rename**: SFTP `rename` (opcode 0x12) is not guaranteed atomic;
  SFTP v4+ provides `posix-rename@openssh.com` extension for guaranteed
  atomic replace on OpenSSH.
- **Synology quirk**: Synology DSM's SFTP implementation (OpenSSH-based
  but constrained) rejects parallel channel opens beyond ~10 with
  "open failed"; recommended client concurrency is ≤ 4.
  (Referenced in unidrive source: `SftpApiService.kt:25-27`.)

## What unidrive does today

`core/providers/sftp/src/main/kotlin/org/krost/unidrive/sftp/SftpApiService.kt`

- **Single `ClientSession`, pooled SFTP subsystems** bounded by a
  `Semaphore` (`maxConcurrency`, default **4**) — `:39-41`, config at
  `SftpConfig.kt:37`. Matches the Synology-safe recommendation.
- **Host-key verification**: three branches (`:53-106`) — explicit
  null = accept all (logged loud), OpenSSH hashed entries filtered out to
  a temp file because MINA cannot parse `|1|` lines (`:66-88`), missing
  file = reject-all with a help message pointing at `ssh-keyscan`.
- **Authentication**: tries pubkey identity file then password
  (`:112-125`); 30 s verify timeout on both connect and auth.
- **OpenSSH-format private keys supported** via `SecurityUtils.loadKeyPairIdentities`
  (`:329-339`). Legacy PEM/PKCS8 also fine.
- **Pooled clients reused across operations**; broken clients
  discarded (`:348-361`, `:364-372`) — sound pooling.
- **mkdir idempotency** tolerates `SSH_FX_FAILURE` and
  `SSH_FX_FILE_ALREADY_EXISTS` (`:309-327`).
- **No retry loop for SFTP errors, no rekey test, no timeout knob**
  beyond the 30 s connect/auth verifies. Writes use 32 KB buffers
  (`:173`) — matches typical `remote_maximum_packet_size`.

## Gaps → UD-228

- [ ] **No retry for transient SFTP errors.** A
      `SSH_FX_CONNECTION_LOST (7)` mid-transfer kills the sync.
- [ ] **No rekey resilience test.** 1 GB uploads or 1 h sessions will
      trigger rekey; we have no test that exercises it.
- [ ] **Window/packet size not tuned.** MINA defaults give the
      documented 2× slowdown; we have not set `SftpModuleProperties.POOL_SIZE`
      or bumped `CHANNEL_WINDOW_SIZE` / `CHANNEL_PACKET_SIZE`.
- [ ] **No `posix-rename@openssh.com` use.** Plain `rename` is not
      guaranteed atomic on all servers; a target that already exists can
      fail on non-OpenSSH.
- [ ] **Upload is not resumable.** A 10 GB upload that fails at 9 GB
      restarts from zero. SFTP v3 allows seek-and-append via the `offset`
      parameter of `write` — we use it (`:171-178`) but never persist the
      offset across invocations.
- [ ] **No keepalive / ServerAliveInterval.** Long idle syncs behind a
      NAT will silently drop and surface as next-operation failure.
- [ ] **`known_hosts` hashed-entry workaround writes a temp file** on
      every connect (`:74-88`); plain-text filter is fine for now but
      blocks adoption of OpenSSH 8+ hashed defaults.
- [ ] **No explicit cap on listing depth** in `listAll` (`:250-285`).
      Symlink loops on server would spin forever (no visited-set).

## Priority for UD-228

**High-medium.** SFTP is widely used for NAS / small-server sync; the
failure modes (rekey, transient drops, long-running uploads) are all
daily-real for gigabyte-sized backups. The symlink-loop gap is a real
liveness bug. Concurrency is already handled correctly — this is the
one provider where we got the hard part right.
