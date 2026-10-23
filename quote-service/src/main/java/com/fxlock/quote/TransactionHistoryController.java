package com.fxlock.quote;

import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import static com.fxlock.quote.TransactionHistoryRepository.*;

@RestController
@RequestMapping("/api/transaction-history")
public class TransactionHistoryController {
    private final TransactionHistoryRepository history;
    public TransactionHistoryController(TransactionHistoryRepository history) { this.history=history; }
    @GetMapping("/accounts") List<Account> accounts(Principal principal) { return history.accounts(principal.getName()); }
    @GetMapping HistoryPage entries(Principal principal,@RequestParam(required=false) String walletId,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return history.history(principal.getName(),walletId,page,size);
    }
}