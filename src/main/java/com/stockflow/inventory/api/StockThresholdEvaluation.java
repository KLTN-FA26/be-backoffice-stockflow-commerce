package com.stockflow.inventory.api;

/** Usable on-hand, not ATP: reservations do not subtract from physical replenishment thresholds. */
public record StockThresholdEvaluation(long usableOnHand, Integer reorderPoint, Integer safetyStock,
                                       Boolean reorderRequired, Boolean belowSafetyStock) {}
