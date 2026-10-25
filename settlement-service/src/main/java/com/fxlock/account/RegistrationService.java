package com.fxlock.account;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistrationService {

    private final JdbcTemplate jdbcTemplate;
    private final AccountPasswords passwords;

    public RegistrationService(
            JdbcTemplate jdbcTemplate,
            @Qualifier("accountPasswords")
            AccountPasswords passwords
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwords = passwords;
    }

    @Transactional
    public RegistrationResponse register(
            RegistrationRequest request
    ) {
        passwords.requireEnabled();
        String fullName = request.fullName().strip();

        String email = request.email()
                .strip()
                .toLowerCase(Locale.ROOT);

        validateStorageLengths(
                fullName,
                email,
                request.password()
        );

        String storedPassword =
                passwords.forStorage(request.password());

        String customerRef = "CUST-" + UUID.randomUUID();

        requireOneRow(
                jdbcTemplate.update(
                        """
                        INSERT INTO customer (
                            customer_ref,
                            full_name,
                            email,
                            status
                        )
                        VALUES (?, ?, ?, 'ACTIVE')
                        """,
                        customerRef,
                        fullName,
                        email
                )
        );

        Long customerId = jdbcTemplate.queryForObject(
                """
                SELECT customer_id
                FROM customer
                WHERE customer_ref = ?
                """,
                Long.class,
                customerRef
        );

        if (customerId == null) {
            throw new IllegalStateException(
                    "Customer ID was not generated"
            );
        }

        try {
            requireOneRow(
                    jdbcTemplate.update(
                            """
                            INSERT INTO customer_account (
                                customer_id,
                                email,
                                login_password,
                                account_status
                            )
                            VALUES (?, ?, ?, 'ACTIVE')
                            """,
                            customerId,
                            email,
                            storedPassword
                    )
            );
        } catch (DuplicateKeyException exception) {
            // Throwing out of this method rolls back the customer insert too.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "REGISTRATION_CONFLICT"
            );
        }

        createWallet(customerId, "EUR");
        createWallet(customerId, "USD");

        return new RegistrationResponse(
                customerRef,
                "Account created."
        );
    }

    private void createWallet(
            Long customerId,
            String currencyCode
    ) {
        requireOneRow(
                jdbcTemplate.update(
                        """
                        INSERT INTO wallet (
                            customer_id,
                            currency_code,
                            balance,
                            version_no,
                            status
                        )
                        VALUES (?, ?, 0, 0, 'ACTIVE')
                        """,
                        customerId,
                        currencyCode
                )
        );
    }

    private void validateStorageLengths(
            String fullName,
            String email,
            String password
    ) {
        if (fullName.isBlank() || utf8Length(fullName) > 150) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REGISTRATION_NAME"
            );
        }

        if (utf8Length(email) > 200) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REGISTRATION_EMAIL"
            );
        }

        if (utf8Length(password) > 72) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REGISTRATION_PASSWORD"
            );
        }
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    "Registration did not write the expected record"
            );
        }
    }
}