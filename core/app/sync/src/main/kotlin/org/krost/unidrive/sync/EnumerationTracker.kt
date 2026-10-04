package org.krost.unidrive.sync

import org.krost.unidrive.ScanProgress
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the signals of an enumeration (start, the providers' progress reports, the phase switch,
 * the way it ended) into the [EnumerationStatus] a client polls. Written from coroutines and read
 * from the IPC thread, so every access takes one lock; the time comes from [clock], which makes the
 * rate and the estimates reproducible.
 *
 * The rate is the item gain over the last [RATE_WINDOW_MS], known once [RATE_MIN_SPAN_MS] of data
 * exist and while it is above zero. Samples are taken at most once per [SAMPLE_INTERVAL_MS], so the
 * callbacks may arrive as often as they like. The window starts over when the items go down or the
 * listing strategy changes (a fallback restarts the count).
 */
class EnumerationTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A previous complete full enumeration: what a running one is measured against. */
    data class Expected(
        val items: Int,
        val folders: Int?,
        val durationMs: Long?,
    )

    private class Sample(
        val atMs: Long,
        val items: Int,
        val foldersDone: Int,
    )

    private class Target(
        val total: Int,
        val remaining: Int,
        val rate: Double?,
        val kind: EnumerationStatus.EtaKind,
    )

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    private var state = EnumerationStatus.State.IDLE
    private var phase = EnumerationStatus.Phase.LISTING
    private var attempt = 0
    private var first = true
    private var expected: Expected? = null
    private var startedAtMs: Long? = null
    private var listing: String? = null
    private var items = 0
    private var providerReported = false
    private var foldersDone: Int? = null
    private var foldersKnown: Int? = null
    private var foldersSkipped: Int? = null
    private var lastSuccessAtMs: Long? = null
    private var lastError: String? = null
    private var nextAttemptAtMs: Long? = null

    /** How long the listing of the running attempt took, frozen at its [saving] transition. */
    private var listingElapsedMs: Long? = null

    /** An attempt starts: counted, progress cleared, a pending retry time dropped. */
    fun begin() {
        synchronized(lock) {
            val now = clock()
            state = EnumerationStatus.State.RUNNING
            phase = EnumerationStatus.Phase.LISTING
            attempt++
            startedAtMs = now
            nextAttemptAtMs = null
            clearProgress()
            samples.addLast(Sample(now, 0, 0))
        }
    }

    /** What the running attempt starts from: whether it is the first one, and the previous complete run if any. */
    fun baseline(
        first: Boolean,
        expected: Expected?,
    ) {
        synchronized(lock) {
            this.first = first
            this.expected = expected
        }
    }

    /** A provider's progress report. Replaces what the page callbacks said: the provider knows better. */
    fun onProgress(progress: ScanProgress) {
        synchronized(lock) {
            if (!gathering()) return
            providerReported = true
            val strategyChanged = progress.listing != null && listing != null && progress.listing != listing
            progress.listing?.let { listing = it }
            foldersDone = progress.foldersDone?.coerceAtLeast(0)
            foldersKnown = progress.foldersKnown?.let { max(it, foldersDone ?: 0) }
            foldersSkipped = progress.foldersSkipped?.coerceAtLeast(0)?.let { min(it, foldersDone ?: it) }
            record(progress.items, restart = strategyChanged)
        }
    }

    /** The item count a page callback reports. Used while no provider reports progress itself. */
    fun onItems(count: Int) {
        synchronized(lock) {
            if (!gathering() || providerReported) return
            record(count, restart = false)
        }
    }

    /**
     * The listing is done and [finalItems] items go to state.db. Returns how long the listing took.
     * The rate and the estimate end here: they describe the listing. That duration is also what the
     * status keeps reporting for the elapsed: the listing is over, and a count that keeps growing
     * while state.db is written made the client show "found N items in 48 min" for what took 22
     * (unidrive-windows#136).
     */
    fun saving(finalItems: Int): Long =
        synchronized(lock) {
            val now = clock()
            phase = EnumerationStatus.Phase.SAVING
            items = max(finalItems, 0)
            samples.clear()
            (now - (startedAtMs ?: now)).also { listingElapsedMs = it }
        }

    /** The attempt completed. The next one counts from zero and the last failure is history. */
    fun succeeded() {
        synchronized(lock) {
            state = EnumerationStatus.State.IDLE
            attempt = 0
            first = false
            lastSuccessAtMs = clock()
            lastError = null
            nextAttemptAtMs = null
            clearProgress()
        }
    }

    /** The attempt failed. What it had reached stays visible; [reason] is sanitised. */
    fun failed(reason: String?) {
        synchronized(lock) {
            state = EnumerationStatus.State.FAILED
            attempt = max(attempt, 1)
            lastError = sanitizeError(reason)
            samples.clear()
        }
    }

    /** The attempt was cancelled (shutdown): neither a success nor a failure. */
    fun aborted() {
        synchronized(lock) {
            if (state != EnumerationStatus.State.RUNNING) return
            state = EnumerationStatus.State.IDLE
            clearProgress()
        }
    }

    /** When the poller will try again after a failure; null clears it. */
    fun nextAttemptAt(epochMs: Long?) {
        synchronized(lock) {
            nextAttemptAtMs = epochMs
        }
    }

    fun snapshot(): EnumerationStatus =
        synchronized(lock) {
            val now = clock()
            val running = state == EnumerationStatus.State.RUNNING
            val measuring = gathering()
            val (rate, folderRate) = if (measuring) rates(now) else null to null
            val eta = if (measuring) eta(now, rate, folderRate) else null
            val progressShown = state != EnumerationStatus.State.IDLE
            EnumerationStatus(
                state = state,
                first = first,
                attempt = attempt,
                phase = if (running) phase else null,
                listing = if (progressShown) listing else null,
                startedAtMs = startedAtMs,
                elapsedMs = if (running) {
                    startedAtMs?.let {
                        // #136: during the saving phase the listing is over — report its frozen
                        // duration, not a count that grows while state.db is written.
                        if (phase == EnumerationStatus.Phase.SAVING) {
                            listingElapsedMs
                        } else {
                            max(now - it, 0)
                        }
                    }
                } else null,
                items = if (progressShown) items else null,
                foldersDone = if (progressShown) foldersDone else null,
                foldersKnown = if (progressShown) foldersKnown else null,
                foldersSkipped = if (progressShown) foldersSkipped else null,
                ratePerS = rate,
                etaS = eta?.first,
                etaKind = eta?.second,
                lastSuccessAtMs = lastSuccessAtMs,
                lastError = lastError,
                nextAttemptAtMs = if (state == EnumerationStatus.State.FAILED) nextAttemptAtMs else null,
            )
        }

    // Running and still gathering: the only time progress reports mean anything.
    private fun gathering(): Boolean = state == EnumerationStatus.State.RUNNING && phase == EnumerationStatus.Phase.LISTING

    private fun clearProgress() {
        listing = null
        items = 0
        providerReported = false
        foldersDone = null
        foldersKnown = null
        foldersSkipped = null
        listingElapsedMs = null
        samples.clear()
    }

    private fun record(
        count: Int,
        restart: Boolean,
    ) {
        val now = clock()
        val newItems = max(count, 0)
        if (restart || newItems < items) {
            samples.clear()
            samples.addLast(Sample(now, newItems, foldersDone ?: 0))
            items = newItems
            return
        }
        items = newItems
        val last = samples.lastOrNull()
        if (last == null || now - last.atMs >= SAMPLE_INTERVAL_MS) {
            samples.addLast(Sample(now, newItems, foldersDone ?: 0))
            // Keep one sample at or before the window start: it is the baseline of a full-width window.
            while (samples.size > 2 && samples[1].atMs <= now - RATE_WINDOW_MS) samples.removeFirst()
        }
    }

    // Items and folders per second over the last minute, or null where the data is too young or flat.
    private fun rates(now: Long): Pair<Double?, Double?> {
        val base = windowBase(now) ?: return null to null
        val spanMs = now - base.atMs
        if (spanMs < RATE_MIN_SPAN_MS) return null to null
        val seconds = spanMs / 1000.0
        val itemRate = (items - base.items) / seconds
        val folderRate = foldersDone?.let { (it - base.foldersDone) / seconds }
        return itemRate.takeIf { it.isFinite() && it > 0 } to folderRate?.takeIf { it.isFinite() && it > 0 }
    }

    // The newest sample at or before the window start gives a full-width window; younger data starts at the oldest.
    private fun windowBase(now: Long): Sample? = samples.lastOrNull { it.atMs <= now - RATE_WINDOW_MS } ?: samples.firstOrNull()

    private fun isYoung(now: Long): Boolean = samples.firstOrNull()?.let { now - it.atMs < RATE_MIN_SPAN_MS } ?: false

    private fun target(
        rate: Double?,
        folderRate: Double?,
    ): Target? {
        val done = foldersDone
        val known = foldersKnown
        if (listing == ScanProgress.LISTING_TREE && done != null && known != null) {
            // A folder walk counts folders: the previous run's total while the queue has not outgrown it, the queue otherwise.
            val expectedFolders = expected?.folders
            if (expectedFolders != null && expectedFolders > known) {
                return Target(expectedFolders, expectedFolders - done, folderRate, EnumerationStatus.EtaKind.ESTIMATE)
            }
            return Target(known, known - done, folderRate, EnumerationStatus.EtaKind.LOWER_BOUND)
        }
        val expectedItems = expected?.items ?: return null
        return Target(expectedItems, expectedItems - items, rate, EnumerationStatus.EtaKind.ESTIMATE)
    }

    private fun eta(
        now: Long,
        rate: Double?,
        folderRate: Double?,
    ): Pair<Long, EnumerationStatus.EtaKind>? {
        val target = target(rate, folderRate) ?: return null
        if (target.remaining <= 0 || target.total <= 0) return null
        val seconds =
            when {
                target.rate != null -> target.remaining / target.rate
                // No rate yet: scale the previous run's duration by what is left of it.
                target.kind == EnumerationStatus.EtaKind.ESTIMATE && isYoung(now) ->
                    (expected?.durationMs ?: return null) / 1000.0 * target.remaining / target.total
                else -> return null
            }
        if (!seconds.isFinite() || seconds > ETA_MAX_S) return null
        return max(ceil(seconds).toLong(), 1L) to target.kind
    }

    companion object {
        const val RATE_WINDOW_MS: Long = 60_000
        const val RATE_MIN_SPAN_MS: Long = 10_000
        const val SAMPLE_INTERVAL_MS: Long = 1_000
        const val ERROR_MAX_CHARS: Int = 200

        // Beyond a year the figure only says that the rate is almost zero.
        private const val ETA_MAX_S: Double = 365.0 * 24 * 3600

        private val URL = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s'"<>]+""")
        private val EMAIL = Regex("""[^\s'"<>@]+@[^\s'"<>@]+\.[^\s'"<>@]+""")
        private val QUOTED = Regex("""(?<![A-Za-z0-9])(?:'[^'\r\n]*'|"[^"\r\n]*"|`[^`\r\n]*`)""")

        // A path runs to a quote, a bracket, a colon or a "(": the reason that follows it survives.
        private val PATH = Regex("""(?<![\w/\\.:-])(?:[A-Za-z]:[\\/]|\\\\|/(?=\S))[^'"<>|:(\[\]]*(?<=\S)""")
        private val WHITESPACE = Regex("""\s+""")

        /**
         * The first line of [raw] without anything that names a file, a folder, an address or an
         * account, collapsed to at most [ERROR_MAX_CHARS] characters; null when nothing is left.
         * Quoted text goes too: provider messages quote the names of folders and files.
         */
        fun sanitizeError(raw: String?): String? {
            val line = raw?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return null
            val clean =
                line
                    .replace(URL, "<url>")
                    .replace(EMAIL, "<address>")
                    .replace(QUOTED, "<name>")
                    .replace(PATH, "<path>")
                    .replace(WHITESPACE, " ")
                    .trim()
            if (clean.isEmpty()) return null
            return if (clean.length <= ERROR_MAX_CHARS) clean else clean.take(ERROR_MAX_CHARS - 3) + "..."
        }
    }
}
