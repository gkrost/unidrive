package org.krost.unidrive.internxt.model

import kotlinx.serialization.json.Json
import org.krost.unidrive.UnidriveJson

// How every wire shape of this package is read. Unknown keys are ignored (UnidriveJson), and a null where the model
// has a default takes the default: the server sends "not set" as an explicit null as often as it leaves the key out
// (a folder's parentId, listing rows), and a value without a meaning of its own, a flag or a count, is no reason to
// refuse the item. Two things stay strict on purpose. A field whose null means something is nullable in its model
// (InternxtFolder.parentId: null is the top of a tree), so the coercion never has to invent it. A field without a
// default, a uuid or a token, is required: a null there fails the read, because an item without its identity must not
// be guessed at.
internal val InternxtJson: Json = Json(from = UnidriveJson) { coerceInputValues = true }
