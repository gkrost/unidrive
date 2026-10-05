package org.krost.unidrive.sync

import java.text.Normalizer

// #171: canonical path form for cross-platform matching. The same visible filename
// can be encoded as different bytes — composed (NFC: "ö" = U+00F6) vs decomposed
// (NFD: "o" + U+0308) — so byte-exact path comparison fails to match the same
// logical name (macOS-origin names, decomposed accents). We canonicalize every path
// to NFC at the ingestion boundaries (local scan, remote enumeration) and the
// state.db read/write boundary, so the reconciler always compares NFC-vs-NFC.
// NFC is chosen because the providers already return NFC and Linux/Windows are
// NFC-dominant — only the occasional decomposed name needs normalizing.
object PathNormalizer {
    // Returns [path] in NFC. The already-normalized fast path avoids allocating for
    // the common case (ASCII / already-NFC). Idempotent: nfc(nfc(x)) == nfc(x).
    fun nfc(path: String): String =
        if (Normalizer.isNormalized(path, Normalizer.Form.NFC)) {
            path
        } else {
            Normalizer.normalize(path, Normalizer.Form.NFC)
        }
}
