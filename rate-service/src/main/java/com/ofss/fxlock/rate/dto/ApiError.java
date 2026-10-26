package com.ofss.fxlock.rate.dto;

import java.time.OffsetDateTime;

public record ApiError(

        OffsetDateTime timestamp,

        String service,

        String errorCode,

        String message,

        String path

) {
}