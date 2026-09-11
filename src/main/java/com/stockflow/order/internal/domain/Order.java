package com.stockflow.order.internal.domain;

import com.stockflow.contracts.OrderPlaced;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>The aggregate root of the order module.</b>
 *
 * <p>Invariants it protects:</p>
 * <ul>
 *   <li>an order always has at least one line;</li>
 *   <li>{@code total} is derived from the lines and can never be set independently of them;</li>
 *   <li>status only ever moves along the edges {@link OrderStatus#canTransitionTo} allows;</li>
 *   <li>an order cannot be submitted until every line holds a reservation — the rule that stops a
 *       customer paying for stock nobody set aside.</li>
 * </ul>
 *
 * <p>The last one is worth dwelling on, because it is the invariant a distributed system cannot
 * express. Across services, "order saved" and "stock reserved" are two commits, so there is always
 * a window where one is true and the other is not, and a saga exists solely to clean that window
 * up afterwards. Here both happen in one transaction and the window does not exist, so the rule
 * can simply be checked and enforced.</p>
 */
public final class Order extends AggregateRoot {

    private final OrderId id;
    private final OrderNumber orderNumber;
    private final UUID customerId;
    private final UUID requestId;
    private final List<OrderLine> lines;
    private final Instant placedAt;

    private OrderStatus status;
    private String cancellationReason;
    private final long version;

    public Order(OrderId id, OrderNumber orderNumber, UUID customerId, UUID requestId,
                 List<OrderLine> lines, OrderStatus status, Instant placedAt,
                 String cancellationReason, long version) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.orderNumber = java.util.Objects.requireNonNull(orderNumber, "orderNumber");
        this.customerId = java.util.Objects.requireNonNull(customerId, "customerId");
        this.requestId = java.util.Objects.requireNonNull(requestId, "requestId");
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.placedAt = java.util.Objects.requireNonNull(placedAt, "placedAt");
        this.lines = new ArrayList<>(lines == null ? List.of() : lines);
        this.cancellationReason = cancellationReason;
        this.version = version;
        if (this.lines.isEmpty()) {
            throw new IllegalArgumentException("An order must have at least one line");
        }
    }

    /** Open a new order in DRAFT. Nothing is reserved yet. */
    public static Order draft(OrderNumber orderNumber, UUID customerId, UUID requestId,
                              List<OrderLine> lines, Instant now) {
        return new Order(OrderId.newId(), orderNumber, customerId, requestId,
                lines, OrderStatus.DRAFT, now, null, 0L);
    }

    /**
     * Factory for a line, so callers never need to reach for {@code OrderLine}'s constructor.
     *
     * <p>The id is supplied rather than generated, because the application layer derives it from
     * the checkout's request id: a retried checkout must produce the same line ids, otherwise the
     * reservation keys derived from them differ and inventory sees a new request rather than a
     * replay.</p>
     */
    public static OrderLine line(UUID lineId, Sku sku, int quantity, Money unitPrice,
                                 UUID designSnapshotId) {
        return OrderLine.of(lineId, sku, quantity, unitPrice, designSnapshotId);
    }

    /** Convenience for tests and any caller with no need for a stable id. */
    public static OrderLine line(Sku sku, int quantity, Money unitPrice, UUID designSnapshotId) {
        return OrderLine.of(UUID.randomUUID(), sku, quantity, unitPrice, designSnapshotId);
    }

    /** Sum of the lines. Derived on every call rather than stored, so it cannot go stale. */
    public Money total() {
        return lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(Money.zero(lines.getFirst().unitPrice().currency()), Money::plus);
    }

    public void attachReservations(UUID lineId, List<UUID> reservationIds) {
        lineOf(lineId).attachReservations(reservationIds);
    }

    /**
     * Submit the order for payment.
     *
     * <p>Refuses unless every line holds a reservation. The application service calls this only
     * after {@code InventoryService.reserve(...)} has returned for each line, so in practice the
     * check never fires — which is the point: it is the guard that catches the day somebody adds a
     * code path that forgets to reserve.</p>
     */
    public void submit() {
        // Validate BEFORE transitioning. The order matters even though the surrounding transaction
        // would roll back anyway: a caller that catches this exception - a test, a batch importer -
        // would otherwise hold an aggregate that says PENDING_PAYMENT while nothing is reserved,
        // which is precisely the state this method exists to make impossible.
        List<OrderLine> unreserved = lines.stream()
                .filter(line -> !line.isReserved())
                .toList();
        if (!unreserved.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot submit order %s: %d line(s) hold no stock reservation"
                            .formatted(orderNumber, unreserved.size()));
        }
        transitionTo(OrderStatus.PENDING_PAYMENT);
        registerEvent(new OrderEvent.Placed(new OrderPlaced(
                id.value(),
                customerId,
                orderNumber.value(),
                lines.stream()
                        .map(line -> new OrderPlaced.OrderLine(
                                line.sku().code(), line.quantity(),
                                line.unitPrice().amount(), line.designSnapshotId()))
                        .toList(),
                total().amount(),
                total().currency().getCurrencyCode())));
    }

    /** Payment captured. Called from the listener on {@code PaymentCaptured}. */
    public void markPaid() {
        transitionTo(OrderStatus.PAID);
    }

    public void release() {
        transitionTo(OrderStatus.IN_FULFILMENT);
    }

    /**
     * Cancel.
     *
     * <p>Refused from SHIPPED onwards (BR-031): the goods are with the carrier and the correct
     * process is a return. The exception names the current status so the caller can tell the user
     * why rather than showing a generic failure.</p>
     */
    public void cancel(String reason) {
        // The database has CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL).
        // Rejecting a blank reason here turns what would be a ConstraintViolationException at
        // flush time - thrown far from the call that caused it - into an immediate, obvious error.
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A cancellation must record a reason");
        }
        if (!status.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new IllegalStateException(
                    ("Order %s cannot be cancelled from status %s; "
                     + "raise a return instead").formatted(orderNumber, status));
        }
        this.status = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    /** Every live hold this order is carrying, across all of its lines. */
    public List<UUID> reservationIds() {
        return lines.stream().flatMap(line -> line.reservationIds().stream()).toList();
    }

    /** Forget the holds after inventory has released them, so nothing releases them twice. */
    public void clearReservations() {
        lines.forEach(OrderLine::clearReservations);
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Order %s cannot move from %s to %s".formatted(orderNumber, status, target));
        }
        this.status = target;
    }

    private OrderLine lineOf(UUID lineId) {
        return lines.stream()
                .filter(line -> line.id().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Line %s does not belong to order %s".formatted(lineId, orderNumber)));
    }

    public Optional<OrderLine> findLine(UUID lineId) {
        return lines.stream().filter(line -> line.id().equals(lineId)).findFirst();
    }

    public OrderId id() { return id; }
    public OrderNumber orderNumber() { return orderNumber; }
    public UUID customerId() { return customerId; }
    public UUID requestId() { return requestId; }
    public OrderStatus status() { return status; }
    public Instant placedAt() { return placedAt; }
    public String cancellationReason() { return cancellationReason; }
    public long version() { return version; }

    public List<OrderLine> lines() {
        return java.util.Collections.unmodifiableList(lines);
    }
}
