package org.krost.unidrive.cli

import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.MDC
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HydrationUploadScopeMdcTest {
    // MDC is thread-local and several command tests in this module populate `profile` on the shared
    // worker thread without clearing it (Main.resolveCurrentProfile). Clear it around each test so the
    // "does not leak onto the caller" guard cannot pass or fail because of discovery order.
    @BeforeTest
    fun clearMdc() {
        MDC.remove("profile")
    }

    @AfterTest
    fun clearMdcAfter() {
        MDC.remove("profile")
    }

    @Test
    fun `hydration_upload_scope_carries_the_profile_mdc_across_suspensions`() =
        runBlocking {
            val scope = DaemonRuntime.hydrationUploadScope("qwprofile")
            try {
                val seen =
                    scope
                        .async {
                            val before = MDC.get("profile")
                            delay(5)
                            before to MDC.get("profile")
                        }.await()
                assertEquals("qwprofile" to "qwprofile", seen)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `hydration_upload_scope_does_not_leak_the_profile_mdc_onto_the_caller`() =
        runBlocking {
            val scope = DaemonRuntime.hydrationUploadScope("qwprofile")
            try {
                scope.async { delay(1) }.await()
                assertNull(MDC.get("profile"))
            } finally {
                scope.cancel()
            }
        }
}
