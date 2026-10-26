package com.fxlock.quote;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Backend-only account calls. No retries: a failed registration may already have committed. */
@Component
public class AccountClient {
    private final RestClient client;
    private final String token;

    public AccountClient(@Value("${fxlock.account-service-url}") String baseUrl,
                         @Value("${fxlock.internal-token}") String token) {
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("Account service URL must be an absolute HTTP or HTTPS URL");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.token = token;
    }

    public record Account(String customerRef, String fullName, String email) {}

    // RestClient can log request.toString() at DEBUG. Keep credentials redacted at every log level.
    private record RegistrationRequest(String fullName, String email, String password) {
        @Override public String toString() { return "RegistrationRequest[credentials redacted]"; }
    }

    private record VerificationRequest(String email, String password) {
        @Override public String toString() { return "VerificationRequest[credentials redacted]"; }
    }

    enum Failure { INVALID_CREDENTIALS, PASSWORD_RESET_REQUIRED, CONFLICT, UNAVAILABLE }

    static class AccountFailure extends RuntimeException {
        final Failure failure;

        AccountFailure(Failure failure) {
            super("Account request failed: " + failure);
            this.failure = failure;
        }
    }

    public void register(String fullName, String email, String password) {
        requireToken();
        try {
            var response = client.post().uri("/internal/accounts/register")
                .header("X-Internal-Token", token)
                .body(new RegistrationRequest(fullName, email, password))
                .retrieve().toBodilessEntity();
            if (response.getStatusCode().value() != 201) {
                throw new AccountFailure(Failure.UNAVAILABLE);
            }
        } catch (RestClientResponseException e) {
            throw new AccountFailure(e.getStatusCode().value() == 409 && hasCode(e, "REGISTRATION_CONFLICT")
                ? Failure.CONFLICT : Failure.UNAVAILABLE);
        } catch (RestClientException e) {
            throw new AccountFailure(Failure.UNAVAILABLE);
        }
    }

    public Account verify(String email, String password) {
        requireToken();
        try {
            var response = client.post().uri("/internal/accounts/verify")
                .header("X-Internal-Token", token).body(new VerificationRequest(email, password))
                .retrieve().toEntity(Account.class);
            var account = response.getBody();
            if (response.getStatusCode().value() != 200 || account == null ||
                account.customerRef() == null || account.customerRef().isBlank() ||
                account.fullName() == null || account.fullName().isBlank() ||
                account.email() == null || !email.equalsIgnoreCase(account.email())) {
                throw new AccountFailure(Failure.UNAVAILABLE);
            }
            return account;
        } catch (RestClientResponseException e) {
            // A bad internal token is also a 401. Only the documented credential code is a login rejection.
            boolean invalidCredentials = e.getStatusCode().value() == 401 &&
                hasCode(e, "INVALID_CREDENTIALS");
            if (e.getStatusCode().value() == 409 && hasCode(e, "ACCOUNT_PASSWORD_RESET_REQUIRED")) {
                throw new AccountFailure(Failure.PASSWORD_RESET_REQUIRED);
            }
            throw new AccountFailure(invalidCredentials ? Failure.INVALID_CREDENTIALS : Failure.UNAVAILABLE);
        } catch (RestClientException e) {
            throw new AccountFailure(Failure.UNAVAILABLE);
        }
    }

    private void requireToken() {
        if (token == null || token.isBlank()) {
            throw new AccountFailure(Failure.UNAVAILABLE);
        }
    }

    private static boolean hasCode(RestClientResponseException response, String code) {
        try {
            return code.equals(new ObjectMapper().readTree(response.getResponseBodyAsByteArray()).path("code").asText());
        } catch (Exception ignored) {
            return false;
        }
    }
}
