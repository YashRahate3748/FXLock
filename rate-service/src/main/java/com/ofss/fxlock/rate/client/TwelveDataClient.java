package com.ofss.fxlock.rate.client;

import java.net.http.HttpClient;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.ofss.fxlock.rate.config.TwelveDataProperties;
import com.ofss.fxlock.rate.dto.TwelveDataResponse;

@Component
public class TwelveDataClient {

    private final RestClient restClient;

    private final TwelveDataProperties properties;

    public TwelveDataClient(
            RestClient.Builder builder,
            TwelveDataProperties properties
    ) {

        this.properties = properties;

        HttpClient httpClient =
                HttpClient
                        .newBuilder()
                        .connectTimeout(
                                properties.connectTimeout()
                        )
                        .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(
                        httpClient
                );

        requestFactory.setReadTimeout(
                properties.readTimeout()
        );

        this.restClient =
                builder
                        .baseUrl(
                                properties.baseUrl()
                        )
                        .requestFactory(
                                requestFactory
                        )
                        .build();
    }

    public TwelveDataResponse fetchRate() {

        return restClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path(
                                                "/exchange_rate"
                                        )
                                        .queryParam(
                                                "symbol",
                                                properties.symbol()
                                        )
                                        .build()
                )
                .header(
                        "Authorization",
                        "apikey "
                                + properties.apiKey()
                )
                .retrieve()
                .body(
                        TwelveDataResponse.class
                );
    }
}