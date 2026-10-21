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
 * <p>{@code amount} is the goods' value at the order's prices, fixed at the handover. {@code billedAmount} is what its
 * delivery billed a credit order (a receivable): at most {@code amount}, less when money the customer paid ahead
 * covers part of it. Returned goods are never billed.</p>
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
    private BigDecimal billedAmount;

    public OrderShipment(UUID id, Map<UUID, Integer> quantities, BigDecimal amount, Instant handedOverAt,
                         UUID handedOverBy, OrderShipmentStatus status, Instant outcomeAt, UUID outcomeBy,
                         DeliveryFailureReason failureReason, String failureNote, BigDecimal billedAmount) {
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
        this.billedAmount = billedAmount == null ? BigDecimal.ZERO : billedAmount;
    }

    static OrderShipment handedOver(UUID id, Map<UUID, Integer> quantities, BigDecimal amount, Instant at, UUID by) {
        return new OrderShipment(id, quantities, amount, at, by, OrderShipmentStatus.HANDED_OVER, null, null, null,
                null, BigDecimal.ZERO);
    }

    /** @return false when it already was delivered */
    boolean delivered(Instant at, UUID by, BigDecimal billed) {
        if (status == OrderShipmentStatus.DELIVERED) {
            return false;
        }
        move(OrderShipmentStatus.DELIVERED);
        this.outcomeAt = at;
        this.outcomeBy = by;
        this.billedAmount = billed;
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
    public BigDecimal billedAmount() { return billedAmount; }
}
