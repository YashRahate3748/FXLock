package com.fxlock.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxlock.account.DashboardAccounts;
import com.fxlock.exception.GlobalExceptionHandler;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DashboardController.class, properties = "eureka.client.enabled=false")
@Import({DashboardSecurity.class, GlobalExceptionHandler.class})
class DashboardApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean DashboardAccounts accounts;
    @MockitoBean FundingService funding;
    @MockitoBean FundingRepository records;
    @MockitoBean JdbcTemplate jdbc;
    MockHttpSession session;
    FundingResult result = new FundingResult("committed", "DEPOSIT", "10", "EUR", "1.00", "0.0000", "1.0000", OffsetDateTime.now(), "original-key-123456", "correlation");

    @BeforeEach void setup() {
        session = new MockHttpSession();
        session.setAttribute(DashboardSecurity.CUSTOMER, "server-verified-customer");
        session.setAttribute(DashboardSecurity.CSRF, "fixture-csrf");
        when(accounts.active("server-verified-customer", false)).thenReturn(new DashboardAccounts.Account(1L,
                "server-verified-customer", "Customer Name", "customer@example.test", "ACTIVE", OffsetDateTime.now(), "ACTIVE"));
        when(funding.complete(eq("server-verified-customer"), anyString(), any())).thenReturn(result);
    }

    String body(String password) throws Exception { return json.writeValueAsString(new java.util.HashMap<>() {{
        put("operation", "DEPOSIT"); put("walletId", "10"); put("amount", "1.00"); put("password", password);
        put("customerRef", "attacker-supplied-ignored");
    }}); }

    @Test void unauthenticatedDataMutationAndOutcomeAreBlocked() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard/funding/key")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/dashboard/funding").contentType("application/json").content(body("x"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/dashboard/")).andExpect(redirectedUrl("/dashboard/login"));
        verifyNoInteractions(accounts, funding, records, jdbc);
    }

    @Test void csrfRejectsFundingAndLogoutBeforePasswordCheck() throws Exception {
        for (String path : new String[]{"/api/dashboard/funding", "/api/dashboard/logout"}) {
            mvc.perform(post(path).session(session).contentType("application/json").content(body("x"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(accounts, funding);
    }

    @Test void encodedAndMatrixParameterPathsCannotBypassCsrf() throws Exception {
        for (String path : new String[]{"/api/dashboard;ignored/funding", "/api/%64ashboard/funding"}) {
            mvc.perform(post(java.net.URI.create(path)).session(session).contentType("application/json").content(body(" x ")))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(accounts, funding);
    }

    @Test void dashboardUsesOwnedWalletsAndNeverReturnsCredentialsOrInternalCustomerId() throws Exception {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<DashboardController.WalletView>>any(), eq(1L)))
            .thenReturn(List.of(new DashboardController.WalletView("10", "EUR", "0.0000", "ACTIVE")));
        mvc.perform(get("/api/dashboard").session(session).param("customerRef", "other-customer"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.account.customerRef").value("server-verified-customer"))
            .andExpect(jsonPath("$.account.customerId").doesNotExist())
            .andExpect(jsonPath("$.account.password").doesNotExist())
            .andExpect(jsonPath("$.wallets[0].balance").value("0.0000"))
            .andExpect(jsonPath("$.csrfToken").value("fixture-csrf"));
        verify(jdbc).query(contains("WHERE customer_id = ?"), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<DashboardController.WalletView>>any(), eq(1L));
    }

    @Test void failedWalletReadIsAnErrorAndNeverFabricatesZeroBalances() throws Exception {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<DashboardController.WalletView>>any(), eq(1L)))
            .thenThrow(new IllegalStateException("fixture retrieval failure"));
        mvc.perform(get("/api/dashboard").session(session)).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.wallets").doesNotExist());
    }

    @Test void submissionAndReplayAlwaysConfirmTheExactPasswordForSessionOwner() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/dashboard/funding").session(session).header("X-CSRF-TOKEN", "fixture-csrf")
                    .header("Idempotency-Key", result.idempotencyKey()).contentType("application/json").content(body(" x ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transactionRef").value("committed"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(" x "))));
        }
        var order = inOrder(accounts, funding);
        for (int i = 0; i < 2; i++) {
            order.verify(accounts).confirm("server-verified-customer", " x ");
            order.verify(funding).complete(eq("server-verified-customer"), eq(result.idempotencyKey()), any());
        }
        assertThat(new FundingRequest("DEPOSIT", "10", "1", "private").toString()).doesNotContain("private");
        assertThat(json.writeValueAsString(new FundingRequest("DEPOSIT", "10", "1", "private"))).doesNotContain("private");
    }

    @Test void incorrectPasswordAndThrottlingCannotReachMutation() throws Exception {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS}) {
            doThrow(new ResponseStatusException(status, "CONFIRMATION_REJECTED")).when(accounts).confirm(anyString(), any());
            mvc.perform(post("/api/dashboard/funding").session(session).header("X-CSRF-TOKEN", "fixture-csrf")
                    .header("Idempotency-Key", result.idempotencyKey()).contentType("application/json").content(body("wrong")))
                .andExpect(status().is(status.value()));
        }
        verifyNoInteractions(funding);
    }

    @Test void outcomeLookupUsesOnlySessionOwnerAndDoesNotMutate() throws Exception {
        when(records.find("owned-key", 1)).thenReturn(Optional.of(result));
        when(records.find("other-key", 1)).thenReturn(Optional.empty());
        mvc.perform(get("/api/dashboard/funding/owned-key").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/funding/other-key").session(session)).andExpect(status().isNotFound());
        verifyNoInteractions(funding);
    }

    @Test void logoutInvalidatesM3AndRequiresFinishingM2Logout() throws Exception {
        mvc.perform(post("/api/dashboard/logout").session(session).header("X-CSRF-TOKEN", "fixture-csrf"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.logoutUrl").value("http://localhost:8082/dashboard/logout"));
        assertThat(session.isInvalid()).isTrue();
    }
}
