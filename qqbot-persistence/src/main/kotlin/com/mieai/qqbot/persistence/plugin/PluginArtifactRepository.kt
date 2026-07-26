package com.mieai.qqbot.persistence.plugin

interface PluginArtifactRepository {
    fun upsert(artifact: PluginArtifact)

    fun findById(pluginId: String): PluginArtifact?

    fun findAll(): List<PluginArtifact>
}
