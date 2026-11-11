package com.stockflow.order.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The warehouse the order is shipped from (SCRUM-423). How it travels is not chosen: every order ships as a full
 * truckload (kltn-docs 09 BR-08).
 *
 * @param deliveryBatchId 4B-8 (kltn-docs 17 BR-29): the delivery batch the caller means to release - it must be the
 *                        order's next one; null: the next one, whichever it is
 */
public record ReleaseOrderRequest(@NotNull UUID warehouseId, UUID deliveryBatchId) {

    /** Without a batch: the next one. */
    public ReleaseOrderRequest(UUID warehouseId) {
        this(warehouseId, null);
    }
}
