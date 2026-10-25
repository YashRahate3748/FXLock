package com.fxlock.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DashboardAccounts {
    private final JdbcTemplate jdbc;
    private final AccountPasswords passwords;
    private final int attempts;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Attempts> failures = new ConcurrentHashMap<>();

    public DashboardAccounts(JdbcTemplate jdbc, @Qualifier("accountPasswords") AccountPasswords passwords,
            @Value("${fxlock.funding.password-attempts:5}") int attempts,
            @Value("${fxlock.funding.password-window-seconds:60}") long seconds) {
        if (attempts < 1 || seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Invalid funding throttle configuration");
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.attempts = attempts;
        this.windowMillis = seconds * 1000;
    }

    public Account active(String ref, boolean lock) {
        var rows = jdbc.query("""
                SELECT c.customer_id, c.customer_ref, c.full_name, a.email, a.account_status,
                       a.created_at, c.status AS customer_status
                FROM customer c JOIN customer_account a ON a.customer_id = c.customer_id
                WHERE c.customer_ref = ?
                """ + (lock ? " FOR UPDATE OF c.status, a.account_status" : ""),
                (rs, n) -> new Account(rs.getLong("customer_id"), rs.getString("customer_ref"),
                        rs.getString("full_name"), rs.getString("email"), rs.getString("account_status"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getString("customer_status")), ref);
        if (rows.size() != 1 || !"ACTIVE".equals(rows.getFirst().accountStatus())
                || !"ACTIVE".equals(rows.getFirst().customerStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_ACTIVE");
        }
        return rows.getFirst();
    }

    public void confirm(String ref, String password) {
        passwords.requireEnabled();
        active(ref, false);
        long now = System.currentTimeMillis();
        failures.entrySet().removeIf(e -> now - e.getValue().started >= windowMillis);
        if (failures.size() >= 10000 && !failures.containsKey(ref)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CONFIRMATION_BUSY");
        }
        var entry = failures.computeIfAbsent(ref, ignored -> new Attempts(now));
        synchronized (entry) {
            if (entry.count >= attempts) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PASSWORD_THROTTLED");
            List<String> stored = jdbc.query("""
                    SELECT a.login_password FROM customer_account a
                    JOIN customer c ON c.customer_id = a.customer_id WHERE c.customer_ref = ?
                    """, (rs, n) -> rs.getString(1), ref);
            if (password == null || password.isEmpty() || stored.size() != 1 || !passwords.matches(password, stored.getFirst())) {
                entry.count++;
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CONFIRMATION_REJECTED");
            }
        }
    }

    private static final class Attempts {
        final long started;
        int count;
        Attempts(long started) { this.started = started; }
    }

    public record Account(Long customerId, String customerRef, String fullName, String email,
                          String accountStatus, OffsetDateTime registeredAt, String customerStatus) {}
}
