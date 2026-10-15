package com.fxlock.settlement;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import jakarta.validation.Valid;

@RestController
@Profile("local")
@RequestMapping("/api/v1/settlements")
public class SettlementController {

	private final SettlementCoordinator settlementCoordinator;
	private final SettlementService settlementService;

	public SettlementController(
	        SettlementCoordinator settlementCoordinator,
	        SettlementService settlementService
	) {
	    this.settlementCoordinator = settlementCoordinator;
	    this.settlementService = settlementService;
	}
	
	@GetMapping("/{settlementId}")
	public SettlementResponse getSettlement(
	        @PathVariable("settlementId") String settlementId
	) {
	    // Temporary simulated customer for local development.
	    return settlementService.getSettlement(
	            settlementId,
	            "CUST001"
	    );
	}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementResponse settle(
            @Valid @RequestBody SettlementRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return settlementCoordinator.settle(
                request.quoteId(),
                "CUST001",
                idempotencyKey
        );
    }
}