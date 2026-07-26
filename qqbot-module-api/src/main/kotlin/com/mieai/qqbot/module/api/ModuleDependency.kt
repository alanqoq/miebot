package com.mieai.qqbot.module.api


/** A versioned dependency on another framework module. */
data class ModuleDependency(
    val moduleId: String,
    val minimumVersion: String,
    val optional: Boolean,
) {
    init {
        ModuleValidation.requireId(moduleId, "moduleId")
        ModuleValidation.requireVersion(minimumVersion, "minimumVersion")
    }

    companion object {
        fun required(moduleId: String, minimumVersion: String): ModuleDependency =
            ModuleDependency(moduleId, minimumVersion, false)

        fun optional(moduleId: String, minimumVersion: String): ModuleDependency =
            ModuleDependency(moduleId, minimumVersion, true)
    }
}
