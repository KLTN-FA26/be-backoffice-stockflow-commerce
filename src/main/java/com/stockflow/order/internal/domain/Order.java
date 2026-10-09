package com.stockflow.order.internal.domain;

import com.stockflow.contracts.OrderPlaced;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentTerm;
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
    private final String contactName;
    private final String contactEmail;
    private final String contactPhone;
    private final OrderAddressSnapshot shippingAddress;
    private final OrderAddressSnapshot billingAddress;
    private final UUID requestId;
    private final List<OrderLine> lines;
    private final Instant placedAt;

    private OrderStatus status;
    private String cancellationReason;
    private PaymentTerm paymentTerm = PaymentTerm.PREPAID;
    private java.math.BigDecimal depositRequired;
    private Instant depositReceivedAt;
    private UUID warehouseId;
    private Instant releasedAt;
    private UUID releasedBy;
    private final long version;
    private final String createdBy;
    private final Instant lastModifiedAt;
    private final String lastModifiedBy;

    public Order(OrderId id, OrderNumber orderNumber, UUID customerId, UUID requestId,
                 List<OrderLine> lines, OrderStatus status, Instant placedAt,
                 String cancellationReason, long version) {
        this(id, orderNumber, customerId, requestId, lines, status, placedAt,
                cancellationReason, version, null, null, null);
    }

    public Order(OrderId id, OrderNumber orderNumber, UUID customerId, UUID requestId,
                 List<OrderLine> lines, OrderStatus status, Instant placedAt,
                 String cancellationReason, long version, String createdBy,
                 Instant lastModifiedAt, String lastModifiedBy) {
        this(id, orderNumber, customerId, requestId, lines, status, placedAt, cancellationReason,
                version, createdBy, lastModifiedAt, lastModifiedBy, null, null, null, null, null);
    }

    /** Account or guest checkout: the contact and address snapshots are frozen with the order. */
    public Order(OrderId id, OrderNumber orderNumber, UUID customerId, UUID requestId,
                 List<OrderLine> lines, OrderStatus status, Instant placedAt,
                 String cancellationReason, long version, String contactName,
                 String contactEmail, String contactPhone, OrderAddressSnapshot shippingAddress,
                 OrderAddressSnapshot billingAddress) {
        this(id, orderNumber, customerId, requestId, lines, status, placedAt, cancellationReason,
                version, null, null, null, contactName, contactEmail, contactPhone, shippingAddress,
                billingAddress);
    }

    public Order(OrderId id, OrderNumber orderNumber, UUID customerId, UUID requestId,
                 List<OrderLine> lines, OrderStatus status, Instant placedAt,
                 String cancellationReason, long version, String createdBy,
                 Instant lastModifiedAt, String lastModifiedBy, String contactName,
                 String contactEmail, String contactPhone, OrderAddressSnapshot shippingAddress,
                 OrderAddressSnapshot billingAddress) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.orderNumber = java.util.Objects.requireNonNull(orderNumber, "orderNumber");
        this.customerId = customerId;
        this.contactName = contactName;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.shippingAddress = shippingAddress;
        this.billingAddress = billingAddress;
        this.requestId = java.util.Objects.requireNonNull(requestId, "requestId");
        this.status = java.util.Objects.requireNonNull(status, "status");
        this.placedAt = java.util.Objects.requireNonNull(placedAt, "placedAt");
        this.lines = new ArrayList<>(lines == null ? List.of() : lines);
        this.cancellationReason = cancellationReason;
        this.version = version;
        this.createdBy = createdBy;
        this.lastModifiedAt = lastModifiedAt;
        this.lastModifiedBy = lastModifiedBy;
        if (this.lines.isEmpty()) {
            throw new IllegalArgumentException("An order must have at least one line");
        }
        if (customerId == null && (contactEmail == null || contactEmail.isBlank()
                || shippingAddress == null || billingAddress == null)) {
            throw new IllegalArgumentException("Guest orders require contact and address snapshots");
        }
    }

    /** Open a new order in DRAFT. Nothing is reserved yet. */
    public static Order draft(OrderNumber orderNumber, UUID customerId, UUID requestId,
                              List<OrderLine> lines, Instant now) {
        return new Order(OrderId.newId(), orderNumber, customerId, requestId,
                lines, OrderStatus.DRAFT, now, null, 0L, null, null, null);
    }

    /** Open an account checkout and freeze the customer's contact/address data for fulfilment. */
    public static Order customerDraft(OrderNumber orderNumber, UUID customerId, UUID requestId,
                                      List<OrderLine> lines, Instant now, String contactName,
                                      String contactEmail, String contactPhone,
                                      OrderAddressSnapshot shippingAddress,
                                      OrderAddressSnapshot billingAddress) {
        return new Order(OrderId.newId(), orderNumber, customerId, requestId, lines,
                OrderStatus.DRAFT, now, null, 0L, contactName, contactEmail, contactPhone,
                shippingAddress, billingAddress);
    }

    public static Order guestDraft(OrderNumber orderNumber, UUID requestId, List<OrderLine> lines,
                                   Instant now, String contactName, String contactEmail,
                                   String contactPhone, OrderAddressSnapshot shippingAddress,
                                   OrderAddressSnapshot billingAddress) {
        return new Order(OrderId.newId(), orderNumber, null, requestId, lines, OrderStatus.DRAFT,
                now, null, 0L, contactName, contactEmail, contactPhone, shippingAddress,
                billingAddress);
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
                contactEmail,
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

    /**
     * Fulfilment takes the order on (picking starts). From READY_TO_FULFILL, or from PAID for an
     * order with nothing to print — an order with print lines that skipped production would ship
     * blank cups (BR-PRD-06).
     */
    public void startFulfilment() {
        if (status == OrderStatus.PAID && hasPrintLines()) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    "release it to production first: it has lines to print");
        }
        transitionTo(OrderStatus.IN_FULFILMENT);
    }

    /**
     * The Order Coordinator releases the order from a warehouse (SCRUM-423, 03 §9): with lines to
     * print it goes to IN_PRODUCTION and asks production for them, otherwise straight to
     * READY_TO_FULFILL.
     *
     * <p>A PAID order may be released; so may a DEPOSIT order still awaiting the balance, once its
     * deposit has arrived (BR-PRD-09). Whether every new design has an approved sample (BR-PRD-08) is
     * the caller's to check — it needs the sample records — and {@code approvedSamples} carries the
     * answer per print line: the sample id, or null for a repeat design.</p>
     */
    public void release(UUID warehouse, String warehouseCode, UUID by, Instant now,
                        java.util.Map<UUID, UUID> approvedSamples) {
        java.util.Objects.requireNonNull(warehouse, "warehouse");
        java.util.Objects.requireNonNull(by, "by");
        boolean depositOrderAwaitingBalance = status == OrderStatus.PENDING_PAYMENT && paymentTerm == PaymentTerm.DEPOSIT;
        if (status != OrderStatus.PAID && !depositOrderAwaitingBalance) {
            throw new InvalidOrderTransitionException(orderNumber, status, "only a paid order can be released");
        }
        if (paymentTerm == PaymentTerm.DEPOSIT && depositReceivedAt == null && hasPrintLines()) {
            throw new com.stockflow.common.error.BusinessException(
                    com.stockflow.common.error.ErrorCode.ORDER_DEPOSIT_NOT_RECEIVED,
                    "Order %s cannot go to production before its deposit arrives (BR-PRD-09)".formatted(orderNumber));
        }
        OrderStatus target = hasPrintLines() ? OrderStatus.IN_PRODUCTION : OrderStatus.READY_TO_FULFILL;
        if (depositOrderAwaitingBalance && target != OrderStatus.IN_PRODUCTION) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    "a deposit order with nothing to print is released once paid in full");
        }
        transitionTo(target);
        this.warehouseId = warehouse;
        this.releasedAt = now;
        this.releasedBy = by;
        if (target == OrderStatus.IN_PRODUCTION) {
            registerEvent(new OrderEvent.LinesReleased(new com.stockflow.contracts.OrderLinesReleasedForProduction(
                    id.value(), orderNumber.value(), warehouse,
                    printLines().stream().map(line -> new com.stockflow.contracts.OrderLinesReleasedForProduction.Line(
                            line.id(), line.sku().code(), line.quantity(), line.designSnapshotId(),
                            line.designChecksum(), approvedSamples.get(line.id()), null)).toList())));
        } else {
            registerEvent(new OrderEvent.Released(new com.stockflow.contracts.OrderReleased(
                    id.value(), warehouseCode, now)));
        }
    }

    /**
     * Production delivered every print line (BR-PRD-06): {@code producedByLine} is the good quantity
     * finished per line so far. Does nothing while a line is still short.
     *
     * @return whether the order moved to READY_TO_FULFILL
     */
    public boolean completeProduction(java.util.Map<UUID, Integer> producedByLine, String warehouseCode, Instant now) {
        if (status != OrderStatus.IN_PRODUCTION) {
            return false;
        }
        boolean allDone = printLines().stream()
                .allMatch(line -> producedByLine.getOrDefault(line.id(), 0) >= line.quantity());
        if (!allDone) {
            return false;
        }
        transitionTo(OrderStatus.READY_TO_FULFILL);
        registerEvent(new OrderEvent.Released(new com.stockflow.contracts.OrderReleased(id.value(), warehouseCode, now)));
        return true;
    }

    /** Lines printed to order: they carry a design snapshot. */
    public List<OrderLine> printLines() {
        return lines.stream().filter(OrderLine::isMadeToOrder).toList();
    }

    public boolean hasPrintLines() {
        return lines.stream().anyMatch(OrderLine::isMadeToOrder);
    }

    /** Rehydration of the payment terms and the release, from the row. */
    public void restoreTermsAndRelease(PaymentTerm term, java.math.BigDecimal deposit, Instant depositReceived,
                                       UUID warehouse, Instant released, UUID releasedByUser) {
        this.paymentTerm = term == null ? PaymentTerm.PREPAID : term;
        this.depositRequired = deposit;
        this.depositReceivedAt = depositReceived;
        this.warehouseId = warehouse;
        this.releasedAt = released;
        this.releasedBy = releasedByUser;
    }

    public void putOnHold() {
        if (status != OrderStatus.ON_HOLD) { transitionTo(OrderStatus.ON_HOLD); }
    }

    public void resumeFromHold() { transitionTo(OrderStatus.IN_FULFILMENT); }

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
            throw new InvalidOrderTransitionException(orderNumber, status, "raise a return instead");
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
            throw new InvalidOrderTransitionException(orderNumber, status, target);
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
    public String contactName() { return contactName; }
    public String contactEmail() { return contactEmail; }
    public String contactPhone() { return contactPhone; }
    public OrderAddressSnapshot shippingAddress() { return shippingAddress; }
    public OrderAddressSnapshot billingAddress() { return billingAddress; }
    public UUID requestId() { return requestId; }
    public OrderStatus status() { return status; }
    public Instant placedAt() { return placedAt; }
    public String cancellationReason() { return cancellationReason; }
    public PaymentTerm paymentTerm() { return paymentTerm; }
    public java.math.BigDecimal depositRequired() { return depositRequired; }
    public Instant depositReceivedAt() { return depositReceivedAt; }
    public UUID warehouseId() { return warehouseId; }
    public Instant releasedAt() { return releasedAt; }
    public UUID releasedBy() { return releasedBy; }
    public long version() { return version; }
    public String createdBy() { return createdBy; }
    public Instant lastModifiedAt() { return lastModifiedAt; }
    public String lastModifiedBy() { return lastModifiedBy; }

    public List<OrderLine> lines() {
        return java.util.Collections.unmodifiableList(lines);
    }
}
