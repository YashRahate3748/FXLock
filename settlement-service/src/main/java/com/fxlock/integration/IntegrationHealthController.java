package com.fxlock.integration;

import com.fxlock.account.InternalServiceAuthenticator;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("integration")
public class IntegrationHealthController {
    private final InternalServiceAuthenticator authenticator;
    private final JdbcTemplate jdbc;

    public IntegrationHealthController(InternalServiceAuthenticator authenticator, JdbcTemplate jdbc) {
        this.authenticator = authenticator;
        this.jdbc = jdbc;
    }

    @GetMapping("/internal/health")
    public ResponseEntity<Map<String, String>> health(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        authenticator.authenticate(token);
        jdbc.queryForObject("SELECT 1 FROM dual", Integer.class);
        // Check the account migration exists without reading or changing customer data.
        jdbc.queryForObject("SELECT COUNT(login_password) FROM customer_account WHERE 1=0", Integer.class);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status", "UP"));
    }
}
