package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.api.ModuleDescriptor

data class ModuleRuntimeSnapshot(
    val descriptor: ModuleDescriptor,
    val state: ModuleRuntimeState,
    val error: String?,
)
