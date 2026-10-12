package org.krost.unidrive.sync.ignore

/**
 * Rule files held in memory, with counters: what [IgnoreTree] asked for and how often. Not thread-safe (tests and
 * the benchmark ask from one thread).
 */
internal class MemoryIgnoreSource(
    files: Map<String, String> = emptyMap(),
) : IgnoreFileSource {
    private class Entry(
        val bytes: ByteArray,
        val modified: Long,
    )

    private val files = HashMap<String, Entry>()
    private val special = HashMap<String, RuleFileProbe>()
    private var clock = 1L
    val probes = HashMap<String, Int>()
    val reads = HashMap<String, Int>()

    init {
        files.forEach { (p, t) -> put(p, t) }
    }

    /** Writes [path]; a new modification time unless [modified] is given (to fake a change the stamp cannot see). */
    fun put(
        path: String,
        text: String,
        modified: Long = clock++,
    ) {
        special.remove(path)
        files[path] = Entry(text.toByteArray(Charsets.UTF_8), modified)
    }

    fun remove(path: String) {
        files.remove(path)
        special.remove(path)
    }

    /** Makes [path] answer [probe] (a symbolic link, an unreadable file). */
    fun fake(
        path: String,
        probe: RuleFileProbe,
    ) {
        special[path] = probe
    }

    fun modifiedOf(path: String): Long = files.getValue(path).modified

    val totalReads: Int get() = reads.values.sum()
    val totalProbes: Int get() = probes.values.sum()

    override fun probe(path: String): RuleFileProbe {
        probes.merge(path, 1, Int::plus)
        special[path]?.let { return it }
        val e = files[path] ?: return RuleFileProbe.Absent
        return RuleFileProbe.Present(e.bytes.size.toLong(), e.modified)
    }

    override fun read(
        path: String,
        maxBytes: Long,
    ): ByteArray? {
        reads.merge(path, 1, Int::plus)
        val e = files[path] ?: throw java.nio.file.NoSuchFileException(path)
        return if (e.bytes.size > maxBytes) null else e.bytes
    }
}
