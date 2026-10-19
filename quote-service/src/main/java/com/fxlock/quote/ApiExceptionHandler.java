package com.fxlock.quote;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import static com.fxlock.quote.Models.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(QuoteException.class) ResponseEntity<ApiError> domain(QuoteException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(),e.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT","Use EUR to USD, an amount with at most two decimal places, and two wallet IDs."));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingRequestHeaderException.class}) ResponseEntity<ApiError> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST","Check the request format and required headers."));
    }
}
