package com.ofss.fxlock.rate.controller;

import com.ofss.fxlock.rate.dto.TestRateRequest;
import com.ofss.fxlock.rate.dto.TwelveDataResponse;
import com.ofss.fxlock.rate.service.RatePersistenceService;

import jakarta.validation.Valid;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/test")
//@Profile("dev")
public class TestRateController {

    private final RatePersistenceService ratePersistenceService;

    public TestRateController(
            RatePersistenceService ratePersistenceService
    ) {
        this.ratePersistenceService =
                ratePersistenceService;
    }

    @PostMapping("/rates")
    public ResponseEntity<Void> injectRate(
            @Valid @RequestBody
            TestRateRequest request
    ) {

        TwelveDataResponse fakeResponse =
                new TwelveDataResponse(
                        "EUR/USD",
                        request.rate().toPlainString(),
                        Instant.now().getEpochSecond(),
                        null,
                        null,
                        null
                );

        ratePersistenceService
                .processProviderResponse(fakeResponse);

        return ResponseEntity.noContent().build();
    }
}