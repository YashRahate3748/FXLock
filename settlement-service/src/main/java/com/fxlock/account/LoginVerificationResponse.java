package com.fxlock.account;

public record LoginVerificationResponse(
        String customerRef,
        String fullName,
        String email
) {
}