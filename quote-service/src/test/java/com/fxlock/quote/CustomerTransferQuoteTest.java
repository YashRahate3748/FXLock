package com.fxlock.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static com.fxlock.quote.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"team","test"})
@TestPropertySource(properties={"spring.datasource.url=jdbc:h2:mem:real-customer-transfer;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
        "fxlock.internal-token=fixture-token","fxlock.account-service-url=http://127.0.0.1:1"})
class CustomerTransferQuoteTest {
    @Autowired QuoteService service;
    @Autowired QuoteRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean RateGateway rates;
    @MockitoBean SettlementGateway settlements;
    @MockitoBean AccountClient accounts;
    @MockitoBean Clock clock;
    static final Instant NOW=Instant.parse("2026-10-24T10:00:00Z");

    @BeforeEach void fixtures() throws Exception {
        try (var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS customer_account (customer_id NUMBER PRIMARY KEY, account_status VARCHAR2(20))");
        for (int id : new int[]{801,802,803}) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE customer_id=?",Integer.class,id)==0) {
                jdbc.update("INSERT INTO customer(customer_id,customer_ref,full_name,status) VALUES (?,?,?,'ACTIVE')",id,"real-fixture-"+id,"Fixture "+id);
                jdbc.update("INSERT INTO customer_account VALUES (?,'ACTIVE')",id);
                jdbc.update("INSERT INTO wallet(wallet_id,customer_id,currency_code,balance,status) VALUES (?,?,'EUR',1000,'ACTIVE')",id*10,id);
                jdbc.update("INSERT INTO wallet(wallet_id,customer_id,currency_code,balance,status) VALUES (?,?,'USD',1000,'ACTIVE')",id*10+1,id);
            }
        }
        jdbc.update("UPDATE customer SET status='ACTIVE' WHERE customer_id>=801");
        jdbc.update("UPDATE customer_account SET account_status='ACTIVE'");
        jdbc.update("UPDATE wallet SET status='ACTIVE' WHERE customer_id>=801");
        when(clock.instant()).thenReturn(NOW);
        when(rates.trustedRate()).thenReturn(QuoteServiceTest.rate(NOW.minusSeconds(5),"1.17",true,true,false,true,"0"));
    }

    CreateQuoteRequest request(String source,String target,String from,String to) {
        return new CreateQuoteRequest(source,target,new BigDecimal("100"),from,to);
    }

    @Test void registeredLoginAndExistingQuoteRouteUseTheVerifiedAccount() throws Exception {
        when(accounts.verify("sender@example.test"," x ")).thenReturn(new AccountClient.Account("real-fixture-801","Fixture 801","sender@example.test"));
        var login=mvc.perform(post("/login").with(csrf()).param("username","sender@example.test").param("password"," x "))
            .andExpect(redirectedUrl("/dashboard/start")).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);
        mvc.perform(get("/").session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"direction\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Transaction history")));
        mvc.perform(get("/wallets/quote-options").session(session).header("X-Customer-Ref","real-fixture-802"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].walletId").value("8010"))
            .andExpect(jsonPath("$[0].canDebit").value(true));
    }

    @Test void bothDirectionsSupportOwnWalletsAndOtherEligibleCustomers() {
        assertThat(service.create(request("EUR","USD","8010","8011"),"real-fixture-801").targetAmount()).isEqualByComparingTo("116.77");
        var sent=service.create(request("USD","EUR","8011","8020"),"real-fixture-801");
        assertThat(sent.targetAmount()).isEqualByComparingTo("85.30");
        assertThat(sent.marketRate()).isEqualByComparingTo("0.8547008547");
        assertThat(sent.customerId()).isEqualTo("801");
        assertThatThrownBy(() -> service.get(sent.quoteId(),"real-fixture-802")).hasMessageContaining("Quote not found");
        var options=repository.wallets("real-fixture-801");
        assertThat(options.stream().filter(WalletOption::canDebit).map(WalletOption::walletId)).containsExactly("8010","8011");
        assertThat(options.stream().filter(w -> w.walletId().equals("8020")).findFirst().orElseThrow().label())
            .contains("Fixture 802","real-fixture-802","EUR").doesNotContain("@");
    }

    @Test void sourceOwnershipAndCurrencyAreCheckedBeforeQuoteCreation() {
        for (var request : new CreateQuoteRequest[]{request("EUR","USD","8020","8011"),request("USD","EUR","8010","8020"),request("EUR","USD","8010","8020")}) {
            assertThatThrownBy(() -> service.create(request,"real-fixture-801")).isInstanceOfSatisfying(QuoteException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_WALLETS"));
        }
        verifyNoInteractions(rates);
    }

    @Test void inactiveCustomerAccountOrWalletCannotBeABeneficiary() {
        jdbc.update("UPDATE customer_account SET account_status='DISABLED' WHERE customer_id=802");
        assertThatThrownBy(() -> service.create(request("EUR","USD","8010","8021"),"real-fixture-801")).isInstanceOf(QuoteException.class);
        jdbc.update("UPDATE customer_account SET account_status='ACTIVE' WHERE customer_id=802");
        jdbc.update("UPDATE customer SET status='INACTIVE' WHERE customer_id=802");
        assertThatThrownBy(() -> service.create(request("EUR","USD","8010","8021"),"real-fixture-801")).isInstanceOf(QuoteException.class);
        jdbc.update("UPDATE customer SET status='ACTIVE' WHERE customer_id=802");
        jdbc.update("UPDATE wallet SET status='CLOSED' WHERE wallet_id=8021");
        assertThatThrownBy(() -> service.create(request("EUR","USD","8010","8021"),"real-fixture-801")).isInstanceOf(QuoteException.class);
        assertThatThrownBy(() -> repository.customerId("customer-demo")).isInstanceOf(QuoteException.class);
    }

    @Test void reverseDirectionKeepsTheTrustedRateAgeAndPairSafeguards() {
        when(rates.trustedRate()).thenReturn(QuoteServiceTest.rate(NOW.minusSeconds(90),"1.17",true,true,false,true,"0"));
        assertThatThrownBy(() -> service.create(request("USD","EUR","8011","8020"),"real-fixture-801"))
            .isInstanceOfSatisfying(QuoteException.class,error -> assertThat(error.code()).isEqualTo("RATE_STALE"));
    }
    @Test void reciprocalOverflowIsRejectedBeforeAnUnrepresentableRateCanBeStored() {
        when(rates.trustedRate()).thenReturn(QuoteServiceTest.rate(NOW.minusSeconds(1),"0.0000000001",true,true,false,true,"0"));
        assertThatThrownBy(() -> service.create(request("USD","EUR","8011","8020"),"real-fixture-801"))
            .isInstanceOfSatisfying(QuoteException.class,error -> assertThat(error.code()).isEqualTo("RATE_UNAVAILABLE"));
    }
}
