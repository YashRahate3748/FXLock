package com.fxlock.settlement;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fxlock.audit.FailureAuditService;

@Service
public class SettlementCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(SettlementCoordinator.class);

    private final SettlementService settlementService;
    private final FailureAuditService failureAuditService;

    public SettlementCoordinator(
            SettlementService settlementService,
            FailureAuditService failureAuditService
    ) {
        this.settlementService = settlementService;
        this.failureAuditService = failureAuditService;
    }

    // Deliberately not transactional.
    public SettlementResponse settle(
            String quoteId,
            String customerRef,
            String idempotencyKey
    ) {
        String correlationId = UUID.randomUUID().toString();

        try {
            return settlementService.settle(
                    quoteId,
                    customerRef,
                    idempotencyKey,
                    correlationId
            );

        } catch (RuntimeException exception) {
            boolean rejected = false;
            String reasonCode = "SETTLEMENT_ERROR";

            if (exception instanceof ResponseStatusException responseError) {
                rejected = responseError.getStatusCode().is4xxClientError();

                if (rejected && responseError.getReason() != null) {
                    reasonCode = responseError.getReason();
                }
            }

            try {
                failureAuditService.recordAttempt(
                        quoteId,
                        customerRef,
                        reasonCode,
                        rejected,
                        correlationId
                );
            } catch (RuntimeException auditException) {
                // Preserve the original settlement error.
                log.error(
                        "Could not record settlement attempt. Correlation ID: {}",
                        correlationId,
                        auditException
                );
            }

            throw exception;
        }
    }
}