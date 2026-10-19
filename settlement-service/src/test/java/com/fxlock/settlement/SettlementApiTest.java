package com.fxlock.settlement;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.fxlock.exception.GlobalExceptionHandler;

@WebMvcTest(
        controllers = SettlementController.class,
        properties = "eureka.client.enabled=false"
)
@ActiveProfiles("local")
@Import(GlobalExceptionHandler.class)
class SettlementApiTest {

    private static final String URL = "/api/v1/settlements";

    private static final String QUOTE_ID =
            "11111111-1111-4111-8111-111111111111";

    private static final String SETTLEMENT_ID =
            "22222222-2222-4222-8222-222222222222";

    private static final String KEY = "api-test-key";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SettlementCoordinator coordinator;

    @MockitoBean
    private SettlementService settlementService;

    @Test
    void validAcceptanceReturnsCreated() throws Exception {
        SettlementResponse response = new SettlementResponse(
                SETTLEMENT_ID,
                QUOTE_ID,
                "COMPLETED",
                "EUR",
                new BigDecimal("100.00"),
                "USD",
                new BigDecimal("109.78")
        );

        when(coordinator.settle(QUOTE_ID, "CUST001", KEY))
                .thenReturn(response);

        mvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementId")
                        .value(SETTLEMENT_ID))
                .andExpect(jsonPath("$.quoteId").value(QUOTE_ID))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.sourceAmount").value(100))
                .andExpect(jsonPath("$.targetAmount").value(109.78));

        verify(coordinator).settle(QUOTE_ID, "CUST001", KEY);
    }

    @Test
    void missingHeaderReturnsBadRequest() throws Exception {
        mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.message")
                        .value("A required request header is missing."))
                .andExpect(jsonPath("$.path").value(URL))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        verifyNoInteractions(coordinator);
    }

    @Test
    void missingQuoteIdReturnsBadRequest() throws Exception {
        mvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(coordinator);
    }

    @Test
    void blankQuoteIdReturnsBadRequest() throws Exception {
        mvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quoteId":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(coordinator);
    }

    @Test
    void oversizedQuoteIdReturnsBadRequest() throws Exception {
        String body = "{\"quoteId\":\"" + "x".repeat(37) + "\"}";

        mvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(coordinator);
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JSON"));

        verifyNoInteractions(coordinator);
    }

    @Test
    void missingBodyReturnsBadRequest() throws Exception {
        mvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JSON"));

        verifyNoInteractions(coordinator);
    }

    @Test
    void expiredQuoteReturnsStructuredConflict() throws Exception {
        when(coordinator.settle(QUOTE_ID, "CUST001", KEY))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "QUOTE_EXPIRED"
                ));

        mvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"))
                .andExpect(jsonPath("$.message")
                        .value(
                            "This quote has expired. Request a new quote."
                        ))
                .andExpect(jsonPath("$.path").value(URL));
    }

    @Test
    void insufficientFundsReturnsStructuredConflict() throws Exception {
        when(coordinator.settle(QUOTE_ID, "CUST001", KEY))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "INSUFFICIENT_FUNDS"
                ));

        mvc.perform(post(URL)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message")
                        .value(
                            "Your source wallet has insufficient funds."
                        ));
    }

    @Test
    void unknownSettlementReturnsStructuredNotFound() throws Exception {
        when(settlementService.getSettlement(
                SETTLEMENT_ID,
                "CUST001"
        )).thenThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "SETTLEMENT_NOT_FOUND"
        ));

        String path = URL + "/" + SETTLEMENT_ID;

        mvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code")
                        .value("SETTLEMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value(
                            "Settlement not found for the current customer."
                        ))
                .andExpect(jsonPath("$.path").value(path));
    }

    private String validBody() {
        return "{\"quoteId\":\"" + QUOTE_ID + "\"}";
    }
}