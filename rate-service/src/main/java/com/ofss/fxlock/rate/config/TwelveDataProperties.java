// src/main/java/com/ofss/fxlock/rate/config/TwelveDataProperties.java

package com.ofss.fxlock.rate.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fxlock.twelve-data")
public record TwelveDataProperties(

        String baseUrl,

        String apiKey,

        String symbol,

        Duration connectTimeout,

        Duration readTimeout,

        Duration rateLimitBackoff

) {
}