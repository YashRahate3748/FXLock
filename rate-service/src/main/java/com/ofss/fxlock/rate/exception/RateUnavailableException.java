package com.ofss.fxlock.rate.exception;

public class RateUnavailableException
        extends RuntimeException {

    public RateUnavailableException(
            String message
    ) {
        super(message);
    }
}