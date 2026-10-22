package com.ofss.fxlock.rate.controller;

import com.ofss.fxlock.rate.dto.QuotingStatusResponse;
import com.ofss.fxlock.rate.dto.RiskReviewRequest;
import com.ofss.fxlock.rate.service.RiskControlService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {

    private final RiskControlService riskControlService;

    public RiskController(
            RiskControlService riskControlService
    ) {
        this.riskControlService =
                riskControlService;
    }

    @PostMapping(
        "/anomalies/{anomalyId}/approve"
    )
    public ResponseEntity<Void> approve(
            @PathVariable Long anomalyId,
            @Valid @RequestBody
            RiskReviewRequest request,
            Authentication authentication
    ) {

        riskControlService.approveAnomaly(
                anomalyId,
                request,
                authentication
        );

        return ResponseEntity.noContent().build();
    }

    @PostMapping(
        "/anomalies/{anomalyId}/reject"
    )
    public ResponseEntity<Void> reject(
            @PathVariable Long anomalyId,
            @Valid @RequestBody
            RiskReviewRequest request,
            Authentication authentication
    ) {

        riskControlService.rejectAnomaly(
                anomalyId,
                request,
                authentication
        );

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/quoting/enable")
    public ResponseEntity<QuotingStatusResponse>
    enableQuoting(
            Authentication authentication
    ) {

        return ResponseEntity.ok(
                riskControlService.setQuoting(
                        true,
                        authentication
                )
        );
    }

    @PostMapping("/quoting/disable")
    public ResponseEntity<QuotingStatusResponse>
    disableQuoting(
            Authentication authentication
    ) {

        return ResponseEntity.ok(
                riskControlService.setQuoting(
                        false,
                        authentication
                )
        );
    }
}