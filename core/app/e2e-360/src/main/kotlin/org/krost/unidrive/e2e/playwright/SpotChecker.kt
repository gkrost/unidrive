package org.krost.unidrive.e2e.playwright

import org.krost.unidrive.e2e.verify.ManifestEntry
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class SpotCheckResult(val checked: Int, val visible: Int, val missing: List<String>)

class SpotChecker(
    private val page: CloudProviderPage,
    private val samplePercentage: Int = 10,
    private val minSample: Int = 10,
    private val maxSample: Int = 200,
) {
    fun check(entries: List<ManifestEntry>, basePath: String, seed: Long = 42): SpotCheckResult {
        val sampleSize = max(minSample, min(entries.size * samplePercentage / 100, maxSample))
        val sample = entries.shuffled(Random(seed)).take(sampleSize)
        var visible = 0
        val missing = mutableListOf<String>()

        for (entry in sample) {
            val folder = entry.path.substringBeforeLast('/', "")
            val fileName = entry.path.substringAfterLast('/')
            val navPath = if (folder.isNotBlank()) "$basePath/$folder" else basePath
            try {
                page.navigateToFolder(navPath)
                if (page.fileExists(fileName)) visible++ else missing.add(entry.path)
            } catch (_: Exception) {
                missing.add(entry.path)
            }
        }

        return SpotCheckResult(checked = sample.size, visible = visible, missing = missing)
    }
}
