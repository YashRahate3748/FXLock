package com.ofss.fxlock.rate.controller;

import com.ofss.fxlock.rate.dto.MonitoringResponse.*;
import com.ofss.fxlock.rate.service.MonitoringReadService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1")
public class MonitoringReadController {

    private final MonitoringReadService service;

    public MonitoringReadController(MonitoringReadService service) {
        this.service = service;
    }

    @GetMapping("/rates/status")
    public Status getStatus() {
        return service.getStatus();
    }

    @GetMapping("/rates/history")
    public History getHistory(
            @RequestParam("from")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime from,

            @RequestParam("to")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime to
    ) {
        return service.getHistory(from, to);
    }

    @GetMapping("/risk/anomalies")
    public AnomalyPage getAnomalies(
            @RequestParam(value = "status", required = false)
            String status,

            @RequestParam(value = "page", defaultValue = "0")
            int page,

            @RequestParam(value = "size", defaultValue = "20")
            int size
    ) {
        return service.getAnomalies(status, page, size);
    }

    @GetMapping("/risk/anomalies/{id}")
    public Anomaly getAnomaly(@PathVariable("id") Long id) {
        return service.getAnomaly(id);
    }
}