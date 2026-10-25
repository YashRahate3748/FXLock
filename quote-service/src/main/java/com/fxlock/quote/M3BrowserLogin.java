package com.fxlock.quote;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/** Browser credentials are verified only by M3; Spring Security owns the resulting session. */
final class M3BrowserLogin {
    private M3BrowserLogin() {}

    static AuthenticationFilter filter(AccountClient client, AccountValidation validation, CsrfTokenRepository csrf) {
        AuthenticationConverter credentials = request -> {
            String email = AccountValidation.normalizeEmail(request.getParameter("username"));
            String password = request.getParameter("password");
            if (!validation.validEmail(email) || !AccountValidation.validPassword(password)) {
                throw new BadCredentialsException("Invalid account credentials.");
            }
            try {
                var verified = client.verify(email, password);
                if (verified == null) {
                    throw new AuthenticationServiceException("Account verification unavailable.");
                }
                return new PreAuthenticatedAuthenticationToken(verified, null);
            } catch (AccountClient.AccountFailure failure) {
                throw switch (failure.failure) {
                    case INVALID_CREDENTIALS -> new BadCredentialsException("Invalid account credentials.");
                    case PASSWORD_RESET_REQUIRED -> new CredentialsExpiredException("Account password reset required.");
                    default -> new AuthenticationServiceException("Account verification unavailable.");
                };
            }
        };
        AuthenticationManager verifiedIdentity = assertion -> {
            if (!(assertion.getPrincipal() instanceof AccountClient.Account account)) {
                throw new BadCredentialsException("Verified account identity required.");
            }
            return new PreAuthenticatedAuthenticationToken(account.customerRef(), null,
                    AuthorityUtils.createAuthorityList("ROLE_CUSTOMER"));
        };
        var filter = new AuthenticationFilter(verifiedIdentity, credentials);
        filter.setRequestMatcher(request -> "POST".equals(request.getMethod())
                && (request.getContextPath() + "/login").equals(request.getRequestURI()));
        filter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        filter.setSuccessHandler((request, response, authentication) -> {
            new CsrfAuthenticationStrategy(csrf).onAuthentication(authentication, request, response);
            response.sendRedirect(request.getContextPath() + "/dashboard/start");
        });
        filter.setFailureHandler((request, response, failure) -> {
            var session = request.getSession(false);
            if (session != null) session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            String reason = failure instanceof CredentialsExpiredException ? "resetRequired"
                    : failure instanceof AuthenticationServiceException ? "unavailable" : "error";
            response.sendRedirect(request.getContextPath() + "/login?" + reason);
        });
        return filter;
    }
}
