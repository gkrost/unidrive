package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.PromptSpec
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Path
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigFileConcurrencyTest {
    private val localFactory =
        object : ProviderFactory {
            override val id = "localfs"
            override val metadata = ProviderMetadata("localfs", "Local filesystem", "test", "none", "none", "local", true, false, null, "test")

            override fun create(properties: Map<String, String?>, tokenPath: Path): CloudProvider =
                throw UnsupportedOperationException()

            override fun isAuthenticated(properties: Map<String, String?>, profileDir: Path): Boolean = false

            override fun credentialPrompts() = listOf(PromptSpec("root_path", "Root path"))
        }

    @Test
    fun `concurrent profile creation preserves every config update`() {
        withFactory {
            val configDir = Files.createTempDirectory("config-concurrency")
            try {
                Files.writeString(configDir.resolve("config.toml"), "[general]\n")
                val count = 12
                val ready = CountDownLatch(count)
                val start = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(count)
                try {
                    val results = (1..count).map { index ->
                        executor.submit<JsonObject> {
                            ready.countDown()
                            start.await()
                            createProfile(configDir, "profile$index", index)
                        }
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS), "workers reached the barrier")
                    start.countDown()
                    val completed = results.map { it.get(20, TimeUnit.SECONDS) }
                    assertEquals(count, completed.size)
                    assertTrue(completed.all { it.containsKey("ok") }, "each profile create should report success")

                    val actual = SyncConfig.parseRaw(Files.readString(configDir.resolve("config.toml"))).providers.keys
                    assertEquals((1..count).map { "profile$it" }.toSet(), actual)
                } finally {
                    start.countDown()
                    executor.shutdownNow()
                    executor.awaitTermination(5, TimeUnit.SECONDS)
                }
            } finally {
                configDir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `concurrent creation of one name writes one profile section`() {
        withFactory {
            val configDir = Files.createTempDirectory("config-duplicate")
            try {
                Files.writeString(configDir.resolve("config.toml"), "[general]\n")
                val count = 8
                val ready = CountDownLatch(count)
                val start = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(count)
                try {
                    val results = (1..count).map { index ->
                        executor.submit<Result<JsonObject>> {
                            ready.countDown()
                            start.await()
                            runCatching { createProfile(configDir, "shared", index) }
                        }
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS), "workers reached the barrier")
                    start.countDown()
                    val completed = results.map { it.get(20, TimeUnit.SECONDS) }
                    assertEquals(1, completed.count { it.isSuccess }, "one concurrent create may own the name")
                    assertTrue(
                        completed.filter { it.isFailure }.all { (it.exceptionOrNull() as? LifecycleError)?.token == "profile_exists" },
                        "all losing creates should observe the duplicate profile",
                    )
                    assertEquals(setOf("shared"), SyncConfig.parseRaw(Files.readString(configDir.resolve("config.toml"))).providers.keys)
                } finally {
                    start.countDown()
                    executor.shutdownNow()
                    executor.awaitTermination(5, TimeUnit.SECONDS)
                }
            } finally {
                configDir.toFile().deleteRecursively()
            }
        }
    }

    private fun withFactory(block: () -> Unit) {
        val previous = LifecycleHooks.factoryFor
        LifecycleHooks.factoryFor = { type -> if (type == "localfs") localFactory else null }
        try {
            block()
        } finally {
            LifecycleHooks.factoryFor = previous
        }
    }

    private fun createProfile(configDir: java.nio.file.Path, name: String, index: Int): JsonObject =
        ProfileCreator.create(
            configDir,
            NewProfile(
                type = "localfs",
                name = name,
                mode = "mirror",
                syncRoot = configDir.resolve("root-$index").toString(),
                label = null,
                options = listOf("root_path=${configDir.resolve("remote-$index")}"),
                makeDefault = false,
            ),
        )
}
