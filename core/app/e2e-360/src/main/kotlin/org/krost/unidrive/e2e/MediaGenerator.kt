package org.krost.unidrive.e2e

import java.nio.file.Path
import java.util.logging.Logger

object MediaGenerator {

    private val log = Logger.getLogger(MediaGenerator::class.java.name)

    fun generateTestVideo(output: Path, codec: String = "libx265", duration: Int = 5) {
        runExternal(
            listOf(
                "ffmpeg", "-y",
                "-f", "lavfi",
                "-i", "testsrc=duration=$duration:size=640x480:rate=15",
                "-c:v", codec,
                "-preset", "ultrafast",
                output.toString(),
            ),
            "video[$codec]",
        )
    }

    fun generateTestAudio(output: Path, codec: String = "flac", duration: Int = 5) {
        runExternal(
            listOf(
                "ffmpeg", "-y",
                "-f", "lavfi",
                "-i", "sine=frequency=440:duration=$duration",
                "-c:a", codec,
                output.toString(),
            ),
            "audio[$codec]",
        )
    }

    fun isAvailable(): Boolean {
        return try {
            val proc = ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start()
            proc.inputStream.readBytes()
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runExternal(cmd: List<String>, label: String) {
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.readBytes()
        val exit = proc.waitFor()
        if (exit != 0) {
            log.warning("MediaGenerator $label exited with code $exit")
        }
    }
}
