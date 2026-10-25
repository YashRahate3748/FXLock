package com.fxlock.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InternalServiceAuthenticator {

    private final String configuredToken;

    public InternalServiceAuthenticator(
            @Value("${fxlock.internal-token:}") String configuredToken
    ) {
        this.configuredToken = configuredToken;
    }

    public void authenticate(String suppliedToken) {
        if (configuredToken == null || configuredToken.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ACCOUNT_SERVICE_NOT_CONFIGURED"
            );
        }

        if (suppliedToken == null
                || !MessageDigest.isEqual(
                        configuredToken.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8)
                )) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_INTERNAL_TOKEN"
            );
        }
    }
}