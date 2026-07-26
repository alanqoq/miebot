package com.mieai.qqbot.module.api

/** Immutable identity, dependency, capability, and Web metadata for one framework module. */
data class ModuleDescriptor private constructor(
    val id: String,
    val name: String,
    val version: String,
    val minimumFrameworkVersion: String,
    val dependencies: List<ModuleDependency>,
    val capabilities: Set<String>,
    val webContributions: List<ModuleWebContribution>,
    val botSettingsContributions: List<ModuleBotSettingsContribution>,
) {
    init {
        val dependencyIds = linkedSetOf<String>()
        for (dependency in dependencies) {
            require(id != dependency.moduleId) { "a module cannot depend on itself" }
            require(dependencyIds.add(dependency.moduleId)) {
                "duplicate module dependency: ${dependency.moduleId}"
            }
        }
        val contributionIds = linkedSetOf<String>()
        for (contribution in webContributions) {
            require(contributionIds.add(contribution.id)) { "duplicate Web contribution: ${contribution.id}" }
        }
        for (contribution in botSettingsContributions) {
            require(contributionIds.add(contribution.id)) { "duplicate Web contribution: ${contribution.id}" }
        }
        capabilities.forEach { ModuleValidation.requireId(it, "capability") }
    }

    companion object {
        fun create(
            id: String,
            name: String,
            version: String = "1.0.0",
            minimumFrameworkVersion: String = FrameworkVersion.CURRENT,
            dependencies: List<ModuleDependency> = emptyList(),
            capabilities: Set<String> = emptySet(),
            webContributions: List<ModuleWebContribution> = emptyList(),
            botSettingsContributions: List<ModuleBotSettingsContribution> = emptyList(),
        ): ModuleDescriptor = ModuleDescriptor(
            ModuleValidation.requireId(id, "id"),
            ModuleValidation.requireText(name, "name", 128),
            ModuleValidation.requireVersion(version, "version"),
            ModuleValidation.requireVersion(minimumFrameworkVersion, "minimumFrameworkVersion"),
            dependencies.toList(),
            capabilities.toSet(),
            webContributions.toList(),
            botSettingsContributions.toList(),
        )
    }
}
