package com.mieai.qqbot.module.host;

import com.mieai.qqbot.module.spi.FrameworkModule;
import com.mieai.qqbot.module.spi.FrameworkModuleLifecycle;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@Import({ModuleCatalogController.class, ModuleAssetController.class})
public class ModuleHostConfiguration {
    @Bean(destroyMethod = "close")
    ModuleArtifactRegistry moduleArtifactRegistry(
            @Value("${qqbot.modules.directory}") String directory) {
        return ModuleArtifactRegistry.load(Path.of(directory));
    }

    @Bean
    FrameworkModuleHost frameworkModuleHost(
            ModuleArtifactRegistry artifactRegistry,
            ObjectProvider<FrameworkModule> modules,
            ObjectProvider<FrameworkModuleLifecycle> lifecycles) {
        return new FrameworkModuleHost(
                artifactRegistry,
                modules.orderedStream().toList(),
                lifecycles.orderedStream().toList());
    }
}
