package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.UUID;

/**
 * Goods a goods receipt just counted in for a production order — a print subcontractor's finished goods
 * (SCRUM-441, decision Q8) — held for that production order at once, on the INBOUND layer the receipt created
 * ({@code locationCode}, {@code lotNumber}, {@code receivedAt} name it). Joins the receipt's transaction: the goods
 * are never free between being counted and being held. Idempotent on {@code requestId}.
 */
public record HoldReceivedStockCommand(UUID requestId, UUID productionOrderId, Sku sku, String lotNumber,
                                       String locationCode, Instant receivedAt, int quantity) {

    public HoldReceivedStockCommand {
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (productionOrderId == null) throw new IllegalArgumentException("productionOrderId is required");
        if (sku == null) throw new IllegalArgumentException("sku is required");
        if (locationCode == null || locationCode.isBlank()) throw new IllegalArgumentException("locationCode is required");
        if (receivedAt == null) throw new IllegalArgumentException("receivedAt is required");
        if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
    }
}
