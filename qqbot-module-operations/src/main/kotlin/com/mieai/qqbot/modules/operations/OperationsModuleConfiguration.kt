package com.mieai.qqbot.modules.operations

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.ComponentScan

@AutoConfiguration
@ConditionalOnProperty(name = ["qqbot.modules.available.operations"], havingValue = "true")
@ComponentScan(
    basePackages = [
        "com.mieai.qqbot.admin.audit",
        "com.mieai.qqbot.admin.events",
        "com.mieai.qqbot.admin.health",
    ],
)
class OperationsModuleConfiguration
