package com.fxlock.dashboard;

import com.fxlock.account.DashboardAccounts;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardBrowserFlowTest {
    HttpServer backend;
    DashboardAccounts accounts;
    DashboardBrowserController controller;
    int redemptions;

    @BeforeEach void setup() throws Exception {
        accounts = mock(DashboardAccounts.class);
        backend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        backend.createContext("/internal/dashboard/redeem", exchange -> {
            if (!"fixture-internal".equals(exchange.getRequestHeaders().getFirst("X-Internal-Token"))) {
                exchange.sendResponseHeaders(401, -1); exchange.close(); return;
            }
            redemptions++;
            byte[] body = "{\"customerRef\":\"verified-from-backend\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        backend.start();
        controller = new DashboardBrowserController("http://localhost:" + backend.getAddress().getPort(), "fixture-internal", accounts);
    }
    @AfterEach void stop() { backend.stop(0); }

    @Test void stateBoundBackendRedemptionRotatesSessionAndCleansUrl() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        controller.login(request, response);
        var session = request.getSession();
        String oldId = session.getId();
        String state = (String) session.getAttribute("dashboard.flow");
        assertThat(response.getRedirectedUrl()).endsWith("/dashboard/handoff?state=" + state);
        response = new MockHttpServletResponse();
        controller.callback(state, "one-time-code", request, response);
        assertThat(redemptions).isEqualTo(1);
        verify(accounts).active("verified-from-backend", false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(session.getAttribute(DashboardSecurity.CUSTOMER)).isEqualTo("verified-from-backend");
        assertThat(session.getAttribute(DashboardSecurity.CSRF)).isNotNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/dashboard/");
        assertThatThrownBy(() -> controller.callback(state, "one-time-code", request, new MockHttpServletResponse()))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(redemptions).isEqualTo(1);
    }

    @Test void missingWrongOrExpiredStateNeverCallsBackendOrAuthenticates() throws Exception {
        var request = new MockHttpServletRequest();
        assertThatThrownBy(() -> controller.callback("state", "code", request, new MockHttpServletResponse()))
            .isInstanceOf(ResponseStatusException.class);
        controller.login(request, new MockHttpServletResponse());
        assertThatThrownBy(() -> controller.callback("wrong", "code", request, new MockHttpServletResponse()))
            .isInstanceOf(ResponseStatusException.class);
        String state = (String) request.getSession().getAttribute("dashboard.flow");
        request.getSession().setAttribute("dashboard.flowTime", System.currentTimeMillis() - 120001);
        assertThatThrownBy(() -> controller.callback(state, "code", request, new MockHttpServletResponse()))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(redemptions).isZero();
        assertThat(request.getSession().getAttribute(DashboardSecurity.CUSTOMER)).isNull();
    }
    @Test void historyReturnPageSurvivesTheStateBoundHandoffWithoutAnOpenRedirect() throws Exception {
        for (String next : new String[]{"/dashboard/history/","https://untrusted.example/"}) {
            var request=new MockHttpServletRequest();
            controller.login(request,new MockHttpServletResponse());
            request.getSession().setAttribute("dashboard.next",next);
            String state=(String)request.getSession().getAttribute("dashboard.flow");
            var response=new MockHttpServletResponse();
            controller.callback(state,"one-time-code",request,response);
            assertThat(response.getRedirectedUrl()).isEqualTo(next.equals("/dashboard/history/") ? "/dashboard/history/" : "/dashboard/");
            assertThat(request.getSession().getAttribute("dashboard.next")).isNull();
        }
    }
}
