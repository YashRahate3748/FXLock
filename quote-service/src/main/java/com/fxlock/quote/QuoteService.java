package com.fxlock.quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.fxlock.quote.Models.*;

@Service
public class QuoteService {

    private static final String EUR = "EUR";
    private static final String USD = "USD";

    private final QuoteRepository repository;
    private final RateGateway rates;
    private final SettlementGateway settlements;
    private final DatabaseConfig config;
    private final Pricing pricing;
    private final Clock clock;
    private final WalletGateway wallets;

    public QuoteService(
            QuoteRepository repository,
            RateGateway rates,
            SettlementGateway settlements,
            DatabaseConfig config,
            Pricing pricing,
            Clock clock,
            WalletGateway wallets
    ) {
        this.repository = repository;
        this.rates = rates;
        this.settlements = settlements;
        this.config = config;
        this.pricing = pricing;
        this.clock = clock;
        this.wallets = wallets;
    }

    @Transactional
    public Quote create(
            CreateQuoteRequest request,
            String customer
    ) {

        validateCurrencyPair(request);

        var settings = config.current();

        BigDecimal amount;

        try {
            amount = request
                    .sourceAmount()
                    .setScale(
                            2,
                            RoundingMode.UNNECESSARY
                    );
        } catch (ArithmeticException e) {

            throw new QuoteException(
                    422,
                    "INVALID_AMOUNT",
                    "Use at most two decimal places."
            );
        }

        if (amount.compareTo(
                settings.minAmount()
        ) < 0
                || amount.compareTo(
                        settings.maxAmount()
                ) > 0) {

            throw new QuoteException(
                    422,
                    "AMOUNT_OUT_OF_RANGE",
                    "Amount must be between "
                            + settings.minAmount()
                            + " and "
                            + settings.maxAmount()
                            + " "
                            + request.sourceCurrency()
                            + "."
            );
        }

        if (request
                .sourceWalletId()
                .equals(
                        request.targetWalletId()
                )) {

            throw new QuoteException(
                    422,
                    "INVALID_WALLETS",
                    "Choose different source and destination wallets."
            );
        }

        /*
         * WalletGateway returns:
         *
         * - sender's own EUR/USD wallets with canDebit=true
         * - all active EUR/USD destination wallets
         *
         * Therefore:
         *
         * source must belong to logged-in sender
         * target may belong to sender or another customer
         */
        var available =
                wallets.options(customer);

        if (available == null) {

            throw new QuoteException(
                    422,
                    "INVALID_WALLETS",
                    "No eligible wallets are available."
            );
        }

        boolean validSource =
                available.stream()
                        .anyMatch(
                                w ->
                                        request
                                                .sourceWalletId()
                                                .equals(
                                                        w.walletId()
                                                )
                                        && request
                                                .sourceCurrency()
                                                .equals(
                                                        w.currency()
                                                )
                                        && w.canDebit()
                        );

        boolean validTarget =
                available.stream()
                        .anyMatch(
                                w ->
                                        request
                                                .targetWalletId()
                                                .equals(
                                                        w.walletId()
                                                )
                                        && request
                                                .targetCurrency()
                                                .equals(
                                                        w.currency()
                                                )
                        );

        if (!validSource
                || !validTarget) {

            throw new QuoteException(
                    422,
                    "INVALID_WALLETS",
                    "Choose an eligible source wallet and destination wallet."
            );
        }

        /*
         * M1 always supplies the trusted EUR/USD market rate.
         */
        TrustedRate rate =
                rates.trustedRate();

        /*
         * Obtain the time after the HTTP request.
         *
         * A slow M1 call must not artificially hide rate ageing.
         */
        Instant now =
                clock.instant()
                        .truncatedTo(
                                java.time.temporal.ChronoUnit.MICROS
                        );

        pricing.validate(
                rate,
                now,
                settings
        );

        /*
         * Convert the M1 EUR/USD market rate into the direction
         * requested by the customer.
         *
         * EUR -> USD:
         *
         *     use EUR/USD directly
         *
         * USD -> EUR:
         *
         *     use reciprocal:
         *
         *     1 / EURUSD
         */
        BigDecimal marketRate =
                directionalMarketRate(
                        request,
                        rate
                );

        /*
         * Apply existing business spread to whichever
         * direction was selected.
         */
        BigDecimal offeredRate =
                pricing.offeredRate(
                        marketRate,
                        settings
                );

        BigDecimal targetAmount =
                pricing.targetAmount(
                        amount,
                        offeredRate
                );

        Quote quote =
                new Quote(
                        UUID.randomUUID()
                                .toString(),

                        Long.toString(
                                repository.customerId(
                                        customer
                                )
                        ),

                        request.sourceWalletId(),

                        request.targetWalletId(),

                        request.sourceCurrency(),

                        request.targetCurrency(),

                        rate.snapshotId(),

                        rate.observedAt(),

                        marketRate.setScale(
                                10,
                                RoundingMode.HALF_EVEN
                        ),

                        offeredRate,

                        amount,

                        targetAmount,

                        settings.spreadBps(),

                        pricing.confidence(
                                rate,
                                now,
                                settings
                        ),

                        now,

                        now.plusSeconds(
                                settings.quoteSeconds()
                        ),

                        "OPEN"
                );

        repository.insert(quote);

        repository.audit(
                quote,
                "QUOTE_CREATED",
                now
        );

        return quote;
    }

    /*
     * Only the two supported directions are permitted.
     *
     * Same-currency movement is not an FX conversion.
     */
    private void validateCurrencyPair(
            CreateQuoteRequest request
    ) {

        boolean eurToUsd =
                EUR.equals(
                        request.sourceCurrency()
                )
                && USD.equals(
                        request.targetCurrency()
                );

        boolean usdToEur =
                USD.equals(
                        request.sourceCurrency()
                )
                && EUR.equals(
                        request.targetCurrency()
                );

        if (!eurToUsd
                && !usdToEur) {

            throw new QuoteException(
                    422,
                    "UNSUPPORTED_PAIR",
                    "Only EUR to USD and USD to EUR conversions are supported."
            );
        }
    }

    /*
     * M1's authoritative market observation remains EUR/USD.
     *
     * We do NOT request another provider rate for USD/EUR.
     *
     * Instead:
     *
     *     USD/EUR = 1 / EUR/USD
     */
    private BigDecimal directionalMarketRate(
            CreateQuoteRequest request,
            TrustedRate trustedRate
    ) {

        if (trustedRate.rate() == null
                || trustedRate.rate()
                        .signum() <= 0) {

            throw new QuoteException(
                    503,
                    "RATE_UNAVAILABLE",
                    "The trusted market rate is unavailable."
            );
        }

        /*
         * M1 is currently authoritative for EUR/USD.
         */
        if (trustedRate.sourceCurrency() != null
                && trustedRate.targetCurrency() != null
                && (!EUR.equals(
                        trustedRate.sourceCurrency()
                )
                || !USD.equals(
                        trustedRate.targetCurrency()
                ))) {

            throw new QuoteException(
                    502,
                    "RATE_PAIR_INVALID",
                    "Rate Service returned an unexpected currency pair."
            );
        }

        if (EUR.equals(
                request.sourceCurrency()
        )) {

            return trustedRate
                    .rate()
                    .setScale(
                            10,
                            RoundingMode.HALF_EVEN
                    );
        }

        return BigDecimal.ONE.divide(
                trustedRate.rate(),
                10,
                RoundingMode.HALF_EVEN
        );
    }

    public Quote get(
            String id,
            String customer
    ) {

        Quote q =
                owned(
                        id,
                        customer
                );

        return "OPEN".equals(
                q.status()
        )
                && !clock.instant()
                        .isBefore(
                                q.expiresAt()
                        )
                ? withStatus(
                        q,
                        "EXPIRED"
                )
                : q;
    }

    @Transactional
    public Quote cancel(
            String id,
            String customer
    ) {

        Quote q =
                repository
                        .lockOwned(
                                id,
                                customer
                        )
                        .orElseThrow(
                                QuoteService::notFound
                        );

        /*
         * Time is checked AFTER waiting for the same
         * quote lock M3 must acquire.
         */
        Instant now =
                clock.instant()
                        .truncatedTo(
                                java.time.temporal.ChronoUnit.MICROS
                        );

        if ("CANCELLED".equals(
                q.status()
        )) {
            return q;
        }

        if (!"OPEN".equals(
                q.status()
        )) {

            throw new QuoteException(
                    409,
                    "QUOTE_NOT_OPEN",
                    "Only an open quote can be cancelled."
            );
        }

        if (!now.isBefore(
                q.expiresAt()
        )) {

            throw new QuoteException(
                    409,
                    "QUOTE_EXPIRED",
                    "This quote has expired."
            );
        }

        repository.cancel(
                id,
                now
        );

        repository.audit(
                q,
                "QUOTE_CANCELLED",
                now
        );

        return withStatus(
                q,
                "CANCELLED"
        );
    }

    public SettlementResult accept(
            String id,
            String customer,
            String key
    ) {

        Quote q =
                owned(
                        id,
                        customer
                );

        if (key == null
                || !key.matches(
                        "[A-Za-z0-9_-]{8,100}"
                )) {

            throw new QuoteException(
                    400,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Supply a retry key of 8–100 letters, digits, underscores or hyphens."
            );
        }

        /*
         * M3 must return previously committed retry results
         * even after expiry.
         *
         * Therefore M2 must not pre-reject expiry here.
         */
        SettlementResult result =
                settlements.settle(
                        id,
                        customer,
                        key
                );

        if (result == null
                || !id.equals(
                        result.quoteId()
                )
                || !"COMPLETED".equals(
                        result.status()
                )
                || result.settlementId() == null
                || result.settlementId()
                        .isBlank()
                || result.completedAt() == null
                || result.sourceAmount() == null
                || result.targetAmount() == null
                || q.sourceAmount()
                        .compareTo(
                                result.sourceAmount()
                        ) != 0
                || q.targetAmount()
                        .compareTo(
                                result.targetAmount()
                        ) != 0) {

            throw new QuoteException(
                    502,
                    "SETTLEMENT_RESPONSE_INVALID",
                    "The result does not match the stored quote. Check M3 history and retry with the same key."
            );
        }

        return result;
    }

    private Quote owned(
            String id,
            String customer
    ) {

        return repository
                .owned(
                        id,
                        customer
                )
                .orElseThrow(
                        QuoteService::notFound
                );
    }

    private static QuoteException notFound() {

        return new QuoteException(
                404,
                "QUOTE_NOT_FOUND",
                "Quote not found for this customer."
        );
    }

    private static Quote withStatus(
            Quote q,
            String status
    ) {

        return new Quote(
                q.quoteId(),
                q.customerId(),
                q.sourceWalletId(),
                q.targetWalletId(),
                q.sourceCurrency(),
                q.targetCurrency(),
                q.snapshotId(),
                q.observedAt(),
                q.marketRate(),
                q.offeredRate(),
                q.sourceAmount(),
                q.targetAmount(),
                q.spreadBps(),
                q.confidence(),
                q.createdAt(),
                q.expiresAt(),
                status
        );
    }
}