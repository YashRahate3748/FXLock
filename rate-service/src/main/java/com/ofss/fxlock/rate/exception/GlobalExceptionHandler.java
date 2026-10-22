package com.ofss.fxlock.rate.exception;

import com.ofss.fxlock.rate.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(
        RateUnavailableException.class
    )
    public ResponseEntity<ApiError>
    handleRateUnavailable(
            RateUnavailableException ex,
            HttpServletRequest request
    ) {

        return ResponseEntity
                .status(
                    HttpStatus.SERVICE_UNAVAILABLE
                )
                .body(
                    error(
                        "RATE_UNAVAILABLE",
                        ex.getMessage(),
                        request
                    )
                );
    }

    @ExceptionHandler(
        ResourceNotFoundException.class
    )
    public ResponseEntity<ApiError>
    handleNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request
    ) {

        return ResponseEntity
                .status(
                    HttpStatus.NOT_FOUND
                )
                .body(
                    error(
                        "RESOURCE_NOT_FOUND",
                        ex.getMessage(),
                        request
                    )
                );
    }

    @ExceptionHandler(
        IllegalArgumentException.class
    )
    public ResponseEntity<ApiError>
    handleBadRequest(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {

        return ResponseEntity
                .badRequest()
                .body(
                    error(
                        "INVALID_REQUEST",
                        ex.getMessage(),
                        request
                    )
                );
    }

    @ExceptionHandler(
        IllegalStateException.class
    )
    public ResponseEntity<ApiError>
    handleConflict(
            IllegalStateException ex,
            HttpServletRequest request
    ) {

        return ResponseEntity
                .status(
                    HttpStatus.CONFLICT
                )
                .body(
                    error(
                        "INVALID_STATE",
                        ex.getMessage(),
                        request
                    )
                );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError>
    handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {

        return ResponseEntity
                .status(
                    HttpStatus.INTERNAL_SERVER_ERROR
                )
                .body(
                    error(
                        "INTERNAL_ERROR",
                        "Unexpected server error",
                        request
                    )
                );
    }

    private ApiError error(
            String code,
            String message,
            HttpServletRequest request
    ) {

        return new ApiError(
                OffsetDateTime.now(
                    ZoneOffset.UTC
                ),
                "rate-service",
                code,
                message,
                request.getRequestURI()
        );
    }
}