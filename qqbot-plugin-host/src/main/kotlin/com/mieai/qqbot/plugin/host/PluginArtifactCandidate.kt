package com.mieai.qqbot.plugin.host

import java.nio.file.Path
/** Validated artifact metadata used before an uploaded JAR is installed. */
class PluginArtifactCandidate(
    val pluginId: String,
    val name: String,
    val version: String,
    val apiCompatibility: String,
    val fileName: String,
    val sha256: String,
    path: Path,
) {
    val path: Path = path.toAbsolutePath().normalize()
}
