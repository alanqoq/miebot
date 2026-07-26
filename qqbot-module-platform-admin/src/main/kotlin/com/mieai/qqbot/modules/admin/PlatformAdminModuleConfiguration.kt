package com.mieai.qqbot.modules.admin

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.ComponentScan

@AutoConfiguration
@ConditionalOnProperty(name = ["qqbot.modules.available.platform-admin"], havingValue = "true")
@ComponentScan(
    basePackages = [
        "com.mieai.qqbot.admin.error",
        "com.mieai.qqbot.admin.onboarding",
        "com.mieai.qqbot.admin.security",
        "com.mieai.qqbot.admin.system",
        "com.mieai.qqbot.admin.web",
        "com.mieai.qqbot.app.onboarding",
        "com.mieai.qqbot.app.web",
    ],
)
class PlatformAdminModuleConfiguration
