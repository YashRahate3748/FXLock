package com.fxlock.account;

import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact demonstration-password verification against mocked rows; no database connections. */
class LoginVerificationServiceTest {
    static final String EMAIL = "registration.test@example.com";
    static final String PASSWORD = " LocalTestPassword!2026 ";
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PlaintextDemoAccountPasswords encoder = new PlaintextDemoAccountPasswords();
    final String storedPassword = PASSWORD;
    final LoginVerificationService service = new LoginVerificationService(jdbc, encoder);
    String accountStatus = "ACTIVE", customerStatus = "ACTIVE";
    boolean found = true;

    @BeforeEach void databaseRow() throws Exception {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq(EMAIL)))
            .thenAnswer(invocation -> {
                if (!found) { return List.of(); }
                ResultSet row = mock(ResultSet.class);
                when(row.getString("customer_ref")).thenReturn("CUST-stored-account");
                when(row.getString("full_name")).thenReturn("Registered Customer");
                when(row.getString("email")).thenReturn(EMAIL);
                when(row.getString("login_password")).thenReturn(storedPassword);
                when(row.getString("account_status")).thenReturn(accountStatus);
                when(row.getString("customer_status")).thenReturn(customerStatus);
                RowMapper<?> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(row, 0));
            });
    }

    @Test void exactStoredPasswordIsVerifiedAndNameIsReturned() {
        assertEquals(PASSWORD, storedPassword);
        var account = service.verify(new LoginVerificationRequest(" Registration.Test@Example.com ", PASSWORD));
        assertEquals("CUST-stored-account", account.customerRef());
        assertEquals("Registered Customer", account.fullName());
        assertEquals(EMAIL, account.email());
    }

    @Test void wrongPasswordOrTrimmedPasswordCannotAuthenticate() {
        rejected("incorrect-password");
        rejected(PASSWORD.strip());
    }

    @Test void inactiveAccountOrCustomerCannotAuthenticate() {
        accountStatus = "DISABLED";
        rejected(PASSWORD);
        accountStatus = "ACTIVE"; customerStatus = "DISABLED";
        rejected(PASSWORD);
    }

    @Test void unknownEmailCannotAuthenticate() {
        found = false;
        rejected(PASSWORD);
    }

    @Test void existingByteLimitIsRejectedBeforeQueryingDatabase() {
        rejected("\u00e9".repeat(37));
        verifyNoInteractions(jdbc);
    }

    private void rejected(String password) {
        var failure = assertThrows(ResponseStatusException.class,
            () -> service.verify(new LoginVerificationRequest(EMAIL, password)));
        assertEquals(401, failure.getStatusCode().value());
        assertEquals("INVALID_CREDENTIALS", failure.getReason());
    }
}
