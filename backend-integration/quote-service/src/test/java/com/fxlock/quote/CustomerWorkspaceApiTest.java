package com.fxlock.quote;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles({"demo","test"})
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:customerworkspace;MODE=Oracle;DB_CLOSE_DELAY=-1")
@Transactional @WithMockUser(username="customer-demo")
class CustomerWorkspaceApiTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired RateGateway rates;
 HistoryFixtures.Fixture mine,other;
 @BeforeEach void fixtures(){mine=HistoryFixtures.conversion(jdbc,"CUST001");other=HistoryFixtures.conversion(jdbc,"CUST002");}
 @Test void overviewHasOwnedBalancesAndSettings() throws Exception {
  mvc.perform(get("/api/customer/overview")).andExpect(status().isOk())
   .andExpect(jsonPath("$.username").value("customer-demo"))
   .andExpect(jsonPath("$.customerName").value("Demo Customer One"))
   .andExpect(jsonPath("$.wallets.length()").value(2))
   .andExpect(jsonPath("$.wallets[0].walletId").value(Long.toString(mine.source())))
   .andExpect(jsonPath("$.wallets[0].balance").isString())
   .andExpect(jsonPath("$.settings.spreadBps").value("20"))
   .andExpect(jsonPath("$.rate.available").value(true));
 }
 @Test void ownReceiptHasExactStoredAmounts() throws Exception {
  mvc.perform(get("/api/customer/settlements/"+mine.settlement())).andExpect(status().isOk())
   .andExpect(jsonPath("$.SETTLEMENT_ID").value(mine.settlement()))
   .andExpect(jsonPath("$.TARGET_AMOUNT").value("1167.66"))
   .andExpect(jsonPath("$.STATUS").value("COMPLETED"));
 }
 @Test void receiptOwnershipIsEnforced() throws Exception {
  for(String id:new String[]{other.settlement(),"missing"})mvc.perform(get("/api/customer/settlements/"+id))
   .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
 }
 @Test @WithAnonymousUser void overviewRequiresLogin() throws Exception {
  mvc.perform(get("/api/customer/overview").servletPath("/api/customer/overview")).andExpect(status().isUnauthorized());
 }
 @Test void historyFiltersApplyBeforePagination() throws Exception {
  mvc.perform(get("/api/transaction-history").param("currency","EUR").param("search",mine.settlement())
    .param("start","2026-10-22T00:00:00Z").param("end","2026-10-23T00:00:00Z").param("size","1"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.entries[0].currency").value("EUR"));
  mvc.perform(get("/api/transaction-history").param("search",other.settlement())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
 }
 @Test void invalidHistoryFiltersAreRejected() throws Exception {
  mvc.perform(get("/api/transaction-history").param("currency","INR")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/transaction-history").param("start","2026-10-23T00:00:00Z").param("end","2026-10-22T00:00:00Z")).andExpect(status().isBadRequest());
 }
 @Test void repeatedDemoSnapshotsRemainPlainIntegerReferences() {
  for(int i=0;i<35;i++) {
   String id=rates.trustedRate().snapshotId();
   org.assertj.core.api.Assertions.assertThat(id).matches("[0-9]+");
   org.assertj.core.api.Assertions.assertThat(Long.parseLong(id)).isPositive();
  }
 }
}
