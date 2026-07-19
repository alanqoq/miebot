package com.mieai.qqbot.app.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.admin.database.DatabaseAdministrationService;
import com.mieai.qqbot.admin.onboarding.OnboardingAdministrationService;
import com.mieai.qqbot.persistence.admin.AdminUserRepository;
import com.mieai.qqbot.persistence.bot.BotRepository;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OnboardingProperties.class)
public class OnboardingConfiguration {
    @Bean
    OnboardingAdministrationService onboardingAdministrationService(
            OnboardingProperties properties,
            ObjectMapper objectMapper,
            AdminUserRepository adminRepository,
            BotRepository botRepository,
            DatabaseAdministrationService databaseService) {
        return new FileOnboardingRuntime(
                properties.getStateFile(),
                objectMapper,
                adminRepository,
                botRepository,
                databaseService,
                Clock.systemUTC());
    }
}
