package com.fxlock.quote;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.http.HttpClient;
import java.security.Principal;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Route M3-owned wallet/receipt pages and APIs through the authenticated browser origin. */
@Controller
@Profile({"team","oracle-local"})
public class SettlementPageController {
    private final RestClient client;
    private final String token;
    private final String dashboardUrl;

    public SettlementPageController(QuotePolicy policy, @Value("${fxlock.internal-token}") String token,
            @Value("${fxlock.dashboard-url:http://localhost:8083}") String dashboardUrl) {
        this.dashboardUrl=dashboardUrl;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.client = RestClient.builder().baseUrl(policy.settlementBaseUrl()).requestFactory(factory).build();
        this.token = token;
    }

    @GetMapping({"/settlement.html", "/css/settlement.css", "/js/settlement.js",
        "/wallets.html", "/css/wallets.css", "/js/wallets.js"})
    @ResponseBody
    ResponseEntity<String> receiptAsset(HttpServletRequest request, Principal principal) {
        try {
            var response = client.get().uri(request.getServletPath()).retrieve().toEntity(String.class);
            String body = response.getBody();
            if ("/settlement.html".equals(request.getServletPath()) && body!=null) {
                body=body.replace("</head>","<meta name=\"dashboard-url\" content=\""+HtmlUtils.htmlEscape(dashboardUrl)+"\">\n</head>");
            }
            if ("/wallets.html".equals(request.getServletPath()) && body != null) {
                var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                String metadata = "<meta name=\"csrf-token\" content=\"" + HtmlUtils.htmlEscape(csrf.getToken()) + "\">\n"
                    + "<meta name=\"csrf-header\" content=\"" + HtmlUtils.htmlEscape(csrf.getHeaderName()) + "\">\n"
                    + "<meta name=\"customer-ref\" content=\""
                    + HtmlUtils.htmlEscape(QuoteRepository.customerRef(principal.getName())) + "\">\n";
                body = body.replace("</head>", metadata + "</head>");
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(response.getHeaders().getContentType()).body(body);
        } catch (RestClientException e) {
            throw new QuoteException(503, "SETTLEMENT_UNAVAILABLE", "M3 wallet and settlement pages are temporarily unavailable.");
        }
    }

    @GetMapping("/api/v1/settlements/{id}/details")
    @ResponseBody
    ResponseEntity<JsonNode> details(@PathVariable String id, Principal principal) {
        try {
            var body = client.get().uri("/api/v1/settlements/{id}/details", id)
                .header("X-Internal-Token", token).header("X-Customer-Ref", QuoteRepository.customerRef(principal.getName()))
                .retrieve().body(JsonNode.class);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new QuoteException(404, "SETTLEMENT_NOT_FOUND", "Settlement was not found for your account.");
            }
            throw new QuoteException(503, "SETTLEMENT_UNAVAILABLE", "Settlement details are temporarily unavailable.");
        } catch (RestClientException e) {
            throw new QuoteException(503, "SETTLEMENT_UNAVAILABLE", "Settlement details are temporarily unavailable.");
        }
    }

    @GetMapping("/api/v1/wallets")
    @ResponseBody
    ResponseEntity<JsonNode> wallets(Principal principal) {
        return read("/api/v1/wallets", principal);
    }

    @GetMapping("/api/v1/settlements/{id}")
    @ResponseBody
    ResponseEntity<JsonNode> settlement(@PathVariable String id, Principal principal) {
        return read("/api/v1/settlements/" + org.springframework.web.util.UriUtils.encodePathSegment(id,
            java.nio.charset.StandardCharsets.UTF_8), principal);
    }

    record AcceptanceRequest(@NotBlank @Size(max = 36) String quoteId) {}

    @PostMapping("/api/v1/settlements")
    @ResponseBody
    ResponseEntity<JsonNode> accept(@Valid @RequestBody AcceptanceRequest body,
            @RequestHeader("Idempotency-Key") String key, Principal principal) {
        try {
            var response = client.post().uri("/api/v1/settlements")
                .header("X-Internal-Token", token)
                .header("X-Customer-Ref", QuoteRepository.customerRef(principal.getName()))
                .header("Idempotency-Key", key).body(body).retrieve().toEntity(JsonNode.class);
            return ResponseEntity.status(response.getStatusCode()).cacheControl(CacheControl.noStore()).body(response.getBody());
        } catch (RestClientException e) {
            throw upstreamFailure(e);
        }
    }

    private ResponseEntity<JsonNode> read(String path, Principal principal) {
        try {
            var body = client.get().uri(path)
                .header("X-Internal-Token", token)
                .header("X-Customer-Ref", QuoteRepository.customerRef(principal.getName()))
                .retrieve().body(JsonNode.class);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        } catch (RestClientException e) {
            throw upstreamFailure(e);
        }
    }

    private QuoteException upstreamFailure(RestClientException failure) {
        if (failure instanceof RestClientResponseException response && response.getStatusCode().is4xxClientError()) {
            try {
                var error = response.getResponseBodyAs(JsonNode.class);
                if (error != null && error.hasNonNull("code") && error.hasNonNull("message")) {
                    return new QuoteException(response.getStatusCode().value(), error.get("code").asText(), error.get("message").asText());
                }
            } catch (RuntimeException ignored) { }
        }
        return new QuoteException(503, "SETTLEMENT_UNAVAILABLE",
            "M3 is unavailable. If an acceptance could not be confirmed, retry with the same quote and key.");
    }
}
