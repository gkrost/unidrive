package org.krost.unidrive.localfs

import java.nio.file.Path

/**
 * Configuration for a local filesystem provider.
 *
 * [rootPath] is the directory that acts as the sync root — all operations
 * are contained within this directory (CWE-22 path containment enforced
 * by [LocalFsProvider]).
 *
 * [tokenPath] stores the snapshot cursor between delta cycles.
 */
data class LocalFsConfig(
    val rootPath: Path,
    val tokenPath: Path,
)
