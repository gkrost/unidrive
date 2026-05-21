package org.krost.unidrive.onedrive

import kotlin.test.*

class OneDriveShareTest {
    @Test
    fun `CloudProvider has share method in interface`() {
        val methods = org.krost.unidrive.CloudProvider::class.java.declaredMethods
        val shareMethod = methods.find { it.name == "share" }
        assertNotNull(shareMethod, "CloudProvider should have share method")
    }

    @Test
    fun `CloudProvider has deltaWithShared method in interface`() {
        val methods = org.krost.unidrive.CloudProvider::class.java.declaredMethods
        val deltaMethod = methods.find { it.name == "deltaWithShared" }
        assertNotNull(deltaMethod, "CloudProvider should have deltaWithShared method")
    }

    @Test
    fun `OneDriveProvider implements share method`() {
        val methods = OneDriveProvider::class.java.declaredMethods
        val shareMethod = methods.find { it.name == "share" }
        assertNotNull(shareMethod, "OneDriveProvider should have share method")
    }

    @Test
    fun `OneDriveProvider implements deltaWithShared method`() {
        val methods = OneDriveProvider::class.java.declaredMethods
        val deltaMethod = methods.find { it.name == "deltaWithShared" }
        assertNotNull(deltaMethod, "OneDriveProvider should have deltaWithShared method")
    }
}
