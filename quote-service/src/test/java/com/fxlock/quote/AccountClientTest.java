package com.fxlock.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AccountClientTest {
    HttpServer server;
    AccountClient client;
    final AtomicInteger calls = new AtomicInteger();
    volatile int status;
    volatile String response;
    volatile boolean delayResponse;
    String path, token, request;

    @BeforeEach void setup() throws Exception {
        status = 200;
        response = "{\"customerRef\":\"CUST-persistent\",\"fullName\":\"Name\",\"email\":\"customer@example.com\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            path = exchange.getRequestURI().getPath();
            token = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (delayResponse) {
                try { Thread.sleep(5000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        client = new AccountClient(url(), "test-internal-token");
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @AfterEach void stop() { server.stop(0); }

    @Test void legacyAccountResetIsReportedWithoutEchoingSavedCredentials() {
        status = 409;
        response = "{\"code\":\"ACCOUNT_PASSWORD_RESET_REQUIRED\"}";
        assertThatThrownBy(() -> client.verify("customer@example.com", "throwaway-password"))
            .isInstanceOfSatisfying(AccountClient.AccountFailure.class, error -> {
                assertThat(error.failure).isEqualTo(AccountClient.Failure.PASSWORD_RESET_REQUIRED);
                assertThat(error.getMessage()).doesNotContain("throwaway-password");
            });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void registrationUsesBackendTokenExactRouteAndUnmodifiedPassword() throws Exception {
        status = 201;
        client.register("Name", "customer@example.com", "  preserved password  ");
        assertThat(path).isEqualTo("/internal/accounts/register");
        assertThat(token).isEqualTo("test-internal-token");
        var body = new ObjectMapper().readTree(request);
        assertThat(body.path("password").asText()).isEqualTo("  preserved password  ");
        assertThat(body.size()).isEqualTo(3);
        assertThat(calls.get()).isOne();
    }

    @Test void verificationUsesExactContract() throws Exception {
        assertThat(client.verify("customer@example.com", "valid-password").customerRef()).isEqualTo("CUST-persistent");
        assertThat(path).isEqualTo("/internal/accounts/verify");
        assertThat(token).isEqualTo("test-internal-token");
        assertThat(new ObjectMapper().readTree(request).path("password").asText()).isEqualTo("valid-password");
    }

    @Test void errorCodesAreMappedAndRegistrationIsNeverRetried() {
        status = 409; response = "{\"code\":\"REGISTRATION_CONFLICT\"}";
        fails(AccountClient.Failure.CONFLICT, () -> client.register("Name", "customer@example.com", "valid-password"));
        assertThat(calls.get()).isOne();
        status = 503; response = "unavailable";
        fails(AccountClient.Failure.UNAVAILABLE, () -> client.register("Name", "customer@example.com", "valid-password"));
        assertThat(calls.get()).isEqualTo(2);
        status = 401; response = "{\"code\":\"INVALID_CREDENTIALS\"}";
        fails(AccountClient.Failure.INVALID_CREDENTIALS, () -> client.verify("customer@example.com", "valid-password"));
        response = "{\"code\":\"INVALID_INTERNAL_TOKEN\"}";
        fails(AccountClient.Failure.UNAVAILABLE, () -> client.verify("customer@example.com", "valid-password"));
    }

    @Test void malformedOrIncompleteVerificationAndUnexpectedSuccessAreUnavailable() {
        for (String body : new String[]{"not-json", "{}", "{\"customerRef\":\"\"}", "null"}) {
            response = body;
            fails(AccountClient.Failure.UNAVAILABLE, () -> client.verify("customer@example.com", "valid-password"));
        }
        status = 200;
        fails(AccountClient.Failure.UNAVAILABLE, () -> client.register("Name", "customer@example.com", "valid-password"));
    }

    @Test void missingTokenMakesNoRequest() {
        client = new AccountClient(url(), "");
        fails(AccountClient.Failure.UNAVAILABLE, () -> client.register("Name", "customer@example.com", "valid-password"));
        fails(AccountClient.Failure.UNAVAILABLE, () -> client.verify("customer@example.com", "valid-password"));
        assertThat(calls.get()).isZero();
    }

    @Test void unavailableConnectionFailsSafelyWithoutLeakingCredentials() {
        server.stop(0);
        fails(AccountClient.Failure.UNAVAILABLE, () -> client.verify("customer@example.com", "secret-password"));
        assertThatThrownBy(() -> client.register("Name", "customer@example.com", "secret-password"))
            .hasMessageNotContaining("secret-password").hasNoCause();
    }

    @Test void passwordByteBoundariesAreExact() {
        assertThat(AccountValidation.validPassword("a".repeat(72))).isTrue();
        assertThat(AccountValidation.validPassword("é".repeat(36))).isTrue();
        assertThat(AccountValidation.validPassword("é".repeat(37))).isFalse();
        assertThat(AccountValidation.validPassword("a".repeat(73))).isFalse();
        assertThat(AccountValidation.validPassword("a")).isTrue();
        assertThat(AccountValidation.validPassword("")).isFalse();
        assertThat(AccountValidation.validPassword("   ")).isFalse();
        assertThat(AccountValidation.validPassword(null)).isFalse();
    }

    @Test void uncertainRegistrationTimesOutWithoutRetry() {
        delayResponse = true;
        status = 201;
        fails(AccountClient.Failure.UNAVAILABLE, () -> client.register("Name", "customer@example.com", "valid-password"));
        assertThat(calls.get()).isOne();
    }

    @Test void debugRequestLoggingRedactsPasswordsAndInternalToken(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.springframework.web.client");
        var previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            status = 201;
            client.register("Name", "customer@example.com", "unique-secret-password");
            status = 200;
            client.verify("customer@example.com", "unique-secret-password");
            assertThat(output.getAll()).contains("credentials redacted")
                .doesNotContain("unique-secret-password", "test-internal-token");
        } finally {
            logger.setLevel(previous);
        }
    }

    private void fails(AccountClient.Failure failure, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AccountClient.AccountFailure.class,
            exception -> assertThat(exception.failure).isEqualTo(failure));
    }
}
