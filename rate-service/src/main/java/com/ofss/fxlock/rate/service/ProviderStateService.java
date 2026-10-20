package com.ofss.fxlock.rate.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.ofss.fxlock.rate.enums.ProviderHealth;

@Service
public class ProviderStateService {

    private final AtomicReference<ProviderHealth> health =
            new AtomicReference<>(
                    ProviderHealth.DOWN
            );

    private volatile OffsetDateTime lastSuccess;

    private volatile OffsetDateTime lastFailure;

    private volatile OffsetDateTime nextAllowedFetch;

    private volatile String lastError;

    public void markSuccess() {

        health.set(
                ProviderHealth.UP
        );

        lastSuccess =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        nextAllowedFetch = null;

        lastError = null;
    }

    public void markFailure(
            String message
    ) {

        if (lastSuccess == null) {

            health.set(
                    ProviderHealth.DOWN
            );

        } else {

            health.set(
                    ProviderHealth.DEGRADED
            );
        }

        lastFailure =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        lastError = message;
    }

    public void markRateLimited(
            Duration backoff
    ) {

        health.set(
                ProviderHealth.DEGRADED
        );

        lastFailure =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        lastError =
                "External provider rate limit reached";

        nextAllowedFetch =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                ).plus(backoff);
    }

    public boolean canFetchNow() {

        if (nextAllowedFetch == null) {
            return true;
        }

        OffsetDateTime now =
                OffsetDateTime.now(
                        ZoneOffset.UTC
                );

        return !now.isBefore(
                nextAllowedFetch
        );
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

    public OffsetDateTime getNextAllowedFetch() {
        return nextAllowedFetch;
    }

    public String getLastError() {
        return lastError;
    }
}