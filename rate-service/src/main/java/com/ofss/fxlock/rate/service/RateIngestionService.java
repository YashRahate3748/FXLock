package com.ofss.fxlock.rate.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.ofss.fxlock.rate.client.TwelveDataClient;
import com.ofss.fxlock.rate.config.TwelveDataProperties;
import com.ofss.fxlock.rate.dto.TwelveDataResponse;
import com.ofss.fxlock.rate.enums.ProviderFailureType;

@Service
public class RateIngestionService {

    private final TwelveDataClient twelveDataClient;

    private final RatePersistenceService persistenceService;

    private final ProviderStateService providerStateService;

    private final ProviderFailurePersistenceService
            failurePersistenceService;

    private final TwelveDataProperties properties;

    public RateIngestionService(
            TwelveDataClient twelveDataClient,
            RatePersistenceService persistenceService,
            ProviderStateService providerStateService,
            ProviderFailurePersistenceService
                    failurePersistenceService,
            TwelveDataProperties properties
    ) {

        this.twelveDataClient = twelveDataClient;
        this.persistenceService = persistenceService;
        this.providerStateService =
                providerStateService;
        this.failurePersistenceService =
                failurePersistenceService;
        this.properties = properties;
    }

    public void fetchAndProcess() {

        try {

            TwelveDataResponse response =
                    twelveDataClient.fetchRate();

            persistenceService
                    .processProviderResponse(
                            response
                    );

            providerStateService
                    .markSuccess();

        } catch (
                HttpClientErrorException
                        .TooManyRequests ex
        ) {

            providerStateService
                    .markRateLimited(
                            properties
                                    .rateLimitBackoff()
                    );

            failurePersistenceService
                    .recordProviderFailure(
                            ProviderFailureType
                                    .RATE_LIMITED,
                            ex.getMessage()
                    );

        } catch (
                ResourceAccessException ex
        ) {

            providerStateService
                    .markFailure(
                            ex.getMessage()
                    );

            failurePersistenceService
                    .recordProviderFailure(
                            ProviderFailureType
                                    .TIMEOUT_OR_NETWORK,
                            ex.getMessage()
                    );

        } catch (
                RestClientResponseException ex
        ) {

            providerStateService
                    .markFailure(
                            ex.getMessage()
                    );

            failurePersistenceService
                    .recordProviderFailure(
                            ProviderFailureType
                                    .HTTP_ERROR,
                            ex.getMessage()
                    );

        } catch (
                IllegalArgumentException ex
        ) {

            providerStateService
                    .markFailure(
                            ex.getMessage()
                    );

            failurePersistenceService
                    .recordProviderFailure(
                            ProviderFailureType
                                    .INVALID_RESPONSE,
                            ex.getMessage()
                    );

        } catch (Exception ex) {

            providerStateService
                    .markFailure(
                            ex.getMessage()
                    );

            failurePersistenceService
                    .recordProviderFailure(
                            ProviderFailureType
                                    .UNKNOWN,
                            ex.getMessage()
                    );
        }
    }
}