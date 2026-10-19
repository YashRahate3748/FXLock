package com.fxlock.quote;

import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles({"demo","test"})
@org.springframework.test.context.TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:m2api;MODE=Oracle;DB_CLOSE_DELAY=-1")
class QuoteApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean Clock clock;
    String request(String amount) { return """
        {"sourceCurrency":"EUR","targetCurrency":"USD","sourceAmount":"%s","sourceWalletId":"1","targetWalletId":"2"}
        """.formatted(amount); }
    @BeforeEach void setup() { when(clock.instant()).thenReturn(Instant.parse("2026-10-19T10:00:00Z")); }
    @Test void actualLoginAuthenticatesTheSyntheticCustomer() throws Exception {
        mvc.perform(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin().user("customer-demo").password("learn-fxlock"))
            .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated().withUsername("customer-demo"));
    }
    @Test void wrongPasswordIsRejected() throws Exception {
        mvc.perform(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin().user("customer-demo").password("wrong-password"))
            .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated());
    }
    @Test void unauthenticatedQuoteReadIs401() throws Exception { mvc.perform(get("/quotes/unknown")).andExpect(status().isUnauthorized()); }
    @Test @WithMockUser(username="customer-demo") void postRequiresCsrf() throws Exception {
        mvc.perform(post("/quotes").contentType("application/json").content(request("1000"))).andExpect(status().isForbidden());
    }
    @Test @WithMockUser(username="customer-demo") void validQuoteIsCreatedWithStringDecimals() throws Exception {
        mvc.perform(post("/quotes").with(csrf()).contentType("application/json").content(request("1000")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.quote.targetAmount").value("1167.66"))
            .andExpect(jsonPath("$.quote.customerId").value("1")).andExpect(jsonPath("$.demoMode").value(true));
    }
    @Test @WithMockUser(username="customer-demo") void negativeAmountIs400() throws Exception {
        mvc.perform(post("/quotes").with(csrf()).contentType("application/json").content(request("-1"))).andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(username="customer-demo") void unexpectedFieldsCannotOverrideOwnership() throws Exception {
        String body=request("1000").trim().replace("}",",\"customerId\":\"another-customer\"}");
        mvc.perform(post("/quotes").with(csrf()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(username="customer-demo") void commonCustomerLayoutRenders() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Learning demo")));
    }
    @Test @WithMockUser(username="customer-other") void walletOptionsAreCustomerSpecific() throws Exception {
        mvc.perform(get("/wallets/quote-options")).andExpect(status().isOk()).andExpect(jsonPath("$[0].walletId").value("3"));
    }
}
