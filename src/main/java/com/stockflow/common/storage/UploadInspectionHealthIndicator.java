package com.stockflow.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether clamd answers, so a dead scanner shows on the dashboard before the first upload
 * fails on it.
 *
 * <p>Not part of readiness, for the reason {@link ObjectStorageHealthIndicator} gives: uploads fail
 * closed without the scanner, and taking every instance out of the load balancer because of that
 * would turn a degraded feature into a total outage. With scanning switched off the indicator says
 * so ({@code UNKNOWN}, {@code enabled=false}) instead of reporting a scanner it never asks.</p>
 */
@Component
class UploadInspectionHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(UploadInspectionHealthIndicator.class);

    private final UploadInspection inspection;

    UploadInspectionHealthIndicator(UploadInspection inspection) {
        this.inspection = inspection;
    }

    @Override
    public Health health() {
        if (!inspection.enabled()) {
            return Health.unknown().withDetail("enabled", false).build();
        }
        long startedAt = System.nanoTime();
        try {
            inspection.ping();
            return Health.up().withDetail("enabled", true)
                    .withDetail("responseTimeMs", (System.nanoTime() - startedAt) / 1_000_000).build();
        } catch (RuntimeException ex) {
            log.warn("Upload inspection health check failed: {}", ex.toString());
            // The type only: the message names the scanner's host and port.
            return Health.down().withDetail("enabled", true).withDetail("error", ex.getClass().getSimpleName()).build();
        }
    }
}
