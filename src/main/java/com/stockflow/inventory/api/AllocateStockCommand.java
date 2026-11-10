package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Intent: the order was released to a warehouse; turn its SKU reservations into allocations on pickable bins of
 * that warehouse (docs 04c F-FUL step 02, BR-STK-006). One order is fulfilled from one warehouse (OQ-04).
 *
 * @param warehouseCode  the warehouse's prefix ({@code HCM}), the first part of every location code in it
 * @param reservationIds 4B-8 (kltn-docs 17 BR-29): only these SKU reservations of the order - one delivery batch's -
 *                       are allocated; null: every one the order holds
 */
public record AllocateStockCommand(UUID orderId, String warehouseCode, java.util.Set<UUID> reservationIds) {

    public AllocateStockCommand {
        Objects.requireNonNull(orderId, "orderId");
        if (warehouseCode == null || warehouseCode.isBlank()) {
            throw new IllegalArgumentException("warehouseCode is required");
        }
        reservationIds = reservationIds == null ? null : java.util.Set.copyOf(reservationIds);
    }

    /** Every SKU reservation the order holds. */
    public AllocateStockCommand(UUID orderId, String warehouseCode) {
        this(orderId, warehouseCode, null);
    }

    /** Whether {@code reservationId} is one of those to allocate. */
    public boolean includes(UUID reservationId) {
        return reservationIds == null || reservationIds.contains(reservationId);
    }
}
