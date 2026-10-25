package com.fxlock.integration;

import com.fxlock.account.DashboardAccounts;
import com.fxlock.history.LedgerHistoryRepository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Keeps the reference branch's M2 JSON contract behind the existing protected service identity. */
@RestController
@Profile("integration")
@RequestMapping("/internal/transaction-history")
public class IntegrationHistoryController {
    private final InternalCustomerResolver resolver;
    private final DashboardAccounts accounts;
    private final LedgerHistoryRepository history;

    public IntegrationHistoryController(InternalCustomerResolver resolver, DashboardAccounts accounts, LedgerHistoryRepository history) {
        this.resolver=resolver; this.accounts=accounts; this.history=history;
    }

    private long customer(String token, String ref) {
        return accounts.active(resolver.resolve(token,ref),false).customerId();
    }

    @GetMapping
    public ResponseEntity<LedgerHistoryRepository.HistoryPage> history(
            @RequestHeader(value="X-Internal-Token",required=false) String token,
            @RequestHeader(value="X-Customer-Ref",required=false) String ref,
            @RequestParam(required=false) String walletId, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(history.history(customer(token,ref),walletId,page,size));
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<LedgerHistoryRepository.Account>> accounts(
            @RequestHeader(value="X-Internal-Token",required=false) String token,
            @RequestHeader(value="X-Customer-Ref",required=false) String ref) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.accounts(customer(token,ref)));
    }
}
