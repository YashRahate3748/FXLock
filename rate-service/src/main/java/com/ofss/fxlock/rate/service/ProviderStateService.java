package com.ofss.fxlock.rate.service;

import com.ofss.fxlock.rate.enums.ProviderHealth;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ProviderStateService {

    private final AtomicReference<ProviderHealth> health =
            new AtomicReference<>(ProviderHealth.DOWN);

    private volatile OffsetDateTime lastSuccess;
    private volatile OffsetDateTime lastFailure;

    public void markSuccess() {
        health.set(ProviderHealth.UP);
        lastSuccess = OffsetDateTime.now();
    }

    public void markFailure() {

        if (lastSuccess == null) {
            health.set(ProviderHealth.DOWN);
        } else {
            health.set(ProviderHealth.DEGRADED);
        }

        lastFailure = OffsetDateTime.now();
    }

    public ProviderHealth getHealth() {
        return health.get();
    }

    public OffsetDateTime getLastSuccess() {
        return lastSuccess;
    }

    public OffsetDateTime getLastFailure() {
        return lastFailure;
    }
}