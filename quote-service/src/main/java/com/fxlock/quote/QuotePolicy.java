package com.fxlock.quote;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Runtime connection settings only. Pricing and limits come from FX_CONFIG. */
@Validated
@ConfigurationProperties("fxlock")
public record QuotePolicy(@NotBlank String demoPassword,String rateBaseUrl,String settlementBaseUrl) {}
