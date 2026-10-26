package com.fxlock.dashboard;

import com.fxlock.account.DashboardAccounts;
import com.fxlock.wallet.WalletRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FundingService {
    static final BigDecimal MAX_BALANCE = new BigDecimal("999999999999999.9999");
    private final DashboardAccounts accounts;
    private final WalletRepository wallets;
    private final FundingRepository records;
    private final BigDecimal maximum;

    public FundingService(DashboardAccounts accounts, WalletRepository wallets, FundingRepository records,
            @Value("${fxlock.funding.max-amount:1000000.00}") BigDecimal maximum) {
        if (maximum.signum() <= 0 || maximum.compareTo(MAX_BALANCE) > 0) throw new IllegalArgumentException("Invalid funding maximum");
        this.accounts = accounts; this.wallets = wallets; this.records = records; this.maximum = maximum;
    }

    @Transactional(rollbackFor = Exception.class)
    public FundingResult complete(String ref, String key, FundingRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,100}")) throw reject("INVALID_IDEMPOTENCY_KEY");
        if (request.operation() == null || !Set.of("DEPOSIT", "WITHDRAWAL").contains(request.operation())) throw reject("INVALID_OPERATION");
        if (request.walletId() == null || !request.walletId().matches("[1-9][0-9]{0,17}")) throw reject("INVALID_WALLET");
        BigDecimal amount = amount(request.amount());
        var account = accounts.active(ref, true);
        String hash = fingerprint("SIMULATED_FUNDING|" + account.customerId() + "|" + request.operation() + "|" + request.walletId() + "|" + amount.toPlainString());
        var previous = records.begin(key, hash, account.customerId());
        if (previous.isPresent()) return previous.get();
        var wallet = wallets.findByIdForUpdate(Long.valueOf(request.walletId())).orElseThrow(() -> reject("WALLET_UNAVAILABLE"));
        if (!account.customerId().equals(wallet.customerId()) || !"ACTIVE".equals(wallet.status())
                || !Set.of("EUR", "USD").contains(wallet.currencyCode())) throw reject("WALLET_UNAVAILABLE");
        boolean deposit = "DEPOSIT".equals(request.operation());
        BigDecimal after = deposit ? wallet.balance().add(amount) : wallet.balance().subtract(amount);
        if (after.signum() < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS");
        if (after.compareTo(MAX_BALANCE) > 0) throw reject("BALANCE_LIMIT");
        FundingRepository.one(deposit ? wallets.credit(wallet.walletId(), amount) : wallets.debit(wallet.walletId(), amount));
        return records.record(account.customerId(), wallet.walletId(), request.operation(), wallet.currencyCode(),
                amount, wallet.balance(), after, key);
    }

    BigDecimal amount(String value) {
        if (value == null || !value.matches("[0-9]{1,15}(\\.[0-9]{1,2})?")) throw reject("INVALID_AMOUNT");
        BigDecimal amount = new BigDecimal(value).setScale(2);
        if (amount.signum() <= 0 || amount.compareTo(maximum) > 0) throw reject("AMOUNT_LIMIT");
        return amount;
    }

    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static ResponseStatusException reject(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }
}
