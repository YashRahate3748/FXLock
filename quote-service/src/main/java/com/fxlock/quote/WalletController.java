package com.fxlock.quote;

import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import com.fxlock.quote.Models.WalletOption;

@RestController
public class WalletController {
    private final WalletGateway wallets;
    public WalletController(WalletGateway wallets) { this.wallets=wallets; }
    @GetMapping("/wallets/quote-options") List<WalletOption> options(Principal principal) {
        return wallets.options(principal.getName());
    }
}
