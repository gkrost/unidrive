package org.krost.unidrive.hydration

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #560 U3: the mount front-end runs on its host's shared core and there is one of it per host, as when the
 * operations lived in SyncEngine. Two front-ends over one engine would each have their own per-path hydrate
 * locks (#318) and their own rescan guard (#504).
 */
class MountEngineWiringTest {
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("ud-u3-wiring-db").resolve("state.db")).also { it.initialize() }
        engine =
            SyncEngine(
                provider = MinimalFakeProvider(),
                db = db,
                syncRoot = Files.createTempDirectory("ud-u3-wiring-root"),
                cacheRoot = Files.createTempDirectory("ud-u3-wiring-cache"),
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `there is one mount front-end per engine`() {
        val first = MountEngine.over(engine)
        assertSame(first, MountEngine.over(engine))
        assertSame(engine.mountWiring, engine.mountWiring, "the wiring is built once per engine")

        val other = SyncEngine(provider = MinimalFakeProvider(), db = db, syncRoot = Files.createTempDirectory("ud-u3-other"))
        assertTrue(MountEngine.over(other) !== first, "another engine has its own")
    }

    @Test
    fun `the mount front-end resolves cache paths with the engine's layout`() {
        assertEquals(engine.resolveCachePath("/a/b.txt"), MountEngine.over(engine).resolveCachePath("/a/b.txt"))
    }
}
