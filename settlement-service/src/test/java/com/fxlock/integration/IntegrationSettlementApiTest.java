package com.fxlock.integration;

import com.fxlock.account.InternalServiceAuthenticator;
import com.fxlock.exception.GlobalExceptionHandler;
import com.fxlock.settlement.*;
import com.fxlock.wallet.Wallet;
import com.fxlock.wallet.WalletService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {IntegrationSettlementController.class, IntegrationHealthController.class, IntegrationWalletController.class},
    properties = {"eureka.client.enabled=false", "fxlock.internal-token=test-token"})
@ActiveProfiles("integration")
@Import({GlobalExceptionHandler.class, InternalCustomerResolver.class, InternalServiceAuthenticator.class})
class IntegrationSettlementApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean JdbcTemplate jdbc;
    @MockitoBean SettlementCoordinator coordinator;
    @MockitoBean SettlementService settlements;
    @MockitoBean SettlementDetailsService details;
    @MockitoBean WalletService wallets;
    static final String QUOTE = "11111111-1111-4111-8111-111111111111";

    void activeCustomer() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(),
            eq("CUST-new-account"))).thenReturn(java.util.List.of("CUST-new-account"));
    }

    @Test void newCustomerIsPassedToUnchangedCoordinator() throws Exception {
        activeCustomer();
        when(coordinator.settle(QUOTE, "CUST-new-account", "test-key")).thenReturn(new SettlementResponse(
            "settlement", QUOTE, "COMPLETED", "EUR", BigDecimal.TEN, "USD", BigDecimal.ONE));
        mvc.perform(post("/api/v1/settlements").header("X-Internal-Token", "test-token")
                .header("X-Customer-Ref", "CUST-new-account").header("Idempotency-Key", "test-key")
                .contentType("application/json").content("{\"quoteId\":\"" + QUOTE + "\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.settlementId").value("settlement"));
        verify(coordinator).settle(QUOTE, "CUST-new-account", "test-key");
    }

    @Test void missingOrWrongTokenNeverTouchesDatabaseOrSettlements() throws Exception {
        for (String token : new String[]{"", "wrong-token"}) {
            mvc.perform(post("/api/v1/settlements").header("X-Internal-Token", token)
                    .header("X-Customer-Ref", "CUST001").header("Idempotency-Key", "key")
                    .contentType("application/json").content("{\"quoteId\":\"" + QUOTE + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_INTERNAL_TOKEN"));
        }
        verifyNoInteractions(jdbc, coordinator, settlements, details);
    }

    @Test void inactiveCustomerIsRejected() throws Exception {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(),
            eq("CUST-missing"))).thenReturn(java.util.List.of());
        mvc.perform(get("/api/v1/settlements/other/details").header("X-Internal-Token", "test-token")
                .header("X-Customer-Ref", "CUST-missing"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ACTIVE"));
        verifyNoInteractions(coordinator, settlements, details);
    }

    @Test void receiptOwnershipIsEnforcedByExistingService() throws Exception {
        activeCustomer();
        when(details.getDetails("other", "CUST-new-account"))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "SETTLEMENT_NOT_FOUND"));
        mvc.perform(get("/api/v1/settlements/other/details").header("X-Internal-Token", "test-token")
                .header("X-Customer-Ref", "CUST-new-account"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
        verify(details).getDetails("other", "CUST-new-account");
    }

    @Test void integrationHealthRequiresTokenAndChecksSchemaReadOnly() throws Exception {
        mvc.perform(get("/internal/health")).andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc);
        mvc.perform(get("/internal/health").header("X-Internal-Token", "test-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        verify(jdbc).queryForObject("SELECT 1 FROM dual", Integer.class);
        verify(jdbc).queryForObject("SELECT COUNT(login_password) FROM customer_account WHERE 1=0", Integer.class);
    }

    @Test void integrationWalletsUseTheResolvedCustomer() throws Exception {
        activeCustomer();
        when(wallets.getWallets("CUST-new-account")).thenReturn(java.util.List.of(
            new Wallet(12L, 6L, "EUR", new BigDecimal("123.45"))));
        mvc.perform(get("/api/v1/wallets").header("X-Internal-Token", "test-token")
                .header("X-Customer-Ref", "CUST-new-account"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].walletId").value(12))
            .andExpect(jsonPath("$[0].balance").value(123.45));
        verify(wallets).getWallets("CUST-new-account");
    }

    @Test void walletRequestsWithoutServiceAuthenticationNeverReadBalances() throws Exception {
        mvc.perform(get("/api/v1/wallets").header("X-Customer-Ref", "CUST001"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(wallets, jdbc);
    }

    @Test void inactiveWalletCustomerNeverReadsBalances() throws Exception {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<String>>any(),
            eq("CUST-missing"))).thenReturn(java.util.List.of());
        mvc.perform(get("/api/v1/wallets").header("X-Internal-Token", "test-token")
                .header("X-Customer-Ref", "CUST-missing"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(wallets);
    }
}
