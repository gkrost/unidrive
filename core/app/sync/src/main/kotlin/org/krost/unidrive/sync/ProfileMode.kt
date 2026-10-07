package org.krost.unidrive.sync

/**
 * #603 (U4 of the engine split, #560): a profile's hosting mode. Chosen when the profile is created
 * and fixed afterwards — there is no switch (#564); someone who wants the other mode creates a second
 * profile.
 *
 * - [MIRROR] — bidirectional folder sync over a `sync_root` (`unidrive sync`): reconcile plans, the
 *   conflict machinery, trash/versioning.
 * - [MOUNT] — the drive as a filesystem: the hydration verbs, uploads from the engine's cache, the
 *   one-way remote→state enumeration (`sync.enumerate`, `refresh.run`).
 *
 * A profile without a `mode` is not supported: it is refused with one clear message (owner decision
 * 2026-10-07 — no legacy support and no migrations until the first LTS release), and nothing may
 * assume a legacy profile.
 */
enum class ProfileMode(
    val wireName: String,
    /** What a daemon of this mode serves, as reported in `daemon.status` `capabilities` (additive). */
    val capabilities: List<String>,
) {
    MIRROR(
        "mirror",
        listOf("sync", "refresh", "apply", "sweep", "status"),
    ),
    MOUNT(
        "mount",
        listOf("mount", "hydration", "enumerate", "refresh", "status"),
    ),
    ;

    companion object {
        /** The config key in `[providers.<profile>]`. */
        const val CONFIG_KEY: String = "mode"

        /**
         * Parses the `[providers.<profile>] mode` value. Null (key absent) → null: the caller refuses a
         * modeless profile — it must not guess one. Any other value is a config error naming the two
         * legal spellings.
         */
        fun fromConfig(raw: String?): ProfileMode? =
            when (raw?.trim()?.lowercase()) {
                null, "" -> null
                "mirror" -> MIRROR
                "mount" -> MOUNT
                else ->
                    throw IllegalArgumentException(
                        "mode = ${raw.inspect()}: a profile's mode is \"mirror\" or \"mount\" (#603). " +
                            "\"mirror\" is bidirectional folder sync over a sync_root; \"mount\" serves the drive as a filesystem.",
                    )
            }

        /**
         * The refusal for a profile without a mode — one clear message that says to create the profile
         * anew (owner decision: no legacy support, no conversion hint, nothing assumes a legacy
         * profile). [command] names what the operator ran, so the line says what was refused.
         */
        fun modelessMessage(
            profileName: String,
            command: String,
        ): String =
            "profile '$profileName' has no mode. Every profile declares " +
                "mode = \"mirror\" | \"mount\" in its [providers.$profileName] section (#603), and this build " +
                "supports no profiles without one. Refusing '$command' before anything was touched: create the " +
                "profile anew with a mode — mode = \"mirror\" for bidirectional folder sync, mode = \"mount\" " +
                "for the drive as a filesystem — or add the key to config.toml. A profile's mode is fixed " +
                "afterwards; there is no switch."

        private fun String?.inspect(): String = if (this == null) "<absent>" else "\"$this\""
    }
}
