package com.ofss.fxlock.rate.service;

import com.ofss.fxlock.rate.entity.MarketRateSnapshot;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TrustedRateCache {

    private final AtomicReference<MarketRateSnapshot> current =
            new AtomicReference<>();

    public Optional<MarketRateSnapshot> get() {
        return Optional.ofNullable(current.get());
    }

    public void update(MarketRateSnapshot snapshot) {
        current.set(snapshot);
    }

    public void clear() {
        current.set(null);
    }
}