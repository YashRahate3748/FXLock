package com.ofss.fxlock.rate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fxlock.twelve-data")
public record TwelveDataProperties(
        String baseUrl,
        String apiKey,
        String symbol
) {
}