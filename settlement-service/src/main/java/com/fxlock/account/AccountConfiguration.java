package com.fxlock.account;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
public class AccountConfiguration {
    @Bean("accountPasswords")
    @Profile("!plaintext-account-demo")
    public AccountPasswords disabledAccountPasswords() {
        return new AccountPasswords() {
            @Override public void requireEnabled() {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_PASSWORD_MODE_DISABLED");
            }
            @Override public String forStorage(String submitted) { requireEnabled(); return null; }
            @Override public boolean matches(String submitted, String stored) { requireEnabled(); return false; }
        };
    }

    @Bean("accountPasswords")
    @Profile("plaintext-account-demo")
    public AccountPasswords plaintextAccountPasswords(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("integration"))
                || environment.acceptsProfiles(Profiles.of("prod", "production", "local"))) {
            throw new IllegalStateException("plaintext-account-demo requires integration and cannot run with local, prod or production.");
        }
        LoggerFactory.getLogger(AccountConfiguration.class).warn(
                "UNSAFE LOCAL DEMONSTRATION: account passwords are stored in plaintext. Use throwaway passwords only; never deploy this profile.");
        return new PlaintextDemoAccountPasswords();
    }
}
