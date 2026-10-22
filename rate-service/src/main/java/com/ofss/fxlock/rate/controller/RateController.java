package com.ofss.fxlock.rate.controller;


import com.ofss.fxlock.rate.dto.TrustedRateResponse;
import com.ofss.fxlock.rate.service.RateQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rates")
public class RateController {

    private final RateQueryService rateQueryService;

    public RateController(
            RateQueryService rateQueryService
    ) {
        this.rateQueryService =
                rateQueryService;
    }

    @GetMapping("/trusted")
    public ResponseEntity<TrustedRateResponse>
    getTrustedRate() {

        return ResponseEntity.ok(
                rateQueryService.getTrustedRate()
        );
    }
}