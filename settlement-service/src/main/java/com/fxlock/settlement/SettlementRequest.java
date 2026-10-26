package com.fxlock.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SettlementRequest(

        @NotBlank(message = "quoteId is required")
        @Size(max = 36, message = "quoteId must not exceed 36 characters")
        String quoteId

) {}