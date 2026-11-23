package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderShipmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One shipment of an {@link Order}, from the order's side: the units it carries and what became of it (decision
 * G4-Q3, split shipments allowed). An entity inside the aggregate; fulfillment owns the shipment itself and tells the
 * order of its handover and its outcome in the same transaction (architecture decision A1).
 *
 * <p>{@code amount} is the goods' value at the order's prices, fixed at the handover. A delivery bills nothing: the
 * customer pays the contract's installments (kltn-docs 15), and what a delivery billed under the money ledger went with
 * it (4B-10, column dropped by 4B-11).</p>
 *
 * <p>Nothing is collected on delivery (kltn-docs 09 BR-08): the carrier carries the goods, the customer pays by
 * transfer.</p>
 */
public final class OrderShipment {

    private final UUID id;
    private final Map<UUID, Integer> quantities;
    private final BigDecimal amount;
    private final Instant handedOverAt;
    private final UUID handedOverBy;
    private OrderShipmentStatus status;
    private Instant outcomeAt;
    private UUID outcomeBy;
    private DeliveryFailureReason failureReason;
    private String failureNote;
    /** G4-2a: number, parcels and carrier, as the customer reads them; null on shipments recorded before. */
    private final com.stockflow.order.api.ShipmentHandover.Carriage carriage;
    /**
     * 4B-8 (17 BR-29): the delivery batch whose cartons it carried; null for an order without a delivery schedule, or a
     * shipment handed over before batches were released one by one.
     */
    private UUID deliveryBatchId;
    /**
     * WF-05 Q-7 / Q-12: when the shipment's units were closed on their lines without being delivered - the return
     * receipt of a shipment the carrier brought back was confirmed (RETURNED), or Sales closed a shipment the carrier
     * lost (DELIVERY_FAILED, reason LOST). Null while the units are still the shipment's: with the carrier, with the
     * customer, or on their way back. A carrier's word alone never closes them (L-I8): until the goods are seen, a
     * RETURNED shipment still holds its units and nothing of its money moves.
     */
    private Instant unitsClosedAt;
    /**
     * WF-05 Q-2: what the units closed with {@link #unitsClosedAt} were worth with their VAT - what payment settled.
     * Null while the units are the shipment's, and on a row closed before it was recorded.
     */
    private BigDecimal closedGoodsValue;
    /**
     * WF-05 Q-19: the part of the order's shipping fee, with its VAT, payment waived when the units were closed - the
     * shipment's share of the order's goods (payment works it out; the order keeps its answer, for invoicing to
     * invoice the fee less it). Zero otherwise.
     */
    private BigDecimal shippingFeeWaived = BigDecimal.ZERO;

    public OrderShipment(UUID id, Map<UUID, Integer> quantities, BigDecimal amount, Instant handedOverAt,
                         UUID handedOverBy, OrderShipmentStatus status, Instant outcomeAt, UUID outcomeBy,
                         DeliveryFailureReason failureReason, String failureNote) {
        this(id, quantities, amount, handedOverAt, handedOverBy, status, outcomeAt, outcomeBy, failureReason,
                failureNote, null);
    }

    public OrderShipment(UUID id, Map<UUID, Integer> quantities, BigDecimal amount, Instant handedOverAt,
                         UUID handedOverBy, OrderShipmentStatus status, Instant outcomeAt, UUID outcomeBy,
                         DeliveryFailureReason failureReason, String failureNote,
                         com.stockflow.order.api.ShipmentHandover.Carriage carriage) {
        this.carriage = carriage;
        this.id = Objects.requireNonNull(id, "id");
        this.quantities = Collections.unmodifiableMap(new LinkedHashMap<>(quantities));
        this.amount = Objects.requireNonNull(amount, "amount");
        this.handedOverAt = Objects.requireNonNull(handedOverAt, "handedOverAt");
        this.handedOverBy = handedOverBy;
        this.status = Objects.requireNonNull(status, "status");
        this.outcomeAt = outcomeAt;
        this.outcomeBy = outcomeBy;
        this.failureReason = failureReason;
        this.failureNote = failureNote;
    }

    static OrderShipment handedOver(UUID id, Map<UUID, Integer> quantities, BigDecimal amount, Instant at, UUID by,
                                    com.stockflow.order.api.ShipmentHandover.Carriage carriage, UUID deliveryBatchId) {
        OrderShipment shipment = new OrderShipment(id, quantities, amount, at, by, OrderShipmentStatus.HANDED_OVER,
                null, null, null, null, carriage);
        shipment.deliveryBatchId = deliveryBatchId;
        return shipment;
    }

    /** Rehydration of when its units were closed (WF-05 Q-7 / Q-12); null: they are still the shipment's. */
    public OrderShipment restoreUnitsClosed(Instant at) {
        this.unitsClosedAt = at;
        return this;
    }

    /** Rehydration of what its closed units were worth and the shipping fee waived with them (WF-05 Q-2, Q-19). */
    public OrderShipment restoreClosedMoney(BigDecimal goodsValue, BigDecimal feeWaived) {
        this.closedGoodsValue = goodsValue;
        this.shippingFeeWaived = feeWaived == null ? BigDecimal.ZERO : feeWaived;
        return this;
    }

    /** Rehydration of the batch it carried (4B-8). */
    public OrderShipment restoreDeliveryBatch(UUID batchId) {
        this.deliveryBatchId = batchId;
        return this;
    }

    /** @return false when it already was delivered */
    boolean delivered(Instant at, UUID by) {
        if (status == OrderShipmentStatus.DELIVERED) {
            return false;
        }
        // A lost shipment Sales closed: its units are off the order and their money settled (Q-12). Delivering it now
        // would hand the customer goods the order no longer owes, billed nowhere.
        if (unitsClosedAt != null) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION, ("Shipment %s was closed as lost "
                    + "at %s: it is not delivered on this order any more").formatted(id, unitsClosedAt));
        }
        move(OrderShipmentStatus.DELIVERED);
        this.outcomeAt = at;
        this.outcomeBy = by;
        return true;
    }

    /** @return false when it already failed */
    boolean failed(DeliveryFailureReason reason, String note, Instant at, UUID by) {
        if (status == OrderShipmentStatus.DELIVERY_FAILED) {
            return false;
        }
        move(OrderShipmentStatus.DELIVERY_FAILED);
        this.failureReason = Objects.requireNonNull(reason, "reason");
        this.failureNote = note;
        this.outcomeAt = at;
        this.outcomeBy = by;
        return true;
    }

    /**
     * A delivery the carrier reported wrongly, corrected by the warehouse manager (09 §4.3 step 5, E17): DELIVERED →
     * DELIVERY_FAILED.
     *
     * @return false when it already failed
     */
    boolean correctedToFailed(DeliveryFailureReason reason, String note, Instant at, UUID by) {
        if (status == OrderShipmentStatus.DELIVERY_FAILED) {
            return false;
        }
        if (status != OrderShipmentStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION,
                    "Shipment %s is %s: only a delivery is corrected to a failure".formatted(id, status));
        }
        this.status = OrderShipmentStatus.DELIVERY_FAILED;
        this.failureReason = Objects.requireNonNull(reason, "reason");
        this.failureNote = note;
        this.outcomeAt = at;
        this.outcomeBy = by;
        return true;
    }

    /**
     * The warehouse manager learnt the real reason a delivery failed (kltn-docs 09 edge "hoàn hàng khi còn Handed Over
     * ... Quản lý kho sửa lý do thất bại nếu biết lý do thật"): the reason and note of a failed or returned shipment.
     *
     * @return false when they are the ones recorded
     */
    boolean correctFailureReason(DeliveryFailureReason reason, String note) {
        if (status != OrderShipmentStatus.DELIVERY_FAILED && status != OrderShipmentStatus.RETURNED) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION,
                    "Shipment %s is %s: only a failed delivery has a reason to correct".formatted(id, status));
        }
        if (Objects.requireNonNull(reason, "reason") == failureReason && Objects.equals(note, failureNote)) {
            return false;
        }
        this.failureReason = reason;
        this.failureNote = note;
        return true;
    }

    /** @return false when it already came back */
    boolean returned(Instant at, UUID by) {
        if (status == OrderShipmentStatus.RETURNED) {
            return false;
        }
        move(OrderShipmentStatus.RETURNED);
        this.outcomeAt = at;
        this.outcomeBy = by;
        return true;
    }

    private void move(OrderShipmentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION,
                    "Shipment %s is %s and cannot become %s".formatted(id, status, target));
        }
        this.status = target;
    }

    /** The units of {@code orderLineId} in this shipment; 0 when it carries none. */
    int quantityOf(UUID orderLineId) {
        return quantities.getOrDefault(orderLineId, 0);
    }

    public boolean isReturned() {
        return status == OrderShipmentStatus.RETURNED;
    }

    /**
     * Closes the shipment's units (WF-05 Q-7 / Q-12): from now on they are not the shipment's, and the order counts
     * them closed on their lines. Only a shipment that came back or failed.
     *
     * @return false when they were closed already
     */
    boolean closeUnits(Instant at) {
        if (unitsClosedAt != null) {
            return false;
        }
        if (status != OrderShipmentStatus.RETURNED && status != OrderShipmentStatus.DELIVERY_FAILED) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION,
                    "Shipment %s is %s: only a shipment not delivered has its units closed".formatted(id, status));
        }
        this.unitsClosedAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /** What the closure of its units settled (WF-05 Q-2): their value with VAT. */
    void recordClosedValue(BigDecimal goodsValue) {
        if (unitsClosedAt == null || goodsValue.signum() < 0) {
            throw new IllegalStateException("Shipment " + id + ": only closed units carry a value, zero or more");
        }
        this.closedGoodsValue = goodsValue;
    }

    /** The share of the shipping fee payment waived with its closed units (WF-05 Q-19); once, in the closure's unit. */
    void recordShippingFeeWaived(BigDecimal waived) {
        if (unitsClosedAt == null || waived.signum() < 0 || shippingFeeWaived.signum() != 0) {
            throw new IllegalStateException("Shipment " + id + ": a waiver is recorded once, on closed units, zero or "
                    + "more");
        }
        this.shippingFeeWaived = waived;
    }

    /**
     * Whether the shipment still holds its units - with the carrier, with the customer, or on their way back after
     * the carrier reported it returned and before its receipt is confirmed. What the order counts as shipped.
     */
    public boolean holdsUnits() {
        return unitsClosedAt == null;
    }

    /** The carrier reported it back and its return receipt is not confirmed yet: its units wait for that (Q-7). */
    public boolean awaitingReturnReceipt() {
        return status == OrderShipmentStatus.RETURNED && unitsClosedAt == null;
    }

    /**
     * Its goods will not be delivered on this order: it came back (RETURNED), or it failed and Sales closed it as lost.
     * What makes an order whose other shipments arrived partly fulfilled rather than delivered.
     */
    public boolean endedUndelivered() {
        return status == OrderShipmentStatus.RETURNED || unitsClosedAt != null;
    }

    /** Still to be decided: in transit, or failed and neither delivered again, brought back nor closed as lost. */
    public boolean pending() {
        return status == OrderShipmentStatus.HANDED_OVER
                || status == OrderShipmentStatus.DELIVERY_FAILED && unitsClosedAt == null;
    }

    public UUID id() { return id; }
    public Map<UUID, Integer> quantities() { return quantities; }
    public BigDecimal amount() { return amount; }
    public Instant handedOverAt() { return handedOverAt; }
    public UUID handedOverBy() { return handedOverBy; }
    public OrderShipmentStatus status() { return status; }
    public Instant outcomeAt() { return outcomeAt; }
    public UUID outcomeBy() { return outcomeBy; }
    public DeliveryFailureReason failureReason() { return failureReason; }
    public String failureNote() { return failureNote; }
    public com.stockflow.order.api.ShipmentHandover.Carriage carriage() { return carriage; }
    public UUID deliveryBatchId() { return deliveryBatchId; }
    public Instant unitsClosedAt() { return unitsClosedAt; }
    /** What its closed units were worth with VAT; null while they are not closed, or on a row closed before WF-05. */
    public BigDecimal closedGoodsValue() { return closedGoodsValue; }
    /** The part of the order's shipping fee waived when its units were closed (Q-19); zero otherwise. */
    public BigDecimal shippingFeeWaived() { return shippingFeeWaived; }
}
