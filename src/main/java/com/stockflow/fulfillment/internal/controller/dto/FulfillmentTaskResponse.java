package com.stockflow.fulfillment.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A pick/pack task. No storage keys.
 *
 * <p>{@code lines} is present only when one task is read by id ({@code GET /tasks/{taskId}}); list rows
 * and the answers of the write endpoints leave it out. {@code shipmentId} is present on the answer to completing
 * packing: the shipment the sealed packages became (G4-5).</p>
 *
 * <p>{@code destination} (team plan 4C-5, kltn-docs 07 §1): {@code DISPATCH} or {@code STAGING};
 * {@code stagingLocationCode} is the staging area of a STAGING task, {@code stagedAt} when its cartons were last all
 * there ({@code pickStatus} STAGED), {@code movedToDispatchAt} when the warehouse manager moved it to dispatch.</p>
 */
public record FulfillmentTaskResponse(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId,
                                      UUID assignedUserId, String assignedUserName, String pickStatus,
                                      String packStatus, Instant assignedAt, Instant startedAt,
                                      Instant pickedAt, Instant packedAt,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) List<TaskLine> lines,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) PaymentDue paymentDue,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) UUID shipmentId,
                                      String destination, String stagingLocationCode, Instant stagedAt,
                                      Instant movedToDispatchAt) {

    /**
     * Set when completing the package of an order that still owes money due before the handover (G3-Q4, G2-3): the
     * packer's early warning; the handover is refused until it is paid.
     */
    public record PaymentDue(java.math.BigDecimal amountDueBeforeHandover, String currency) { }

    /** One order line to pick. {@code printLine}: made to the customer's design, so it has a snapshot. */
    public record TaskLine(UUID lineId, String sku, int quantity, UUID designSnapshotId, boolean printLine) { }
}
