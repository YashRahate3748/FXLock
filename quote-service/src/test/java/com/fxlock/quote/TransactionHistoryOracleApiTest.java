package com.fxlock.quote;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfSystemProperty(named="runOracle",matches="true")
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("oracle-local") @Transactional
@WithMockUser(username="customer-demo")
class TransactionHistoryOracleApiTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    HistoryFixtures.Fixture mine,other;
    @BeforeEach void fixtures() { mine=HistoryFixtures.conversion(jdbc,"CUST001");other=HistoryFixtures.conversion(jdbc,"CUST002"); }
    @Test void readsTriggerGeneratedOracleHistoryForChosenAccount() throws Exception {
        mvc.perform(get("/api/transaction-history").param("walletId",Long.toString(mine.source())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.entries[0].customerName").value("Demo Customer One"))
            .andExpect(jsonPath("$.entries[0].amountDebited").value("1000"))
            .andExpect(jsonPath("$.entries[0].updatedBalance").value("4000"));
    }
    @Test void rejectsOtherCustomersAccountInOracle() throws Exception {
        mvc.perform(get("/api/transaction-history").param("walletId",Long.toString(other.source())))
            .andExpect(status().isNotFound());
    }
    @Test void paginationUsesOracleOffsetAndFetch() throws Exception {
        mvc.perform(get("/api/transaction-history").param("size","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(1)).andExpect(jsonPath("$.hasNext").value(true));
    }
}
