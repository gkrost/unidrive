package org.krost.unidrive.cli

import org.krost.unidrive.ProviderRegistry
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand

@Command(
    name = "provider",
    description = ["View provider information and ratings"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ProviderListCommand::class,
        ProviderInfoCommand::class,
    ],
)
class ProviderCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        // Default: show list
        ProviderListCommand().also { it.providerCmd = this }.run()
    }
}

// ── provider list ────────────────────────────────────────────────────────────

@Command(name = "list", description = ["List all supported providers by tier"], mixinStandardHelpOptions = true)
class ProviderListCommand : Runnable {
    @ParentCommand
    lateinit var providerCmd: ProviderCommand

    override fun run() {
        val providers = ProviderRegistry.allByTier()
        val ansi = AnsiHelper.isAnsiSupported()

        // Header
        println(
            "%-25s %-8s %-6s %-22s %-26s %-12s %s".format(
                "PROVIDER",
                "RATING",
                "BENCH",
                "JURISDICTION",
                "ENCRYPTION",
                "TIER",
                "LINK",
            ),
        )
        println(GlyphRenderer.boxHorizontal().repeat(115))

        for (p in providers) {
            val rating = formatRating(p.userRating)
            val grade = p.benchmarkGrade ?: GlyphRenderer.dash()
            val jur = truncate(p.jurisdiction, 20)
            val enc = truncate(p.encryption, 24)
            val link = formatLink(p)

            val line =
                "%-25s %-8s %-6s %-22s %-26s %-12s %s".format(
                    p.displayName,
                    rating,
                    grade,
                    jur,
                    enc,
                    p.tier,
                    link,
                )

            println(if (ansi) colorize(line, p) else line)
        }
    }

    private fun formatRating(rating: Double?): String {
        if (rating == null) return GlyphRenderer.dash()
        val filled = rating.toInt()
        val empty = 5 - filled
        return GlyphRenderer.starFilled().repeat(filled) + GlyphRenderer.starEmpty().repeat(empty)
    }

    private fun formatLink(p: org.krost.unidrive.ProviderMetadata): String {
        val url = p.affiliateUrl ?: p.signupUrl ?: return GlyphRenderer.dash()
        val ellipsis = GlyphRenderer.ellipsis()
        // Show shortened domain
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .let { if (it.length > 25) it.take(22) + ellipsis else it }
    }

    private fun truncate(
        s: String,
        max: Int,
    ): String {
        val ellipsis = GlyphRenderer.ellipsis()
        return if (s.length > max) s.take(max - 1) + ellipsis else s
    }

    private fun colorize(
        line: String,
        p: org.krost.unidrive.ProviderMetadata,
    ): String {
        // Self-hosted/protocol: no colour
        if (p.userRating == null && p.benchmarkGrade == null) return line

        // Grade takes priority over rating for row colour
        val rating = p.userRating
        val color =
            when (p.benchmarkGrade) {
                "A+", "A" -> "green"
                "B" -> "yellow"
                "C", "D" -> "red"
                else ->
                    when {
                        rating != null && rating >= 4.5 -> "green"
                        rating != null && rating >= 3.0 -> "yellow"
                        rating != null -> "red"
                        else -> null
                    }
            }

        return when (color) {
            "green" -> AnsiHelper.green(line)
            "yellow" -> AnsiHelper.yellow(line)
            "red" -> AnsiHelper.red(line)
            else -> line
        }
    }
}

// ── provider info ────────────────────────────────────────────────────────────

@Command(
    name = "info",
    description = [
        "Show detailed info for a provider. Valid IDs: " +
            "hidrive, internxt, localfs, onedrive, rclone, s3, sftp, webdav " +
            "(case-insensitive, leading/trailing whitespace is ignored).",
    ],
    mixinStandardHelpOptions = true,
)
class ProviderInfoCommand : Runnable {
    @ParentCommand
    lateinit var providerCmd: ProviderCommand

    @Parameters(
        index = "0",
        description = ["Provider ID (hidrive, internxt, localfs, onedrive, rclone, s3, sftp, webdav)"],
    )
    lateinit var id: String

    override fun run() {
        val canonical = ProviderRegistry.resolveId(id)
        val meta = canonical?.let { ProviderRegistry.getMetadata(it) }
        if (meta == null) {
            System.err.println("Unknown provider: $id")
            System.err.println("Available: ${ProviderRegistry.knownTypes.sorted().joinToString(", ")}")
            System.exit(1)
            return
        }

        println(AnsiHelper.bold(meta.displayName))
        println("  ${meta.description}")
        println()
        println("  Auth:           ${meta.authType}")
        println("  Encryption:     ${meta.encryption}")
        println("  Jurisdiction:   ${meta.jurisdiction}")
        println("  GDPR:           ${if (meta.gdprCompliant) "Yes" else "No"}")
        println("  US CLOUD Act:   ${if (meta.cloudActExposure) "Yes" else "No"}")
        println("  Tier:           ${meta.tier}")
        meta.userRating?.let { rating ->
            val stars =
                GlyphRenderer.starFilled().repeat(rating.toInt()) +
                    GlyphRenderer.starEmpty().repeat(5 - rating.toInt())
            println("  Rating:         $stars ($rating)")
        }
        if (meta.benchmarkGrade != null) {
            println("  Benchmark:      ${meta.benchmarkGrade}")
        }
        if (meta.affiliateUrl != null && meta.affiliateUrl != meta.signupUrl) {
            println("  Affiliate:      ${meta.affiliateUrl}")
        }
        if (meta.signupUrl != null) {
            println()
            println("  Sign up: ${meta.signupUrl}")
        }
    }
}
