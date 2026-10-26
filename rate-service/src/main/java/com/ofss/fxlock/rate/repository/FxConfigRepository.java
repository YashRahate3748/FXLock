package com.ofss.fxlock.rate.repository;

import com.ofss.fxlock.rate.entity.FxConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FxConfigRepository
        extends JpaRepository<FxConfig, Long> {

    Optional<FxConfig> findByCurrencyPair(String currencyPair);
}