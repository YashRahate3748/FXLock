package com.fxlock.quote;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class DashboardHandoffController {
    private final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();
    private final String dashboardUrl;
    private final String token;

    public DashboardHandoffController(@Value("${fxlock.dashboard-url:http://localhost:8083}") String dashboardUrl,
            @Value("${fxlock.internal-token:}") String token) { this.dashboardUrl = dashboardUrl; this.token = token; }

    @GetMapping("/dashboard/start")
    String start() { return "redirect:" + dashboardUrl + "/dashboard/login"; }

    @GetMapping("/dashboard/handoff")
    String handoff(@RequestParam String state, Principal principal, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!state.matches("[A-Za-z0-9_-]{43}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (principal.getName().equals("customer-demo") || principal.getName().equals("customer-other")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registered customer login required");
        }
        long now = System.currentTimeMillis();
        grants.entrySet().removeIf(e -> e.getValue().expires < now);
        if (grants.size() >= 10000) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        grants.put(code, new Grant(state, principal.getName(), now + 60000, request.getSession(false)));
        return "redirect:" + dashboardUrl + "/dashboard/callback?code=" + code + "&state=" + state;
    }

    @PostMapping("/internal/dashboard/redeem")
    @ResponseBody Identity redeem(@RequestHeader(value = "X-Internal-Token", required = false) String supplied, @RequestBody Redemption request) {
        if (token.isBlank() || supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (request.code() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Grant grant = grants.remove(request.code());
        if (grant == null || grant.expires < System.currentTimeMillis() || !grant.state.equals(request.state())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            if (grant.session == null || !(grant.session.getAttribute("SPRING_SECURITY_CONTEXT")
                    instanceof org.springframework.security.core.context.SecurityContext context)
                    || context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()
                    || !grant.customer.equals(context.getAuthentication().getName())) throw new IllegalStateException();
        } catch (IllegalStateException invalid) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
        return new Identity(grant.customer);
    }

    @GetMapping(value = "/dashboard/logout", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody String logout(HttpServletRequest request) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return "<!doctype html><html lang='en'><title>Logging out</title><body><main><h1>Finish logout</h1>"
                + "<form method='post' action='/logout'><input type='hidden' name='_csrf' value='" + csrf.getToken()
                + "'><button>Log out of FXLock</button></form></main></body></html>";
    }

    private record Grant(String state, String customer, long expires, jakarta.servlet.http.HttpSession session) {}
    public record Redemption(String code, String state) {
        @Override public String toString() { return "Redemption[handoff redacted]"; }
    }
    public record Identity(String customerRef) {}
}
