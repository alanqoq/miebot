package com.mieai.qqbot.module.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable identity, dependency, capability, and Web metadata for one framework module. */
public record ModuleDescriptor(
        String id,
        String name,
        String version,
        String minimumFrameworkVersion,
        List<ModuleDependency> dependencies,
        Set<String> capabilities,
        List<ModuleWebContribution> webContributions,
        List<ModuleBotSettingsContribution> botSettingsContributions) {

    public ModuleDescriptor {
        id = ModuleValidation.requireId(id, "id");
        name = ModuleValidation.requireText(name, "name", 128);
        version = ModuleValidation.requireVersion(version, "version");
        minimumFrameworkVersion = ModuleValidation.requireVersion(
                minimumFrameworkVersion, "minimumFrameworkVersion");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies must not be null"));
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities must not be null"));
        webContributions = List.copyOf(Objects.requireNonNull(
                webContributions, "webContributions must not be null"));
        botSettingsContributions = List.copyOf(Objects.requireNonNull(
                botSettingsContributions, "botSettingsContributions must not be null"));

        Set<String> dependencyIds = new LinkedHashSet<>();
        for (ModuleDependency dependency : dependencies) {
            if (id.equals(dependency.moduleId())) {
                throw new IllegalArgumentException("a module cannot depend on itself");
            }
            if (!dependencyIds.add(dependency.moduleId())) {
                throw new IllegalArgumentException("duplicate module dependency: " + dependency.moduleId());
            }
        }
        Set<String> contributionIds = new LinkedHashSet<>();
        for (ModuleWebContribution contribution : webContributions) {
            if (!contributionIds.add(contribution.id())) {
                throw new IllegalArgumentException("duplicate Web contribution: " + contribution.id());
            }
        }
        for (ModuleBotSettingsContribution contribution : botSettingsContributions) {
            if (!contributionIds.add(contribution.id())) {
                throw new IllegalArgumentException("duplicate Web contribution: " + contribution.id());
            }
        }
        for (String capability : capabilities) {
            ModuleValidation.requireId(capability, "capability");
        }
    }

    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private String version = "1.0.0";
        private String minimumFrameworkVersion = FrameworkVersion.CURRENT;
        private final List<ModuleDependency> dependencies = new ArrayList<>();
        private final Set<String> capabilities = new LinkedHashSet<>();
        private final List<ModuleWebContribution> webContributions = new ArrayList<>();
        private final List<ModuleBotSettingsContribution> botSettingsContributions = new ArrayList<>();

        private Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder version(String value) {
            version = value;
            return this;
        }

        public Builder minimumFrameworkVersion(String value) {
            minimumFrameworkVersion = value;
            return this;
        }

        public Builder dependsOn(ModuleDependency dependency) {
            dependencies.add(Objects.requireNonNull(dependency, "dependency must not be null"));
            return this;
        }

        public Builder capability(String capability) {
            capabilities.add(capability);
            return this;
        }

        public Builder web(ModuleWebContribution contribution) {
            webContributions.add(Objects.requireNonNull(contribution, "contribution must not be null"));
            return this;
        }

        public Builder botSettings(ModuleBotSettingsContribution contribution) {
            botSettingsContributions.add(Objects.requireNonNull(
                    contribution, "contribution must not be null"));
            return this;
        }

        public ModuleDescriptor build() {
            return new ModuleDescriptor(id, name, version, minimumFrameworkVersion,
                    dependencies, capabilities, webContributions, botSettingsContributions);
        }
    }
}
