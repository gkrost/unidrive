package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.isValidProfileName
import org.krost.unidrive.sync.setDefaultProfile
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.IOException
import java.nio.file.Files
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
            if (json) {
                printJson(result)
            } else {
                println(result.getValue("message").let { (it as kotlinx.serialization.json.JsonPrimitive).content })
            }
            0
        } catch (e: ProfileCommandError) {
            if (json) printJson(errorJson(e)) else System.err.println("Error: ${e.message}")
            1
        }

    private fun applyChange(): JsonObject {
        val configPath = profileCmd.parent.configBaseDir().resolve("config.toml")
        val target = name
        if (clear && target != null) throw ProfileCommandError("invalid_value", "--clear takes no profile name")
        if (!clear && target == null) throw ProfileCommandError("missing_value", "a profile name is required (or pass --clear)")
        if (target != null && !isValidProfileName(target)) {
            throw ProfileCommandError("invalid_profile_name", "'$target' is not a valid profile name")
        }
        if (!Files.exists(configPath)) throw ProfileCommandError("config_not_found", "no config.toml in ${configPath.parent}")
        val text = Files.readString(configPath)
        val raw =
            try {
                SyncConfig.parseRaw(text, configPath.toString())
            } catch (_: Exception) {
                // The parser's message can quote the offending config line, which may hold a credential.
                throw ProfileCommandError("config_invalid", "config.toml could not be parsed")
            }
        if (target != null && target !in raw.providers) {
            throw ProfileCommandError("profile_not_found", "profile '$target' is not configured")
        }
        val previous = raw.general.default_profile?.takeIf { it.isNotBlank() }

        // A config without a [general] section is a supported shape: anchor one before the edit.
        val anchored = if (text.lineSequence().none { it.trim() == "[general]" }) "[general]\n\n$text" else text
        val edited = setDefaultProfile(anchored, target)
        val reread =
            try {
                SyncConfig.parseRaw(edited, configPath.toString()).general.default_profile?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                throw ProfileCommandError("config_invalid", "the edit does not produce a valid config")
            }
        if (reread != target) throw ProfileCommandError("config_invalid", "the edit does not read back as asked")
        if (edited != text) {
            try {
                writeConfigAtomically(configPath, edited)
            } catch (e: IOException) {
                throw ProfileCommandError("write_failed", "config.toml could not be written: ${e.javaClass.simpleName}")
            }
        }

        return buildJsonObject {
            put("ok", true)
            put("default_profile", target)
            put("previous", previous)
            put(
                "message",
                if (target != null) "Default profile is now '$target'." else "Default profile cleared.",
            )
        }
    }
}
