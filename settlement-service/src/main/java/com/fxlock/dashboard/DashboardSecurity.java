package com.fxlock.dashboard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Session and synchronizer-token protection scoped to the new dashboard. */
@Component
public class DashboardSecurity extends OncePerRequestFilter {
    public static final String CUSTOMER = "dashboard.customer";
    public static final String CSRF = "dashboard.csrf";
    public static String customer(HttpServletRequest request) {
        var session=request.getSession(false);
        if (session == null || !(session.getAttribute(CUSTOMER) instanceof String ref)) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        return ref;
    }
    public static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !(path.equals("/dashboard") || path.startsWith("/dashboard/") || path.equals("/api/dashboard") || path.startsWith("/api/dashboard/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        String path = path(request);
        boolean publicFlow = path.equals("/dashboard/login") || path.equals("/dashboard/callback") || path.equals("/dashboard/logout");
        var session = request.getSession(false);
        if (!publicFlow && (session == null || session.getAttribute(CUSTOMER) == null)) {
            if (path.startsWith("/api/")) error(response, 401, "AUTHENTICATION_REQUIRED");
            else {
                if (path.equals("/dashboard/history") || path.equals("/dashboard/history/")) {
                    request.getSession(true).setAttribute("dashboard.next", "/dashboard/history/");
                }
                response.sendRedirect("/dashboard/login");
            }
            return;
        }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            String expected = session == null ? null : (String) session.getAttribute(CSRF);
            String supplied = request.getHeader("X-CSRF-TOKEN");
            if (supplied == null && path.equals("/dashboard/logout")) supplied = request.getParameter("_csrf");
            if (expected == null || supplied == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                error(response, 403, "CSRF_REJECTED"); return;
            }
        }
        chain.doFilter(request, response);
    }

    private void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    private String path(HttpServletRequest request) {
        // Match MVC's decoded application path, including matrix-parameter removal.
        return org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
