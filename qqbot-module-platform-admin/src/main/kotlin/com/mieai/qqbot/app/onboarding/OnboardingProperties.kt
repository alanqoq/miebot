package com.mieai.qqbot.app.onboarding

import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("qqbot.onboarding")
class OnboardingProperties {
    var stateFile: Path = Path.of("onboarding.json")
}
