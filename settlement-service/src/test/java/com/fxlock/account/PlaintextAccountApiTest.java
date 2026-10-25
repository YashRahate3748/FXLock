package com.fxlock.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fxlock.exception.GlobalExceptionHandler;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {RegistrationController.class, LoginVerificationController.class},
        properties = {"eureka.client.enabled=false", "fxlock.internal-token=fixture-token"})
@ActiveProfiles({"integration", "plaintext-account-demo"})
@Import({AccountConfiguration.class, RegistrationService.class, LoginVerificationService.class,
        InternalServiceAuthenticator.class, GlobalExceptionHandler.class})
@ExtendWith(OutputCaptureExtension.class)
class PlaintextAccountApiTest {
    static final String PASSWORD = "  DisposableApiPassword!2026  ";
    String saved = PASSWORD;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JdbcTemplate jdbc;

    @BeforeEach void fixtures() throws Exception {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq("throwaway@example.com")))
                .thenAnswer(call -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("customer_ref")).thenReturn("CUST-fixture");
                    when(row.getString("full_name")).thenReturn("Disposable Customer");
                    when(row.getString("email")).thenReturn("throwaway@example.com");
                    when(row.getString("login_password")).thenReturn(saved);
                    when(row.getString("account_status")).thenReturn("ACTIVE");
                    when(row.getString("customer_status")).thenReturn("ACTIVE");
                    RowMapper<?> mapper = call.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
    }

    String request(String password) throws Exception {
        return json.writeValueAsString(Map.of("fullName", "Disposable Customer", "email", "throwaway@example.com", "password", password));
    }

    @Test void registrationAndVerificationKeepResponseAndLogsPasswordFree(CapturedOutput output) throws Exception {
        var created = mvc.perform(post("/internal/accounts/register").header("X-Internal-Token", "fixture-token")
                .contentType("application/json").content(request(PASSWORD)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.customerRef").exists()).andReturn();
        var verified = mvc.perform(post("/internal/accounts/verify").header("X-Internal-Token", "fixture-token")
                .contentType("application/json").content(request(PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerRef").value("CUST-fixture"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.loginPassword").doesNotExist()).andReturn();
        assertThat(created.getResponse().getContentAsString()).doesNotContain(PASSWORD);
        assertThat(verified.getResponse().getContentAsString()).doesNotContain(PASSWORD);
        assertThat(output.getAll()).doesNotContain(PASSWORD);
        assertThat(new RegistrationRequest("Name", "throwaway@example.com", PASSWORD).toString()).doesNotContain(PASSWORD);
        assertThat(new LoginVerificationRequest("throwaway@example.com", PASSWORD).toString()).doesNotContain(PASSWORD);
        assertThat(json.writeValueAsString(new RegistrationRequest("Name", "throwaway@example.com", PASSWORD))).doesNotContain(PASSWORD);
    }

    @Test void shortPasswordRegistrationAndVerificationUseTheExactValue() throws Exception {
        saved = "x";
        mvc.perform(post("/internal/accounts/register").header("X-Internal-Token", "fixture-token")
                .contentType("application/json").content(request(saved)))
            .andExpect(status().isCreated());
        verify(jdbc).update(contains("INSERT INTO customer_account"), eq(42L), eq("throwaway@example.com"), eq(saved));
        mvc.perform(post("/internal/accounts/verify").header("X-Internal-Token", "fixture-token")
                .contentType("application/json").content(request(saved)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.customerRef").value("CUST-fixture"));
        mvc.perform(post("/internal/accounts/verify").header("X-Internal-Token", "fixture-token")
                .contentType("application/json").content(request("y")))
            .andExpect(status().isUnauthorized());
    }

    @Test void legacyStoredHashReturnsResetCodeWithoutReturningTheValue() throws Exception {
        saved = "$2a$10$" + "A".repeat(53);
        var result = mvc.perform(post("/internal/accounts/verify").header("X-Internal-Token", "fixture-token")
                .contentType("application/json").content(request(saved)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_PASSWORD_RESET_REQUIRED")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(saved);
    }

    @Test void internalTokenStillRequiredForBothPaths() throws Exception {
        for (String action : new String[]{"register", "verify"}) {
            mvc.perform(post("/internal/accounts/" + action).contentType("application/json").content(request(PASSWORD)))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(jdbc);
    }

    @Test void missingIncorrectAndInvalidPasswordsAreRejectedWithoutWrites(CapturedOutput output) throws Exception {
        mvc.perform(post("/internal/accounts/verify").header("X-Internal-Token", "fixture-token")
                .contentType("application/json").content(request("wrong-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        for (String password : new String[]{"", "   ", "x".repeat(73)}) {
            mvc.perform(post("/internal/accounts/register").header("X-Internal-Token", "fixture-token")
                    .contentType("application/json").content(request(password))).andExpect(status().isBadRequest());
        }
        assertThat(output.getAll()).doesNotContain("x".repeat(73));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void unexpectedAccountErrorsNeverLogExceptionCredentials(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException(PASSWORD)).when(jdbc).update(anyString(), any(Object[].class));
        var result = mvc.perform(post("/internal/accounts/register").header("X-Internal-Token", "fixture-token")
                .contentType("application/json").content(request(PASSWORD))).andExpect(status().isInternalServerError()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD);
        assertThat(output.getAll()).doesNotContain(PASSWORD);
    }
}
