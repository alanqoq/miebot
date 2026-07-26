package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.spi.FrameworkModule
import com.mieai.qqbot.module.spi.FrameworkModuleLifecycle
import java.nio.file.Path
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@AutoConfiguration
@Import(ModuleCatalogController::class, ModuleAssetController::class)
class ModuleHostConfiguration {
    @Bean(destroyMethod = "close")
    fun moduleArtifactRegistry(
        @Value("\${qqbot.modules.directory}") directory: String,
    ): ModuleArtifactRegistry = ModuleArtifactRegistry.load(Path.of(directory))

    @Bean
    fun frameworkModuleHost(
        artifactRegistry: ModuleArtifactRegistry,
        modules: ObjectProvider<FrameworkModule>,
        lifecycles: ObjectProvider<FrameworkModuleLifecycle>,
    ): FrameworkModuleHost = FrameworkModuleHost(
        discoveredModules = modules.orderedStream().toList(),
        artifactRegistry = artifactRegistry,
        discoveredLifecycles = lifecycles.orderedStream().toList(),
    )
}
