package com.ofss.fxlock.rate.scheduler;

import com.ofss.fxlock.rate.service.RateIngestionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RateFetchScheduler {

    private final RateIngestionService rateIngestionService;

    public RateFetchScheduler(
            RateIngestionService rateIngestionService
    ) {
        this.rateIngestionService =
                rateIngestionService;
    }

    @Scheduled(
        fixedDelayString =
        "${fxlock.rate.scheduler-delay-ms:15000}"
    )
    public void fetchRate() {

        rateIngestionService.fetchAndProcess();
    }
}