package com.fxlock.quote;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class ProfileGuard {
    public ProfileGuard(Environment environment) {
        if(environment.acceptsProfiles(Profiles.of("demo")) && environment.acceptsProfiles(Profiles.of("oracle-local","team")))
            throw new IllegalStateException("Choose demo OR oracle-local OR team. Demo fixture writers must never run against Oracle.");
        if(environment.acceptsProfiles(Profiles.of("oracle-local")) && environment.acceptsProfiles(Profiles.of("team")))
            throw new IllegalStateException("Choose oracle-local OR team, not both.");
    }
}
