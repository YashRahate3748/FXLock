package com.fxlock.integration;

import com.fxlock.settlement.SettlementCoordinator;
import com.fxlock.settlement.SettlementDetailsResponse;
import com.fxlock.settlement.SettlementDetailsService;
import com.fxlock.settlement.SettlementRequest;
import com.fxlock.settlement.SettlementResponse;
import com.fxlock.settlement.SettlementService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Trusted M2 calls only. Financial operations remain in the existing coordinator/services. */
@RestController
@Profile("integration")
@RequestMapping("/api/v1/settlements")
public class IntegrationSettlementController {
    private final InternalCustomerResolver customers;
    private final SettlementCoordinator coordinator;
    private final SettlementService settlements;
    private final SettlementDetailsService details;

    public IntegrationSettlementController(InternalCustomerResolver customers, SettlementCoordinator coordinator,
                                          SettlementService settlements, SettlementDetailsService details) {
        this.customers = customers;
        this.coordinator = coordinator;
        this.settlements = settlements;
        this.details = details;
    }

    @PostMapping
    public ResponseEntity<SettlementResponse> settle(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Customer-Ref", required = false) String customer,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SettlementRequest request) {
        String resolved = customers.resolve(token, customer);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(coordinator.settle(request.quoteId(), resolved, key));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SettlementResponse> settlement(@PathVariable String id,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Customer-Ref", required = false) String customer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(settlements.getSettlement(id, customers.resolve(token, customer)));
    }

    @GetMapping("/{id}/details")
    public ResponseEntity<SettlementDetailsResponse> details(@PathVariable String id,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Customer-Ref", required = false) String customer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(details.getDetails(id, customers.resolve(token, customer)));
    }
}
