package com.fxlock.account;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/accounts")
public class LoginVerificationController {

    private final InternalServiceAuthenticator authenticator;
    private final LoginVerificationService verificationService;

    public LoginVerificationController(
            InternalServiceAuthenticator authenticator,
            LoginVerificationService verificationService
    ) {
        this.authenticator = authenticator;
        this.verificationService = verificationService;
    }

    @PostMapping("/verify")
    public ResponseEntity<LoginVerificationResponse> verify(
            @RequestHeader(
                    name = "X-Internal-Token",
                    required = false
            )
            String internalToken,

            @Valid
            @RequestBody
            LoginVerificationRequest request
    ) {
        authenticator.authenticate(internalToken);

        LoginVerificationResponse response =
                verificationService.verify(request);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}