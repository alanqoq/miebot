package com.mieai.qqbot.module.api;

/** A versioned dependency on another framework module. */
public record ModuleDependency(String moduleId, String minimumVersion, boolean optional) {
    public ModuleDependency {
        moduleId = ModuleValidation.requireId(moduleId, "moduleId");
        minimumVersion = ModuleValidation.requireVersion(minimumVersion, "minimumVersion");
    }

    public static ModuleDependency required(String moduleId, String minimumVersion) {
        return new ModuleDependency(moduleId, minimumVersion, false);
    }

    public static ModuleDependency optional(String moduleId, String minimumVersion) {
        return new ModuleDependency(moduleId, minimumVersion, true);
    }
}
