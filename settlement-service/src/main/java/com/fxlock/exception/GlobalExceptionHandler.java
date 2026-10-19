package com.fxlock.exception;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleBusinessError(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        String code = exception.getReason();

        if (code == null) {
            code = "REQUEST_REJECTED";
        }

        String message = switch (code) {
            case "QUOTE_EXPIRED" ->
                    "This quote has expired. Request a new quote.";

            case "INSUFFICIENT_FUNDS" ->
                    "Your source wallet has insufficient funds.";

            case "QUOTE_NOT_OPEN" ->
                    "This quote is no longer open for acceptance.";

            case "QUOTE_NOT_FOUND" ->
                    "The requested quote was not found.";

            case "QUOTE_NOT_OWNED" ->
                    "This quote does not belong to the current customer.";

            case "CUSTOMER_NOT_ACTIVE" ->
                    "The current customer is unavailable or inactive.";

            case "WALLET_NOT_ACTIVE" ->
                    "One of the wallets is unavailable for conversion.";

            case "WALLET_NOT_OWNED" ->
                    "A wallet does not belong to the current customer.";

            case "WALLET_CURRENCY_MISMATCH" ->
                    "The quote currencies do not match its wallets.";

            case "QUOTE_WALLET_NOT_FOUND",
                 "QUOTE_WALLETS_INVALID" ->
                    "The quote references invalid or unavailable wallets.";

            case "QUOTE_CURRENCIES_INVALID",
                 "QUOTE_AMOUNTS_INVALID" ->
                    "The quote contains invalid conversion details.";

            case "IDEMPOTENCY_CONFLICT" ->
                    "This retry key was already used for another request.";

            case "INVALID_IDEMPOTENCY_KEY" ->
                    "A valid acceptance retry key is required.";

            case "IDEMPOTENCY_RESULT_UNAVAILABLE" ->
                    "The saved acceptance result is unavailable.";

            case "SETTLEMENT_NOT_FOUND" ->
                    "Settlement not found for the current customer.";

            default -> "The request could not be completed.";
        };

        return respond(
                exception.getStatusCode().value(),
                code,
                message,
                request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        return respond(
                400,
                "INVALID_REQUEST",
                "Check the required request fields and their lengths.",
                request
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        return respond(
                400,
                "MISSING_HEADER",
                "A required request header is missing.",
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleInvalidJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return respond(
                400,
                "INVALID_JSON",
                "The request body must contain valid JSON.",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedError(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unhandled API error for {}",
                request.getRequestURI(),
                exception
        );

        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "The request could not be confirmed. " +
                "For acceptance, retry using the original key.",
                request
        );
    }

    private ResponseEntity<ApiError> respond(
            int status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(
                new ApiError(
                        Instant.now(),
                        status,
                        code,
                        message,
                        request.getRequestURI()
                )
        );
    }
}