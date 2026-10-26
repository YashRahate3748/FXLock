package com.fxlock.settlement;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fxlock.ledger.LedgerRepository;
import com.fxlock.quote.QuoteRepository;
import com.fxlock.quote.QuoteSnapshot;
import com.fxlock.wallet.WalletRepository;
import com.fxlock.wallet.WalletRepository.LockedWallet;
import com.fxlock.audit.AuditRepository;
import com.fxlock.outbox.OutboxRepository;

import com.fxlock.idempotency.IdempotencyRepository;

@Service
public class SettlementService {

    private final QuoteRepository quoteRepository;
    private final WalletRepository walletRepository;
    private final SettlementRepository settlementRepository;
    private final LedgerRepository ledgerRepository;
    private final AuditRepository auditRepository;
    private final OutboxRepository outboxRepository;
    
    private final IdempotencyRepository idempotencyRepository;

    public SettlementService(
            QuoteRepository quoteRepository,
            WalletRepository walletRepository,
            SettlementRepository settlementRepository,
            LedgerRepository ledgerRepository,
            IdempotencyRepository idempotencyRepository,
            AuditRepository auditRepository,
            OutboxRepository outboxRepository
    ) {
        this.quoteRepository = quoteRepository;
        this.walletRepository = walletRepository;
        this.settlementRepository = settlementRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
    }
    
    @Transactional(readOnly = true)
    public SettlementResponse getSettlement(
            String settlementId,
            String customerRef
    ) {
        return settlementRepository
                .findOwnedById(settlementId, customerRef)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "SETTLEMENT_NOT_FOUND"
                ));
    }

    @Transactional(
            rollbackFor = Exception.class,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED
    )
    public SettlementResponse settle(
            String quoteId,
            String customerRef,
            String idempotencyKey,
            String correlationId
    ) {
    	if (idempotencyKey == null
    	        || idempotencyKey.isBlank()
    	        || idempotencyKey.length() > 200) {
    	    throw new ResponseStatusException(
    	            HttpStatus.BAD_REQUEST,
    	            "INVALID_IDEMPOTENCY_KEY"
    	    );
    	}
    	
        Long customerId = settlementRepository
                .findActiveCustomerIdByRef(customerRef)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "CUSTOMER_NOT_ACTIVE"
                ));
        
        var previousResponse = idempotencyRepository.beginOrReplay(
                idempotencyKey,
                customerId,
                quoteId
        );

        if (previousResponse.isPresent()) {
            return previousResponse.get();
        }

        // Lock the quote first.
        QuoteSnapshot quote = quoteRepository
                .findByIdForUpdate(quoteId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "QUOTE_NOT_FOUND"
                ));

        if (!customerId.equals(quote.customerId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "QUOTE_NOT_OWNED"
            );
        }

        if (!"OPEN".equals(quote.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "QUOTE_NOT_OPEN"
            );
        }

        if (quote.sourceWalletId().equals(quote.targetWalletId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "QUOTE_WALLETS_INVALID"
            );
        }

        // Lock both wallets in ascending ID order.
        Long firstId = Math.min(
                quote.sourceWalletId(),
                quote.targetWalletId()
        );

        Long secondId = Math.max(
                quote.sourceWalletId(),
                quote.targetWalletId()
        );

        LockedWallet first = lockWallet(firstId);
        LockedWallet second = lockWallet(secondId);

        LockedWallet source =
                first.walletId().equals(quote.sourceWalletId())
                        ? first : second;

        LockedWallet target =
                first.walletId().equals(quote.targetWalletId())
                        ? first : second;

        if (!customerId.equals(source.customerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "WALLET_NOT_OWNED");
        }
        validateWallet(source, quote.sourceCurrency());
        validateWallet(target, quote.targetCurrency());

        if (quote.sourceCurrency().equals(quote.targetCurrency())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "QUOTE_CURRENCIES_INVALID"
            );
        }

        // Check expiry AFTER acquiring all locks.
        OffsetDateTime now =
                settlementRepository.currentDatabaseTime();

        if (!now.isBefore(quote.expiresAt())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "QUOTE_EXPIRED"
            );
        }

        if (quote.sourceAmount().signum() <= 0
                || quote.targetAmount().signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "QUOTE_AMOUNTS_INVALID"
            );
        }

        if (source.balance().compareTo(quote.sourceAmount()) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "INSUFFICIENT_FUNDS"
            );
        }

        if (target.balance().add(quote.targetAmount()).compareTo(new java.math.BigDecimal("999999999999999.9999")) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BALANCE_LIMIT");
        }

        String settlementId = UUID.randomUUID().toString();
//        String correlationId = UUID.randomUUID().toString();

        requireOneRow(
                walletRepository.debit(
                        source.walletId(),
                        quote.sourceAmount()
                ),
                "Source wallet debit"
        );

        requireOneRow(
                walletRepository.credit(
                        target.walletId(),
                        quote.targetAmount()
                ),
                "Target wallet credit"
        );

        // Insert settlement before ledger because of its foreign key.
        requireOneRow(
                settlementRepository.insertCompleted(
                        settlementId,
                        quote,
                        correlationId
                ),
                "Settlement insert"
        );

        requireOneRow(
                ledgerRepository.insertEntry(
                        settlementId,
                        source.walletId(),
                        "DEBIT",
                        source.currencyCode(),
                        quote.sourceAmount(),
                        source.balance(),
                        source.balance().subtract(quote.sourceAmount())
                ),
                "Debit ledger insert"
        );

        requireOneRow(
                ledgerRepository.insertEntry(
                        settlementId,
                        target.walletId(),
                        "CREDIT",
                        target.currencyCode(),
                        quote.targetAmount(),
                        target.balance(),
                        target.balance().add(quote.targetAmount())
                ),
                "Credit ledger insert"
        );

        requireOneRow(
                quoteRepository.markSettled(quote.quoteId()),
                "Quote status update"
        );

        SettlementResponse response = new SettlementResponse(
                settlementId,
                quote.quoteId(),
                "COMPLETED",
                quote.sourceCurrency(),
                quote.sourceAmount(),
                quote.targetCurrency(),
                quote.targetAmount()
        );

        requireOneRow(
                auditRepository.insertSettlementCompleted(
                        response,
                        customerId,
                        correlationId
                ),
                "Success audit insert"
        );

        requireOneRow(
                outboxRepository.insertSettlementCompleted(
                        response,
                        customerId,
                        correlationId
                ),
                "Completion outbox insert"
        );

        idempotencyRepository.complete(idempotencyKey, response);

        return response;
    }

    private LockedWallet lockWallet(Long walletId) {
        return walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "QUOTE_WALLET_NOT_FOUND"
                ));
    }

    private void validateWallet(
            LockedWallet wallet,
            String expectedCurrency
    ) {
        if (!walletRepository.activeAccount(wallet.customerId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "ACCOUNT_NOT_ACTIVE"
            );
        }

        if (!"ACTIVE".equals(wallet.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "WALLET_NOT_ACTIVE"
            );
        }

        if (!expectedCurrency.equals(wallet.currencyCode())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "WALLET_CURRENCY_MISMATCH"
            );
        }
    }

    private void requireOneRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException(
                    operation + " must affect exactly one row"
            );
        }
    }
}
