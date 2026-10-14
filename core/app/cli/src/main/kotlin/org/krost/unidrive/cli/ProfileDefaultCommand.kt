package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.RawSyncConfig
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.isValidProfileName
import org.krost.unidrive.sync.setDefaultProfile
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "default",
    description = [
        "Make a configured profile the default (the one used when no -p is given), or remove the default " +
            "with --clear. Rewrites only [general] default_profile in config.toml and reports the previous " +
            "value. No daemon is started or restarted.",
    ],
    mixinStandardHelpOptions = true,
)
class ProfileDefaultCommand : Callable<Int> {
    @ParentCommand
    lateinit var profileCmd: ProfileCommand

    @Parameters(index = "0", arity = "0..1", description = ["Profile name to make the default (omit with --clear)"])
    var name: String? = null

    @Option(names = ["--clear"], description = ["Remove the default instead of setting one"])
    var clear: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        try {
            val result = applyChange()
            if (json) printJson(result) else println(result.getValue("message").jsonPrimitive.content)
            0
        } catch (e: ProfileCommandError) {
            if (json) printJson(errorJson(e)) else System.err.println("Error: ${e.message}")
            1
        }

    /** The profile to make the default, or null for `--clear`; refuses a missing, doubled or malformed request. */
    private fun requestedTarget(): String? {
        val target = name
        if (clear == (target != null)) {
            throw if (clear) {
                ProfileCommandError("invalid_value", "--clear takes no profile name")
            } else {
                ProfileCommandError("missing_value", "a profile name is required (or pass --clear)")
            }
        }
        if (target != null && !isValidProfileName(target)) {
            throw ProfileCommandError("invalid_profile_name", "'$target' is not a valid profile name")
        }
        return target
    }

    private fun parseConfig(
        text: String,
        configPath: Path,
    ): RawSyncConfig =
        try {
            SyncConfig.parseRaw(text, configPath.toString())
        } catch (_: Exception) {
            // The parser's message can quote the offending config line, which may hold a credential.
            throw ProfileCommandError("config_invalid", "config.toml could not be parsed")
        }

    private fun readConfigText(configPath: Path): String {
        if (!Files.exists(configPath)) throw ProfileCommandError("config_not_found", "no config.toml in ${configPath.parent}")
        return Files.readString(configPath)
    }

    private fun requireConfigExists(configPath: Path) {
        if (!Files.exists(configPath)) throw ProfileCommandError("config_not_found", "no config.toml in ${configPath.parent}")
    }

    private fun save(
        configPath: Path,
        text: String,
    ) {
        try {
            writeConfigAtomically(configPath, text)
        } catch (e: IOException) {
            throw ProfileCommandError("write_failed", "config.toml could not be written: ${e.javaClass.simpleName}")
        }
    }

    private fun applyChange(): JsonObject {
        val target = requestedTarget()
        val configPath = profileCmd.parent.configBaseDir().resolve("config.toml")
        requireConfigExists(configPath)
        return ConfigFileTransaction.withLock(configPath) {
        val text = readConfigText(configPath)
        val raw = parseConfig(text, configPath)
        if (target != null && target !in raw.providers) {
            throw ProfileCommandError("profile_not_found", "profile '$target' is not configured")
        }

        // A config without a [general] section is a supported shape: anchor one before the edit.
        val anchored = if (text.lineSequence().none { it.trim() == "[general]" }) "[general]\n\n$text" else text
        val edited = setDefaultProfile(anchored, target)
        if (parseConfig(edited, configPath).general.default_profile?.takeIf { it.isNotBlank() } != target) {
            throw ProfileCommandError("config_invalid", "the edit does not read back as asked")
        }
        if (edited != text) save(configPath, edited)

        buildJsonObject {
            put("ok", true)
            put("default_profile", target)
            put("previous", raw.general.default_profile?.takeIf { it.isNotBlank() })
            put("message", if (target != null) "Default profile is now '$target'." else "Default profile cleared.")
        }
        }
    }
}
