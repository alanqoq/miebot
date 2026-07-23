package com.mieai.qqbot.module.host;

import com.mieai.qqbot.module.api.FrameworkVersion;
import com.mieai.qqbot.module.api.ModuleBotSettingsContribution;
import com.mieai.qqbot.module.api.ModuleDependency;
import com.mieai.qqbot.module.api.ModuleWebContribution;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/modules")
public class ModuleCatalogController {
    private final FrameworkModuleHost host;

    public ModuleCatalogController(FrameworkModuleHost host) {
        this.host = host;
    }

    @GetMapping
    public ModuleCatalogResponse catalog() {
        List<ModuleResponse> modules = host.snapshots().stream()
                .map(snapshot -> {
                    String moduleId = snapshot.descriptor().id();
                    ModuleArtifact artifact = host.artifact(moduleId).orElse(null);
                    List<WebContributionResponse> web = snapshot.descriptor().webContributions().stream()
                            .sorted(Comparator.comparingInt(ModuleWebContribution::order))
                            .map(value -> WebContributionResponse.from(
                                    moduleId, value, artifact == null ? null : artifact.sha256()))
                            .toList();
                    List<BotSettingsContributionResponse> botSettings = snapshot.descriptor()
                            .botSettingsContributions().stream()
                            .sorted(Comparator.comparingInt(ModuleBotSettingsContribution::order))
                            .map(value -> BotSettingsContributionResponse.from(
                                    moduleId, value, artifact == null ? null : artifact.sha256()))
                            .toList();
                    return new ModuleResponse(moduleId, snapshot.descriptor().name(),
                            snapshot.descriptor().version(), snapshot.state().name(),
                            snapshot.error().orElse(null), snapshot.descriptor().dependencies(),
                            snapshot.descriptor().capabilities(),
                            artifact == null ? null : artifact.path().getFileName().toString(),
                            artifact == null ? null : artifact.sha256(), web, botSettings);
                })
                .toList();
        return new ModuleCatalogResponse(FrameworkVersion.CURRENT, modules);
    }

    public record ModuleCatalogResponse(String frameworkVersion, List<ModuleResponse> modules) {}
    public record ModuleResponse(String id, String name, String version, String state, String error,
            List<ModuleDependency> dependencies, Set<String> capabilities,
            String artifact, String sha256,
            List<WebContributionResponse> webContributions,
            List<BotSettingsContributionResponse> botSettingsContributions) {}
    public record WebContributionResponse(String id, String label, String route, String icon,
            int order, String componentKey, String entrypoint, String customElement) {
        static WebContributionResponse from(
                String moduleId, ModuleWebContribution value, String sha256) {
            String entrypoint = value.assetPath() == null ? null
                    : "/module-assets/" + moduleId + "/" + value.assetPath()
                            + (sha256 == null ? "" : "?v=" + sha256.substring(0, 16));
            return new WebContributionResponse(value.id(), value.label(), value.route(), value.icon(),
                    value.order(), value.componentKey(), entrypoint, value.customElement());
        }
    }

    public record BotSettingsContributionResponse(
            String id, String label, int order, String entrypoint, String customElement) {
        static BotSettingsContributionResponse from(
                String moduleId, ModuleBotSettingsContribution value, String sha256) {
            String entrypoint = "/module-assets/" + moduleId + "/" + value.assetPath()
                    + (sha256 == null ? "" : "?v=" + sha256.substring(0, 16));
            return new BotSettingsContributionResponse(value.id(), value.label(), value.order(),
                    entrypoint, value.customElement());
        }
    }
}
