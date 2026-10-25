package com.fxlock.integration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@Profile("integration")
public class IntegrationConfiguration {
    public IntegrationConfiguration(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException("Choose integration OR local; simulated customer endpoints must not run in integration.");
        }
    }
}
