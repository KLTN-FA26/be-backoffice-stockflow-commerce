package com.stockflow.inventory.internal.controller.dto;
import java.time.Instant;
import java.util.UUID;
public record StockAlertResponse(UUID id,String sku,String kind,String status,long quantity,int threshold,
                                 Instant openedAt,Instant lastObservedAt,Instant resolvedAt,String resolutionReason,
                                 UUID acknowledgedBy,Instant acknowledgedAt) {}
