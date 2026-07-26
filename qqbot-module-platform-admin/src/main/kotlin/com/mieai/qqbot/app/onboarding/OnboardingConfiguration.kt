package com.mieai.qqbot.app.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.admin.database.DatabaseAdministrationService
import com.mieai.qqbot.admin.onboarding.OnboardingAdministrationService
import com.mieai.qqbot.persistence.admin.AdminUserRepository
import com.mieai.qqbot.persistence.bot.BotRepository
import java.time.Clock
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OnboardingProperties::class)
class OnboardingConfiguration {
    @Bean
    fun onboardingAdministrationService(
        properties: OnboardingProperties,
        objectMapper: ObjectMapper,
        adminRepository: AdminUserRepository,
        botRepository: BotRepository,
        databaseService: DatabaseAdministrationService,
    ): OnboardingAdministrationService = FileOnboardingRuntime(
        properties.stateFile,
        objectMapper,
        adminRepository,
        botRepository,
        databaseService,
        Clock.systemUTC(),
    )
}
