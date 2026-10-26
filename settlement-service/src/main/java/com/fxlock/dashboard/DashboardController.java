package com.fxlock.dashboard;

import com.fxlock.account.DashboardAccounts;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardAccounts accounts;
    private final JdbcTemplate jdbc;
    private final FundingService funding;
    private final FundingRepository records;
    private final String quoteUrl;

    public DashboardController(DashboardAccounts accounts, JdbcTemplate jdbc, FundingService funding,
            FundingRepository records, @Value("${fxlock.dashboard.quote-url:http://localhost:8082}") String quoteUrl) {
        this.accounts = accounts; this.jdbc = jdbc; this.funding = funding; this.records = records; this.quoteUrl = quoteUrl;
    }

    private String customer(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(DashboardSecurity.CUSTOMER) instanceof String ref)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        return ref;
    }

    @GetMapping
    public Dashboard dashboard(HttpServletRequest request) {
        var account = accounts.active(customer(request), false);
        var wallets = jdbc.query("""
                SELECT wallet_id, currency_code, balance, status FROM wallet
                WHERE customer_id = ? ORDER BY currency_code
                """, (rs, n) -> new WalletView(rs.getBigDecimal("wallet_id").toBigIntegerExact().toString(), rs.getString("currency_code"),
                        rs.getBigDecimal("balance").toPlainString(), rs.getString("status")), account.customerId());
        return new Dashboard(new AccountView(account.customerRef(), account.fullName(), account.email(),
                account.accountStatus(), account.registeredAt()), wallets,
                (String) request.getSession().getAttribute(DashboardSecurity.CSRF), quoteUrl + "/");
    }

    @PostMapping("/funding")
    public FundingResult complete(@RequestHeader("Idempotency-Key") String key, @RequestBody FundingRequest intent, HttpServletRequest request) {
        String ref = customer(request);
        accounts.confirm(ref, intent.password());
        // Confirmation precedes the wallet transaction, including on replay.
        return funding.complete(ref, key, intent);
    }

    @GetMapping("/funding/{key}")
    public FundingResult outcome(@PathVariable String key, HttpServletRequest request) {
        var account = accounts.active(customer(request), false);
        return records.find(key, account.customerId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OUTCOME_NOT_FOUND"));
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        request.getSession().invalidate();
        return Map.of("logoutUrl", quoteUrl + "/dashboard/logout");
    }

    public record WalletView(String walletId, String currency, String balance, String status) {}
    public record AccountView(String customerRef, String fullName, String email, String accountStatus,
                              java.time.OffsetDateTime registeredAt) {}
    public record Dashboard(AccountView account, List<WalletView> wallets, String csrfToken, String quoteUrl) {}
}
