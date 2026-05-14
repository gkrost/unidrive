package org.krost.unidrive.e2e.playwright

import java.nio.file.Path

interface CloudProviderPage : AutoCloseable {
    fun login()
    fun navigateToFolder(path: String)
    fun createFolder(name: String)
    fun uploadFile(localPath: Path)
    fun getVisibleFiles(): List<FileInfo>
    fun fileExists(name: String): Boolean
}

data class FileInfo(val name: String, val size: Long?)
