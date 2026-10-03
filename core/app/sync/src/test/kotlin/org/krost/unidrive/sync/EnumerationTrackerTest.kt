package org.krost.unidrive.sync

import org.krost.unidrive.ScanProgress
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnumerationTrackerTest {
    private var now = 1_000_000L
    private val tracker = EnumerationTracker(clock = { now })

    private fun after(seconds: Long) {
        now += seconds * 1_000
    }

    // One report per second of a listing that gains [perSecond] items each second.
    private fun listItems(
        seconds: Int,
        perSecond: Int,
        listing: String? = null,
        from: Int = 0,
    ) {
        for (i in 1..seconds) {
            after(1)
            tracker.onProgress(ScanProgress(items = from + i * perSecond, listing = listing))
        }
    }

    // A walk that lists [foldersPerSecond] folders per second and has [queued] more waiting.
    private fun walkFolders(
        seconds: Int,
        foldersPerSecond: Int,
        queued: Int,
        itemsPerSecond: Int = 50,
    ) {
        for (i in 1..seconds) {
            after(1)
            val done = i * foldersPerSecond
            tracker.onProgress(
                ScanProgress(
                    items = i * itemsPerSecond,
                    foldersDone = done,
                    foldersKnown = done + queued,
                    foldersSkipped = 0,
                    listing = ScanProgress.LISTING_TREE,
                ),
            )
        }
    }

    // ---- what a fresh tracker and a beginning attempt say ---------------------------------------------------------

    @Test
    fun `a tracker that has seen nothing is idle and the first enumeration is still to come`() {
        val s = tracker.snapshot()

        assertEquals(EnumerationStatus(state = EnumerationStatus.State.IDLE, first = true, attempt = 0), s)
    }

    @Test
    fun `a beginning attempt is running, counted, and its time runs`() {
        tracker.begin()
        after(5)

        val s = tracker.snapshot()

        assertEquals(EnumerationStatus.State.RUNNING, s.state)
        assertEquals(1, s.attempt)
        assertEquals(EnumerationStatus.Phase.LISTING, s.phase)
        assertEquals(now - 5_000, s.startedAtMs)
        assertEquals(5_000L, s.elapsedMs)
        assertEquals(0, s.items)
        assertNull(s.ratePerS)
        assertNull(s.etaS)
    }

    @Test
    fun `the baseline tells whether this is the first enumeration`() {
        tracker.begin()
        tracker.baseline(first = false, expected = null)

        assertFalse(tracker.snapshot().first)
    }

    // ---- the rate ---------------------------------------------------------------------------------------------------

    @Test
    fun `the rate is unknown before ten seconds of data and known from then on`() {
        tracker.begin()

        listItems(seconds = 9, perSecond = 100)
        assertNull(tracker.snapshot().ratePerS, "nine seconds of data are too few")

        listItems(seconds = 1, perSecond = 100, from = 900)
        assertEquals(100.0, tracker.snapshot().ratePerS)
    }

    @Test
    fun `the rate follows the last minute, not the whole run`() {
        tracker.begin()
        listItems(seconds = 60, perSecond = 100)
        listItems(seconds = 60, perSecond = 300, from = 6_000)

        assertEquals(300.0, tracker.snapshot().ratePerS, "the first minute is out of the window")
    }

    @Test
    fun `a listing that stopped gaining items has no rate once the window holds nothing but the standstill`() {
        tracker.begin()
        listItems(seconds = 30, perSecond = 100)

        after(70)

        assertNull(tracker.snapshot().ratePerS)
    }

    @Test
    fun `callbacks arriving faster than the sampling interval do not distort the rate`() {
        tracker.begin()
        // 100 reports per second for ten seconds, 1000 items per second.
        for (second in 1..10) {
            for (step in 1..100) {
                now += 10
                tracker.onProgress(ScanProgress(items = (second - 1) * 1_000 + step * 10))
            }
        }

        assertEquals(1_000.0, tracker.snapshot().ratePerS)
    }

    @Test
    fun `a fallback to another listing starts the window over`() {
        tracker.begin()
        listItems(seconds = 30, perSecond = 100, listing = ScanProgress.LISTING_ACCOUNT)
        // The account-wide listing was cut: the tree walk counts from nothing.
        after(1)
        tracker.onProgress(ScanProgress(items = 0, foldersDone = 0, foldersKnown = 1, listing = ScanProgress.LISTING_TREE))
        for (i in 1..10) {
            after(1)
            tracker.onProgress(
                ScanProgress(items = i * 50, foldersDone = i, foldersKnown = i + 5, listing = ScanProgress.LISTING_TREE),
            )
        }

        val s = tracker.snapshot()

        assertEquals(ScanProgress.LISTING_TREE, s.listing)
        assertEquals(500, s.items)
        assertEquals(50.0, s.ratePerS, "the 3000 items of the cut listing are not part of the tree walk's rate")
    }

    // ---- the estimate -----------------------------------------------------------------------------------------------

    @Test
    fun `the queue of a folder walk gives a lower bound of the time left`() {
        tracker.begin()
        tracker.baseline(first = true, expected = null)

        walkFolders(seconds = 20, foldersPerSecond = 10, queued = 300)

        val s = tracker.snapshot()
        assertEquals(200, s.foldersDone)
        assertEquals(500, s.foldersKnown)
        assertEquals(300L / 10, s.etaS, "300 folders queued, 10 listed per second")
        assertEquals(EnumerationStatus.EtaKind.LOWER_BOUND, s.etaKind)
    }

    @Test
    fun `a walk whose queue is empty has no estimate`() {
        tracker.begin()

        walkFolders(seconds = 20, foldersPerSecond = 10, queued = 0)

        val s = tracker.snapshot()
        assertNull(s.etaS)
        assertNull(s.etaKind)
    }

    @Test
    fun `a previous complete run turns the estimate of a walk into an estimate against its folder total`() {
        tracker.begin()
        tracker.baseline(first = true, expected = EnumerationTracker.Expected(items = 50_000, folders = 1_000, durationMs = null))

        walkFolders(seconds = 20, foldersPerSecond = 10, queued = 300)

        val s = tracker.snapshot()
        assertEquals((1_000 - 200) / 10L, s.etaS, "800 of the previous run's 1000 folders are left")
        assertEquals(EnumerationStatus.EtaKind.ESTIMATE, s.etaKind)
    }

    @Test
    fun `a walk that outgrew the previous total is a lower bound again`() {
        tracker.begin()
        tracker.baseline(first = true, expected = EnumerationTracker.Expected(items = 5_000, folders = 100, durationMs = null))

        walkFolders(seconds = 20, foldersPerSecond = 10, queued = 300)

        val s = tracker.snapshot()
        assertEquals(EnumerationStatus.EtaKind.LOWER_BOUND, s.etaKind, "500 folders are known, the old total said 100")
        assertEquals(300L / 10, s.etaS)
    }

    @Test
    fun `an account-wide listing has an estimate only when a previous complete run gives the total`() {
        tracker.begin()
        tracker.baseline(first = true, expected = null)
        listItems(seconds = 20, perSecond = 10, listing = ScanProgress.LISTING_ACCOUNT)
        assertNull(tracker.snapshot().etaS, "no total, no estimate")

        tracker.begin()
        tracker.baseline(first = true, expected = EnumerationTracker.Expected(items = 1_000, folders = null, durationMs = null))
        listItems(seconds = 20, perSecond = 10, listing = ScanProgress.LISTING_ACCOUNT)

        val s = tracker.snapshot()
        assertEquals((1_000 - 200) / 10L, s.etaS)
        assertEquals(EnumerationStatus.EtaKind.ESTIMATE, s.etaKind)
    }

    @Test
    fun `a listing past the previous total has no estimate`() {
        tracker.begin()
        tracker.baseline(first = true, expected = EnumerationTracker.Expected(items = 100, folders = null, durationMs = null))

        listItems(seconds = 20, perSecond = 50, listing = ScanProgress.LISTING_ACCOUNT)

        assertNull(tracker.snapshot().etaS)
    }

    @Test
    fun `without a rate there is no estimate from the queue`() {
        tracker.begin()

        walkFolders(seconds = 5, foldersPerSecond = 10, queued = 300)

        val s = tracker.snapshot()
        assertNull(s.ratePerS)
        assertNull(s.etaS)
    }

    @Test
    fun `until the rate is known the duration of the previous run scales to what is left`() {
        tracker.begin()
        tracker.baseline(first = true, expected = EnumerationTracker.Expected(items = 1_000, folders = null, durationMs = 100_000))

        listItems(seconds = 5, perSecond = 50, listing = ScanProgress.LISTING_ACCOUNT)

        val s = tracker.snapshot()
        assertNull(s.ratePerS)
        assertEquals(75L, s.etaS, "750 of 1000 items left, the previous run took 100 s")
        assertEquals(EnumerationStatus.EtaKind.ESTIMATE, s.etaKind)
    }

    @Test
    fun `a stalled listing does not borrow the previous duration`() {
        tracker.begin()
        tracker.baseline(first = true, expected = EnumerationTracker.Expected(items = 1_000, folders = null, durationMs = 100_000))
        listItems(seconds = 20, perSecond = 10, listing = ScanProgress.LISTING_ACCOUNT)
        after(120)

        val s = tracker.snapshot()

        assertNull(s.ratePerS)
        assertNull(s.etaS, "the standstill is not covered by the earlier estimate")
    }

    // ---- the phases ------------------------------------------------------------------------------------------------

    @Test
    fun `saving ends the rate and the estimate and says how long the listing took`() {
        tracker.begin()
        tracker.baseline(first = true, expected = EnumerationTracker.Expected(items = 1_000, folders = null, durationMs = null))
        listItems(seconds = 20, perSecond = 10, listing = ScanProgress.LISTING_ACCOUNT)

        val listingMs = tracker.saving(finalItems = 777)

        assertEquals(20_000L, listingMs)
        val s = tracker.snapshot()
        assertEquals(EnumerationStatus.Phase.SAVING, s.phase)
        assertEquals(777, s.items)
        assertNull(s.ratePerS)
        assertNull(s.etaS)
    }

    @Test
    fun `reports that arrive after the listing ended change nothing`() {
        tracker.begin()
        tracker.saving(finalItems = 10)

        tracker.onProgress(ScanProgress(items = 99, listing = ScanProgress.LISTING_TREE))
        tracker.onItems(98)

        val s = tracker.snapshot()
        assertEquals(10, s.items)
        assertNull(s.listing)
    }

    // ---- where the items come from -------------------------------------------------------------------------------------

    @Test
    fun `page callbacks count the items until a provider reports, then the provider is believed`() {
        tracker.begin()
        tracker.onItems(500)
        assertEquals(500, tracker.snapshot().items)
        assertNull(tracker.snapshot().listing, "the page callback does not name a listing")

        tracker.onProgress(ScanProgress(items = 700, listing = ScanProgress.LISTING_ACCOUNT))
        tracker.onItems(600)

        val s = tracker.snapshot()
        assertEquals(700, s.items, "a late page callback must not undo what the provider said")
        assertEquals(ScanProgress.LISTING_ACCOUNT, s.listing)
    }

    @Test
    fun `folder counts stay consistent however the provider rounds them`() {
        tracker.begin()

        tracker.onProgress(
            ScanProgress(items = 10, foldersDone = 8, foldersKnown = 5, foldersSkipped = 20, listing = ScanProgress.LISTING_TREE),
        )

        val s = tracker.snapshot()
        assertEquals(8, s.foldersDone)
        assertEquals(8, s.foldersKnown, "known is never below done")
        assertEquals(8, s.foldersSkipped, "skipped is never above done")
    }

    // ---- how an attempt ends ---------------------------------------------------------------------------------------

    @Test
    fun `failures accumulate attempts and a success starts the count over`() {
        tracker.begin()
        tracker.failed("boom")
        assertEquals(1, tracker.snapshot().attempt)
        assertEquals(EnumerationStatus.State.FAILED, tracker.snapshot().state)

        tracker.begin()
        assertEquals(2, tracker.snapshot().attempt, "the running attempt is counted")
        tracker.failed("boom again")
        assertEquals(2, tracker.snapshot().attempt)

        tracker.begin()
        assertEquals(3, tracker.snapshot().attempt)
        after(3)
        tracker.succeeded()

        val s = tracker.snapshot()
        assertEquals(EnumerationStatus.State.IDLE, s.state)
        assertEquals(0, s.attempt)
        assertFalse(s.first, "the first enumeration has completed")
        assertEquals(now, s.lastSuccessAtMs)
        assertNull(s.lastError, "a success ends the story of the failures")
        assertNull(s.items, "an idle tracker shows no progress")

        tracker.begin()
        assertEquals(1, tracker.snapshot().attempt, "the next attempt after a success is the first again")
    }

    @Test
    fun `a failure keeps what the attempt had reached and carries a sanitised reason`() {
        tracker.begin()
        tracker.onProgress(ScanProgress(items = 4_000, foldersDone = 30, foldersKnown = 90, foldersSkipped = 2, listing = ScanProgress.LISTING_TREE))
        after(2)

        tracker.failed("cannot list /Secret/Plans: connection closed")

        val s = tracker.snapshot()
        assertEquals(EnumerationStatus.State.FAILED, s.state)
        assertEquals(4_000, s.items)
        assertEquals(30, s.foldersDone)
        assertEquals(90, s.foldersKnown)
        assertEquals(2, s.foldersSkipped)
        assertEquals(ScanProgress.LISTING_TREE, s.listing)
        assertEquals("cannot list <path>: connection closed", s.lastError)
        assertNull(s.phase)
        assertNull(s.elapsedMs)
        assertNull(s.ratePerS)
        assertNull(s.etaS)
    }

    @Test
    fun `the poller's next attempt is shown only after a failure and dropped when a run starts`() {
        tracker.begin()
        tracker.failed("boom")
        tracker.nextAttemptAt(now + 240_000)
        assertEquals(now + 240_000, tracker.snapshot().nextAttemptAtMs)

        tracker.begin()
        assertNull(tracker.snapshot().nextAttemptAtMs, "the retry is under way")
        tracker.failed("boom")
        assertNull(tracker.snapshot().nextAttemptAtMs, "a new failure does not inherit the old time")

        tracker.begin()
        tracker.nextAttemptAt(now + 5)
        assertNull(tracker.snapshot().nextAttemptAtMs, "a time recorded while running is not shown")
    }

    @Test
    fun `a cancelled attempt is neither a success nor a failure and still counts as an attempt`() {
        tracker.begin()
        tracker.onItems(40)

        tracker.aborted()

        val s = tracker.snapshot()
        assertEquals(EnumerationStatus.State.IDLE, s.state)
        assertEquals(1, s.attempt)
        assertNull(s.lastSuccessAtMs)
        assertNull(s.lastError)
        assertNull(s.items)
    }

    @Test
    fun `cancelling a finished attempt changes nothing`() {
        tracker.begin()
        tracker.failed("boom")

        tracker.aborted()

        assertEquals(EnumerationStatus.State.FAILED, tracker.snapshot().state)
    }

    @Test
    fun `the last error survives the retry that follows it`() {
        tracker.begin()
        tracker.failed("boom")

        tracker.begin()

        val s = tracker.snapshot()
        assertEquals(EnumerationStatus.State.RUNNING, s.state)
        assertEquals("boom", s.lastError)
    }

    // ---- sanitising the reason -------------------------------------------------------------------------------------

    @Test
    fun `the reason names no path, whichever the platform`() {
        val cases =
            mapOf(
                "Permission denied: /home/jane/Documents/secret.pdf" to "Permission denied: <path>",
                "java.io.FileNotFoundException: C:\\Users\\Jane Doe\\a b.txt (The system cannot find the file specified)" to
                    "java.io.FileNotFoundException: <path> (The system cannot find the file specified)",
                "cannot read \\\\nas\\share\\tax\\2025.pdf" to "cannot read <path>",
                "Failed to open /a/b.txt for reading" to "Failed to open <path>",
                "sync_path '/Documents/Taxes 2025' not found on the remote: no folder 'Taxes 2025'" to
                    "sync_path <name> not found on the remote: no folder <name>",
                "Folder \"My Taxes\" is gone" to "Folder <name> is gone",
            )

        for ((raw, expected) in cases) {
            assertEquals(expected, EnumerationTracker.sanitizeError(raw), raw)
        }
    }

    @Test
    fun `the reason names no address or account`() {
        assertEquals(
            "GET <url> failed: 503",
            EnumerationTracker.sanitizeError("GET https://gateway.example.invalid/drive/folders/content/3f2a?limit=50 failed: 503"),
        )
        assertEquals("login failed for <address>", EnumerationTracker.sanitizeError("login failed for jane@example.invalid"))
    }

    @Test
    fun `an apostrophe or a fraction is not mistaken for a name or a path`() {
        assertEquals(
            "couldn't parse <name> at 1/2",
            EnumerationTracker.sanitizeError("couldn't parse 'abc' at 1/2"),
        )
        assertEquals("server said application/json", EnumerationTracker.sanitizeError("server said application/json"))
    }

    @Test
    fun `the reason is one line of at most 200 characters`() {
        assertEquals("first line", EnumerationTracker.sanitizeError("first line\n\tat org.example.Foo(Foo.kt:12)\r\n"))
        assertEquals("a b c", EnumerationTracker.sanitizeError("a \t b   c"))
        val long = EnumerationTracker.sanitizeError("x".repeat(500))
        assertNotNull(long)
        assertEquals(EnumerationTracker.ERROR_MAX_CHARS, long.length)
        assertTrue(long.endsWith("..."))
        assertEquals("y".repeat(200), EnumerationTracker.sanitizeError("y".repeat(200)), "200 characters fit as they are")
    }

    @Test
    fun `a reason with nothing left to say is none`() {
        assertNull(EnumerationTracker.sanitizeError(null))
        assertNull(EnumerationTracker.sanitizeError(""))
        assertNull(EnumerationTracker.sanitizeError("  \n \n"))
    }

    // ---- threads ---------------------------------------------------------------------------------------------------

    @Test
    fun `writers on several threads and a reader never see a broken snapshot`() {
        val live = EnumerationTracker()
        live.begin()
        live.baseline(first = true, expected = EnumerationTracker.Expected(items = 1_000_000, folders = 50_000, durationMs = 600_000))
        val failure = AtomicReference<Throwable?>(null)
        val writers =
            (1..4).map { id ->
                thread {
                    runCatching {
                        repeat(5_000) { i ->
                            live.onProgress(
                                ScanProgress(
                                    items = i * 4 + id,
                                    foldersDone = i,
                                    foldersKnown = i / 2,
                                    foldersSkipped = i / 3,
                                    listing = ScanProgress.LISTING_TREE,
                                ),
                            )
                            if (i % 500 == 0) live.onItems(i)
                        }
                    }.onFailure { failure.compareAndSet(null, it) }
                }
            }
        val reader =
            thread {
                runCatching {
                    repeat(3_000) {
                        val s = live.snapshot()
                        assertEquals(EnumerationStatus.State.RUNNING, s.state)
                        val done = s.foldersDone
                        if (done != null) {
                            assertTrue(s.foldersKnown!! >= done, "known $s")
                            assertTrue(s.foldersSkipped!! <= done, "skipped $s")
                        }
                    }
                }.onFailure { failure.compareAndSet(null, it) }
            }
        (writers + reader).forEach { it.join(30_000) }

        failure.get()?.let { throw it }
        assertEquals(ScanProgress.LISTING_TREE, live.snapshot().listing)
    }
}
