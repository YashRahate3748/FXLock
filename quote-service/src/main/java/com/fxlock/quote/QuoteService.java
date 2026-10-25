package com.fxlock.quote;

import java.math.*;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.fxlock.quote.Models.*;

@Service
public class QuoteService {
    private final QuoteRepository repository;
    private final RateGateway rates;
    private final SettlementGateway settlements;
    private final DatabaseConfig config;
    private final Pricing pricing;
    private final Clock clock;
    private final WalletGateway wallets;
    public QuoteService(QuoteRepository repository,RateGateway rates,SettlementGateway settlements,DatabaseConfig config,Pricing pricing,Clock clock,WalletGateway wallets) {
        this.repository=repository; this.rates=rates; this.settlements=settlements; this.config=config; this.pricing=pricing; this.clock=clock;
        this.wallets=wallets;
    }
    @Transactional
    public Quote create(CreateQuoteRequest request,String customer) {
        boolean forward = "EUR".equals(request.sourceCurrency()) && "USD".equals(request.targetCurrency());
        boolean reverse = "USD".equals(request.sourceCurrency()) && "EUR".equals(request.targetCurrency());
        if (!forward && !reverse)
            throw new QuoteException(422,"UNSUPPORTED_PAIR","Choose EUR to USD or USD to EUR.");
        var settings=config.current();
        BigDecimal amount;
        try { amount=request.sourceAmount().setScale(2,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException e) { throw new QuoteException(422,"INVALID_AMOUNT","Use at most two decimal places."); }
        if (amount.compareTo(settings.minAmount())<0 || amount.compareTo(settings.maxAmount())>0)
            throw new QuoteException(422,"AMOUNT_OUT_OF_RANGE","Amount must be between "+settings.minAmount()+" and "+settings.maxAmount()+" "+request.sourceCurrency()+".");
        if (request.sourceWalletId().equals(request.targetWalletId()))
            throw new QuoteException(422,"INVALID_WALLETS","Choose different source and destination wallets.");
        var available=wallets.options(customer);
        if (available==null || available.stream().noneMatch(w -> request.sourceWalletId().equals(w.walletId()) && request.sourceCurrency().equals(w.currency()) && w.canDebit())
            || available.stream().noneMatch(w -> request.targetWalletId().equals(w.walletId()) && request.targetCurrency().equals(w.currency())))
            throw new QuoteException(422,"INVALID_WALLETS","Choose your own source wallet and an eligible destination in the other currency.");
        TrustedRate rate=rates.trustedRate();
        // Obtain time after the HTTP call. A slow upstream response must not hide rate ageing.
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        pricing.validate(rate,now,settings);
        // Validate M1's original EUR/USD observation before deriving a reverse-direction rate.
        BigDecimal market = forward ? rate.rate().setScale(10,RoundingMode.HALF_EVEN)
            : BigDecimal.ONE.divide(rate.rate(),10,RoundingMode.HALF_EVEN);
        if (market.signum() <= 0 || market.compareTo(new BigDecimal("10000000000")) >= 0)
            throw new QuoteException(503,"RATE_UNAVAILABLE","The directional rate cannot be represented by the quote schema.");
        BigDecimal offered=pricing.offeredRate(market,settings);
        Quote q=new Quote(UUID.randomUUID().toString(),Long.toString(repository.customerId(customer)),request.sourceWalletId(),request.targetWalletId(),request.sourceCurrency(),request.targetCurrency(),
            rate.snapshotId(),rate.observedAt(),market,offered,amount,
            pricing.targetAmount(amount,offered),settings.spreadBps(),pricing.confidence(rate,now,settings),now,now.plusSeconds(settings.quoteSeconds()),"OPEN");
        repository.insert(q); repository.audit(q,"QUOTE_CREATED",now); return q;
    }
    public Quote get(String id,String customer) {
        Quote q=owned(id,customer);
        return "OPEN".equals(q.status()) && !clock.instant().isBefore(q.expiresAt())?withStatus(q,"EXPIRED"):q;
    }
    @Transactional
    public Quote cancel(String id,String customer) {
        Quote q=repository.lockOwned(id,customer).orElseThrow(QuoteService::notFound);
        // Time is checked AFTER waiting for the same quote lock that M3 must acquire.
        Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if ("CANCELLED".equals(q.status())) return q;
        if (!"OPEN".equals(q.status())) throw new QuoteException(409,"QUOTE_NOT_OPEN","Only an open quote can be cancelled.");
        if (!now.isBefore(q.expiresAt())) throw new QuoteException(409,"QUOTE_EXPIRED","This quote has expired.");
        repository.cancel(id,now); repository.audit(q,"QUOTE_CANCELLED",now); return withStatus(q,"CANCELLED");
    }
    public SettlementResult accept(String id,String customer,String key) {
        Quote q=owned(id,customer);
        if (key==null || !key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new QuoteException(400,"INVALID_IDEMPOTENCY_KEY","Supply a retry key of 8–100 letters, digits, underscores or hyphens.");
        // M3 must return committed retry results even after expiry. Do not pre-reject here.
        SettlementResult result=settlements.settle(id,customer,key);
        if (result==null || !id.equals(result.quoteId()) || !"COMPLETED".equals(result.status()) || result.settlementId()==null || result.settlementId().isBlank() || result.completedAt()==null
            || result.sourceAmount()==null || result.targetAmount()==null
            || q.sourceAmount().compareTo(result.sourceAmount())!=0 || q.targetAmount().compareTo(result.targetAmount())!=0)
            throw new QuoteException(502,"SETTLEMENT_RESPONSE_INVALID","The result does not match the stored quote. Check M3 history and retry with the same key.");
        return result;
    }
    private Quote owned(String id,String customer) { return repository.owned(id,customer).orElseThrow(QuoteService::notFound); }
    private static QuoteException notFound() { return new QuoteException(404,"QUOTE_NOT_FOUND","Quote not found for this customer."); }
    private static Quote withStatus(Quote q,String status) {
        return new Quote(q.quoteId(),q.customerId(),q.sourceWalletId(),q.targetWalletId(),q.sourceCurrency(),q.targetCurrency(),q.snapshotId(),q.observedAt(),
            q.marketRate(),q.offeredRate(),q.sourceAmount(),q.targetAmount(),q.spreadBps(),q.confidence(),q.createdAt(),q.expiresAt(),status);
    }
}
