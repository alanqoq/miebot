package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.api.FrameworkVersion
import com.mieai.qqbot.module.api.ModuleBotSettingsContribution
import com.mieai.qqbot.module.api.ModuleDependency
import com.mieai.qqbot.module.api.ModuleWebContribution
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/modules")
class ModuleCatalogController(
    private val host: FrameworkModuleHost,
) {
    @GetMapping
    fun catalog(): ModuleCatalogResponse {
        val modules = host.snapshots().map { snapshot ->
            val descriptor = snapshot.descriptor
            val moduleId = descriptor.id
            val artifact = host.artifact(moduleId)
            val web = descriptor.webContributions
                .sortedBy(ModuleWebContribution::order)
                .map { WebContributionResponse.from(moduleId, it, artifact?.sha256) }
            val botSettings = descriptor.botSettingsContributions
                .sortedBy(ModuleBotSettingsContribution::order)
                .map { BotSettingsContributionResponse.from(moduleId, it, artifact?.sha256) }
            ModuleResponse(
                moduleId,
                descriptor.name,
                descriptor.version,
                snapshot.state.name,
                snapshot.error,
                descriptor.dependencies,
                descriptor.capabilities,
                artifact?.path?.fileName?.toString(),
                artifact?.sha256,
                web,
                botSettings,
            )
        }
        return ModuleCatalogResponse(FrameworkVersion.CURRENT, modules)
    }

    data class ModuleCatalogResponse(
        val frameworkVersion: String,
        val modules: List<ModuleResponse>,
    )

    data class ModuleResponse(
        val id: String,
        val name: String,
        val version: String,
        val state: String,
        val error: String?,
        val dependencies: List<ModuleDependency>,
        val capabilities: Set<String>,
        val artifact: String?,
        val sha256: String?,
        val webContributions: List<WebContributionResponse>,
        val botSettingsContributions: List<BotSettingsContributionResponse>,
    )

    data class WebContributionResponse(
        val id: String,
        val label: String,
        val route: String,
        val icon: String,
        val order: Int,
        val componentKey: String?,
        val entrypoint: String?,
        val customElement: String?,
    ) {
        companion object {
            fun from(
                moduleId: String,
                value: ModuleWebContribution,
                sha256: String?,
            ): WebContributionResponse {
                val entrypoint = value.assetPath?.let { assetPath ->
                    "/module-assets/$moduleId/$assetPath" + versionQuery(sha256)
                }
                return WebContributionResponse(
                    value.id,
                    value.label,
                    value.route,
                    value.icon,
                    value.order,
                    value.componentKey,
                    entrypoint,
                    value.customElement,
                )
            }
        }
    }

    data class BotSettingsContributionResponse(
        val id: String,
        val label: String,
        val order: Int,
        val entrypoint: String,
        val customElement: String,
    ) {
        companion object {
            fun from(
                moduleId: String,
                value: ModuleBotSettingsContribution,
                sha256: String?,
            ): BotSettingsContributionResponse = BotSettingsContributionResponse(
                value.id,
                value.label,
                value.order,
                "/module-assets/$moduleId/${value.assetPath}" + versionQuery(sha256),
                value.customElement,
            )
        }
    }

    private companion object {
        fun versionQuery(sha256: String?): String =
            if (sha256 == null) "" else "?v=${sha256.substring(0, 16)}"
    }
}
