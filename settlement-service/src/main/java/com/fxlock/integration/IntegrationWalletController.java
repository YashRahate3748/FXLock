package com.fxlock.integration;

import com.fxlock.wallet.Wallet;
import com.fxlock.wallet.WalletService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("integration")
public class IntegrationWalletController {
    private final InternalCustomerResolver customers;
    private final WalletService wallets;

    public IntegrationWalletController(InternalCustomerResolver customers, WalletService wallets) {
        this.customers = customers;
        this.wallets = wallets;
    }

    @GetMapping("/api/v1/wallets")
    public ResponseEntity<List<Wallet>> wallets(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Customer-Ref", required = false) String customer) {
        String resolved = customers.resolve(token, customer);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(wallets.getWallets(resolved));
    }
}
