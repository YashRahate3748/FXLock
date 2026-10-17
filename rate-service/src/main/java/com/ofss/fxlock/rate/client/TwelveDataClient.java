package com.ofss.fxlock.rate.client;

import com.ofss.fxlock.rate.config.TwelveDataProperties;
import com.ofss.fxlock.rate.dto.TwelveDataResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TwelveDataClient {

    private final RestClient restClient;
    private final TwelveDataProperties properties;

    public TwelveDataClient(
            RestClient.Builder builder,
            TwelveDataProperties properties
    ) {
        this.properties = properties;

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .build();
    }

    public TwelveDataResponse fetchRate() {

        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/exchange_rate")
                        .queryParam("symbol", properties.symbol())
                        .build())
                .header("Authorization", "apikey " + properties.apiKey())
                .retrieve()
                .body(TwelveDataResponse.class);
    }
}