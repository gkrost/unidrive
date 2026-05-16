package org.krost.unidrive.sync.testfixtures

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Path

/**
 * UD-821: test-only `ProviderFactory` for `:app:sync` so the ServiceLoader
 * lookup in `ProviderRegistry` returns a non-empty result during this
 * module's tests.
 *
 * `:app:sync` does not depend on any provider module (engine stays
 * provider-agnostic), so without these stubs the `META-INF/services/`
 * resource list is empty.
 *
 * Today (pre-UD-013) the silent `defaultTypes` fallback in
 * `ProviderRegistry.knownTypes` masks the empty-classpath case — it
 * substitutes the hardcoded list `{"localfs", "onedrive", "rclone", "s3",
 * "sftp", "webdav"}`. Several `:app:sync` tests (notably `SyncConfigTest`)
 * depend on those type IDs being known, not just on the lookup being
 * non-empty.
 *
 * Once UD-013 lands and removes the silent fallback, we need real
 * `ProviderFactory` instances whose `id` matches each expected type.
 * UD-821 ships one stub per default type: registering them via
 * `META-INF/services/org.krost.unidrive.ProviderFactory` makes
 * ServiceLoader return the same set the fallback used to fake.
 *
 * `create()` deliberately throws if invoked — tests must either inject
 * their own `CloudProvider` or not exercise the provider-instantiation
 * path. The stubs satisfy the SPI lookup; they are not functioning
 * providers.
 *
 * Subclasses pick the type id; everything else is fixed boilerplate.
 */
abstract class StubProviderFactory(
    final override val id: String,
) : ProviderFactory {
    final override val metadata =
        ProviderMetadata(
            id = id,
            displayName = "Stub ($id, test fixture)",
            description = "Test-only ProviderFactory satisfying ServiceLoader discovery in :app:sync.",
            authType = "None (test fixture)",
            encryption = "None (test fixture)",
            jurisdiction = "Test process",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Test",
        )

    final override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider = error("StubProviderFactory($id).create() called — tests must inject a real CloudProvider")

    final override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = false
}

/** UD-821: stub matching real `localfs` factory id. */
class StubLocalfsFactory : StubProviderFactory("localfs")

/** UD-821: stub matching real `onedrive` factory id. */
class StubOnedriveFactory : StubProviderFactory("onedrive")

/** UD-821: stub matching real `rclone` factory id. */
class StubRcloneFactory : StubProviderFactory("rclone")

/** UD-821: stub matching real `s3` factory id. */
class StubS3Factory : StubProviderFactory("s3")

/** UD-821: stub matching real `sftp` factory id. */
class StubSftpFactory : StubProviderFactory("sftp")

/** UD-821: stub matching real `webdav` factory id. */
class StubWebdavFactory : StubProviderFactory("webdav")
