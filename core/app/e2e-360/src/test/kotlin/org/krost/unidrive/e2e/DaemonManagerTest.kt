package org.krost.unidrive.e2e

import kotlin.test.Test
import kotlin.test.assertTrue

class DaemonManagerTest {

    @Test
    fun `daemon manager can be instantiated`() {
        val manager = DaemonManager()
        assertTrue(manager::class.java.isInstance(manager))
    }

    // Note: Actual systemctl calls would require root privileges or mocking
    // This is a basic sanity check that the class compiles and instantiates
}