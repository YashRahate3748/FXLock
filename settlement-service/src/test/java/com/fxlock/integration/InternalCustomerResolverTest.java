package com.fxlock.integration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalCustomerResolverTest {
    @Test void authenticationHappensBeforeAnyDatabaseQuery() {
        var jdbc = mock(JdbcTemplate.class);
        var resolver = new InternalCustomerResolver(jdbc, "expected-token");
        assertThrows(ResponseStatusException.class, () -> resolver.resolve(null, "CUST001"));
        assertThrows(ResponseStatusException.class, () -> resolver.resolve("wrong", "CUST001"));
        verifyNoInteractions(jdbc);
    }

    @Test void invalidCustomerReferenceNeverReachesDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var resolver = new InternalCustomerResolver(jdbc, "expected-token");
        for (String customer : new String[]{null, "", " CUST001", "a".repeat(51)}) {
            assertThrows(ResponseStatusException.class, () -> resolver.resolve("expected-token", customer));
        }
        verifyNoInteractions(jdbc);
    }

    @Test void integrationCannotEnableLocalSimulatedIdentity() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("integration", "local");
        assertThrows(IllegalStateException.class, () -> new IntegrationConfiguration(environment));
        environment.setActiveProfiles("integration");
        assertDoesNotThrow(() -> new IntegrationConfiguration(environment));
    }
}
