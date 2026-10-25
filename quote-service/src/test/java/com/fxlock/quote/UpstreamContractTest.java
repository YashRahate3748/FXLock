package com.fxlock.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.fxlock.quote.Models.*;

class UpstreamContractTest {
    HttpServer server;
    int status;
    String body;
    final AtomicReference<String> path=new AtomicReference<>(),key=new AtomicReference<>(),auth=new AtomicReference<>(),requestBody=new AtomicReference<>(),internalToken=new AtomicReference<>(),customerRef=new AtomicReference<>();
    QuotePolicy policy;
    SharedRateReader reader;
    QuoteRepository repository;
    final UpstreamConfiguration config=new UpstreamConfiguration();
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @BeforeEach void setup() throws Exception {
        status=200; body="{}";
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            path.set(exchange.getRequestURI().toString()); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization")); requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            internalToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            customerRef.set(exchange.getRequestHeaders().getFirst("X-Customer-Ref"));
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length); try(var output=exchange.getResponseBody()) { output.write(bytes); }
        }); server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        policy=new QuotePolicy("learn-fxlock",url,url);
        reader=mock(SharedRateReader.class); repository=mock(QuoteRepository.class);
        when(reader.byId("1")).thenReturn(new TrustedRate("1","EUR","USD",new BigDecimal("1.17"),Instant.parse("2026-10-19T10:00:00Z"),true,true,false,true,BigDecimal.ZERO));
        when(repository.customerId(anyString())).thenReturn(1L);
        when(repository.owned(eq("q1"),anyString())).thenReturn(java.util.Optional.of(stored("EUR","USD","1000","1167.66")));
        when(repository.settlementCompletedAt(eq("s1"),eq("q1"),anyString())).thenReturn(Instant.parse("2026-10-19T10:00:20Z"));
    }
    @AfterEach void stop() { server.stop(0); }
    RateGateway rates() { return config.teamRates(reader,policy,"fxlock-service","test-password"); }
    SettlementGateway settlement() { return config.integratedSettlement(repository,policy,json,"test-token"); }
    Quote stored(String source,String target,String amount,String converted) {
        Instant now=Instant.parse("2026-10-19T10:00:00Z");
        return new Quote("q1","42","10","20",source,target,"1",now,new BigDecimal("1.17"),new BigDecimal("1.16766"),
            new BigDecimal(amount),new BigDecimal(converted),new BigDecimal("20"),null,now,now.plusSeconds(60),"OPEN");
    }
    String validRate() { return """
        {"snapshotId":1,"currencyPair":"EUR/USD","marketRate":1.17,"receivedAt":"2026-10-19T10:00:00Z","ageSeconds":5,"fresh":true,"quotingEnabled":true,"providerHealth":"UP","movementPercent":0,"freshnessScore":94.44,"anomalyScore":100,"providerHealthScore":100,"stabilityScore":100}
        """; }
    @Test void rateUsesActualM1EndpointAndBasicAuthentication() {
        body=validRate(); var r=rates().trustedRate(); assertThat(r.rate()).isEqualByComparingTo("1.17");
        assertThat(path.get()).isEqualTo("/api/v1/rates/trusted"); assertThat(auth.get()).startsWith("Basic ");
        assertThat(r.inputs().freshness()).isEqualByComparingTo("94.44"); verify(reader).byId("1");
    }
    @Test void providerHttpFailureBecomesSafeUnavailable() {
        status=503; assertThatThrownBy(() -> rates().trustedRate()).isInstanceOf(QuoteException.class);
    }
    @Test void malformedRateResponseDoesNotEscapeAsSuccess() {
        body="not-json"; assertThatThrownBy(() -> rates().trustedRate()).isInstanceOf(QuoteException.class);
    }
    @Test void snapshotMismatchCannotCreateOffer() {
        body=validRate().replace("\"marketRate\":1.17","\"marketRate\":1.18");
        assertThatThrownBy(() -> rates().trustedRate()).isInstanceOf(QuoteException.class);
    }
    @Test void pauseIsNotOverriddenByGoodConfidence() {
        body=validRate().replace("\"quotingEnabled\":true","\"quotingEnabled\":false");
        assertThatThrownBy(() -> rates().trustedRate()).isInstanceOf(QuoteException.class);
    }
    @Test void settlementUsesActualM3PayloadAndCommittedOracleTimestamp() {
        body="""
            {"settlementId":"s1","quoteId":"q1","status":"COMPLETED","sourceCurrency":"EUR","sourceAmount":1000,"targetCurrency":"USD","targetAmount":1167.66}
            """;
        var result=settlement().settle("q1","customer-demo","retry-key-123");
        assertThat(result.targetAmount()).isEqualByComparingTo("1167.66");
        assertThat(result.completedAt()).isEqualTo(Instant.parse("2026-10-19T10:00:20Z"));
        assertThat(path.get()).isEqualTo("/api/v1/settlements"); assertThat(requestBody.get()).isEqualTo("{\"quoteId\":\"q1\"}");
        assertThat(key.get()).isEqualTo("retry-key-123");
    }
    @Test void anotherCustomersQuoteCannotReachM3() {
        when(repository.owned("q1","another-real-customer")).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> settlement().settle("q1","another-real-customer","retry-key-123"))
            .isInstanceOfSatisfying(QuoteException.class,e -> assertThat(e.code()).isEqualTo("QUOTE_NOT_FOUND"));
        assertThat(path.get()).isNull();
    }
    @Test void teamSettlementForwardsAuthenticatedCustomerAndTokenWithoutLocalIdentityRestriction() {
        body="""
            {"settlementId":"s1","quoteId":"q1","status":"COMPLETED","sourceCurrency":"EUR","sourceAmount":1000,"targetCurrency":"USD","targetAmount":1167.66}
            """;
        when(repository.settlementCompletedAt("s1","q1","CUST-new-account")).thenReturn(Instant.parse("2026-10-19T10:00:20Z"));
        var gateway=config.integratedSettlement(repository,policy,json,"test-token");
        assertThat(gateway.settle("q1","CUST-new-account","retry-key-123").settlementId()).isEqualTo("s1");
        assertThat(internalToken.get()).isEqualTo("test-token");
        assertThat(customerRef.get()).isEqualTo("CUST-new-account");
        assertThat(key.get()).isEqualTo("retry-key-123");
        assertThat(requestBody.get()).isEqualTo("{\"quoteId\":\"q1\"}");
        verify(repository,never()).customerId(anyString());
    }
    @Test void teamSettlementRequiresInternalToken() {
        assertThatThrownBy(() -> config.integratedSettlement(repository,policy,json,""))
            .isInstanceOf(IllegalStateException.class);
        assertThat(path.get()).isNull();
    }
    @Test void serviceContractNeverTranslatesTrainingUsernamesIntoRealCustomerReferences() {
        body="""
            {"settlementId":"s1","quoteId":"q1","status":"COMPLETED","sourceCurrency":"EUR","sourceAmount":1000,"targetCurrency":"USD","targetAmount":1167.66}
            """;
        var gateway=config.integratedSettlement(repository,policy,json,"test-token");
        gateway.settle("q1","customer-demo","retry-key-123");
        assertThat(customerRef.get()).isEqualTo("customer-demo");
        gateway.settle("q1","customer-other","retry-key-456");
        assertThat(customerRef.get()).isEqualTo("customer-other");
    }
    @Test void reverseSettlementMustMatchTheOriginalQuoteCurrenciesAndAmounts() {
        when(repository.owned(eq("q1"),anyString())).thenReturn(java.util.Optional.of(stored("USD","EUR","100","85.30")));
        body="""
            {"settlementId":"s1","quoteId":"q1","status":"COMPLETED","sourceCurrency":"USD","sourceAmount":100,"targetCurrency":"EUR","targetAmount":85.30}
            """;
        assertThat(settlement().settle("q1","verified-real-customer","same-key-123").targetAmount()).isEqualByComparingTo("85.30");
        body=body.replace("85.30","85.31");
        assertThatThrownBy(() -> settlement().settle("q1","verified-real-customer","same-key-123"))
            .isInstanceOfSatisfying(QuoteException.class,e -> assertThat(e.code()).isEqualTo("SETTLEMENT_RESPONSE_INVALID"));
    }
    @Test void actualM3ErrorEnvelopeRetainsBusinessCode() {
        status=409; body="{\"timestamp\":\"now\",\"status\":409,\"code\":\"INSUFFICIENT_FUNDS\",\"message\":\"test\",\"path\":\"/api/v1/settlements\"}";
        assertThatThrownBy(() -> settlement().settle("q1","customer-demo","retry-key-123"))
            .isInstanceOfSatisfying(QuoteException.class,e -> { assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"); assertThat(e.status()).isEqualTo(409); });
    }
    @Test void malformedSettlementCannotBeConfirmed() {
        assertThatThrownBy(() -> settlement().settle("q1","customer-demo","retry-key-123"))
            .isInstanceOfSatisfying(QuoteException.class,e -> assertThat(e.code()).isEqualTo("SETTLEMENT_RESPONSE_INVALID"));
    }
}
