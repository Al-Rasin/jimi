package com.alrasin.jarvis.actions

internal object DeviceActionPolicy {
    fun hasMiuiSettings(manufacturer: String): Boolean =
        manufacturer.trim().lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco")

    fun torchDirectories(paths: List<String>): List<String> = paths.map(String::trim)
        .filter { path ->
            val name = path.removePrefix("/sys/class/leds/")
            path.startsWith("/sys/class/leds/") && name.matches(Regex("[A-Za-z0-9_.:-]+")) &&
                (name.contains("torch", ignoreCase = true) || name.equals("flashlight", ignoreCase = true))
        }.distinct().sorted()

    // Some MTK flash drivers report max 2047 but silently stay at 0 for values above ~255.
    fun torchBrightness(maximum: String): Int? = maximum.trim().toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(255)
}
