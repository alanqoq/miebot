package com.mieai.qqbot.persistence.plugin

import java.util.UUID

class PluginBindingOptimisticLockException(
    val bindingId: UUID,
    val expectedRevision: Long,
) : RuntimeException("Plugin binding $bindingId does not have expected revision $expectedRevision")
