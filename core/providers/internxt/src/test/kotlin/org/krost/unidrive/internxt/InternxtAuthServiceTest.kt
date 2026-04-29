package org.krost.unidrive.internxt

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Refresh-race invariants.
 *
 * These tests decompose the refresh race into two orthogonal invariants per
 * global CLAUDE.md rule: a single fix can regress one invariant while satisfying
 * the other, so each gets a dedicated named test.
 *
 *   1. `refreshToken_serializes_concurrent_callers_into_single_http_roundtrip`
 *      — under N concurrent callers, only ONE refresh network call fires.
 *      If this test regresses, we waste refresh calls and risk hitting rate
 *      limits or the server invalidating one of two concurrent tokens.
 *
 *   2. `refreshToken_returns_consistent_credentials_to_all_concurrent_callers`
 *      — under N concurrent callers, all returned credentials have the same
 *      post-refresh jwt. If this test regresses, some callers walk away with
 *      a stale jwt (or the loser's jwt from a lost race) and subsequent API
 *      calls fail.
 *
 * IF EITHER TEST IS REMOVED OR LOOSENED, the corresponding invariant silently
 * regresses. Both must exist.
 */
class InternxtAuthServiceTest {
    /**
     * Fake that counts network roundtrips and emits a monotonically increasing
     * jwt per call. No real HTTP. The first caller through the critical section
     * sees call #1; any caller that was racing should NOT observe a second call.
     */
    private class CountingAuthService(
        config: InternxtConfig,
        val callCount: AtomicInteger = AtomicInteger(0),
        val perCallDelayMs: Long = 50,
    ) : AuthService(config) {
        override suspend fun fetchRefreshedJwt(currentJwt: String): String {
            val n = callCount.incrementAndGet()
            // Force a race window: all concurrent callers enter refreshToken()
            // before any of them completes the "network call".
            delay(perCallDelayMs)
            return "refreshed-jwt-#$n"
        }
    }

    private fun seedCredentials(
        tmp: Path,
        jwt: String = "stale-jwt",
    ) {
        val creds =
            InternxtCredentials(
                jwt = jwt,
                mnemonic = "test-mnemonic",
                rootFolderId = "test-root",
                email = "test@example.com",
            )
        Files.createDirectories(tmp)
        val file = tmp.resolve("credentials.json")
        Files.writeString(file, Json.encodeToString(InternxtCredentials.serializer(), creds))
    }

    @Test
    fun `refreshToken serializes concurrent callers into single http roundtrip`() =
        runBlocking {
            val tmp = Files.createTempDirectory("internxt-auth-race-")
            try {
                seedCredentials(tmp)
                val auth = CountingAuthService(InternxtConfig(tokenPath = tmp))
                auth.initialize()

                // Fan out N concurrent refreshToken() calls.
                val n = 20
                coroutineScope {
                    (1..n)
                        .map {
                            async { auth.refreshToken() }
                        }.awaitAll()
                }

                assertEquals(
                    1,
                    auth.callCount.get(),
                    "Expected exactly one network refresh roundtrip under $n concurrent callers, " +
                        "got ${auth.callCount.get()}. The refresh critical section is not mutually exclusive.",
                )
            } finally {
                Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }

    @Test
    fun `refreshToken returns consistent credentials to all concurrent callers`() =
        runBlocking {
            val tmp = Files.createTempDirectory("internxt-auth-consistency-")
            try {
                seedCredentials(tmp)
                val auth = CountingAuthService(InternxtConfig(tokenPath = tmp))
                auth.initialize()

                val n = 20
                val results =
                    coroutineScope {
                        (1..n)
                            .map {
                                async { auth.refreshToken() }
                            }.awaitAll()
                    }

                val distinctJwts = results.map { it.jwt }.toSet()
                assertEquals(
                    1,
                    distinctJwts.size,
                    "Expected all $n concurrent callers to receive the same refreshed jwt, " +
                        "got ${distinctJwts.size} distinct values: $distinctJwts. " +
                        "Some caller saw a stale or lost-race jwt.",
                )
                assertTrue(
                    distinctJwts.single().startsWith("refreshed-jwt-#"),
                    "Expected refreshed jwt, got stale value: ${distinctJwts.single()}",
                )
                // And the in-memory credentials must match what callers saw.
                assertEquals(distinctJwts.single(), auth.currentCredentials?.jwt)
            } finally {
                Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
}
