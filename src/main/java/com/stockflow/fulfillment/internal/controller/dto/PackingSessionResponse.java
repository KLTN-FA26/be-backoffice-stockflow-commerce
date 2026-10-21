package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The packing session of one task: the order's lines with what is packed of them, the packages, and the shipments
 * completed so far.
 *
 * @param packStatus {@code PENDING}, {@code PACKING}, {@code ON_HOLD} (a design check), {@code PACKED} or
 *                   {@code CANCELLED}
 * @param packerId   who took the order at the packing table; null while PENDING
 * @param paymentDue money the order's terms want before its goods leave (G3-Q4): the handover will be refused until
 *                   it is paid. Absent when nothing is due
 */
public record PackingSessionResponse(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId,
                                     String packStatus, UUID packerId, String packerName, Instant startedAt,
                                     Instant packedAt, List<Line> lines, List<PackageResponse> packages,
                                     List<Shipment> shipments,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) PaymentDue paymentDue) {

    /**
     * One order line at the packing table.
     *
     * @param wanted    what the order still wants of it
     * @param available what reached the packing area for it (picked, or produced for a print line)
     * @param packed    in packages not voided
     * @param shipped   in packages already in a shipment
     * @param remaining still to pack
     * @param lotTracked    a scan of it must name the lot; {@code lots} lists the ones picked
     * @param serialTracked a scan of it names one serial number, one unit at a time
     */
    public record Line(UUID orderLineId, String sku, String barcode, boolean printLine, UUID designSnapshotId,
                       int wanted, int available, int packed, int shipped, int remaining, boolean lotTracked,
                       boolean serialTracked, List<Lot> lots) { }

    public record Lot(String lotNumber, int available, int packed) { }

    /**
     * @param status   {@code READY_TO_DISPATCH} ... {@code CANCELLED} (kltn-docs 09 §5)
     * @param codAmount what the carrier collects for it as of packing (09 BR-04); fixed at the handover
     */
    public record Shipment(UUID shipmentId, String code, String status, String deliveryMode, int packageCount,
                           BigDecimal codAmount, boolean partial, String partialNote, Instant readyAt) { }

    public record PaymentDue(BigDecimal amountDueBeforeHandover, String currency) { }
}
