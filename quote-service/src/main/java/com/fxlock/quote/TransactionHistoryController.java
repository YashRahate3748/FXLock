package com.fxlock.quote;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.security.Principal;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@RestController
@Profile({"team","oracle-local"})
@RequestMapping("/api/transaction-history")
public class TransactionHistoryController {
    private final RestClient client;
    private final String token;

    public TransactionHistoryController(@Value("${fxlock.account-service-url}") String baseUrl,
            @Value("${fxlock.internal-token}") String token) {
        this.token=token;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.client=RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @GetMapping
    public ResponseEntity<JsonNode> history(Principal principal, @RequestParam(required=false) String walletId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        if (page<0 || page>1000000 || size<1 || size>100) {
            throw new QuoteException(400,"INVALID_PAGE","Use a non-negative page and a page size between 1 and 100.");
        }
        if (walletId!=null && !walletId.isBlank()) {
            try {
                if (!walletId.matches("[1-9][0-9]{0,18}") || Long.parseLong(walletId)<=0) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                throw new QuoteException(400,"INVALID_ACCOUNT","Choose a valid wallet filter.");
            }
        }
        return read(principal,"",walletId,page,size);
    }

    @GetMapping("/accounts")
    public ResponseEntity<JsonNode> accounts(Principal principal) { return read(principal,"/accounts",null,0,20); }

    private ResponseEntity<JsonNode> read(Principal principal, String suffix, String walletId, int page, int size) {
        try {
            var body=client.get().uri(builder -> {
                builder.path("/internal/transaction-history"+suffix);
                if (suffix.isEmpty()) {
                    builder.queryParam("page",page).queryParam("size",size);
                    if (walletId!=null) builder.queryParam("walletId",walletId);
                }
                return builder.build();
            }).header("X-Internal-Token",token).header("X-Customer-Ref",principal.getName()).retrieve().body(JsonNode.class);
            if (body==null) throw new QuoteException(503,"HISTORY_UNAVAILABLE","Transaction history is temporarily unavailable.");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().is4xxClientError()) {
                try {
                    JsonNode error=failure.getResponseBodyAs(JsonNode.class);
                    if (error!=null && error.hasNonNull("code") && error.hasNonNull("message")) {
                        throw new QuoteException(failure.getStatusCode().value(),error.get("code").asText(),error.get("message").asText());
                    }
                } catch (QuoteException rejected) { throw rejected; }
                catch (RuntimeException unreadable) { /* Keep unreadable upstream bodies out of the API. */ }
                throw new QuoteException(failure.getStatusCode().value(),"HISTORY_REQUEST_REJECTED","History was not available for this account or filter.");
            }
            throw new QuoteException(503,"HISTORY_UNAVAILABLE","Transaction history is temporarily unavailable.");
        } catch (RestClientException failure) {
            throw new QuoteException(503,"HISTORY_UNAVAILABLE","Transaction history is temporarily unavailable.");
        }
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Models.ApiError> invalidPage() {
        return ResponseEntity.badRequest().body(new Models.ApiError("INVALID_PAGE","Use numeric page and page-size values."));
    }
}
