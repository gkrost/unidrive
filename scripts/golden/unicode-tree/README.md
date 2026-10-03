# Golden unicode tree

A fixed, deterministic test tree for the whole unidrive chain (Windows client, daemon, Internxt or any other provider): names in every
major script, symbols, cat emoji, and a catalogue of name hazards. It is meant as a reference ("golden") suite: the same tree goes in,
the same tree has to come out, and every difference is a finding.

| file | what it is |
|---|---|
| `layout.json` | the pin of the layout: every directory, file name, text and size. Literal UTF-8 plus `{U+XXXX}` / `{rep:X:N}` tokens for what cannot be typed |
| `manifest.tsv` | generated from the layout: one row per directory and file with size, SHA-256, modification time, normalisation form, ASCII-escaped path and path |
| `manifest.sha256` | SHA-256 of `manifest.tsv`: **this one value identifies the layout** |
| `generate.ps1` | builds the tree (`-Out <dir>`, refused when the layout no longer matches the committed manifest), checks the committed manifest (`-Check`), or rewrites it after a deliberate layout change (`-UpdateManifest`) |
| `verify.ps1` | compares a directory (a mount, a download, a restore) with the manifest: missing, extra, renamed (NFC/NFD), case, size, hash, mtime. Exact names are matched first, so the result does not depend on iteration order (twin pairs) |
| `compare-expected.ps1` | sorts a tool's findings (`verify`, `daemon-view`, `remote-live`) against a file of known deviations: **unexpected** (fails the run), **expected** (counted per rule, with its issue), **stale** rules that matched nothing (fixed? remove the rule). The tools themselves stay strict |
| `expected/<suite>@<engine>+<client>.tsv` | the known deviations for one engine and client build: `tool, class, path (* wildcard, all else literal), issue (required), note`, tab-separated. A deviation becomes "expected" only through a reviewed commit with an issue, and leaves when the fix lands. The run report names the file it used |
| `selftest.ps1` | feeds `compare-expected.ps1` made-up findings (expected, unexpected, stale, literal brackets, case, a rule without an issue), and damages fresh trees one way per case (rename to NFD, truncate, flip a byte, touch, add, delete, case swap, folder to file, either NFC/NFD twin deleted) and checks that `verify.ps1` classifies each correctly |
| `daemon-view.ps1` | asks the running daemon (`hydration.list` over its IPC socket, one connection for the whole walk) for the cloud-side view and compares it with the manifest; `-Watch` waits for the uploads to drain. state.db keys paths in NFC (#171), so an NFD name that collapses onto another entry is reported as `NFCMERGE`, and an entry listed under the wrong parent as `LEAK` |
| `remote-live.ps1` | asks the provider itself (`unidrive ls --live`, folder by folder, bypassing state.db) and compares names with the manifest; limited to folders with an ASCII-only path (the Windows CLI cannot take other arguments, gkrost/unidrive#487) |
| `probes/` | read-only probes behind the numbers of the run report: `subscribe-events.ps1` (the daemon's event stream, flags lines that are not JSON), `daemon-counts.ps1` (upload progress), `local-vs-cloud-mtime.ps1` (times of the placeholders against the cloud) |
| `RUN-2026-10-03.md` | the first run through the whole chain: setup, procedure, interventions, results, findings (interim until it says otherwise) |

PowerShell 7 (`pwsh`) is needed. The scripts are ASCII only.

## Pin

```
layout:   290 directories, 249 files, 4,227,907 bytes
manifest: 87eaab5cd7d7353f7081e20be68fb1c799b281781a561407729d5ee766fae4a5   (sha256 of manifest.tsv)
rules:    no path has more than 7 segments below the golden root (directories plus file name)
          no file is larger than 1,000,000 bytes
          every file has the modification time 2026-01-01T12:00:00Z unless the layout says otherwise (hazards/timestamps)
          text files end with their own relative path, binary files are SHA-256 counter-mode streams: the same bytes on every machine
```

`pwsh generate.ps1 -Check` fails when `layout.json` and the committed manifest drift apart. Change the layout only deliberately, and
bump the `id` (`golden-unicode-v2`) when you do: the id is part of every file's content.

## What is in it

Top-level groups, each with native language directories two to five levels deep, native file names and a few empty directories:

| group | content |
|---|---|
| `arabic-and-hebrew` | Arabic, Persian, Urdu, Pashto, Hebrew, Yiddish, Syriac, Thaana (right to left, joining, points) |
| `cjk` | simplified and traditional Chinese (with plane-2 ideographs), Cantonese, Japanese (kanji, kana, half-width), Korean, Bopomofo, Yi, Mongolian |
| `cyrillic` | Russian, Ukrainian, Bulgarian, Serbian, Belarusian, Macedonian, Mongolian, Kazakh |
| `european-latin` | German, French, Spanish, Portuguese, Italian, Polish, Czech, Turkish, Icelandic, Nordic, Hungarian, Romanian, Vietnamese, Finnish, Baltic, Celtic, Maltese, Esperanto |
| `greek-and-caucasus` | Greek (also polytonic), Georgian, Armenian |
| `african` | Amharic (Ge'ez), Yoruba, Hausa, Swahili, Zulu, Afrikaans, Tifinagh, N'Ko, Adlam (plane 1), Vai, Malagasy and Somali |
| `americas` | Spanish and Portuguese (Americas), Quechua, Guarani, Mapudungun, Nahuatl, Cherokee, Inuktitut, Navajo, Haitian Creole, K'iche' |
| `indic` | Hindi, Marathi, Sanskrit, Nepali, Bengali, Tamil, Telugu, Kannada, Malayalam, Gujarati, Punjabi, Odia, Sinhala, Tibetan, Santali |
| `southeast-asia` | Thai, Lao, Khmer, Burmese, Javanese, Baybayin, Indonesian and Tagalog |
| `historic-and-exotic` | runes, Ogham, Braille, hieroglyphs, Linear B, cuneiform, Gothic, Phoenician, Glagolitic, Coptic, Shavian, Deseret, mathematical alphanumerics, musical symbols, Mahjong, cards, chess, IPA |
| `symbols` | stars, check marks, arrows, maths, currencies, music, key caps, enclosed alphanumerics, box drawing, warning signs, fractions, quotation marks, weather |
| `cats` | six directory levels of cat emoji, cat names with `&`, `#`, quotes, brackets, ZWJ sequences, cat words in nine scripts |
| `hazards` | see below |
| `binary` | file sizes at every boundary from 0 to 1,000,000 bytes; one 1 MB file below a path of five scripts |

### Hazards (`hazards/...`)

| directory | what it tests |
|---|---|
| `nfc-nfd-twins` | the same visible name in NFC and in NFD in one directory (accented Latin, Hangul syllables and jamo, katakana with and without a separate dakuten) |
| `invisible-and-lookalike-twins` | names that differ only by an invisible or look-alike code point: zero width space, joiner and word joiner, no-break and ideographic space, direction marks, variation selector 16, stacked combining marks, a Cyrillic `a`, full-width letters |
| `emoji-sequences` | ZWJ family, flag pairs, keycap, skin tone, rainbow flag, tag sequence (England), person-astronaut |
| `windows-and-url-special-characters` | full-width look-alikes of the characters Windows forbids, legal punctuation with meaning in URLs and shells, leading and doubled spaces, dotfile, no extension, many dots, `%41%2F%2e%2e`, non-ASCII and mixed-case extensions |
| `case-fold` | `Straße`/`STRASSE`, `Σ`/`ς`, `ǅ`, `Å` (U+00C5) next to the angstrom sign (U+212B) |
| `long-names` | a 120 character name, 80 CJK characters, 60 emoji (the last two are 240 bytes in UTF-8) |
| `deep-and-long-path` | six directory levels of 20 characters each |
| `timestamps` | modification times at 1970-01-02, 1980-01-01, 2000-02-29, 2038-01-19 03:14:08 and 2099-12-31 |
| `content-encodings` | UTF-8 with BOM, UTF-16 LE and BE with BOM, Latin-1, CRLF, no trailing newline, NUL-heavy binary |
| `duplicates` | identical content under three names in three scripts; identical binary content under two names |
| `empty-things` | zero-byte files and empty directories with ASCII, CJK, emoji and Cyrillic names |

### Not covered, on purpose

Things Windows cannot create through the normal API, or that would turn the run into a path-length test: reserved device names (`CON`,
`NUL`, ...), names with a trailing dot or space, paths of 260 characters or more, control characters, unpaired surrogates (not valid
UTF-8), and an actual case-only twin in one directory (NTFS merges them). Test those separately and deliberately.

## Use

```powershell
# build the tree somewhere outside the mount
pwsh scripts/golden/unicode-tree/generate.ps1 -Out C:\Users\me\unidrive-golden
pwsh scripts/golden/unicode-tree/verify.ps1 -Root C:\Users\me\unidrive-golden\golden-unicode-v1        # 0 findings expected

# put it through the chain: copy it into the mount (this is what a person does in Explorer)
Copy-Item C:\Users\me\unidrive-golden\golden-unicode-v1 <mount>\_INBOX\golden-unicode-v1 -Recurse

# cloud side, as the daemon sees it (waits for the uploads)
pwsh scripts/golden/unicode-tree/daemon-view.ps1 -Profile <profile> -Watch

# the provider's own listing (ASCII-only folders; one engine start per folder, minutes)
pwsh scripts/golden/unicode-tree/remote-live.ps1 -Profile <profile> -Subtree hazards

# local side after the round trip: names, sizes, hashes (reading hydrates every placeholder)
# -StrictMtime makes a changed modification time a finding (the golden files carry fixed times on purpose)
pwsh scripts/golden/unicode-tree/verify.ps1 -Root <mount>\_INBOX\golden-unicode-v1 -StrictMtime
```

Findings of verify.ps1 and daemon-view.ps1 use `{U+XXXX}` for every non-ASCII character, so they survive terminals and loggers that mangle
Unicode. The first run and its findings: `RUN-2026-10-03.md`.
