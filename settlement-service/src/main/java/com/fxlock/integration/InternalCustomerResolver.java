package com.fxlock.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("integration")
public class InternalCustomerResolver {

    private final JdbcTemplate jdbcTemplate;
    private final byte[] expectedToken;

    public InternalCustomerResolver(
            JdbcTemplate jdbcTemplate,
            @Value("${fxlock.internal-token}") String internalToken) {

        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException(
                    "FX_INTERNAL_TOKEN must be configured for integration");
        }

        this.jdbcTemplate = jdbcTemplate;
        this.expectedToken =
                internalToken.getBytes(StandardCharsets.UTF_8);
    }

    public String resolve(
            String suppliedToken,
            String customerRef) {

        // Authenticate the caller before resolving customer identity.
        if (suppliedToken == null
                || !MessageDigest.isEqual(
                        expectedToken,
                        suppliedToken.getBytes(StandardCharsets.UTF_8))) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_INTERNAL_TOKEN");
        }

        if (customerRef == null
                || customerRef.isBlank()
                || customerRef.length() > 50
                || !customerRef.equals(customerRef.trim())) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_CUSTOMER_REFERENCE");
        }

        List<String> customers = jdbcTemplate.query(
                """
                SELECT c.customer_ref
                FROM customer c JOIN customer_account a ON a.customer_id=c.customer_id
                WHERE c.customer_ref = ?
                  AND c.status = 'ACTIVE' AND a.account_status='ACTIVE'
                """,
                (rs, rowNum) -> rs.getString("customer_ref"),
                customerRef);

        if (customers.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "CUSTOMER_NOT_ACTIVE");
        }

        return customers.getFirst();
    }
}
