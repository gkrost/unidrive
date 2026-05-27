package org.krost.unidrive.sync

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * XDG user-dir locale aliasing (#115).
 *
 * Standard XDG user folders (`Pictures`, `Documents`, …) are locale-aware on
 * the OS side: a German install calls the local folder `Bilder`, a Spanish
 * install calls it `Imágenes`, yet the cloud holds whichever name was first
 * created there (the "canonical" name).  Without alias resolution the
 * reconciler sees the locale-renamed local folder as a brand-new folder and
 * emits a `CreateRemoteFolder`, producing a duplicate parallel tree.
 *
 * [XdgLocaleDirAliases] resolves this at reconcile time: given the set of
 * existing remote top-level folder names, it maps a local folder name to the
 * cloud-canonical name when the two are locale aliases of the same XDG dir.
 *
 * ## Design constraints (injectable seam)
 *
 * - The alias groups and the user-dirs.dirs content are passed in, not
 *   read from the host filesystem inside the engine.  Tests inject synthetic
 *   data; production wires the real path via [fromUserDirsFile].
 * - Only top-level folders are aliased (XDG user dirs are always immediate
 *   children of HOME / the sync root, never nested).
 * - Canonical-selection rule (MVP): **the existing cloud folder wins**.  If
 *   the cloud already holds any alias of the same XDG dir, adopt its name.
 *   User-configurable overrides are Phase-2 and out of scope here.
 */

// ---------------------------------------------------------------------------
// Static well-known alias table
// ---------------------------------------------------------------------------

/**
 * Per-XDG-key set of well-known folder names across common locales (en / de /
 * es / fr / it / pt / nl / pl / ru).  Each set is the equivalence class for
 * that logical directory — any two names in the same set are aliases of one
 * another.
 *
 * Extend as needed; the set is closed under locale expansion at any time
 * without touching engine logic.
 */
internal val XDG_ALIAS_GROUPS: List<Set<String>> = listOf(
    // XDG_DESKTOP_DIR
    setOf("Desktop", "Escritorio", "Bureau", "Schreibtisch", "Scrivania",
          "Área de Trabalho", "Bureaublad", "Pulpit", "Рабочий стол"),
    // XDG_DOWNLOAD_DIR  (also covers common variant "Downloads" en/de)
    setOf("Downloads", "Download", "Téléchargements", "Descargas",
          "Scaricati", "Transferências", "Pobrane", "Загрузки"),
    // XDG_DOCUMENTS_DIR
    setOf("Documents", "Dokumente", "Documentos", "Mes documents",
          "Documenti", "Documentos", "Documenten", "Dokumenty", "Документы"),
    // XDG_MUSIC_DIR
    setOf("Music", "Musik", "Música", "Musique", "Musica",
          "Música", "Muziek", "Muzyka", "Музыка"),
    // XDG_PICTURES_DIR
    setOf("Pictures", "Bilder", "Imágenes", "Images", "Foto", "Immagini",
          "Imagens", "Afbeeldingen", "Obrazy", "Изображения"),
    // XDG_VIDEOS_DIR
    setOf("Videos", "Video", "Vidéos", "Vídeos", "Video",
          "Vídeos", "Video's", "Wideo", "Видео"),
    // XDG_TEMPLATES_DIR
    setOf("Templates", "Vorlagen", "Plantillas", "Modèles", "Modelli",
          "Modelos", "Sjablonen", "Szablony", "Шаблоны"),
    // XDG_PUBLICSHARE_DIR
    setOf("Public", "Öffentlich", "Público", "Public", "Pubblica",
          "Público", "Openbaar", "Publiczny", "Общедоступные"),
)

// Flat reverse-lookup: name (lower-case) → the alias group it belongs to.
// Built once from XDG_ALIAS_GROUPS.
internal val XDG_NAME_TO_GROUP: Map<String, Set<String>> by lazy {
    val m = HashMap<String, Set<String>>()
    for (group in XDG_ALIAS_GROUPS) {
        for (name in group) m[name.lowercase()] = group
    }
    m
}

// ---------------------------------------------------------------------------
// user-dirs.dirs parser
// ---------------------------------------------------------------------------

/**
 * Parse a `~/.config/user-dirs.dirs`-format file and return a map of
 * `XDG_*_DIR → unquoted folder name (basename only)`.
 *
 * Lines look like:
 * ```
 * XDG_PICTURES_DIR="$HOME/Bilder"
 * XDG_DOWNLOAD_DIR="$HOME/Downloads"
 * ```
 *
 * We extract the basename (last path component) and strip the quotes.
 * Lines starting with `#` and blank lines are ignored.
 *
 * Returns an empty map when the file does not exist or cannot be read.
 */
fun parseUserDirsFile(path: Path): Map<String, String> {
    if (!Files.exists(path)) return emptyMap()
    return try {
        Files.readAllLines(path)
            .filter { it.isNotBlank() && !it.trimStart().startsWith('#') }
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq < 0) return@mapNotNull null
                val key = line.substring(0, eq).trim()
                if (!key.startsWith("XDG_") || !key.endsWith("_DIR")) return@mapNotNull null
                // Value is quoted, may contain $HOME prefix.
                val raw = line.substring(eq + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                // Extract basename — the folder name relative to HOME.
                val basename = raw
                    .removePrefix("\$HOME/")
                    .removePrefix("~/")
                    .trimEnd('/')
                    .substringAfterLast('/')
                    .ifEmpty { null } ?: return@mapNotNull null
                key to basename
            }.toMap()
    } catch (e: Exception) {
        LoggerFactory.getLogger("XdgUserDirs").warn("Could not parse user-dirs file {}: {}", path, e.message)
        emptyMap()
    }
}

// ---------------------------------------------------------------------------
// XdgLocaleDirAliases
// ---------------------------------------------------------------------------

/**
 * Resolves local folder names to their cloud-canonical equivalents when the
 * two are locale aliases of the same XDG user dir.
 *
 * @param localToCanonical  Ready-built mapping `localFolderName →
 *   cloudCanonicalFolderName`.  Computed externally (see [build]) so the
 *   engine can share one instance across reconcile calls.
 */
class XdgLocaleDirAliases private constructor(
    private val localToCanonical: Map<String, String>,
) {
    /**
     * Returns the canonical cloud folder name for [localName], or `null` if
     * no alias mapping applies (the local name IS the canonical, or is not
     * an XDG alias at all).
     */
    fun canonicalFor(localName: String): String? = localToCanonical[localName]

    /** True when this instance has no mappings (fast-path no-op). */
    val isEmpty: Boolean get() = localToCanonical.isEmpty()

    /**
     * Translate a sync-root-relative path by substituting the top-level
     * component when it is an aliased local name.
     *
     * `/Bilder/Urlaub/photo.jpg`  →  `/Pictures/Urlaub/photo.jpg`
     * `/Pictures/foo.txt`         →  `/Pictures/foo.txt`  (no change)
     * `/Other/file.txt`           →  `/Other/file.txt`    (no alias)
     *
     * Only top-level components are translated — XDG user dirs are always
     * immediate children of the sync root.
     */
    fun translatePath(path: String): String {
        if (isEmpty) return path
        val noSlash = path.removePrefix("/")
        val slash = noSlash.indexOf('/')
        val top = if (slash < 0) noSlash else noSlash.substring(0, slash)
        val canonical = localToCanonical[top] ?: return path
        val rest = if (slash < 0) "" else noSlash.substring(slash) // includes leading '/'
        return "/$canonical$rest"
    }

    companion object {
        /** A no-op instance that never translates anything. */
        val NONE: XdgLocaleDirAliases = XdgLocaleDirAliases(emptyMap())

        /**
         * Build an [XdgLocaleDirAliases] from the existing remote top-level
         * folder names and an optional user-dirs.dirs override mapping.
         *
         * @param remoteTopLevelNames  Names of top-level folders that already
         *   exist in the cloud (canonical names, as reported by the provider).
         * @param userDirsOverrides  Explicit `XDG_*_DIR → basename` mapping
         *   from `user-dirs.dirs` (or a synthetic equivalent injected in
         *   tests).  When a key appears here, its value is trusted as the
         *   current locale name and used directly instead of relying on the
         *   static alias table alone.
         * @param aliasGroups  Override for the static [XDG_ALIAS_GROUPS] —
         *   injected in tests; defaults to the production table.
         */
        fun build(
            remoteTopLevelNames: Set<String>,
            userDirsOverrides: Map<String, String> = emptyMap(),
            aliasGroups: List<Set<String>> = XDG_ALIAS_GROUPS,
        ): XdgLocaleDirAliases {
            if (remoteTopLevelNames.isEmpty()) return NONE

            // Build reverse lookup for this call's aliasGroups.
            val nameToGroup = HashMap<String, Set<String>>()
            for (group in aliasGroups) {
                for (name in group) nameToGroup[name.lowercase()] = group
            }

            val mapping = HashMap<String, String>()

            // For each remote top-level folder, check if it belongs to an alias
            // group.  If so, any OTHER name in that group is a potential local
            // alias that should be translated to the canonical remote name.
            for (remoteName in remoteTopLevelNames) {
                val group = nameToGroup[remoteName.lowercase()] ?: continue
                // Find the canonical name — the remote name itself.
                val canonical = group.firstOrNull { it.equals(remoteName, ignoreCase = true) }
                    ?: continue
                for (alias in group) {
                    if (alias.equals(canonical, ignoreCase = true)) continue
                    // Don't override another remote folder that happens to share the
                    // alias name — that would be a genuine distinct folder, not an alias.
                    if (alias in remoteTopLevelNames) continue
                    mapping[alias] = canonical
                }
            }

            // user-dirs.dirs overrides: if the OS says "XDG_PICTURES_DIR=Bilder" and
            // "Bilder" is not already mapped (e.g. the static table covers it), add it
            // explicitly.  This handles locale names the static table doesn't yet cover.
            for ((_, localName) in userDirsOverrides) {
                if (localName in mapping) continue          // already covered
                if (localName in remoteTopLevelNames) continue // IS the canonical
                val group = nameToGroup[localName.lowercase()] ?: continue
                // Find which remote name from the same group is the canonical.
                val canonical = remoteTopLevelNames.firstOrNull { r ->
                    group.any { it.equals(r, ignoreCase = true) }
                } ?: continue
                mapping[localName] = canonical
            }

            return if (mapping.isEmpty()) NONE else XdgLocaleDirAliases(mapping)
        }

        /**
         * Production factory: reads the real `~/.config/user-dirs.dirs` file.
         */
        fun fromUserDirsFile(
            remoteTopLevelNames: Set<String>,
            userDirsPath: Path = Paths.get(
                System.getenv("HOME") ?: System.getProperty("user.home"),
                ".config", "user-dirs.dirs",
            ),
            aliasGroups: List<Set<String>> = XDG_ALIAS_GROUPS,
        ): XdgLocaleDirAliases =
            build(
                remoteTopLevelNames = remoteTopLevelNames,
                userDirsOverrides = parseUserDirsFile(userDirsPath),
                aliasGroups = aliasGroups,
            )
    }
}
