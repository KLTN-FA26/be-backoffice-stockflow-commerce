package com.stockflow.inventory.api;

import com.stockflow.common.api.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Transfer orders between warehouses for other modules to show, read through the list the transfer screen reads
 * ({@code GET /api/v1/inventory/transfer-orders}). The back-office assistant (ADR-0010) answers with it what the
 * warehouses ask each other every day - which transfers are on the road, which wait for approval, which are late -
 * that no other {@code api} could: transfers are otherwise this module's alone ({@code TransferOrders} drives them and
 * stays internal).
 *
 * <p><b>Scope.</b> The rules are that screen's: a warehouse-bound caller sees only the transfers leaving or reaching
 * one of their warehouses. Call it inside that endpoint's guard,
 * {@code @RequiresPermission(resource = "inventory-transfer-orders", action = READ)}; outside a guard no scope is in
 * force and every warehouse is seen, as by the system.</p>
 *
 * <p>Read only. Nothing here submits, approves, dispatches or receives anything.</p>
 */
public interface TransferOrderQueries {

    /** The most transfers one call returns. */
    int MAX_TRANSFERS = 50;

    /**
     * Every status, in the order a transfer moves through them (docs 10 §5, {@code ck_transfer_order_status}): written
     * (DRAFT), waiting for an approver above the value threshold (PENDING_APPROVAL), approved, being picked at the
     * source, on the road (IN_TRANSIT, until the destination counts it in), counted in partly (PARTIALLY_RECEIVED: the
     * rest still awaited) or wholly (RECEIVED: the count done, a shortage settled at receipt), then COMPLETED, or
     * CLOSED short; CANCELLED before any goods left.
     */
    List<String> STATUSES = List.of("DRAFT", "PENDING_APPROVAL", "APPROVED", "PICKING", "IN_TRANSIT",
            "PARTIALLY_RECEIVED", "RECEIVED", "COMPLETED", "CLOSED", "CANCELLED");

    /** The transfers still being worked on: everything but COMPLETED, CLOSED and CANCELLED. */
    List<String> OPEN_STATUSES = List.of("DRAFT", "PENDING_APPROVAL", "APPROVED", "PICKING", "IN_TRANSIT",
            "PARTIALLY_RECEIVED", "RECEIVED");

    /**
     * The transfers that match.
     *
     * @param statuses      any of {@link #STATUSES}; empty for every status
     * @param number        text the transfer number contains; null or blank for any
     * @param expectedFirst the earliest expected date first, transfers without one last, so the late ones lead a cut
     *                      list; otherwise the most recently created first, as the screen lists them
     * @param limit         1..{@value #MAX_TRANSFERS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a status not in
     *         {@link #STATUSES} or a limit out of range
     */
    PageResponse<TransferOrderSummary> transfers(List<String> statuses, String number, boolean expectedFirst,
                                                 int limit);

    /**
     * One transfer as the list shows it, without its lines.
     *
     * @param fromWarehouseCode the source warehouse's prefix ({@code HCM}); null if the warehouse is gone
     * @param status            one of {@link #STATUSES}
     * @param expectedDate      when the destination expects it; null when none was given
     * @param dispatchedAt      when it left the source; null before IN_TRANSIT
     * @param deliveryMode      FULL_TRUCKLOAD or PARCEL; null when not chosen
     * @param packedAt          when its packing was completed; null before
     */
    record TransferOrderSummary(String number, String fromWarehouseCode, String toWarehouseCode, String status,
                                LocalDate expectedDate, Instant createdAt, Instant dispatchedAt, String deliveryMode,
                                Instant packedAt) {
    }
}
