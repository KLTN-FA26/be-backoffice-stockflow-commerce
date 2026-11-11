package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The check session of one task (kltn-docs 08 §4-§5): the order's lines with what is checked and labelled of them, the
 * shipping cartons, the shipments completed so far, and the payment warning.
 *
 * @param status                {@code PENDING}, {@code IN_PROGRESS}, {@code COMPLETED} or {@code CANCELLED}
 * @param startedBy             who started the check: only they, or a warehouse manager, work it (08 BR-06)
 * @param orderLocked           a carton of the order is labelled: it is no longer cancelled and its delivery address
 *                              does not change (08 BR-04)
 * @param paymentDue            what the installments due before delivery still owe: a warning, the handover is
 *                              refused until it is paid (08 BR-11). Absent when nothing is due
 * @param skusWithoutCartonSpec SKUs whose carton spec has no size or weight: their cartons get no label until a
 *                              warehouse manager completes it (08 BR-07)
 */
public record CartonCheckSessionResponse(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId,
                                         String status, UUID startedBy, String startedByName, Instant startedAt,
                                         Instant completedAt, boolean orderLocked, List<Line> lines,
                                         List<Carton> cartons, List<Shipment> shipments,
                                         @JsonInclude(JsonInclude.Include.NON_NULL) PaymentDue paymentDue,
                                         List<String> skusWithoutCartonSpec) {

    /**
     * @param wantedUnits  what the order still wants of the line
     * @param labeledUnits units in its labelled cartons, shipped or not
     * @param missingUnits what a completion now would leave behind: only with a partial completion the order allows
     */
    public record Line(UUID orderLineId, String sku, int wantedUnits, int cartons, int checkedCartons,
                       int labeledCartons, int removedCartons, int shippedCartons, int labeledUnits,
                       int missingUnits) { }

    /**
     * One shipping carton - the LPN carton the producer packed (08 BR-02).
     *
     * @param packageId    null for a carton the session has not received yet (read before it started)
     * @param status       {@code TO_CHECK}, {@code CHECKED}, {@code LABELED} or {@code REMOVED}
     * @param labelVersion 0 before its first label; a reprint goes up one and voids the old label (08 BR-08)
     * @param dimWeightKg  volumetric weight, length x width x height / the configured divisor (08 BR-09)
     * @param removalReason {@code DAMAGED} or {@code ORDER_CANCELLED} for a removed carton
     */
    public record Carton(UUID packageId, int packageNo, String labelCode, String lpn, UUID pickLineId,
                         UUID orderLineId, String sku, String lotNumber, int quantity, String status,
                         Instant checkedAt, Instant labeledAt, int labelVersion, BigDecimal lengthCm,
                         BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg, BigDecimal dimWeightKg,
                         boolean rotatable, UUID shipmentId, String removalReason, String removalNote) { }

    public record Shipment(UUID shipmentId, String code, String status, int packageCount, boolean partial,
                           String partialNote, Instant readyAt) { }

    public record PaymentDue(BigDecimal amount, String currency) { }
}
