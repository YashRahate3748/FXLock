package com.ofss.fxlock.rate.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ofss.fxlock.rate.entity.MarketRateSnapshot;

public interface MarketRateRepository extends JpaRepository<MarketRateSnapshot,Long> {
    Optional<MarketRateSnapshot>
    findFirstByCurrencyPairAndTrustedFlagOrderByReceivedAtDesc(
            String currencyPair,
            String trustedFlag
    );
    
    Optional<MarketRateSnapshot>
    findFirstByCurrencyPairAndProviderNameAndTrustedFlagAndValidationStatusOrderByProviderTimestampDesc(
            String currencyPair,
            String providerName,
            String trustedFlag,
            String validationStatus
    );
}