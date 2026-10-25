package com.fxlock.account;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Disposable mocked rows only; never loads the application datasource. */
class PlaintextAccountServiceTest {
    static final String EMAIL = "throwaway@example.com";
    static final String PASSWORD = "  ThrowawayPassword!2026  ";
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PlaintextDemoAccountPasswords encoder = new PlaintextDemoAccountPasswords();
    final LoginVerificationService login = new LoginVerificationService(jdbc, encoder);
    final RegistrationService registration = new RegistrationService(jdbc, encoder);
    String saved = PASSWORD, accountStatus = "ACTIVE", customerStatus = "ACTIVE";
    boolean found = true;

    @BeforeEach void fixtures() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq(EMAIL)))
                .thenAnswer(call -> {
                    if (!found) return List.of();
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("customer_ref")).thenReturn("CUST-fixture");
                    when(row.getString("full_name")).thenReturn("Throwaway Customer");
                    when(row.getString("email")).thenReturn(EMAIL);
                    when(row.getString("login_password")).thenReturn(saved);
                    when(row.getString("account_status")).thenReturn(accountStatus);
                    when(row.getString("customer_status")).thenReturn(customerStatus);
                    RowMapper<?> mapper = call.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
    }

    @Test void registrationStoresExactPasswordAndRetainsNewAccountWalletCreation() {
        var response = registration.register(new RegistrationRequest(" Name ", EMAIL, PASSWORD));
        assertThat(response.customerRef()).startsWith("CUST-");
        verify(jdbc).update(contains("INSERT INTO customer_account"), eq(42L), eq(EMAIL), eq(PASSWORD));
        verify(jdbc).update(contains("login_password"), eq(42L), eq(EMAIL), eq(PASSWORD));
        verify(jdbc).update(contains("INSERT INTO wallet"), eq(42L), eq("EUR"));
        verify(jdbc).update(contains("INSERT INTO wallet"), eq(42L), eq("USD"));
    }

    @Test void correctPasswordReturnsOnlyIdentity() {
        var result = login.verify(new LoginVerificationRequest(" Throwaway@Example.com ", PASSWORD));
        assertThat(result.customerRef()).isEqualTo("CUST-fixture");
        assertThat(result.toString()).doesNotContain(PASSWORD);
        verify(jdbc).query(contains("a.login_password"), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq(EMAIL));
    }

    @Test void unicodePasswordsAreComparedWithoutNormalization() {
        saved = "Throwaway_\u00e9_2026";
        assertThat(login.verify(new LoginVerificationRequest(EMAIL, saved)).customerRef()).isEqualTo("CUST-fixture");
        reject("Throwaway_e\u0301_2026", 401, "INVALID_CREDENTIALS");
    }

    @Test void incorrectAndTrimmedPasswordsAreRejected() {
        reject("wrong-password", 401, "INVALID_CREDENTIALS");
        reject(PASSWORD.strip(), 401, "INVALID_CREDENTIALS");
    }

    @Test void inactiveAccountCustomerAndMissingAccountAreRejected() {
        accountStatus = "DISABLED"; reject(PASSWORD, 401, "INVALID_CREDENTIALS");
        accountStatus = "ACTIVE"; customerStatus = "INACTIVE"; reject(PASSWORD, 401, "INVALID_CREDENTIALS");
        customerStatus = "ACTIVE"; found = false; reject(PASSWORD, 401, "INVALID_CREDENTIALS");
    }

    @Test void legacyBcryptValuesRequireResetEvenWhenSubmittedVerbatim() {
        saved = "$2a$10$" + "A".repeat(53);
        reject(PASSWORD, 409, "ACCOUNT_PASSWORD_RESET_REQUIRED");
        reject(saved, 409, "ACCOUNT_PASSWORD_RESET_REQUIRED");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void inactiveLegacyAccountsRemainGenericCredentialRejections() {
        saved = "$2a$10$" + "A".repeat(53);
        accountStatus = "DISABLED"; reject(saved, 401, "INVALID_CREDENTIALS");
    }

    @Test void hashShapedNewPasswordsAreRejectedBeforeAnyWrites() {
        String legacy = "$2a$10$" + "A".repeat(53);
        assertThatThrownBy(() -> registration.register(new RegistrationRequest("Name", EMAIL, legacy)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getReason()).isEqualTo("DEMO_PASSWORD_FORMAT_UNSUPPORTED"));
        verifyNoInteractions(jdbc);
    }

    @Test void existingByteLimitIsPreserved() {
        reject("\u00e9".repeat(37), 401, "INVALID_CREDENTIALS");
        assertThatThrownBy(() -> registration.register(new RegistrationRequest("Name", EMAIL, "\u00e9".repeat(37))))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    private void reject(String password, int status, String code) {
        assertThatThrownBy(() -> login.verify(new LoginVerificationRequest(EMAIL, password)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(status);
                    assertThat(error.getReason()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain(password);
                });
    }
}
