package com.fxlock.history;

import com.fxlock.account.DashboardAccounts;
import com.fxlock.dashboard.*;
import com.fxlock.exception.GlobalExceptionHandler;
import com.fxlock.integration.*;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={DashboardHistoryController.class,IntegrationHistoryController.class,DashboardBrowserController.class},
        properties={"eureka.client.enabled=false","fxlock.internal-token=fixture-token"})
@ActiveProfiles("integration")
@Import({DashboardSecurity.class,InternalCustomerResolver.class,GlobalExceptionHandler.class})
class HistoryApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean DashboardAccounts accounts;
    @MockitoBean LedgerHistoryRepository history;
    @MockitoBean JdbcTemplate jdbc;
    MockHttpSession session;

    @BeforeEach void fixtures() {
        session=new MockHttpSession();
        session.setAttribute(DashboardSecurity.CUSTOMER,"real-customer");
        when(accounts.active("real-customer",false)).thenReturn(new DashboardAccounts.Account(41L,"real-customer","Own Name","own@example.test","ACTIVE",OffsetDateTime.now(),"ACTIVE"));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any(),eq("real-customer"))).thenReturn(List.of("real-customer"));
        when(history.history(eq(41L),nullable(String.class),anyInt(),anyInt())).thenReturn(new LedgerHistoryRepository.HistoryPage(List.of(),0,20,0,false));
        when(history.accounts(41L)).thenReturn(List.of(new LedgerHistoryRepository.Account("410","EUR","ACTIVE")));
    }

    @Test void dashboardHistoryIsAProtectedReadablePageAndApisRemainJson() throws Exception {
        mvc.perform(get("/dashboard/history/").session(session)).andExpect(status().isOk()).andExpect(forwardedUrl("/dashboard/index.html"));
        mvc.perform(get("/api/dashboard/history").session(session)).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.entries").isArray());
        mvc.perform(get("/dashboard/history/")).andExpect(redirectedUrl("/dashboard/login"));
        mvc.perform(get("/api/dashboard/history")).andExpect(status().isUnauthorized());
        mvc.perform(get("/dashboard/not-a-page").session(session)).andExpect(status().isNotFound());
    }

    @Test void directHistoryNavigationIsRestoredAfterTheExistingLoginHandoff() throws Exception {
        var result=mvc.perform(get("/dashboard/history/")).andExpect(redirectedUrl("/dashboard/login")).andReturn();
        assertThat(result.getRequest().getSession().getAttribute("dashboard.next")).isEqualTo("/dashboard/history/");
    }

    @Test void browserIdentityHeadersCannotChooseAnotherCustomersHistory() throws Exception {
        mvc.perform(get("/api/dashboard/history").session(session).header("X-Customer-Ref","other")
                .param("customerRef","other").param("walletId","410").param("page","2").param("size","50"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(accounts).active("real-customer",false);
        verify(history).history(41L,"410",2,50);
    }

    @Test void internalHistoryRequiresTheBackendTokenBeforeAnyDatabaseLookup() throws Exception {
        for (String path : new String[]{"/internal/transaction-history","/internal/transaction-history/accounts"}) {
            mvc.perform(get(path).header("X-Customer-Ref","real-customer")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("X-Customer-Ref","real-customer").header("X-Internal-Token","wrong"))
                .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(jdbc,history,accounts);
    }

    @Test void trustedM2IdentityGetsTheSameHistoryAndAccountContracts() throws Exception {
        mvc.perform(get("/internal/transaction-history").header("X-Internal-Token","fixture-token").header("X-Customer-Ref","real-customer"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.entries").isArray()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/internal/transaction-history/accounts").header("X-Internal-Token","fixture-token").header("X-Customer-Ref","real-customer"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].walletId").value("410"));
    }

    @Test void inactiveAccountAndWrongWalletFilterAreRejectedWithoutEmptySuccess() throws Exception {
        when(history.history(41L,"999",0,20)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND"));
        mvc.perform(get("/api/dashboard/history").session(session).param("walletId","999")).andExpect(status().isNotFound());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"ACCOUNT_NOT_ACTIVE")).when(accounts).active("real-customer",false);
        mvc.perform(get("/api/dashboard/history").session(session)).andExpect(status().isForbidden());
    }
    @Test void malformedPaginationIsABadRequestRatherThanASuccessfulEmptyLedger() throws Exception {
        mvc.perform(get("/api/dashboard/history").session(session).param("page","not-a-number"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.entries").doesNotExist());
        verifyNoInteractions(history);
    }
}
