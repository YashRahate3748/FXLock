package com.ofss.fxlock.rate.dto;

import jakarta.validation.constraints.NotBlank;

public record RiskReviewRequest(

        @NotBlank
        String reason

) {
}