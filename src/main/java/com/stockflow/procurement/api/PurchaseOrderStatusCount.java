package com.stockflow.procurement.api;

/**
 * SCRUM-119/WBS 3.2.7. One bucket of the PO status dashboard.
 *
 * <p>{@link ProcurementService#statusDashboard} always returns one row per known status, even a
 * status with zero purchase orders right now — a dashboard that silently omits an empty bucket
 * reads as "this status doesn't exist" rather than "nothing is in it".</p>
 */
public record PurchaseOrderStatusCount(String status, long count) {
}
