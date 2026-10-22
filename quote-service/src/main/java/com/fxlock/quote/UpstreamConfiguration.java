package com.fxlock.quote;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import static com.fxlock.quote.Models.*;

@Configuration
public class UpstreamConfiguration {

    @Bean
    WalletGateway wallets(
            QuoteRepository repository
    ) {
        return repository::wallets;
    }

    @Bean
    @Profile("demo")
    RateGateway demoRates(
            java.time.Clock clock,
            JdbcTemplate jdbc
    ) {

        return () -> {

            Instant observed =
                    clock.instant()
                            .minusSeconds(5);

            var key =
                    new GeneratedKeyHolder();

            jdbc.update(
                    connection -> {

                        var ps =
                                connection.prepareStatement(
                                        """
                                        INSERT INTO MARKET_RATE_SNAPSHOT
                                        (
                                            CURRENCY_PAIR,
                                            PROVIDER_NAME,
                                            MARKET_RATE,
                                            PROVIDER_TIMESTAMP,
                                            RECEIVED_AT,
                                            VALIDATION_STATUS,
                                            TRUSTED_FLAG,
                                            MOVEMENT_PERCENT,
                                            VALIDATION_REASON
                                        )
                                        VALUES
                                        (
                                            'EUR/USD',
                                            'M2_TEST_FIXTURE',
                                            1.17,
                                            ?,
                                            ?,
                                            'TRUSTED',
                                            'Y',
                                            0.02,
                                            'H2 demo fixture only'
                                        )
                                        """,
                                        new String[]{
                                                "RATE_SNAPSHOT_ID"
                                        }
                                );

                        ps.setObject(
                                1,
                                observed.atOffset(
                                        ZoneOffset.UTC
                                )
                        );

                        ps.setObject(
                                2,
                                clock.instant()
                                        .atOffset(
                                                ZoneOffset.UTC
                                        )
                        );

                        return ps;
                    },
                    key
            );

            return new TrustedRate(
                    key.getKey().toString(),
                    "EUR",
                    "USD",
                    new BigDecimal("1.1700"),
                    observed,
                    true,
                    true,
                    false,
                    true,
                    new BigDecimal("0.0002")
            );
        };
    }

    @Bean
    @Profile("oracle-local")
    RateGateway databaseRates(
            SharedRateReader reader
    ) {
        return reader::latest;
    }

    @Bean
    @Profile("demo")
    SettlementGateway unavailableSettlement() {

        return (
                id,
                customer,
                key
        ) -> {

            throw new QuoteException(
                    503,
                    "SETTLEMENT_UNAVAILABLE",
                    "M3 is not connected in this mode. No wallets have been changed."
            );
        };
    }

    record M1Response(
            Long snapshotId,
            String currencyPair,
            BigDecimal marketRate,
            OffsetDateTime receivedAt,
            long ageSeconds,
            boolean fresh,
            boolean quotingEnabled,
            String providerHealth,
            BigDecimal movementPercent,
            BigDecimal freshnessScore,
            BigDecimal anomalyScore,
            BigDecimal providerHealthScore,
            BigDecimal stabilityScore
    ) {
    }

    @Bean
    @Profile("team")
    RateGateway teamRates(
            SharedRateReader reader,
            QuotePolicy policy,

            @Value(
                    "${fxlock.rate-username:fxlock-service}"
            )
            String username,

            @Value(
                    "${fxlock.rate-password}"
            )
            String password
    ) {

        return () -> {

            try {

                var response =
                        client(
                                requiredUrl(
                                        policy.rateBaseUrl(),
                                        "RATE_BASE_URL"
                                )
                        )
                        .defaultHeaders(
                                headers ->
                                        headers.setBasicAuth(
                                                username,
                                                password
                                        )
                        )
                        .build()
                        .get()
                        .uri(
                                "/api/v1/rates/trusted"
                        )
                        .retrieve()
                        .body(
                                M1Response.class
                        );

                if (response == null
                        || response.snapshotId() == null
                        || !response.fresh()
                        || !response.quotingEnabled()
                        || !"EUR/USD".equals(
                                response.currencyPair()
                        )) {

                    throw new QuoteException(
                            503,
                            "RATE_UNAVAILABLE",
                            "M1 did not supply an enabled, fresh EUR/USD snapshot."
                    );
                }

                var stored =
                        reader.byId(
                                response.snapshotId()
                                        .toString()
                        );

                if (response.marketRate() == null
                        || response.marketRate()
                                .compareTo(
                                        stored.rate()
                                ) != 0) {

                    throw new QuoteException(
                            503,
                            "RATE_UNAVAILABLE",
                            "M1 rate does not match the common Oracle snapshot."
                    );
                }

                return new TrustedRate(
                        stored.snapshotId(),
                        "EUR",
                        "USD",
                        stored.rate(),
                        stored.observedAt(),
                        stored.trusted(),
                        response.quotingEnabled()
                                && stored.quotingEnabled(),
                        stored.unresolvedAnomaly(),
                        "UP".equals(
                                response.providerHealth()
                        ),
                        stored.relativeMovement(),

                        new ConfidenceInputs(
                                response.freshnessScore(),
                                response.anomalyScore(),
                                response.providerHealthScore(),
                                response.stabilityScore()
                        )
                );

            } catch (RestClientException e) {

                throw new QuoteException(
                        503,
                        "RATE_UNAVAILABLE",
                        "M1 could not supply a trusted rate."
                );
            }
        };
    }

    /*
     * Response returned by M3.
     *
     * Both directions are allowed:
     *
     * EUR -> USD
     * USD -> EUR
     */
    record M3Response(
            String settlementId,
            String quoteId,
            String status,
            String sourceCurrency,
            BigDecimal sourceAmount,
            String targetCurrency,
            BigDecimal targetAmount
    ) {
    }

    @Bean
    @Profile({
            "team",
            "oracle-local"
    })
    SettlementGateway teamSettlement(
            QuoteRepository repository,
            QuotePolicy policy,
            ObjectMapper json
    ) {

        return (
                id,
                customer,
                key
        ) -> {

            /*
             * Temporary training limitation.
             *
             * customer-demo maps to CUST001.
             *
             * Gyanant's login/customer integration can
             * replace this restriction later.
             */
            if (repository.customerId(customer)
                    != repository.customerId(
                            "CUST001"
                    )) {

                throw new QuoteException(
                        503,
                        "M3_IDENTITY_UNSUPPORTED",
                        "The current M3 local controller only supports CUST001."
                );
            }

            /*
             * Load the original quote before calling M3.
             *
             * This lets us validate that M3 returned
             * exactly the currencies and amounts that
             * were locked in the quote.
             */
            Quote quote =
                    repository
                            .owned(
                                    id,
                                    customer
                            )
                            .orElseThrow(
                                    () ->
                                            new QuoteException(
                                                    404,
                                                    "QUOTE_NOT_FOUND",
                                                    "Quote not found for this customer."
                                            )
                            );

            try {

                var result =
                        client(
                                requiredUrl(
                                        policy.settlementBaseUrl(),
                                        "SETTLEMENT_BASE_URL"
                                )
                        )
                        .build()
                        .post()
                        .uri(
                                "/api/v1/settlements"
                        )
                        .header(
                                "Idempotency-Key",
                                key
                        )
                        .body(
                                Map.of(
                                        "quoteId",
                                        id
                                )
                        )
                        .retrieve()
                        .body(
                                M3Response.class
                        );

                /*
                 * Generic settlement validation.
                 *
                 * Do NOT hardcode EUR -> USD.
                 *
                 * M3 must return exactly what the
                 * stored quote says.
                 */
                if (result == null
                        || !id.equals(
                                result.quoteId()
                        )
                        || result.settlementId() == null
                        || result.settlementId()
                                .isBlank()
                        || !"COMPLETED".equals(
                                result.status()
                        )
                        || !quote.sourceCurrency()
                                .equals(
                                        result.sourceCurrency()
                                )
                        || !quote.targetCurrency()
                                .equals(
                                        result.targetCurrency()
                                )
                        || result.sourceAmount() == null
                        || result.targetAmount() == null
                        || quote.sourceAmount()
                                .compareTo(
                                        result.sourceAmount()
                                ) != 0
                        || quote.targetAmount()
                                .compareTo(
                                        result.targetAmount()
                                ) != 0) {

                    throw new QuoteException(
                            502,
                            "SETTLEMENT_RESPONSE_INVALID",
                            "M3 returned a settlement response that does not match the stored quote."
                    );
                }

                return new SettlementResult(
                        result.settlementId(),
                        id,
                        result.status(),
                        result.sourceAmount(),
                        result.targetAmount(),

                        repository.settlementCompletedAt(
                                result.settlementId(),
                                id,
                                customer
                        )
                );

            } catch (
                    RestClientResponseException e
            ) {

                String code =
                        "SETTLEMENT_UNAVAILABLE";

                try {

                    String parsed =
                            json.readTree(
                                    e.getResponseBodyAsByteArray()
                            )
                            .path("code")
                            .asText();

                    if (!parsed.isBlank()) {
                        code = parsed;
                    }

                } catch (Exception ignored) {
                }

                throw new QuoteException(
                        e.getStatusCode()
                                .is4xxClientError()
                                        ? e.getStatusCode()
                                                .value()
                                        : 503,

                        code,

                        "Settlement was not confirmed. "
                                + "For an uncertain result, retry with the same key or check history."
                );

            } catch (
                    RestClientException e
            ) {

                throw new QuoteException(
                        503,
                        "SETTLEMENT_UNCERTAIN",
                        "M3 may have committed. Retry with the same key."
                );
            }
        };
    }

    private static String requiredUrl(
            String value,
            String setting
    ) {

        if (value == null
                || value.isBlank()) {

            throw new QuoteException(
                    503,
                    "SERVICE_UNAVAILABLE",
                    "Configure "
                            + setting
                            + " for the actual teammate service."
            );
        }

        return value;
    }

    private static RestClient.Builder client(
            String base
    ) {

        URI uri =
                URI.create(base);

        if (!"http".equals(
                uri.getScheme()
        )
                && !"https".equals(
                        uri.getScheme()
                )) {

            throw new IllegalArgumentException(
                    "Service URL must use HTTP or HTTPS"
            );
        }

        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient
                                .newBuilder()
                                .connectTimeout(
                                        Duration.ofSeconds(
                                                2
                                        )
                                )
                                .build()
                );

        factory.setReadTimeout(
                Duration.ofSeconds(4)
        );

        return RestClient
                .builder()
                .baseUrl(base)
                .requestFactory(
                        factory
                );
    }
}