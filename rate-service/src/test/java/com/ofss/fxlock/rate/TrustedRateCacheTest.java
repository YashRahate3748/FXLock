package com.ofss.fxlock.rate;

import com.ofss.fxlock.rate.entity.MarketRateSnapshot;
import com.ofss.fxlock.rate.service.TrustedRateCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrustedRateCacheTest {

    @Test
    void cacheStoresTrustedSnapshot() {

        TrustedRateCache cache =
                new TrustedRateCache();

        MarketRateSnapshot snapshot =
                new MarketRateSnapshot();

        cache.update(snapshot);

        assertTrue(
                cache.get().isPresent()
        );

        assertSame(
                snapshot,
                cache.get().orElseThrow()
        );
    }
}