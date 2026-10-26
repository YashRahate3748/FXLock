package com.fxlock.account;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/accounts")
public class RegistrationController {

    private final RegistrationService registrationService;
    private final InternalServiceAuthenticator authenticator;

    public RegistrationController(
            RegistrationService registrationService,
            InternalServiceAuthenticator authenticator
    ) {
        this.registrationService = registrationService;
        this.authenticator = authenticator;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationResponse> register(
            @RequestHeader(
                    name = "X-Internal-Token",
                    required = false
            )
            String internalToken,
            @Valid @RequestBody RegistrationRequest request
    ) {
        authenticator.authenticate(internalToken);

        RegistrationResponse response =
                registrationService.register(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}