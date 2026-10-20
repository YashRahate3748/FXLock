package com.ofss.fxlock.rate.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ofss.fxlock.rate.service.ProviderStateService;
import com.ofss.fxlock.rate.service.RateIngestionService;

@Component
public class RateFetchScheduler {

    private final RateIngestionService
            rateIngestionService;

    private final ProviderStateService
            providerStateService;

    public RateFetchScheduler(
            RateIngestionService rateIngestionService,
            ProviderStateService providerStateService
    ) {

        this.rateIngestionService =
                rateIngestionService;

        this.providerStateService =
                providerStateService;
    }

    @Scheduled(
            fixedDelayString =
                    "${fxlock.rate.scheduler-delay-ms:15000}"
    )
    public void fetchRate() {

        if (!providerStateService
                .canFetchNow()) {

            return;
        }

        rateIngestionService
                .fetchAndProcess();
    }
}