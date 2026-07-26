package com.mieai.qqbot.module.api

/** Stable typed identifier used for communication between framework modules. */
data class ModuleServiceKey<T : Any>(
    val name: String,
    val type: Class<T>,
) {
    init {
        ModuleValidation.requireId(name, "name")
        require(!type.isPrimitive) { "service type must not be primitive" }
    }
}
