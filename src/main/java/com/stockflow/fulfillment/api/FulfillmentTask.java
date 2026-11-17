package com.stockflow.fulfillment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task-scoped fulfillment view; it deliberately exposes no design storage key.
 *
 * @param orderNumber      the number the order is known by, so a pick list needs no call to the orders API
 * @param warehouseId      the warehouse the goods are picked in (BR-SEC-002)
 * @param assignedUserName display name of the assignee; null while nobody is assigned
 * @param lines            what to pick, set only when one task is read by id; null on list rows
 * @param paymentDue       set on the answer to completing the package when the order still owes money that must be
 *                         paid before its goods leave (G3-Q4, G2-3): the early warning - the handover will be refused
 *                         until it is paid; null otherwise
 * @param shipmentId       set on the answer to completing packing: the shipment the sealed packages now make up
 *                         (READY_TO_DISPATCH, G4-5); null otherwise
 * @param destination      where the cartons go (kltn-docs 07 §1): {@code DISPATCH} or {@code STAGING}
 * @param stagingLocationCode the staging area of a {@code STAGING} task; null for a {@code DISPATCH} one
 * @param stagedAt         when every carton of a {@code STAGING} task was last in its staging area; null until then
 * @param movedToDispatchAt when the warehouse manager moved the staged task to dispatch, deducting its cartons (07
 *                         §4.3 step 8); null until then
 */
public record FulfillmentTask(
        UUID taskId,
        UUID orderId,
        String orderNumber,
        UUID warehouseId,
        UUID assignedUserId,
        String assignedUserName,
        String pickStatus,
        String packStatus,
        Instant assignedAt,
        Instant startedAt,
        Instant pickedAt,
        Instant packedAt,
        List<Line> lines,
        PaymentDue paymentDue,
        UUID shipmentId,
        PickDestination destination,
        String stagingLocationCode,
        Instant stagedAt,
        Instant movedToDispatchAt
) {
    public FulfillmentTask {
        lines = lines == null ? null : List.copyOf(lines);
        destination = destination == null ? PickDestination.DISPATCH : destination;
    }

    /** A task picked to dispatch, as written before team plan 4C-5 gave tasks a destination. */
    public FulfillmentTask(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId, UUID assignedUserId,
                           String assignedUserName, String pickStatus, String packStatus, Instant assignedAt,
                           Instant startedAt, Instant pickedAt, Instant packedAt, List<Line> lines,
                           PaymentDue paymentDue, UUID shipmentId) {
        this(taskId, orderId, orderNumber, warehouseId, assignedUserId, assignedUserName, pickStatus, packStatus,
                assignedAt, startedAt, pickedAt, packedAt, lines, paymentDue, shipmentId, PickDestination.DISPATCH,
                null, null, null);
    }

    public FulfillmentTask(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId, UUID assignedUserId,
                           String assignedUserName, String pickStatus, String packStatus, Instant assignedAt,
                           Instant startedAt, Instant pickedAt, Instant packedAt, List<Line> lines) {
        this(taskId, orderId, orderNumber, warehouseId, assignedUserId, assignedUserName, pickStatus, packStatus,
                assignedAt, startedAt, pickedAt, packedAt, lines, null, null);
    }

    public FulfillmentTask withPaymentDue(PaymentDue due) {
        return new FulfillmentTask(taskId, orderId, orderNumber, warehouseId, assignedUserId, assignedUserName,
                pickStatus, packStatus, assignedAt, startedAt, pickedAt, packedAt, lines, due, shipmentId,
                destination, stagingLocationCode, stagedAt, movedToDispatchAt);
    }

    public FulfillmentTask withShipment(UUID shipment) {
        return new FulfillmentTask(taskId, orderId, orderNumber, warehouseId, assignedUserId, assignedUserName,
                pickStatus, packStatus, assignedAt, startedAt, pickedAt, packedAt, lines, paymentDue, shipment,
                destination, stagingLocationCode, stagedAt, movedToDispatchAt);
    }

    /** Money the order must receive before its goods may be handed to the carrier. */
    public record PaymentDue(java.math.BigDecimal amountDueBeforeHandover, String currency) { }

    /** One order line to pick (docs 07 Input: order number and lines with SKU and quantity). */
    public record Line(UUID lineId, String sku, int quantity, UUID designSnapshotId, boolean printLine) { }
}
