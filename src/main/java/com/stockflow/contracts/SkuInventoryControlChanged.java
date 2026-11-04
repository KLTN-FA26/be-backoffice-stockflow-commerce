package com.stockflow.contracts;

import java.time.Instant;

/**
 * Re-evaluate immediately after commit; SCRUM-147 owns delivery/deduplication of alerts.
 * The consumer re-reads current policy and stock: an older event must not restore stale settings.
 * Scheduled checks are reconciliation, not the primary trigger for configuration changes.
 */
public record SkuInventoryControlChanged(String sku, long revision, Instant occurredAt) {}
