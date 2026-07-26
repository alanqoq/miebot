package com.mieai.qqbot.module.host

private val SEMANTIC_VERSION_PATTERN = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?$")

internal data class SemanticVersion(val major: Long, val minor: Long, val patch: Long) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int = compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    companion object {
        fun parse(value: String): SemanticVersion {
            val match = SEMANTIC_VERSION_PATTERN.matchEntire(value)
                ?: throw IllegalArgumentException("Invalid semantic version: $value")
            return try {
                SemanticVersion(match.groupValues[1].toLong(), match.groupValues[2].toLong(), match.groupValues[3].toLong())
            } catch (error: NumberFormatException) {
                throw IllegalArgumentException("Semantic version component is too large: $value", error)
            }
        }
    }
}
