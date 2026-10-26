package com.fxlock.quote;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class ProfileGuardTest {
    @Test void rejectsDemoFixtureWritersWithEitherOracleProfile() {
        for(String profile:new String[]{"oracle-local","team"}) {
            var env=new MockEnvironment(); env.setActiveProfiles("demo",profile);
            assertThatThrownBy(() -> new ProfileGuard(env)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void acceptsIsolatedOracleAndDemoModes() {
        for(String profile:new String[]{"oracle-local","demo","team"}) {
            var env=new MockEnvironment(); env.setActiveProfiles(profile);
            assertThatCode(() -> new ProfileGuard(env)).doesNotThrowAnyException();
        }
    }
}
