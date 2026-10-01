package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `INTERNXT_NOTIFICATIONS=off` (also `0` / `false`) keeps the socket.io wake-signal
 * client from starting. Default behaviour (unset, or any other value) is unchanged.
 */
class InternxtNotificationsSwitchTest {
    @Test
    fun `unset and unrelated values keep notifications on`() {
        for (raw in listOf(null, "", "on", "1", "true", "yes", "offline", "enabled")) {
            assertTrue(InternxtConfig.parseNotificationsEnabled(raw), "'$raw' must not disable notifications")
        }
    }

    @Test
    fun `off 0 and false in any case and padding disable notifications`() {
        for (raw in listOf("off", "OFF", " Off ", "0", "false", "False", " FALSE\n")) {
            assertFalse(InternxtConfig.parseNotificationsEnabled(raw), "'$raw' must disable notifications")
        }
    }

    @Test
    fun `a disabled provider starts no notifications client and logs one INFO line`() =
        runBlocking {
            val tmp = Files.createTempDirectory("internxt-notif-off-")
            val logger = LoggerFactory.getLogger(InternxtProvider::class.java) as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)
            try {
                // A JWT that is valid for days, so authenticate() neither prompts nor refreshes.
                val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
                val exp = System.currentTimeMillis() / 1000 + 10L * 24 * 3600
                val jwt =
                    encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray()) + "." +
                        encoder.encodeToString("""{"exp":$exp}""".toByteArray()) + ".fake-signature"
                val creds = InternxtCredentials(jwt = jwt, mnemonic = "m", rootFolderId = "root", email = "t@example.invalid")
                Files.writeString(tmp.resolve("credentials.json"), Json.encodeToString(InternxtCredentials.serializer(), creds))

                val provider = InternxtProvider(InternxtConfig(tokenPath = tmp, notificationsEnabled = false))
                try {
                    provider.authenticate()
                    provider.authenticate()

                    val field = InternxtProvider::class.java.getDeclaredField("notificationsClient")
                    field.isAccessible = true
                    assertNull(field.get(provider), "no NotificationsClient may be built while switched off")
                    val infos =
                        appender.list.filter { it.level == Level.INFO && it.formattedMessage.contains("Internxt notifications disabled") }
                    assertEquals(1, infos.size, "one INFO line, not one per authenticate(): ${appender.list.map { it.formattedMessage }}")
                } finally {
                    provider.close()
                }
            } finally {
                logger.detachAppender(appender)
                Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
}
