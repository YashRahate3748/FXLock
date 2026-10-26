package com.fxlock.quote;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionHistoryController.class)
@ActiveProfiles("team")
@Import({SecurityConfiguration.class,AccountValidation.class})
class TransactionHistoryApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountClient accounts;
    static HttpServer backend;
    static volatile String requestPath,customer,token;
    static volatile int status;
    static volatile String body;

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) throws Exception {
        backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        backend.createContext("/",exchange -> {
            requestPath=exchange.getRequestURI().toString();
            customer=exchange.getRequestHeaders().getFirst("X-Customer-Ref");
            token=exchange.getRequestHeaders().getFirst("X-Internal-Token");
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        backend.start();
        registry.add("fxlock.account-service-url",() -> "http://127.0.0.1:"+backend.getAddress().getPort());
        registry.add("fxlock.internal-token",() -> "fixture-token");
    }
    @BeforeEach void setup() { status=200; body="{\"entries\":[],\"page\":0,\"size\":20,\"total\":0,\"hasNext\":false}"; requestPath=null; customer=null; token=null; }
    @AfterAll static void stop() { backend.stop(0); }

    @Test void referenceJsonRouteIsPreservedAndUsesOnlyTheAuthenticatedPrincipal() throws Exception {
        mvc.perform(get("/api/transaction-history").with(user("actual-account"))
                .header("X-Customer-Ref","forged").header("X-Internal-Token","forged")
                .param("walletId","410").param("page","1").param("size","50"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.entries").isArray()).andExpect(header().string("Cache-Control","no-store"));
        assertThat(requestPath).isEqualTo("/internal/transaction-history?page=1&size=50&walletId=410");
        assertThat(customer).isEqualTo("actual-account");
        assertThat(token).isEqualTo("fixture-token");
    }

    @Test void referenceAccountsRouteRemainsAJsonArray() throws Exception {
        body="[{\"walletId\":\"410\",\"currency\":\"EUR\",\"status\":\"ACTIVE\"}]";
        mvc.perform(get("/api/transaction-history/accounts").with(user("actual-account")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].walletId").value("410"));
        assertThat(requestPath).isEqualTo("/internal/transaction-history/accounts");
    }

    @Test void anonymousAccessCannotReachM3() throws Exception {
        mvc.perform(get("/api/transaction-history").accept("application/json")).andExpect(status().isUnauthorized());
        assertThat(requestPath).isNull();
    }

    @Test void errorsAreNotConvertedIntoEmptyHistory() throws Exception {
        status=404;
        body="{\"code\":\"ACCOUNT_NOT_FOUND\",\"message\":\"Account not found for this customer.\"}";
        mvc.perform(get("/api/transaction-history").with(user("actual-account"))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        status=503;
        mvc.perform(get("/api/transaction-history").with(user("actual-account"))).andExpect(status().isServiceUnavailable());
    }
    @Test void malformedPaginationDoesNotReachTheBackend() throws Exception {
        mvc.perform(get("/api/transaction-history").with(user("actual-account")).param("size","invalid"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        assertThat(requestPath).isNull();
    }
    @Test void invalidFilterAndOutOfRangePagesAreRejectedBeforeBuildingAnUpstreamUri() throws Exception {
        mvc.perform(get("/api/transaction-history").with(user("actual-account")).param("walletId","{unsafe}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ACCOUNT"));
        mvc.perform(get("/api/transaction-history").with(user("actual-account")).param("page","-1"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        assertThat(requestPath).isNull();
    }
}
