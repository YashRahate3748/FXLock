package com.fxlock.quote;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.client.*;
import static com.fxlock.quote.Models.*;

@Configuration
public class UpstreamConfiguration {
    @Bean WalletGateway wallets(QuoteRepository repository) { return repository::wallets; }
    @Bean @Profile("demo") RateGateway demoRates(Clock clock,JdbcTemplate jdbc) {
        return () -> {
            Instant observed=clock.instant().minusSeconds(5);
            var key=new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var ps=connection.prepareStatement("""
                    INSERT INTO MARKET_RATE_SNAPSHOT (CURRENCY_PAIR,PROVIDER_NAME,MARKET_RATE,PROVIDER_TIMESTAMP,
                    RECEIVED_AT,VALIDATION_STATUS,TRUSTED_FLAG,MOVEMENT_PERCENT,VALIDATION_REASON)
                    VALUES ('EUR/USD','M2_TEST_FIXTURE',1.17,?,?,'TRUSTED','Y',0.02,'H2 demo fixture only')
                    """,new String[]{"RATE_SNAPSHOT_ID"});
                ps.setObject(1,observed.atOffset(ZoneOffset.UTC)); ps.setObject(2,clock.instant().atOffset(ZoneOffset.UTC)); return ps;
            },key);
            return new TrustedRate(Long.toString(key.getKey().longValue()),"EUR","USD",new BigDecimal("1.1700"),observed,true,true,false,true,new BigDecimal("0.0002"));
        };
    }
    @Bean @Profile("oracle-local") RateGateway databaseRates(SharedRateReader reader) { return reader::latest; }
    @Bean @Profile("demo") SettlementGateway unavailableSettlement() {
        return (id,customer,key) -> { throw new QuoteException(503,"SETTLEMENT_UNAVAILABLE","M3 is not connected in this mode. No wallets have been changed."); };
    }
    record M1Response(Long snapshotId,String currencyPair,BigDecimal marketRate,OffsetDateTime receivedAt,
        long ageSeconds,boolean fresh,boolean quotingEnabled,String providerHealth,BigDecimal movementPercent,
        BigDecimal freshnessScore,BigDecimal anomalyScore,BigDecimal providerHealthScore,BigDecimal stabilityScore) {}
    @Bean @Profile("team") RateGateway teamRates(SharedRateReader reader,QuotePolicy policy,
        @Value("${fxlock.rate-username:fxlock-service}") String username,
        @Value("${fxlock.rate-password}") String password) {
        return () -> {
            try {
                var r=client(requiredUrl(policy.rateBaseUrl(),"RATE_BASE_URL"))
                    .defaultHeaders(h -> h.setBasicAuth(username,password)).build()
                    .get().uri("/api/v1/rates/trusted").retrieve().body(M1Response.class);
                if(r==null || r.snapshotId()==null || !r.fresh() || !r.quotingEnabled() || !"EUR/USD".equals(r.currencyPair()))
                    throw new QuoteException(503,"RATE_UNAVAILABLE","M1 did not supply an enabled, fresh EUR/USD snapshot.");
                var stored=reader.byId(r.snapshotId().toString());
                if(r.marketRate()==null || r.marketRate().compareTo(stored.rate())!=0)
                    throw new QuoteException(503,"RATE_UNAVAILABLE","M1 rate does not match the common Oracle snapshot.");
                return new TrustedRate(stored.snapshotId(),"EUR","USD",stored.rate(),stored.observedAt(),stored.trusted(),
                    r.quotingEnabled() && stored.quotingEnabled(),stored.unresolvedAnomaly(),"UP".equals(r.providerHealth()),stored.relativeMovement(),
                    new ConfidenceInputs(r.freshnessScore(),r.anomalyScore(),r.providerHealthScore(),r.stabilityScore()));
            } catch(RestClientException e) { throw new QuoteException(503,"RATE_UNAVAILABLE","M1 could not supply a trusted rate."); }
        };
    }
    record M3Response(String settlementId,String quoteId,String status,String sourceCurrency,
        BigDecimal sourceAmount,String targetCurrency,BigDecimal targetAmount) {}
    @Bean @Profile({"team", "oracle-local"}) SettlementGateway teamSettlement(QuoteRepository repository,QuotePolicy policy,ObjectMapper json) {
        return (id,customer,key) -> {
            // Current teammate controller simulates CUST001; refuse all other identities until M3 has authenticated identity support.
            if(repository.customerId(customer)!=repository.customerId("CUST001"))
                throw new QuoteException(503,"M3_IDENTITY_UNSUPPORTED","The current M3 local controller only supports CUST001.");
            try {
                var result=client(requiredUrl(policy.settlementBaseUrl(),"SETTLEMENT_BASE_URL")).build()
                    .post().uri("/api/v1/settlements").header("Idempotency-Key",key)
                    .body(Map.of("quoteId",id)).retrieve().body(M3Response.class);
                if(result==null || !id.equals(result.quoteId()) || result.settlementId()==null || !"COMPLETED".equals(result.status())
                    || !"EUR".equals(result.sourceCurrency()) || !"USD".equals(result.targetCurrency()))
                    throw new QuoteException(502,"SETTLEMENT_RESPONSE_INVALID","M3 returned an invalid settlement response.");
                return new SettlementResult(result.settlementId(),id,result.status(),result.sourceAmount(),result.targetAmount(),
                    repository.settlementCompletedAt(result.settlementId(),id,customer));
            } catch(RestClientResponseException e) {
                String code="SETTLEMENT_UNAVAILABLE";
                try { String parsed=json.readTree(e.getResponseBodyAsByteArray()).path("code").asText(); if(!parsed.isBlank()) code=parsed; } catch(Exception ignored) { }
                throw new QuoteException(e.getStatusCode().is4xxClientError()?e.getStatusCode().value():503,code,
                    "Settlement was not confirmed. For an uncertain result, retry with the same key or check history.");
            } catch(RestClientException e) { throw new QuoteException(503,"SETTLEMENT_UNCERTAIN","M3 may have committed. Retry with the same key."); }
        };
    }
    private static String requiredUrl(String value,String setting) {
        if(value==null || value.isBlank()) throw new QuoteException(503,"SERVICE_UNAVAILABLE","Configure "+setting+" for the actual teammate service.");
        return value;
    }
    private static RestClient.Builder client(String base) {
        URI uri=URI.create(base);
        if(!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw new IllegalArgumentException("Service URL must use HTTP or HTTPS");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        return RestClient.builder().baseUrl(base).requestFactory(factory);
    }
}
