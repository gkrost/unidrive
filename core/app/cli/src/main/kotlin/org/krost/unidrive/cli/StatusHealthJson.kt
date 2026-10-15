package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.PauseGate
import org.krost.unidrive.sync.ignore.HeldBackDir

/**
 * The health objects of the `daemon.status` reply (#658; docs/dev/specs/unidrive-daemon-design.md §4.3).
 * A value the daemon does not have is `null` here, never 0 and never a "healthy" default.
 */

/**
 * `uploads`: see [HydrationImpl.UploadHealth] for what each count includes and leaves out. `as_of_ms` (#747) is the epoch-milliseconds stamp of the reading: the last good one is served while state.db is busy, so a front-end tells fresh from frozen by the stamp not advancing. `blocked` is present only
 * while there is something blocked by name: absent means none, so a reply with nothing blocked is unchanged.
 */
internal fun HydrationImpl.UploadHealth.toJson(): JsonObject =
    buildJsonObject {
        put("pending", pending)
        put("in_flight", inFlight)
        put("failed", failed)
        put("oldest_pending_age_ms", oldestPendingAgeMs)
        // #747: when the counts were taken. The last good reading is served when state.db is busy, so a
        // front-end tells fresh from frozen by the stamp not advancing. Absent when never read.
        put("as_of_ms", asOfMs)
        if (blocked.count > 0) put("blocked", blocked.toJson())
    }

/**
 * `uploads.blocked`: the entries the drive refuses by their name. `items` is capped (see [HydrationImpl.BLOCKED_ITEMS_CAP]);
 * `more` counts the rest. A `similar_name` item carries `sibling` (the name it conflicts with, null when unknown); a
 * `blocked_by_parent` item carries `parent` (the blocked folder it waits for).
 */
internal fun HydrationImpl.BlockedUploads.toJson(): JsonObject =
    buildJsonObject {
        put("count", count)
        put("more", more)
        putJsonArray("items") {
            for (item in items) {
                add(
                    buildJsonObject {
                        put("path", item.path)
                        put("reason", item.reason)
                        if (item.parent != null) put("parent", item.parent) else put("sibling", item.sibling)
                    },
                )
            }
        }
    }

/** `cache`: `bytes` is null until the cache directory has been measured, `budget_bytes` null without a budget. */
internal fun HydrationImpl.CacheHealth.toJson(): JsonObject =
    buildJsonObject {
        put("bytes", bytes)
        put("budget_bytes", budgetBytes)
    }

/** `provider_health`: `last_contact_ms` is null until the provider has answered once since the daemon started. */
internal fun providerHealthJson(lastContactMs: Long?): JsonObject =
    buildJsonObject {
        put("last_contact_ms", lastContactMs)
    }

/**
 * `paused` (#778): present only while the daemon is paused. `since_ms` is epoch ms of the first request of the
 * pause; `auto` says whether a rule of the client (true) or a person (false) paused; `until_ms` (epoch ms at which
 * the daemon resumes by itself) is present only for a timed pause. The same object is the reply of
 * `daemon.pause` and the body of the `paused` event.
 */
internal fun PauseGate.State.toJson(): JsonObject =
    buildJsonObject {
        put("reason", reason)
        put("since_ms", sinceMs)
        put("auto", auto)
        untilMs?.let { put("until_ms", it) }
    }

/**
 * When the provider last answered (epoch ms): the later of the last completed enumeration and the last
 * transfer that succeeded through the daemon. Failures move neither, so an outage keeps the last success.
 */
internal fun lastProviderContactMs(
    enumerationLastSuccessAtMs: Long?,
    lastTransferReachedAtMs: Long?,
): Long? =
    when {
        enumerationLastSuccessAtMs == null -> lastTransferReachedAtMs
        lastTransferReachedAtMs == null -> enumerationLastSuccessAtMs
        else -> maxOf(enumerationLastSuccessAtMs, lastTransferReachedAtMs)
    }

/**
 * `credential_health` (#586): `state` is one of `ok`, `expiring`, `expired`, `missing`, `unknown`. `expires_in_ms` is the
 * time left at this reply (0 for `expired`), null when no expiry is known; `checked_at_ms` is when the daemon last
 * checked, or saw the provider reject the credentials, null while it has not checked yet.
 */
internal fun CredentialHealthMonitor.Reading.toJson(nowMs: Long): JsonObject =
    buildJsonObject {
        put("state", state.wire)
        put("expires_in_ms", expiresInMs(nowMs))
        put("checked_at_ms", checkedAtMs)
    }

/**
 * `trash` in `daemon.status`: present only while something is in the provider's trash. [rootPath] is where the read-only
 * folder that shows it is now (`/Papierkorb`), `folder_name` its name on disk (the configured name, suffixed with ` (2)`, ...
 * when a live top-level item already has it) and `configured_name` the name the profile asks for (`trash_folder_name`, or the
 * default), so a client sees at once whether the two differ.
 */
internal fun trashStatusJson(
    rootPath: String,
    configuredName: String,
): JsonObject =
    buildJsonObject {
        put("root_path", rootPath)
        put("folder_name", rootPath.substringAfterLast('/'))
        put("configured_name", configuredName)
    }

/**
 * `ignore` in `daemon.status` (#660): the folders whose uploads are held back because a rule file in them cannot be
 * read (fail closed). `held_back.count` counts the folders found so far: a folder's rules are read when something below
 * it is written or asked about, so a folder nothing was written to is not known yet. `items` is capped
 * ([HELD_BACK_ITEMS_CAP]) and `more` counts the rest; each item names the folder, the rule file and why it cannot be read.
 */
internal fun ignoreHealthJson(heldBack: List<HeldBackDir>): JsonObject =
    buildJsonObject {
        put(
            "held_back",
            buildJsonObject {
                put("count", heldBack.size)
                put("more", (heldBack.size - HELD_BACK_ITEMS_CAP).coerceAtLeast(0))
                putJsonArray("items") {
                    for (h in heldBack.take(HELD_BACK_ITEMS_CAP)) {
                        add(
                            buildJsonObject {
                                put("dir", h.dir)
                                put("file", h.file)
                                put("reason", h.reason)
                            },
                        )
                    }
                }
            },
        )
    }

internal const val HELD_BACK_ITEMS_CAP = 20
