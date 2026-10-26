package com.fxlock.history;

import com.fxlock.account.DashboardAccounts;
import com.fxlock.dashboard.DashboardSecurity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard/history")
public class DashboardHistoryController {
    private final DashboardAccounts accounts;
    private final LedgerHistoryRepository history;

    public DashboardHistoryController(DashboardAccounts accounts, LedgerHistoryRepository history) {
        this.accounts=accounts; this.history=history;
    }

    @GetMapping
    public LedgerHistoryRepository.HistoryPage history(HttpServletRequest request,
            @RequestParam(required=false) String walletId, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        var account=accounts.active(DashboardSecurity.customer(request),false);
        return history.history(account.customerId(),walletId,page,size);
    }
}
