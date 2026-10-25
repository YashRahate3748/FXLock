package com.fxlock.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class AccountPasswordProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AccountConfiguration.class);

    @Test void ordinaryProfilesDisablePasswordOperationsInsteadOfHashingOrStoringPlaintext() {
        for (String profiles : new String[]{"", "integration", "local"}) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).hasSingleBean(AccountPasswords.class);
                var passwords = context.getBean(AccountPasswords.class);
                assertThatThrownBy(() -> passwords.forStorage("DisposablePassword!2026"))
                        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
                assertThatThrownBy(() -> passwords.matches("anything", "anything"))
                        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            });
        }
    }

    @Test void disabledServicesConstructSuccessfullyAndRejectBeforeDatabaseAccess() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        runner.withPropertyValues("spring.profiles.active=integration")
                .withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> jdbc)
                .withUserConfiguration(RegistrationService.class, LoginVerificationService.class).run(context -> {
                    assertThat(context).hasNotFailed();
                    org.mockito.Mockito.verify(jdbc).afterPropertiesSet();
                    org.mockito.Mockito.verifyNoMoreInteractions(jdbc);
                    org.mockito.Mockito.clearInvocations(jdbc);
                    assertThatThrownBy(() -> context.getBean(RegistrationService.class)
                            .register(new RegistrationRequest("Name", "fixture@example.com", "ThrowawayPassword!")))
                            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
                    assertThatThrownBy(() -> context.getBean(LoginVerificationService.class)
                            .verify(new LoginVerificationRequest("fixture@example.com", "ThrowawayPassword!")))
                            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
                    org.mockito.Mockito.verifyNoInteractions(jdbc);
                });
    }

    @Test void plaintextRequiresExplicitIntegrationDemoProfiles() {
        runner.withPropertyValues("spring.profiles.active=integration,plaintext-account-demo").run(context -> {
            assertThat(context).hasSingleBean(AccountPasswords.class);
            var encoder = context.getBean(AccountPasswords.class);
            assertThat(encoder).isInstanceOf(PlaintextDemoAccountPasswords.class);
            assertThat(encoder.forStorage("  Throwaway only  ")).isEqualTo("  Throwaway only  ");
            assertThat(encoder.matches("  Throwaway only  ", "  Throwaway only  ")).isTrue();
            assertThat(encoder.matches("Throwaway only", "  Throwaway only  ")).isFalse();
        });
    }

    @Test void missingIntegrationAndIncompatibleProfilesFailStartup() {
        for (String profiles : new String[]{"plaintext-account-demo", "integration,plaintext-account-demo,prod",
                "integration,plaintext-account-demo,production", "integration,plaintext-account-demo,local"}) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
            });
        }
    }
}
