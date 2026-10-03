# Run 2, 2026-10-03: the golden unicode tree on an isolated localfs profile (hermetic)

Why: the first run (RUN-2026-10-03.md) shared its transfer slots and its gateway with a live account, and the engine was swapped
while it ran. This run has no cloud, nothing else uploading, and no restart during the copy. Same builds as run 1: engine
`67a4236` (main 9f7864e plus #484), client `b564eda`. Tags: **[V]** verified by running or reading, **[I]** inferred, **[U]** not verified.
Times are local (UTC+2); the client log is UTC.

## 1. Setup

| | |
|---|---|
| stack | the live Internxt daemon and mount were stopped first (the owner's decision: "stop all"), nothing deleted |
| profile | `golden_localfs`, provider `localfs`, `sync_path = ["/_INBOX"]`, own config dir `C:/Users/gerno/unidrive-golden/env-localfs/config`, provider directory `.../env-localfs/remote`, engine mirror `.../env-localfs/engine-sync-root`, own socket and cache by profile name |
| mount | `C:/Users/gerno/unidrive-golden/mount` (fresh), later `mount2` (fresh, round trip) |
| tree | `golden-unicode-v1`, pin `87eaab5c...`, copied with `Copy-Item -Recurse` at 10:39:18 (3 s, no error) |
| tools | `verify.ps1 -StrictMtime` (provider directory, mirror, mount), `daemon-view.ps1`, `compare-expected.ps1` against `expected/golden-unicode-v1@67a4236+b564eda.tsv`; outputs kept outside the repository in `C:/Users/gerno/unidrive-golden/run2/` |

## 2. What happened

1. **Pass A (10:39:19 to 10:40:44):** 137 files were uploaded in 85 s (1.6 files per second), no error anywhere. Then the client's upload
   queue stopped: nothing new for 6 minutes although the daemon had no pending row, no error, the event stream was healthy (no
   JsonReaderException, no reconnect) and nothing was restarted. The client log has no `failed N times` line, no queue line at all after the
   last `is in the cloud`. [V]
2. **Where it stopped:** in processing order the queue had reached `hazards/invisible-and-lookalike-twins`. Exactly one file was uploaded
   per daemon but still a plain file locally: `a` + five combining marks (`a{U+0301}{U+0302}{U+0303}{U+0304}{U+0305}.txt`), a name that is not in NFC. The
   engine uploaded it under the NFC form of its name and its `completed` event carries that path. [V]
3. **Pass B (the owner's likely remedy, tried here first):** the mount was stopped (all 249 files stay as plain files, nothing lost), started
   again in place. `unidrive-mount start` refuses a non-empty root, `-Force` is needed. With `UNIDRIVE_TRACE=1` the queue then finished in
   2 minutes: 107 new files, 30 `hydration.create ... path_exists` fallbacks to the modify flow for files that were already uploaded; 244 of 249
   files and 290 of 290 directories are in the provider. Six items failed six times and stopped: the five second members of NFC-merging pairs
   (`U+212B`, the NFD forms of Hangul, katakana with dakuten, `Ångström`, `café`) and the combining-mark file above. [V]
4. **Round trip (10:53):** the mount was stopped, the root moved aside, a fresh root mounted, so every placeholder comes from the daemon's
   listing; then `verify.ps1` with hashes (9 s, hydrates every file from the provider). [V]

## 3. Results

| check | result |
|---|---|
| provider directory against the manifest | 357 ok, 5 MISSING (the second twin members), 1 FORM, 177 MTIME, 0 EXTRA, 0 SIZE, 0 HASH [V] |
| engine mirror | same, plus the 10 empty directories missing (the mirror holds files and their parents only) [V] |
| old mount (local files) | 539 of 539 present, hashes ok, 70 MTIME (placeholders restamped) [V] |
| fresh mount, names and sizes | 5 MISSING, 1 FORM, **18 EXTRA**, 177 MTIME [V] |
| fresh mount, hashes | **0 HASH, 0 SIZE: all 244 files come back byte-identical** (names with emoji, ZWJ sequences, plane 1 and 2, Hebrew, Arabic, Indic, all of it) [V] |
| daemon-view | 23 findings: 17 LEAK, 6 NFCMERGE [V] |
| compare-expected, provider directory | unexpected 6 (the 5 missing twins and the FORM), expected 177 (#486) |
| compare-expected, fresh mount | unexpected 24 (6 as above, 18 EXTRA), expected 177 |
| compare-expected, daemon-view | unexpected 17 (all LEAK: the rule covers only `african/*`), expected 6, **stale 2** (the two empty-file ERROR rules: empty files upload fine on localfs, #485 is Internxt-only) |

## 4. Findings

| # | finding | evidence | status |
|---|---|---|---|
| G1 | **An NFD-named file stops the client's upload queue, hermetically reproduced.** The engine reports `completed` under the NFC path; `UploadQueue.OnCompleted` looks the event up with `_inFlight.Contains(evt.Path)` (ordinal), which never matches the client's NFD name, so the item waits for ever and keeps one of the two slots; the settle by listing compares names ordinally as well. Two such files, and nothing uploads any more. No daemon restart or stream reset is needed (this was the healthy case). NFD names are what macOS, iOS and many zip tools produce for every accented letter | pass A and B above, `UploadQueue.cs` lines of `OnCompleted` and `SettleByListingAsync` [V code] | gkrost/unidrive-windows#115, #119, draft fix #116 (NFC matching): a regression test with an NFD name through `OnCompleted` is the proof it covers this |
| G2 | the second members of the five NFC-merging pairs are never uploaded; no overwrite of the first member happened in this run (the provider holds the NFC member with the right hash), the client shows them as failed after six attempts | provider directory, pass B log | gkrost/unidrive#491, decision pending |
| G3 | a lone NFD name is stored under its NFC form (`a` + five marks became `U+00E1` + four marks): names are not preserved, by design of #171 | FORM in provider and fresh mount | belongs to #491 / the #171 decision; add an expected rule |
| G4 | the listing leak of #489 is visible in the real mount: in a fresh mount the folder `cats/.../U+1F639` shows 12 files and two folders that belong one level lower, 18 phantom entries in all (also under `african/` and `historic-and-exotic/`) | fresh mount EXTRA, daemon-view LEAK | gkrost/unidrive#489, draft fix #492; the expected rule must cover `cats/*` and `historic-and-exotic/*` too |
| G5 | modification times: the engine's cache copy never has the original time (0 of 244); the provider and the mirror kept the original for the 67 files uploaded second in pass A (queue items 71 to 137, a sharp boundary) and lost it for the other 177; the placeholders are restamped from the engine's `mtime_ms` by `RefreshDirectory` (code: `Placeholders.Refresh(localPath, entry)` with `e.LastWriteTimeMs`). Why the second half of pass A reached the provider with the original time is not known (the modify flow reads the local file, so that is the candidate) | per-group counts of the four copies, `Program.cs` RefreshDirectory | gkrost/unidrive#486 (restamp mechanism now certain), flow difference open [V counts, I flow] |
| G6 | `RefreshDirectory` only updates items that already exist locally (`if (!File.Exists(localPath) && !Directory.Exists(localPath)) continue;`): cloud-side files in an already populated folder never appear. Three files created directly in the provider directory and a `refresh.run` later, `mount2/_INBOX` still shows only the golden folder (see section 5 for the final state) | `Program.cs`, the experiment | known: unidrive-windows #32 (create and delete are not implemented), gkrost/unidrive#463 [V] |
| G7 | state column: pushing the state of an **empty directory** fails three times and is given up, deterministically (the same six directories as in run 1: `...下書き`, `空のフォルダ`, `empty-dir`, a cyrillic one, the emoji one, `symbols/★/☆/♥/♦/♣/♠`; the log line has no error text). `[REFRESH] update ... failed: error 0x80070178` for five items during the concurrent copy (`cjk`, `cjk/中文`, ...), none in the fresh mount | client log, FNV hashes mapped back to manifest paths [V] | not filed |
| G8 | empty files: 5 of 5 uploaded and came back (localfs has no `fileId` rule) | provider, fresh mount | #485 stays Internxt-only |
| G9 | the engine mirror (#449) has no empty directories | mirror verify | observation |

## 5. Restart in place with cloud-only content (the owner's open question about the live mount)

Setup: three files created directly in the provider directory (`_INBOX/cloud-only/hello.txt`, `cloud-only/sub/deep.txt`, `_INBOX/cloud-only-top.txt`), then `refresh.run`; the daemon lists them (3 rows, `remote_id` set). [V]

1. **They do not appear in the mount**, not after 80 s and not after the refresh: `mount2/_INBOX` keeps showing only the golden folder (G6). [V]
2. **Stop and start in place (`-Force`)** (what restarting the live mount with the #116 build would be): after the stop the folder `_INBOX` is a plain directory holding the golden folder and no other entry (the dehydrated placeholders are removed on unregister, as in the live mount: 3 of 191 entries were left there); after the start `_INBOX` is a placeholder again, and **the cloud-only entries are still not there**. They are in the cloud (and in the daemon's listing), the mount just does not show them. [V]
3. So a restart in place makes everything that is only in the cloud invisible in the mount until the client can create placeholders for cloud entries (#32) or the folders are populated afresh by a new, empty root. A fresh empty root shows everything again (round trip above), but the files that exist only locally are not in it: they have to be moved into the new root after the start (the queue then uploads them).

Consequence for the live profile: do not restart the live mount in place. Start it on a fresh empty root (`deploy-local.ps1 -MoveLeftoversAside` does the move), copy the pending local files (the golden tree, the files below `immo-service-data-downloads`) from the old root into the new one, and only with a client that matches NFC names (#116), or the first two NFD-named files stop the queue again (G1).

## 6. Not done

- Dehydrate and hydrate in place; the Explorer column and overlays (not scriptable); hazards that need names a cloud creates and Windows cannot (download direction, see #495).
- The same run on the fixed builds (#116, #117, #118, #120, #492 and the engine fixes) once they are merged: the expected-deviation file has to move to the new build ids then.

## 7. Runs with the runner (`run-localfs.ps1`), 2026-10-03

### 7.1 Runs 2b and 2c: the installed builds again

The same builds as run 2, one command each. Both stalled in the same place: 153 and 152 of 249 files uploaded (run 2: 137), then nothing,
no error, healthy event stream; the runner reported `STUCK` (92 plain files with NFC names still waiting). The number differs from run to run
(which two files take the slots depends on timing), the cause does not: a non-NFC name never matches the engine's NFC `completed` event
(G1, unidrive-windows#115). Run 2b also showed a typo of mine in the runner. [V]

### 7.2 Run 3: the integration builds of the fix PRs

| | |
|---|---|
| engine | `1a00eaf` = main `1db480a` + #492 (exact prefix queries) #496 (empty files) #497 (create sends the time) #498 (Windows argv) #499 (replay failed uploads later) #501 (mirror empty folders); `check` green, 2298 tests |
| client | `6ea4bf5` = main `b564eda` + #116 (settle, NFC) #117 (event stream) #118 (folder ensure) #120 (cache copy keeps the time) #122 (reasons in the log); tests green |
| expectations | `expected/golden-unicode-v1@localfs@1a00eaf+6ea4bf5.tsv`: only the #491 rules (everything else has a fix in these builds, so it must not show up) |
| result | uploads settled 156 s after the copy: 290 of 290 folders, 243 files uploaded, no stall; **12 findings per surface, 6 of them unexpected** |

| surface | result |
|---|---|
| provider directory | 527 ok; 6 MISSING, 1 FORM, **5 SIZE**; **0 MTIME**, 0 EXTRA |
| engine mirror | the same as the provider, **the 10 empty folders are there now** |
| mount | 539 of 539 present, all hashes ok; 248 placeholders and 1 plain file, 290 of 290 folders are placeholders |
| daemon view | 11 findings: 5 NFCMERGE (expected), 5 SIZE, 1 MISSING; **0 LEAK** |
| fresh mount (round trip) | 527 ok, 6 MISSING, 1 FORM, 5 SIZE, **0 EXTRA**, 0 MTIME; size mismatches stop the hash check for those five files |

Fixed, as the PRs claim: the stall (G1), the listing leak (G4, 0 of 16 leaks), the lost modification times (G5: **0 MTIME on every surface**, localfs and
placeholders alike), the mirror's missing empty folders (G9), the 18 phantom entries of the fresh mount. [V]

### 7.3 What run 3 found

| # | finding | evidence | status |
|---|---|---|---|
| H1 | **Silent data loss in all five NFC-merging pairs.** With NFC matching in the client (#116) the NFD member is now uploaded; the engine maps it onto the NFC member's row, and the upload replaces that row's content. The provider's file under the NFC name holds the other member's bytes in 5 of 5 pairs (`café`, `Ångström`, Hangul, katakana with dakuten, `Å` against the angstrom sign): SHA-256 of the provider file equals the other member's manifest hash, compared ordinally. The NFC member still exists locally, and a fresh mount serves the wrong content under its name. In run 2 the same pairs were harmless only because the NFD members never got through the stalled queue | provider SIZE findings, hashes, fresh mount | gkrost/unidrive#491: the guard (option 1, detect and refuse) has to come with or before #116 |
| H2 | a file whose name equals another file's name under case folding is never reported to the client: `hazards/case-fold/ς.txt` (final sigma) next to `Σ.txt` has no `notify-file-close` in the trace, no queue line, no row; `Σ` and the other five case-fold files are uploaded. It stays a plain, local-only file until the mount is restarted (the start scan finds it: in run 2 pass B it was uploaded). The runner reports it as `STUCK`, which is correct | client trace log (0 lines for the name, 6 for its neighbours) | not filed [V] |
| H3 | the state column's push for an empty folder fails with `ArgumentException 0x80070057` (E_INVALIDARG), the same six folders as before; #122 made the reason visible | client log of run 3 | unidrive-windows#121 (root cause found in this log) |
| H4 | the combining-mark name is still stored in NFC (`FORM`) | provider | by design (#171), see #491 |
