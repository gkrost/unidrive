package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** #463: `daemon_poll_seconds` in the profile section — how often `daemon run` polls the cloud. */
class SyncConfigDaemonPollTest {
    private fun parse(
        providerBody: String,
        profile: String = "p",
    ) = SyncConfig.parse("[providers.$profile]\ntype = \"internxt\"\n$providerBody\n", profile)

    @Test
    fun `absent means the daemon polls every 60 seconds`() {
        assertEquals(60, SyncConfig.DEFAULT_DAEMON_POLL_SECONDS)
        assertEquals(60, parse("").daemonPollSeconds("p"))
    }

    @Test
    fun `an explicit value is read per profile`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\ndaemon_poll_seconds = 300\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )
        assertEquals(300, config.daemonPollSeconds("a"))
        assertEquals(SyncConfig.DEFAULT_DAEMON_POLL_SECONDS, config.daemonPollSeconds("b"))
    }

    @Test
    fun `zero turns the poll off and a negative value is read as zero`() {
        assertEquals(0, parse("daemon_poll_seconds = 0").daemonPollSeconds("p"))
        assertEquals(0, parse("daemon_poll_seconds = -5").daemonPollSeconds("p"))
    }
}
