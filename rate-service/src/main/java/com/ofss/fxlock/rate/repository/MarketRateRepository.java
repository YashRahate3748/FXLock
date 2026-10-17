package com.ofss.fxlock.rate.repository;

import com.ofss.fxlock.rate.entity.MarketRateSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MarketRateRepository
        extends JpaRepository<MarketRateSnapshot, Long> {

    Optional<MarketRateSnapshot>
    findFirstByCurrencyPairAndTrustedFlagOrderByReceivedAtDesc(
            String currencyPair,
            String trustedFlag
    );
}