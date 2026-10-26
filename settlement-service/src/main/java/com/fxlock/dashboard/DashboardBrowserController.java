package com.fxlock.dashboard;

import com.fxlock.account.DashboardAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class DashboardBrowserController {
    private final String quoteUrl;
    private final String token;
    private final RestClient quote;
    private final DashboardAccounts accounts;

    public DashboardBrowserController(@Value("${fxlock.dashboard.quote-url:http://localhost:8082}") String quoteUrl,
            @Value("${fxlock.internal-token:}") String token, DashboardAccounts accounts) {
        this.quoteUrl = quoteUrl; this.token = token; this.accounts = accounts;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.quote = RestClient.builder().baseUrl(quoteUrl).requestFactory(factory).build();
    }

    @GetMapping("/dashboard/login")
    void login(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String state = DashboardSecurity.randomToken();
        var session = request.getSession(true);
        session.setAttribute("dashboard.flow", state);
        session.setAttribute("dashboard.flowTime", System.currentTimeMillis());
        response.sendRedirect(quoteUrl + "/dashboard/handoff?state=" + state);
    }

    @GetMapping("/dashboard/callback")
    void callback(@RequestParam String state, @RequestParam String code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false);
        if (session == null || !state.equals(session.getAttribute("dashboard.flow"))
                || !(session.getAttribute("dashboard.flowTime") instanceof Long time)
                || System.currentTimeMillis() - time > 120000 || code.length() > 100) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_HANDOFF");
        }
        session.removeAttribute("dashboard.flow");
        session.removeAttribute("dashboard.flowTime");
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HANDOFF_NOT_CONFIGURED");
        Handoff identity;
        try {
            identity = quote.post().uri("/internal/dashboard/redeem").header("X-Internal-Token", token)
                    .body(new RedemptionRequest(code, state)).retrieve().body(Handoff.class);
        } catch (Exception failure) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "HANDOFF_FAILED"); }
        if (identity == null || identity.customerRef() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "HANDOFF_FAILED");
        accounts.active(identity.customerRef(), false);
        request.changeSessionId();
        session.setAttribute(DashboardSecurity.CUSTOMER, identity.customerRef());
        session.setAttribute(DashboardSecurity.CSRF, DashboardSecurity.randomToken());
        Object next=session.getAttribute("dashboard.next");
        session.removeAttribute("dashboard.next");
        response.sendRedirect("/dashboard/history/".equals(next) ? "/dashboard/history/" : "/dashboard/");
    }

    public record Handoff(String customerRef) {}
    private record RedemptionRequest(String code, String state) {
        @Override public String toString() { return "RedemptionRequest[handoff redacted]"; }
    }

    @GetMapping({"/dashboard", "/dashboard/"})
    String dashboard() { return "forward:/dashboard/index.html"; }

    @GetMapping({"/dashboard/history", "/dashboard/history/"})
    String history() { return "forward:/dashboard/index.html"; }

    @GetMapping(value = "/dashboard/logout", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody String logoutPage(HttpServletRequest request) {
        var session = request.getSession(true);
        if (session.getAttribute(DashboardSecurity.CSRF) == null) session.setAttribute(DashboardSecurity.CSRF, DashboardSecurity.randomToken());
        return "<!doctype html><html lang='en'><title>Finish logout</title><body><main><h1>Finish logout</h1>"
                + "<form method='post' action='/dashboard/logout'><input type='hidden' name='_csrf' value='"
                + session.getAttribute(DashboardSecurity.CSRF) + "'><button>Finish logout</button></form>"
                + "</main></body></html>";
    }

    @PostMapping("/dashboard/logout")
    void finishLogout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.getSession().invalidate();
        response.sendRedirect(quoteUrl + "/login");
    }
}
