package com.ofss.fxlock.rate;

import com.ofss.fxlock.rate.service.RateValidationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class RateValidationServiceTest {

    private final RateValidationService service =
            new RateValidationService();

    @Test
    void movementShouldBeCalculatedCorrectly() {

        BigDecimal result =
                service.calculateMovementPercent(
                        new BigDecimal("1.1700"),
                        new BigDecimal("1.17585")
                );

        assertEquals(
                0,
                result.compareTo(
                    new BigDecimal("0.50000000")
                )
        );
    }

    @Test
    void identicalRateHasZeroMovement() {

        BigDecimal result =
                service.calculateMovementPercent(
                        new BigDecimal("1.1700"),
                        new BigDecimal("1.1700")
                );

        assertEquals(
                BigDecimal.ZERO
                    .setScale(8),
                result
        );
    }
}