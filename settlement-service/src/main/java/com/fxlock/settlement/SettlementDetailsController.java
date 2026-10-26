package com.fxlock.settlement;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("local")
@RequestMapping("/api/v1/settlements")
public class SettlementDetailsController {

    private final SettlementDetailsService detailsService;

    public SettlementDetailsController(
            SettlementDetailsService detailsService) {

        this.detailsService = detailsService;
    }

    @GetMapping("/{settlementId}/details")
    public ResponseEntity<SettlementDetailsResponse> getDetails(
            @PathVariable String settlementId) {

        SettlementDetailsResponse details =
                detailsService.getDetails(
                        settlementId,
                        "CUST001");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(details);
    }
}