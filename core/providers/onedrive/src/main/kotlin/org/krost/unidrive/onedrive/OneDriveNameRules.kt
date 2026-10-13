package org.krost.unidrive.onedrive

/** Local checks for names and paths that OneDrive refuses before processing a write. */
internal object OneDriveNameRules {
    private val forbidden = setOf('"', '*', ':', '<', '>', '?', '/', '\\', '|')
    private val deviceName = Regex("(?i)^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?$")

    fun issue(path: String): String? {
        val normalized = path.trim('/')
        if (normalized.length > 400) return "OneDrive cloud path exceeds 400 characters"
        for (name in normalized.split('/')) {
            when {
                name.any { it in forbidden } -> return "name contains a character OneDrive forbids"
                name.startsWith(' ') || name.endsWith(' ') -> return "name starts or ends with a space"
                name.endsWith('.') -> return "name ends with a period"
                name.equals(".lock", ignoreCase = true) -> return "name is reserved: .lock"
                deviceName.matches(name) -> return "name is a reserved device name"
                name.contains("_vti_", ignoreCase = true) -> return "name contains the reserved _vti_ token"
                name.equals("desktop.ini", ignoreCase = true) -> return "name is reserved: desktop.ini"
                name.startsWith("~$") -> return "name starts with the reserved ~\$ prefix"
            }
        }
        return null
    }
}
