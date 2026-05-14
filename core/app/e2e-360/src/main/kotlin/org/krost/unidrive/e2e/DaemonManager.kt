package org.krost.unidrive.e2e

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Manages UniDrive daemon lifecycle for isolated test runs.
 * Stops daemon before test, prevents rogue processes, restarts after.
 */
class DaemonManager {

    /**
     * Stops the UniDrive systemd service if running.
     * @return true if daemon was running and was stopped, false if not running
     */
    fun stopDaemon(): Boolean {
        val wasRunning = isDaemonActive()
        if (wasRunning) {
            runCommand("systemctl", "--user", "stop", "unidrive.service")
            // Wait for process to fully exit
            Thread.sleep(2000)
        }
        return wasRunning
    }

    /**
     * Starts the UniDrive systemd service.
     */
    fun startDaemon() {
        runCommand("systemctl", "--user", "start", "unidrive.service")
    }

    /**
     * Detects any rogue UniDrive sync processes that could interfere with test.
     * @return list of PIDs for any unidrive.*sync processes found
     */
    fun detectRunningProcesses(): List<Int> {
        val pids: MutableList<Int> = mutableListOf()

        // Check for uniDrive CLI sync processes (exclude our own JVM and gradle wrappers)
        val ownPid = ProcessHandle.current().pid()
        try {
            val proc = ProcessBuilder("pgrep", "-f", "unidrive\\.jar.*sync")
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var line: String? = reader.readLine()
            while (line != null) {
                val pid = line.trim().toIntOrNull()
                if (pid != null && pid.toLong() != ownPid) {
                    pids.add(pid)
                }
                line = reader.readLine()
            }
            proc.waitFor()
        } catch (_: java.io.IOException) {
            // pgrep not available (e.g. minimal Docker container)
        }
        
        // Also check for lock files that might indicate running processes
        val lockFilePaths = listOf(
            System.getProperty("java.io.tmpdir") + "/unidrive.lock",
            System.getProperty("user.home") + "/.config/unidrive/lock"
        )
        
        for (lockPath in lockFilePaths) {
            val lockFile = java.io.File(lockPath)
            if (lockFile.exists()) {
                // Could read PID from lock file if format known
                // For now, just note that a lock exists
            }
        }
        
        return pids
    }

    /**
     * Runs a system command and waits for completion.
     * Throws RuntimeException if command fails.
     */
    private fun runCommand(vararg commands: String) {
        val proc = ProcessBuilder(*commands)
            .redirectErrorStream(true)
            .start()
        
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            val error = proc.inputStream.bufferedReader().readText()
            throw RuntimeException("Command failed: ${commands.joinToString(" ")}. Exit code: $exitCode. Error: $error")
        }
    }

    /**
     * Checks if UniDrive systemd service is currently active.
     * @return true if service is active, false otherwise
     */
    private fun isDaemonActive(): Boolean {
        return try {
            val proc = ProcessBuilder("systemctl", "--user", "is-active", "--quiet", "unidrive.service")
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (_: java.io.IOException) {
            false // systemctl not available (e.g. Docker container)
        }
    }
}