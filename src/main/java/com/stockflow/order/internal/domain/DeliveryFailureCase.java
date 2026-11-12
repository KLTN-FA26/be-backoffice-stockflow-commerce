package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.DeliveryFailureReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A shipment the carrier brought back, received on a return receipt, waiting for Sales to decide it (WF-05 fix plan,
 * sections 2 and 3; kltn-docs 09 §4.3, 03 §4.2, 17 §4.8).
 *
 * <p>Why a case and not a status of the order. The goods are printed to the customer's design and are the customer's
 * own SKU: they are sold to nobody else, so a refund is not what follows a failed delivery by default. What follows is
 * a decision under the contract, taken once the goods are back and their condition is known - and until then the
 * order waits ON_HOLD for {@code DELIVERY_FAILED}, where a shipment's outcome used to move it to RETURNED by itself.</p>
 *
 * <pre>
 *   OPEN ─deliver again─▶ REDELIVERY ─released, picked, checked, handed over─▶ REDELIVERED
 *     └──────────────────────┴─cancel what was not delivered─▶ CANCELLED
 * </pre>
 *
 * <p><b>The failed trip</b> is charged to the customer only when they made it fail - {@link DeliveryFailureReason#REFUSED}
 * or {@link DeliveryFailureReason#WRONG_ADDRESS} - and then it must be: Sales enter the amount, payment owes it on a
 * fee item. For any other reason nothing is charged and the case is marked for a claim against the carrier, which is
 * settled outside the system. {@code ck_delivery_failure_case_fee} says both again.</p>
 *
 * <p>Its lines are the returned shipment's: the units of each order line that came back and are owed a delivery
 * again. The cartons are those the receipt listed, with the condition the warehouse noted - a copy for Sales to
 * decide by. Every rule here is stated again by {@code ordering.check_delivery_failure_case} and the table's CHECKs.</p>
 */
public final class DeliveryFailureCase {

    /** Where a case stands. */
    public enum Status {
        /** The goods are back; Sales decide. */
        OPEN,
        /** Sales decided to deliver again: the goods are held for the order, then released, picked and checked. */
        REDELIVERY,
        /** The goods were handed over again, on a new shipment. */
        REDELIVERED,
        /** What was not delivered was cancelled. */
        CANCELLED;

        public boolean isClosed() {
            return this == REDELIVERED || this == CANCELLED;
        }
    }

    /** A carton received back, as the return receipt listed it; {@code condition} null when nothing was noted. */
    public record Carton(String lpn, String sku, String lotNumber, int quantity, String condition) {
    }

    private final UUID id;
    private final UUID orderId;
    private final UUID shipmentId;
    private final UUID deliveryBatchId;
    private final UUID receiptId;
    private final String receiptNumber;
    private final UUID warehouseId;
    private final DeliveryFailureReason failureReason;
    private final String failureNote;
    private final Map<UUID, Integer> units;
    private final List<Carton> cartons;
    private final Instant openedAt;
    private Status status;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private BigDecimal failedTripFee;
    private UUID feeInstallmentId;
    private boolean carrierClaim;
    private Instant releasedAt;
    private UUID releasedBy;
    private UUID redeliveryShipmentId;
    private Instant redeliveredAt;
    private Instant closedAt;
    private UUID closedBy;
    private final long version;

    /** Restores one as stored. */
    public DeliveryFailureCase(UUID id, UUID orderId, UUID shipmentId, UUID deliveryBatchId, UUID receiptId,
                               String receiptNumber, UUID warehouseId, DeliveryFailureReason failureReason,
                               String failureNote, Map<UUID, Integer> units, List<Carton> cartons, Instant openedAt,
                               Status status, UUID decidedBy, Instant decidedAt, String decisionNote,
                               BigDecimal failedTripFee, UUID feeInstallmentId, boolean carrierClaim, Instant releasedAt,
                               UUID releasedBy, UUID redeliveryShipmentId, Instant redeliveredAt, Instant closedAt,
                               UUID closedBy, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.shipmentId = Objects.requireNonNull(shipmentId, "shipmentId");
        this.deliveryBatchId = deliveryBatchId;
        this.receiptId = Objects.requireNonNull(receiptId, "receiptId");
        this.receiptNumber = Objects.requireNonNull(receiptNumber, "receiptNumber");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.failureReason = Objects.requireNonNull(failureReason, "failureReason");
        this.failureNote = failureNote;
        this.units = Map.copyOf(units);
        this.cartons = List.copyOf(cartons);
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
        this.failedTripFee = failedTripFee;
        this.feeInstallmentId = feeInstallmentId;
        this.carrierClaim = carrierClaim;
        this.releasedAt = releasedAt;
        this.releasedBy = releasedBy;
        this.redeliveryShipmentId = redeliveryShipmentId;
        this.redeliveredAt = redeliveredAt;
        this.closedAt = closedAt;
        this.closedBy = closedBy;
        this.version = version;
        if (this.units.isEmpty() || this.units.values().stream().anyMatch(u -> u == null || u <= 0)) {
            throw new IllegalArgumentException("A delivery failure case is about the units of a returned shipment");
        }
    }

    /**
     * The case a confirmed return receipt opens for the shipment it was against (03 §4.2 step 12): OPEN, with the
     * shipment's lines and failure reason and the cartons that came back.
     *
     * @param units the returned shipment's units per order line
     */
    public static DeliveryFailureCase open(UUID id, UUID orderId, UUID shipmentId, UUID deliveryBatchId, UUID receiptId,
                                           String receiptNumber, UUID warehouseId, DeliveryFailureReason failureReason,
                                           String failureNote, Map<UUID, Integer> units, List<Carton> cartons,
                                           Instant at) {
        return new DeliveryFailureCase(id, orderId, shipmentId, deliveryBatchId, receiptId, receiptNumber, warehouseId,
                failureReason, failureNote, units, cartons, at, Status.OPEN, null, null, null, null, null, false, null,
                null, null, null, null, null, 0L);
    }

    /** Whether the customer made the trip fail, and so pays for it when the goods are delivered again. */
    public boolean customerPaysFailedTrip() {
        return chargeable(failureReason);
    }

    /** The reasons that are the customer's doing: they refused the goods, or the address they gave was wrong. */
    public static boolean chargeable(DeliveryFailureReason reason) {
        return reason == DeliveryFailureReason.REFUSED || reason == DeliveryFailureReason.WRONG_ADDRESS;
    }

    /**
     * What a redelivery charges, checked before any money is written: a fee, more than zero and in whole units, when
     * the customer made the trip fail; none otherwise.
     *
     * @throws BusinessException {@code DELIVERY_FAILURE_INVALID_TRANSITION} for a case not OPEN;
     *         {@code VALIDATION_FAILED} for a fee missing, not wanted, or not a whole positive amount; a blank note
     */
    public void requireRedeliverable(BigDecimal fee, String note) {
        requireStatus(Status.OPEN, "deliver again");
        requireNote(note);
        if (customerPaysFailedTrip()) {
            if (fee == null || fee.signum() <= 0 || fee.stripTrailingZeros().scale() > 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("The delivery failed for %s: the failed trip "
                        + "is charged to the customer, more than zero in whole units of the currency")
                        .formatted(failureReason));
            }
        } else if (fee != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("The delivery failed for %s, which is not the "
                    + "customer's doing: nothing is charged; the cost is claimed from the carrier")
                    .formatted(failureReason));
        }
    }

    /**
     * Sales decide to deliver the goods again: OPEN → REDELIVERY. {@code fee} with the fee item payment made for it
     * when the customer made the trip fail; neither otherwise, and the case is marked for a claim against the carrier.
     *
     * @param feeInstallmentId the fee item owing {@code fee}; null with a null fee, and for an order without
     *                         installments, which has nothing to owe it on
     */
    public void redeliver(BigDecimal fee, UUID feeInstallmentId, String note, UUID by, Instant at) {
        requireRedeliverable(fee, note);
        if (fee != null && feeInstallmentId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "The failed trip is charged on a fee item of the order's "
                    + "contract; this order has no installments to add one to");
        }
        this.status = Status.REDELIVERY;
        this.decidedBy = Objects.requireNonNull(by, "by");
        this.decidedAt = Objects.requireNonNull(at, "at");
        this.decisionNote = note.strip();
        this.failedTripFee = fee;
        this.feeInstallmentId = fee == null ? null : feeInstallmentId;
        this.carrierClaim = fee == null;
    }

    /** Whether it waits to be released to the warehouse: decided to deliver again, not released yet. */
    public boolean awaitingRelease() {
        return status == Status.REDELIVERY && releasedAt == null;
    }

    /** Whether its goods are with the warehouse to be picked and handed over again. */
    public boolean released() {
        return status == Status.REDELIVERY && releasedAt != null;
    }

    /** Whether it still wants the goods that came back held for its order: not decided yet, or to be delivered again. */
    public boolean wantsGoodsHeld() {
        return status == Status.OPEN || awaitingRelease();
    }

    /** The warehouse manager released the redelivery to the warehouse, every unit of it held there. */
    public void release(UUID by, Instant at) {
        if (!awaitingRelease()) {
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION, ("Delivery failure case of "
                    + "receipt %s is %s%s: only a redelivery not yet released is released").formatted(receiptNumber,
                    status, releasedAt == null ? "" : ", released"));
        }
        this.releasedAt = Objects.requireNonNull(at, "at");
        this.releasedBy = Objects.requireNonNull(by, "by");
    }

    /** The goods were handed over again on {@code shipment}: REDELIVERY → REDELIVERED. */
    public void redelivered(UUID shipment, UUID by, Instant at) {
        if (!released()) {
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION, ("Delivery failure case of "
                    + "receipt %s is %s: only a released redelivery is handed over").formatted(receiptNumber, status));
        }
        if (shipmentId.equals(Objects.requireNonNull(shipment, "shipment"))) {
            throw new IllegalArgumentException("A redelivery is a new shipment");
        }
        this.status = Status.REDELIVERED;
        this.redeliveryShipmentId = shipment;
        this.redeliveredAt = Objects.requireNonNull(at, "at");
        this.closedAt = at;
        this.closedBy = by;
    }

    /**
     * What was not delivered is cancelled: OPEN or REDELIVERY → CANCELLED. From REDELIVERY the first decision stays on
     * record - who decided to deliver again, and the fee of the failed trip, which is owed whatever becomes of the
     * goods - and the cancellation is who closed it and why.
     */
    public void cancel(String note, UUID by, Instant at) {
        if (status.isClosed()) {
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION, ("Delivery failure case of "
                    + "receipt %s is %s and is not decided again").formatted(receiptNumber, status));
        }
        requireNote(note);
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(at, "at");
        if (status == Status.OPEN) {
            this.decidedBy = by;
            this.decidedAt = at;
            this.decisionNote = note.strip();
        } else {
            String both = decisionNote + " | cancelled: " + note.strip();
            this.decisionNote = both.length() > 1000 ? both.substring(0, 1000) : both;
        }
        this.status = Status.CANCELLED;
        this.closedAt = at;
        this.closedBy = by;
    }

    private void requireStatus(Status wanted, String what) {
        if (status != wanted) {
            throw new BusinessException(ErrorCode.DELIVERY_FAILURE_INVALID_TRANSITION, ("Delivery failure case of "
                    + "receipt %s is %s; only an %s case can %s").formatted(receiptNumber, status, wanted, what));
        }
    }

    private static void requireNote(String note) {
        if (note == null || note.isBlank() || note.strip().length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The decision says why, in at most 1000 characters");
        }
    }

    /** Every unit it is about, over its lines. */
    public int totalUnits() {
        return units.values().stream().mapToInt(Integer::intValue).sum();
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID shipmentId() { return shipmentId; }
    /** The delivery batch the returned shipment carried; null for an order without a delivery schedule. */
    public UUID deliveryBatchId() { return deliveryBatchId; }
    public UUID receiptId() { return receiptId; }
    public String receiptNumber() { return receiptNumber; }
    public UUID warehouseId() { return warehouseId; }
    public DeliveryFailureReason failureReason() { return failureReason; }
    public String failureNote() { return failureNote; }
    /** The returned shipment's units per order line. */
    public Map<UUID, Integer> units() { return units; }
    public List<Carton> cartons() { return cartons; }
    public Instant openedAt() { return openedAt; }
    public Status status() { return status; }
    public UUID decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionNote() { return decisionNote; }
    public BigDecimal failedTripFee() { return failedTripFee; }
    public UUID feeInstallmentId() { return feeInstallmentId; }
    public boolean carrierClaim() { return carrierClaim; }
    public Instant releasedAt() { return releasedAt; }
    public UUID releasedBy() { return releasedBy; }
    public UUID redeliveryShipmentId() { return redeliveryShipmentId; }
    public Instant redeliveredAt() { return redeliveredAt; }
    public Instant closedAt() { return closedAt; }
    public UUID closedBy() { return closedBy; }
    public long version() { return version; }
}
