package com.fxlock.account;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LoginVerificationService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountPasswords passwords;

    public LoginVerificationService(
            JdbcTemplate jdbcTemplate,
            @Qualifier("accountPasswords")
            AccountPasswords passwords
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwords = passwords;
    }

    public LoginVerificationResponse verify(
            LoginVerificationRequest request
    ) {
        passwords.requireEnabled();
        String email = request.email()
                .strip()
                .toLowerCase(Locale.ROOT);

        String password = request.password();

        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }

        List<AccountCredentials> accounts = jdbcTemplate.query(
                """
                SELECT
                    c.customer_ref,
                    c.full_name,
                    a.email,
                    a.login_password,
                    a.account_status,
                    c.status AS customer_status
                FROM customer_account a
                JOIN customer c
                    ON c.customer_id = a.customer_id
                WHERE a.email = ?
                """,
                (resultSet, rowNumber) -> new AccountCredentials(
                        resultSet.getString("customer_ref"),
                        resultSet.getString("full_name"),
                        resultSet.getString("email"),
                        resultSet.getString("login_password"),
                        resultSet.getString("account_status"),
                        resultSet.getString("customer_status")
                ),
                email
        );

        AccountCredentials account = accounts.isEmpty()
                ? null
                : accounts.get(0);

        boolean active = account != null
                && "ACTIVE".equals(account.accountStatus())
                && "ACTIVE".equals(account.customerStatus());
        if (!active || !passwords.matches(password, account.storedPassword())) {
            throw invalidCredentials();
        }

        return new LoginVerificationResponse(
                account.customerRef(),
                account.fullName(),
                account.email()
        );
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS"
        );
    }

    private record AccountCredentials(
            String customerRef,
            String fullName,
            String email,
            String storedPassword,
            String accountStatus,
            String customerStatus
    ) {
        @Override public String toString() { return "AccountCredentials[credentials redacted]"; }
    }
}