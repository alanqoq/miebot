package com.mieai.qqbot.plugin.host

internal object PluginApiCompatibility {
    fun accepts(required: String, current: String): Boolean {
        val requiredVersion = Version.parse(required)
        val currentVersion = Version.parse(current)
        return requiredVersion.major == currentVersion.major && requiredVersion <= currentVersion
    }

    private data class Version(
        val major: Long,
        val minor: Long,
        val patch: Long,
    ) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        companion object {
            private val PATTERN = Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)")

            fun parse(value: String): Version {
                val match = PATTERN.matchEntire(value.trim())
                    ?: throw IllegalArgumentException("Plugin API version must use major.minor.patch")
                return try {
                    Version(
                        match.groupValues[1].toLong(),
                        match.groupValues[2].toLong(),
                        match.groupValues[3].toLong(),
                    )
                } catch (exception: NumberFormatException) {
                    throw IllegalArgumentException("Plugin API version is too large", exception)
                }
            }
        }
    }
}
