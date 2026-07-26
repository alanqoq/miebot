package com.mieai.qqbot.plugin.host

import java.nio.file.Path
class LoadedPluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val apiCompatibility: String,
    val path: Path,
    val sha256: String,
    val entrypoint: String,
    val configurationSchema: String,
    val defaultConfigurationPath: String,
    val defaultConfiguration: String,
    capabilities: Set<String>,
) {
    val capabilities: Set<String> = capabilities.toSet()

    init {
        require(defaultConfigurationPath.isNotBlank()) { "defaultConfigurationPath must not be blank" }
        require(defaultConfiguration.isNotBlank()) { "defaultConfiguration must not be blank" }
    }
}
