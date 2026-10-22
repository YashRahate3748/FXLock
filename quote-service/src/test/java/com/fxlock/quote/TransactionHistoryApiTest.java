package com.fxlock.quote;

import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles({"demo","test"})
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:historyapi;MODE=Oracle;DB_CLOSE_DELAY=-1")
@Transactional
class TransactionHistoryApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    HistoryFixtures.Fixture mine,other;
    @BeforeEach void fixtures() { mine=HistoryFixtures.conversion(jdbc,"CUST001");other=HistoryFixtures.conversion(jdbc,"CUST002"); }
    @Test void anonymousApiIs401AndPageRequiresLogin() throws Exception {
        mvc.perform(get("/api/transaction-history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/transaction-history/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/transactions").accept(org.springframework.http.MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
    }
    @Test @WithMockUser(username="customer-demo") void historyContainsOnlyAuthenticatedCustomersRows() throws Exception {
        var response=mvc.perform(get("/api/transaction-history").param("customerId","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.entries.length()").value(2)).andReturn();
        assertThat(response.getResponse().getContentAsString()).contains(mine.settlement()).doesNotContain(other.settlement());
    }
    @Test @WithMockUser(username="customer-demo") void accountFilterUsesHistoricalBalanceWithoutChangingWallet() throws Exception {
        BigDecimal before=jdbc.queryForObject("SELECT BALANCE FROM WALLET WHERE WALLET_ID=?",BigDecimal.class,mine.source());
        mvc.perform(get("/api/transaction-history").param("walletId",Long.toString(mine.source())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.entries[0].customerName").value("Demo Customer One"))
            .andExpect(jsonPath("$.entries[0].currency").value("EUR"))
            .andExpect(jsonPath("$.entries[0].amountDebited").value("1000"))
            .andExpect(jsonPath("$.entries[0].amountCredited").value("0"))
            .andExpect(jsonPath("$.entries[0].updatedBalance").value("4000"));
        assertThat(jdbc.queryForObject("SELECT BALANCE FROM WALLET WHERE WALLET_ID=?",BigDecimal.class,mine.source())).isEqualByComparingTo(before);
    }
    @Test @WithMockUser(username="customer-demo") void foreignAndMissingWalletsReturnSameNotFound() throws Exception {
        for(String wallet:new String[]{Long.toString(other.source()),"9999999"})
            mvc.perform(get("/api/transaction-history").param("walletId",wallet)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }
    @Test @WithMockUser(username="customer-other") void accountListAndHistoryFollowSecondLogin() throws Exception {
        mvc.perform(get("/api/transaction-history/accounts")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].walletId").value(Long.toString(other.source())));
        var response=mvc.perform(get("/api/transaction-history")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andReturn();
        assertThat(response.getResponse().getContentAsString()).contains(other.settlement()).doesNotContain(mine.settlement());
    }
    @Test @WithMockUser(username="customer-demo") void closedAccountsRemainAvailableForHistoricalEntries() throws Exception {
        jdbc.update("UPDATE WALLET SET STATUS='CLOSED' WHERE WALLET_ID=?",mine.source());
        mvc.perform(get("/api/transaction-history/accounts")).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("CLOSED"));
        mvc.perform(get("/api/transaction-history").param("walletId",Long.toString(mine.source())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }
    @Test @WithMockUser(username="customer-demo") void paginationHasStableLedgerTieBreaker() throws Exception {
        mvc.perform(get("/api/transaction-history").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.entries[0].currency").value("USD"));
        mvc.perform(get("/api/transaction-history").param("size","1").param("page","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(false))
            .andExpect(jsonPath("$.entries[0].currency").value("EUR"));
    }
    @Test @WithMockUser(username="customer-demo") void invalidQueryValuesAreRejected() throws Exception {
        for(String wallet:new String[]{"-1","not-an-id","1 OR 1=1"})
            mvc.perform(get("/api/transaction-history").param("walletId",wallet)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/transaction-history").param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/transaction-history").param("size","101")).andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(username="customer-demo") void emptyHistoryIsSuccessfulAndPageRenders() throws Exception {
        jdbc.update("DELETE FROM TRANSACTION_HISTORY WHERE SETTLEMENT_ID=?",mine.settlement());
        mvc.perform(get("/api/transaction-history")).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.entries.length()").value(0));
        mvc.perform(get("/transactions")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Balance after transaction")));
    }
    @Test @WithMockUser(username="customer-demo") void decimalValuesAreReturnedAsExactStrings() throws Exception {
        jdbc.update("UPDATE TRANSACTION_HISTORY SET UPDATED_BALANCE=123456789012345.1234 WHERE SETTLEMENT_ID=? AND CURRENCY_CODE='USD'",mine.settlement());
        mvc.perform(get("/api/transaction-history").param("walletId",Long.toString(mine.target())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].updatedBalance").value("123456789012345.1234"));
    }
}
