package com.mieai.qqbot.app.onboarding;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qqbot.onboarding")
public class OnboardingProperties {
    private Path stateFile = Path.of("onboarding.json");

    public Path getStateFile() {
        return stateFile;
    }

    public void setStateFile(Path stateFile) {
        this.stateFile = stateFile;
    }
}
