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
}
