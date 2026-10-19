package com.fxlock.quote;

import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fxlock.quote.Models.*;

@SpringBootTest
@ActiveProfiles({"demo","test"})
class QuoteServiceTest {
    static final Instant NOW=Instant.parse("2026-10-19T10:00:00Z");
    @Autowired QuoteService service;
    @Autowired Pricing pricing;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoBean RateGateway rates;
    @MockitoBean SettlementGateway settlements;
    @MockitoSpyBean QuoteRepository repository;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM OUTBOX_EVENT"); jdbc.update("DELETE FROM AUDIT_EVENT");
        jdbc.update("DELETE FROM QUOTE_CONFIDENCE"); jdbc.update("DELETE FROM FX_QUOTE");
        jdbc.update("UPDATE FX_CONFIG SET QUOTING_ENABLED='Y',BUSINESS_SPREAD_BPS=20,QUOTE_VALIDITY_SECONDS=60,MIN_TRANSACTION_AMOUNT=10,MAX_TRANSACTION_AMOUNT=100000");
        when(clock.instant()).thenReturn(NOW);
        when(rates.trustedRate()).thenReturn(rate(NOW.minusSeconds(5),"1.1700",true,true,false,true,"0.0002"));
    }
    static TrustedRate rate(Instant observed,String value,boolean trusted,boolean enabled,boolean anomaly,boolean healthy,String movement) {
        return new TrustedRate("1","EUR","USD",new BigDecimal(value),observed,trusted,enabled,anomaly,healthy,new BigDecimal(movement));
    }
    static CreateQuoteRequest request(String amount) {
        return new CreateQuoteRequest("EUR","USD",new BigDecimal(amount),"1","2");
    }
    Quote create() { return service.create(request("1000.00"),"customer-demo"); }
    void fails(String code,Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(QuoteException.class,e -> assertThat(e.code()).isEqualTo(code));
    }
    @Test void storesExactPricingAndSixtySecondLock() {
        Quote q=create(); Quote stored=service.get(q.quoteId(),"customer-demo");
        assertThat(stored.offeredRate()).isEqualByComparingTo("1.1676600000");
        assertThat(stored.targetAmount()).isEqualByComparingTo("1167.66");
        assertThat(stored.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(stored.snapshotId()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT",Integer.class)).isEqualTo(1);
    }
    @Test void marketChangeNeverRepricesStoredOffer() {
        Quote q=create(); when(rates.trustedRate()).thenReturn(rate(NOW,"1.5",true,true,false,true,"0"));
        assertThat(service.get(q.quoteId(),"customer-demo").targetAmount()).isEqualByComparingTo("1167.66");
        verify(rates,times(1)).trustedRate();
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","9.99","100000.01"})
    void rejectsAmountsOutsideLimits(String value) { fails("AMOUNT_OUT_OF_RANGE",() -> service.create(request(value),"customer-demo")); }
    @Test void rejectsFractionalCents() { fails("INVALID_AMOUNT",() -> service.create(request("10.001"),"customer-demo")); }
    @Test void allowsBothLimitBoundaries() {
        assertThat(service.create(request("10"),"customer-demo").sourceAmount()).isEqualByComparingTo("10");
        assertThat(service.create(request("100000"),"customer-demo").sourceAmount()).isEqualByComparingTo("100000");
    }
    @Test void rejectsUnsupportedDirection() {
        fails("UNSUPPORTED_PAIR",() -> service.create(new CreateQuoteRequest("USD","EUR",new BigDecimal("100"),"2","1"),"customer-demo"));
    }
    @Test void validatesSourceOwnershipBeforeIssuingQuote() {
        fails("INVALID_WALLETS",() -> service.create(new CreateQuoteRequest("EUR","USD",new BigDecimal("100"),"3","2"),"customer-demo"));
    }
    @Test void rejectsRateAtNinetySeconds() {
        when(rates.trustedRate()).thenReturn(rate(NOW.minusSeconds(90),"1.17",true,true,false,true,"0"));
        fails("RATE_STALE",this::create);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FX_QUOTE",Integer.class)).isZero();
    }
    @Test void allowsRateImmediatelyBelowBoundary() {
        when(rates.trustedRate()).thenReturn(rate(NOW.minusMillis(89999),"1.17",true,true,false,true,"0"));
        assertThat(create().status()).isEqualTo("OPEN");
    }
    @Test void checksFreshnessAfterSlowRateCall() {
        when(rates.trustedRate()).thenAnswer(inv -> {
            when(clock.instant()).thenReturn(NOW.plusSeconds(3));
            return rate(NOW.minusSeconds(88),"1.17",true,true,false,true,"0");
        });
        fails("RATE_STALE",this::create);
    }
    @Test void rejectsFutureObservation() {
        when(rates.trustedRate()).thenReturn(rate(NOW.plusSeconds(1),"1.17",true,true,false,true,"0")); fails("RATE_UNAVAILABLE",this::create);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","0.1234567890123"})
    void rejectsInvalidRateValues(String value) {
        when(rates.trustedRate()).thenReturn(rate(NOW,value,true,true,false,true,"0")); fails("RATE_UNAVAILABLE",this::create);
    }
    @Test void hardPauseOverridesExcellentConfidence() {
        when(rates.trustedRate()).thenReturn(rate(NOW,"1.17",true,false,false,true,"0")); fails("RATE_UNAVAILABLE",this::create);
    }
    @Test void untrustedRateCannotCreateQuote() {
        when(rates.trustedRate()).thenReturn(rate(NOW,"1.17",false,true,false,true,"0")); fails("RATE_UNAVAILABLE",this::create);
    }
    @Test void freshTrustedCacheCanBeUsedDuringProviderOutage() {
        when(rates.trustedRate()).thenReturn(rate(NOW.minusSeconds(5),"1.17",true,true,false,false,"0"));
        assertThat(create().confidence().score()).isLessThan(new BigDecimal("80"));
    }
    @Test void newerAnomalyLowersScoreButDoesNotInvalidatePreviousTrustedRate() {
        when(rates.trustedRate()).thenReturn(rate(NOW.minusSeconds(5),"1.17",true,true,true,true,"0"));
        assertThat(create().confidence().score()).isLessThan(new BigDecimal("80"));
    }
    @Test void confidenceWeightsHaveKnownResult() {
        var score=pricing.confidence(rate(NOW.minusSeconds(45),"1.17",true,true,false,true,"0.0025"),NOW);
        assertThat(score.score()).isEqualByComparingTo("70"); assertThat(score.label()).isEqualTo("MEDIUM");
    }
    @Test void halfEvenRoundingIsExplicit() {
        assertThat(pricing.targetAmount(new BigDecimal("10"),new BigDecimal("1.0005"))).isEqualByComparingTo("10.00");
        assertThat(pricing.targetAmount(new BigDecimal("10"),new BigDecimal("1.0015"))).isEqualByComparingTo("10.02");
    }
    @Test void quoteExpiresExactlyAtSixtySeconds() {
        Quote q=create(); when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(service.get(q.quoteId(),"customer-demo").status()).isEqualTo("EXPIRED");
    }
    @Test void cannotReadOrAcceptAnotherCustomersQuote() {
        Quote q=create(); fails("QUOTE_NOT_FOUND",() -> service.get(q.quoteId(),"customer-other"));
        fails("QUOTE_NOT_FOUND",() -> service.accept(q.quoteId(),"customer-other","same-key-123")); verifyNoInteractions(settlements);
    }
    @Test void cancellationIsPersistentAndRepeatable() {
        Quote q=create(); service.cancel(q.quoteId(),"customer-demo"); service.cancel(q.quoteId(),"customer-demo");
        assertThat(service.get(q.quoteId(),"customer-demo").status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT WHERE EVENT_TYPE='QUOTE_CANCELLED'",Integer.class)).isEqualTo(1);
    }
    @Test void cancellationAfterExpiryIsRejected() {
        Quote q=create(); when(clock.instant()).thenReturn(NOW.plusSeconds(60)); fails("QUOTE_EXPIRED",() -> service.cancel(q.quoteId(),"customer-demo"));
    }
    @Test void quoteAndCreationAuditRollBackTogether() {
        doThrow(new IllegalStateException("injected audit failure")).when(repository).audit(any(),eq("QUOTE_CREATED"),any());
        assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FX_QUOTE",Integer.class)).isZero();
    }
    @Test void forwardsCommittedRetryAfterQuoteExpiryWithoutFetchingNewRate() {
        Quote q=create(); when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        var committed=new SettlementResult("settlement-1",q.quoteId(),"COMPLETED",q.sourceAmount(),q.targetAmount(),NOW.plusSeconds(20));
        when(settlements.settle(q.quoteId(),"customer-demo","retry-key-123")).thenReturn(committed);
        assertThat(service.accept(q.quoteId(),"customer-demo","retry-key-123")).isEqualTo(committed);
        verify(rates,times(1)).trustedRate();
        // M2 must not independently change the shared quote status: M3 owns that transaction.
        assertThat(jdbc.queryForObject("SELECT STATUS FROM FX_QUOTE WHERE QUOTE_ID=?",String.class,q.quoteId())).isEqualTo("OPEN");
    }
    @Test void responseLossRetryPreservesTheSameIdempotencyKey() {
        Quote q=create(); var done=new SettlementResult("settlement-1",q.quoteId(),"COMPLETED",q.sourceAmount(),q.targetAmount(),NOW);
        when(settlements.settle(q.quoteId(),"customer-demo","retry-key-123"))
            .thenThrow(new QuoteException(503,"SETTLEMENT_UNCERTAIN","response lost")).thenReturn(done);
        fails("SETTLEMENT_UNCERTAIN",() -> service.accept(q.quoteId(),"customer-demo","retry-key-123"));
        assertThat(service.accept(q.quoteId(),"customer-demo","retry-key-123")).isEqualTo(done);
        verify(settlements,times(2)).settle(q.quoteId(),"customer-demo","retry-key-123");
    }
    @Test void insufficientFundsIsReturnedWithoutChangingQuote() {
        Quote q=create(); when(settlements.settle(anyString(),anyString(),anyString())).thenThrow(new QuoteException(422,"INSUFFICIENT_FUNDS","Not enough money"));
        fails("INSUFFICIENT_FUNDS",() -> service.accept(q.quoteId(),"customer-demo","retry-key-123"));
        assertThat(service.get(q.quoteId(),"customer-demo").status()).isEqualTo("OPEN");
    }
    @Test void cannotDisplaySuccessForMismatchedSettlement() {
        Quote q=create(); when(settlements.settle(anyString(),anyString(),anyString())).thenReturn(new SettlementResult("s",q.quoteId(),"COMPLETED",q.sourceAmount(),BigDecimal.ONE,NOW));
        fails("SETTLEMENT_RESPONSE_INVALID",() -> service.accept(q.quoteId(),"customer-demo","retry-key-123"));
    }
    @Test void missingRetryKeyNeverCallsM3() {
        Quote q=create(); fails("INVALID_IDEMPOTENCY_KEY",() -> service.accept(q.quoteId(),"customer-demo","")); verifyNoInteractions(settlements);
    }
}
