package com.ofss.fxlock.rate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TwelveDataResponse(
        String symbol,
        String rate,
        Long timestamp,
        String code,
        String message,
        String status
) {
}