package com.ofss.fxlock.rate.service;

import org.springframework.stereotype.Service;

import com.ofss.fxlock.rate.repository.MarketRateRepository;

import jakarta.annotation.PostConstruct;

@Service
public class CacheRestoreService {

    private final MarketRateRepository
            rateRepository;

    private final TrustedRateCache cache;

    public CacheRestoreService(
            MarketRateRepository rateRepository,
            TrustedRateCache cache
    ) {

        this.rateRepository =
                rateRepository;

        this.cache = cache;
    }

    @PostConstruct
    public void restoreTrustedRate() {
        rateRepository
                .findFirstByCurrencyPairAndProviderNameAndTrustedFlagAndValidationStatusOrderByProviderTimestampDesc(
                        "EUR/USD",
                        "TWELVE_DATA",
                        "Y",
                        "TRUSTED"
                )
                .ifPresent(cache::update);
    }
}