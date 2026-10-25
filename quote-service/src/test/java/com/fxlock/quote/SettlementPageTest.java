package com.fxlock.quote;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(SettlementPageController.class)
@ActiveProfiles("team")
@Import({SecurityConfiguration.class, AccountValidation.class})
class SettlementPageTest {
    @Autowired MockMvc mvc;
    @MockitoBean UserDetailsService users;
    @MockitoBean AccountClient accounts;
    static HttpServer server;
    static volatile String customer, token, path, body, type, key, requestBody;
    static volatile int status;

    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path = exchange.getRequestURI().getPath();
            customer = exchange.getRequestHeaders().getFirst("X-Customer-Ref");
            token = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        registry.add("fxlock.settlement-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("fxlock.internal-token", () -> "test-internal-token");
    }

    @BeforeEach void setup() {
        status = 200; type = "application/json"; body = "{\"settlementId\":\"s1\",\"ledgerEntries\":[]}";
        path = null; customer = null; token = null; key = null; requestBody = null;
    }

    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @Test @WithMockUser(username = "CUST-signed-in")
    void receiptUsesSessionIdentityEvenWhenBrowserSendsForgedHeaders() throws Exception {
        mvc.perform(get("/api/v1/settlements/s1/details").header("X-Customer-Ref", "CUST-other")
                .header("X-Internal-Token", "forged"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.settlementId").value("s1"))
            .andExpect(content().string(not(containsString("test-internal-token"))));
        assertThat(customer).isEqualTo("CUST-signed-in");
        assertThat(token).isEqualTo("test-internal-token");
        assertThat(path).isEqualTo("/api/v1/settlements/s1/details");
    }

    @Test void anonymousReceiptCannotReachM3() throws Exception {
        mvc.perform(get("/api/v1/settlements/s1/details").accept("text/html"))
            .andExpect(status().is3xxRedirection());
        assertThat(path).isNull();
    }
    @Test @WithMockUser(username = "real-receipt-owner")
    void verifiedReferenceIsForwardedWithoutTrainingAliases() throws Exception {
        mvc.perform(get("/api/v1/settlements/s1/details")).andExpect(status().isOk());
        assertThat(customer).isEqualTo("real-receipt-owner");
    }

    @Test @WithMockUser
    void receiptPreservesTheM3WalletLink() throws Exception {
        type = "text/html"; body = "<a href=\"/wallets.html\">Wallets</a>";
        mvc.perform(get("/settlement.html").servletPath("/settlement.html"))
            .andExpect(status().isOk()).andExpect(content().string("<a href=\"/wallets.html\">Wallets</a>"));
        assertThat(token).isNull();
    }

    @Test @WithMockUser(username = "CUST002")
    void m3WalletPageGetsSessionMetadataWithoutInternalToken() throws Exception {
        type = "text/html"; body = "<html><head></head><body>Your wallets</body></html>";
        mvc.perform(get("/wallets.html").servletPath("/wallets.html"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("Your wallets")))
            .andExpect(content().string(containsString("name=\"csrf-token\"")))
            .andExpect(content().string(containsString("name=\"customer-ref\" content=\"CUST002\"")))
            .andExpect(content().string(not(containsString("test-internal-token"))));
        assertThat(path).isEqualTo("/wallets.html");
    }

    @Test @WithMockUser
    void walletAssetsAreServedFromM3() throws Exception {
        for (String asset : new String[]{"/css/wallets.css", "/js/wallets.js"}) {
            mvc.perform(get(asset).servletPath(asset)).andExpect(status().isOk());
            assertThat(path).isEqualTo(asset);
        }
    }

    @Test @WithMockUser(username = "CUST002")
    void balancesComeFromM3UsingTheSessionOwner() throws Exception {
        body = "[{\"walletId\":3,\"currencyCode\":\"EUR\",\"balance\":25}]";
        mvc.perform(get("/api/v1/wallets").header("X-Customer-Ref", "CUST001"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].walletId").value(3));
        assertThat(path).isEqualTo("/api/v1/wallets");
        assertThat(customer).isEqualTo("CUST002");
        assertThat(token).isEqualTo("test-internal-token");
    }

    @Test void anonymousWalletPageAndApiNeverReachM3() throws Exception {
        for (String route : new String[]{"/wallets.html", "/api/v1/wallets"}) {
            mvc.perform(get(route).accept("text/html")).andExpect(status().is3xxRedirection());
        }
        assertThat(path).isNull();
    }

    @Test @WithMockUser(username = "CUST001")
    void walletSettlementLookupIsRoutedToM3() throws Exception {
        mvc.perform(get("/api/v1/settlements/s1")).andExpect(status().isOk())
            .andExpect(jsonPath("$.settlementId").value("s1"));
        assertThat(path).isEqualTo("/api/v1/settlements/s1");
        assertThat(customer).isEqualTo("CUST001");
    }

    @Test @WithMockUser(username = "CUST001")
    void walletAcceptanceRequiresCsrfAndPreservesIdentityAndRetryKey() throws Exception {
        mvc.perform(post("/api/v1/settlements").header("Idempotency-Key", "same-key")
                .contentType("application/json").content("{\"quoteId\":\"q1\"}"))
            .andExpect(status().isForbidden());
        assertThat(path).isNull();
        status = 201;
        mvc.perform(post("/api/v1/settlements").with(csrf()).header("Idempotency-Key", "same-key")
                .header("X-Customer-Ref", "CUST002").contentType("application/json").content("{\"quoteId\":\"q1\"}"))
            .andExpect(status().isCreated());
        assertThat(customer).isEqualTo("CUST001");
        assertThat(key).isEqualTo("same-key");
        assertThat(requestBody).isEqualTo("{\"quoteId\":\"q1\"}");
    }

    @Test @WithMockUser
    void m3WalletRejectionsKeepTheirBusinessMessage() throws Exception {
        status = 403; body = "{\"code\":\"CUSTOMER_NOT_ACTIVE\",\"message\":\"The current customer is inactive.\"}";
        mvc.perform(get("/api/v1/wallets")).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ACTIVE"));
    }

    @Test @WithMockUser
    void anotherCustomersReceiptRemainsNotFound() throws Exception {
        status = 404;
        mvc.perform(get("/api/v1/settlements/other/details"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));
    }
}
