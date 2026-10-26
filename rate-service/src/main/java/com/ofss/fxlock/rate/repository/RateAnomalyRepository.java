package com.ofss.fxlock.rate.repository;

import com.ofss.fxlock.rate.entity.RateAnomaly;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateAnomalyRepository
        extends JpaRepository<RateAnomaly, Long> {
}