package com.stockflow.inventory.internal.domain;
import java.time.Instant;
import java.util.UUID;
/** Persisted alert episode. Acknowledgement does not resolve a still-breached threshold. */
public record StockAlert(UUID id,String sku,String kind,String status,long quantity,int threshold,
                         Instant openedAt,Instant lastObservedAt,Instant resolvedAt,String resolutionReason,
                         UUID acknowledgedBy,Instant acknowledgedAt) {}
