package com.mieai.qqbot.modules.database;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ConditionalOnProperty(name = "qqbot.modules.available.database-support", havingValue = "true")
@ComponentScan(basePackages = {
        "com.mieai.qqbot.admin.database",
        "com.mieai.qqbot.app.database"
})
public class DatabaseSupportModuleConfiguration {}
