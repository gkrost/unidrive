package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.ignore.IgnoreLayer
import org.krost.unidrive.sync.ignore.UploadIgnore

/**
 * `ignore.explain` (#660; docs/dev/specs/ignore-layering-design.md): why the daemon does or does not upload a path.
 * A read verb (`IpcAuth.VERB_CLASSES`), answered from the daemon's own rules, so it says what the upload path
 * decides: the same rule files, read at the same moment.
 *
 * Request `{"path":"/a/b","is_dir":false}` (`is_dir` optional, default false). Reply:
 * `{"ok":true,"path","excluded","ignored","layer","file","line","pattern","decided_at","reason","exclude_pattern"}`.
 * - `excluded`: the upload path keeps the path local (an ignore rule or one of the profile's `exclude_patterns`);
 * - `ignored`, `layer` (`operational`, `unreadable`, `unidrive_ignore`, `gitignore`, `account`, `default`), `file` (the
 *   rule file, logical path; null for the built-in and account layers), `line`, `pattern`, `decided_at` (the path the
 *   rule matched: the path itself or an ignored folder above it): the deciding ignore rule, all null when none
 *   matched. A matching `!` rule decides too (`ignored` false);
 * - `reason`: why a held-back folder's rule file cannot be read (`layer` `unreadable`), else null;
 * - `exclude_pattern`: the first of the profile's `exclude_patterns` that matches, else null.
 *
 * Errors: `missing_path`, `invalid_path` (a `.` or `..` name, an empty name inside, a control character),
 * `invalid_is_dir`.
 */
class IgnoreRpcHandler(
    private val ignore: UploadIgnore,
    private val excludePattern: (path: String) -> String?,
) {
    fun explain(jsonRequest: String): String {
        val body = runCatching { Json.parseToJsonElement(jsonRequest) as? JsonObject }.getOrNull()
        val raw = (body?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return MISSING_PATH
        val isDir =
            when (val element = body["is_dir"]) {
                null, JsonNull -> false
                else -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: return INVALID_IS_DIR
            }
        val path = logicalPath(raw) ?: return INVALID_PATH
        val v = ignore.explain(path, isDir)
        val rule = v.rule
        val pattern = excludePattern(path)
        val ruleFile = rule != null && (v.layer == IgnoreLayer.UNIDRIVE_IGNORE || v.layer == IgnoreLayer.GITIGNORE || v.layer == IgnoreLayer.UNREADABLE)
        return buildJsonObject {
            put("ok", true)
            put("path", path)
            put("excluded", v.ignored || pattern != null)
            put("ignored", v.ignored)
            put("layer", v.layer?.name?.lowercase())
            put("file", if (ruleFile) "/" + rule!!.source else null)
            put("line", rule?.line?.takeIf { v.layer != IgnoreLayer.UNREADABLE })
            put("pattern", rule?.text?.takeIf { v.layer != IgnoreLayer.UNREADABLE })
            put("decided_at", if (rule != null) "/" + v.decidedAt else null)
            put("reason", rule?.text?.takeIf { v.layer == IgnoreLayer.UNREADABLE })
            put("exclude_pattern", pattern)
        }.toString()
    }

    companion object {
        private const val MISSING_PATH = """{"ok":false,"error":"missing_path"}"""
        private const val INVALID_PATH = """{"ok":false,"error":"invalid_path"}"""
        private const val INVALID_IS_DIR = """{"ok":false,"error":"invalid_is_dir"}"""
        private val NOT_A_NAME = setOf("", ".", "..")

        /** `/a/b` in NFC, or null for a path no entry can have. "" and "/" are the root. */
        internal fun logicalPath(raw: String): String? {
            val names = PathNormalizer.nfc(raw).trim('/').split('/')
            if (names == listOf("")) return "/"
            if (names.any { it in NOT_A_NAME || it.any { c -> c < ' ' } }) return null
            return "/" + names.joinToString("/")
        }
    }
}
