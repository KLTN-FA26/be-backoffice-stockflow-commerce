package com.stockflow.order.internal.domain;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *       customer paying for stock nobody set aside;</li>
 *   <li>it is CONFIRMED only once the prepayment, or the deposit, is in (kltn-docs 15 BR-02), and
 *       the money received only ever grows;</li>
 *   <li>a cancellation names a reason code and keeps no more than was paid (15 BR-05).</li>
 * </ul>
 *
 * <p>The last one is worth dwelling on, because it is the invariant a distributed system cannot
 * express. Across services, "order saved" and "stock reserved" are two commits, so there is always
 * a window where one is true and the other is not, and a saga exists solely to clean that window
 * up afterwards. Here both happen in one transaction and the window does not exist, so the rule
 * can simply be checked and enforced.</p>
 */
public final class Order extends AggregateRoot {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

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
    private CancellationReasonCode cancellationReasonCode;
    private BigDecimal cancellationRetainedAmount;
    private PaymentTerm paymentTerm = PaymentTerm.PREPAID;
    private java.math.BigDecimal depositRequired;
    private Instant depositReceivedAt;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private Instant paidInFullAt;
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

    /**
     * The payment terms the order is sold on, fixed before it is submitted (kltn-docs 15 §3: the
     * term of the order, and the deposit percentage of the customer's terms or the quote).
     *
     * <p>A deposit is {@code depositPercent} of the total, rounded to the currency's whole unit. CREDIT
     * needs the customer's credit limit, which arrives with SCRUM-427; until then it is refused.</p>
     */
    public void applyTerms(PaymentTerm term, BigDecimal depositPercent) {
        if (status != OrderStatus.DRAFT) {
            throw new IllegalStateException("Terms are fixed when the order is placed");
        }
        PaymentTerm chosen = term == null ? PaymentTerm.PREPAID : term;
        if (chosen == PaymentTerm.CREDIT) {
            throw new BusinessException(ErrorCode.PAYMENT_TERM_NOT_ALLOWED,
                    "Credit orders need the customer's credit limit (SCRUM-427)");
        }
        if (chosen == PaymentTerm.DEPOSIT) {
            if (depositPercent == null || depositPercent.signum() <= 0 || depositPercent.compareTo(HUNDRED) >= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A deposit order needs a deposit percentage above 0 and below 100");
            }
            Money total = total();
            this.depositRequired = total.amount().multiply(depositPercent)
                    .divide(HUNDRED, total.currency().getDefaultFractionDigits(), RoundingMode.HALF_UP);
        } else if (depositPercent != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only a deposit order takes a deposit percentage");
        }
        this.paymentTerm = chosen;
    }

    /**
     * Money received for the order, as payment reports it (one call per captured payment; the
     * caller makes a redelivered payment arrive once).
     *
     * <p>kltn-docs 15 BR-02: a prepaid order is CONFIRMED once paid in full, a deposit order once its
     * deposit is in. Later money — the balance of a deposit order arriving while it is in production —
     * only adds up: it never moves the order, so a payment can never fail on an order that has moved
     * on. Money for a cancelled order is recorded and left for Sales to settle with the customer
     * (15, "tiền về sau khi đơn đã huỷ").</p>
     *
     * @return whether this payment confirmed the order
     */
    public boolean recordPayment(Money amount, Instant now) {
        java.util.Objects.requireNonNull(amount, "amount");
        if (!amount.currency().equals(total().currency())) {
            throw new IllegalArgumentException("Order %s is in %s, not %s"
                    .formatted(orderNumber, total().currency(), amount.currency()));
        }
        if (amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("A payment adds money");
        }
        this.paidAmount = paidAmount.add(amount.amount());
        if (paidInFullAt == null && paidAmount.compareTo(total().amount()) >= 0) {
            this.paidInFullAt = now;
        }
        if (paymentTerm == PaymentTerm.DEPOSIT && depositReceivedAt == null
                && paidAmount.compareTo(depositRequired) >= 0) {
            this.depositReceivedAt = now;
        }
        if (status != OrderStatus.PENDING_PAYMENT) {
            return false;
        }
        boolean cleared = switch (paymentTerm) {
            case PREPAID -> paidInFullAt != null;
            case DEPOSIT -> depositReceivedAt != null;
            case CREDIT -> false;
        };
        if (cleared) {
            transitionTo(OrderStatus.CONFIRMED);
        }
        return cleared;
    }

    /** kltn-docs 15 §5.2, derived from the term and the money received. */
    public PaymentStatus paymentStatus() {
        return PaymentStatus.of(paymentTerm, paidAmount, paidInFullAt);
    }

    /**
     * Fulfilment takes the order on (picking starts). From READY_TO_FULFILL, or from CONFIRMED for
     * an order with nothing to print — an order with print lines that skipped production would ship
     * blank cups (BR-PRD-06).
     */
    public void startFulfilment() {
        if (status == OrderStatus.CONFIRMED && hasPrintLines()) {
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
     * <p>Only a CONFIRMED order may be released: paid in full, or for a deposit order once the deposit
     * is in (kltn-docs 15 §4.2, BR-PRD-09); its balance is collected later, at the latest on delivery
     * (17 §4.3). Whether every new design has an approved sample (BR-PRD-08) is the caller's to check
     * — it needs the sample records — and {@code approvedSamples} carries the answer per print line:
     * the sample id, or null for a repeat design.</p>
     */
    public void release(UUID warehouse, String warehouseCode, UUID by, Instant now,
                        java.util.Map<UUID, UUID> approvedSamples) {
        java.util.Objects.requireNonNull(warehouse, "warehouse");
        java.util.Objects.requireNonNull(by, "by");
        if (status == OrderStatus.PENDING_PAYMENT && paymentTerm == PaymentTerm.DEPOSIT) {
            throw new BusinessException(ErrorCode.ORDER_DEPOSIT_NOT_RECEIVED,
                    "Order %s cannot be released before its deposit arrives (BR-PRD-09)".formatted(orderNumber));
        }
        if (status != OrderStatus.CONFIRMED) {
            throw new InvalidOrderTransitionException(orderNumber, status, "only a confirmed order can be released");
        }
        OrderStatus target = hasPrintLines() ? OrderStatus.IN_PRODUCTION : OrderStatus.READY_TO_FULFILL;
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

    /** Rehydration of the money received and how the order was cancelled, from the row. */
    public void restorePaymentAndCancellation(BigDecimal paid, Instant paidInFull, CancellationReasonCode code,
                                              BigDecimal retained) {
        this.paidAmount = paid == null ? BigDecimal.ZERO : paid;
        this.paidInFullAt = paidInFull;
        this.cancellationReasonCode = code;
        this.cancellationRetainedAmount = retained;
    }

    public void putOnHold() {
        if (status != OrderStatus.ON_HOLD) { transitionTo(OrderStatus.ON_HOLD); }
    }

    public void resumeFromHold() { transitionTo(OrderStatus.IN_FULFILMENT); }

    /**
     * Cancel, and announce it so the money and the work in progress follow (kltn-docs 17 §4.5,
     * BR-03; SCRUM-460).
     *
     * <p>Refused from SHIPPED onwards (BR-031): the goods are with the carrier and the correct
     * process is a return — {@code ORDER_NOT_CANCELLABLE}, naming the current status so the caller
     * can tell the user why.</p>
     *
     * <p>{@code retainedPercent} is the share of the money received kept for work already done — a
     * print run under way (17 §4.4, 15 §4.4); the rest is refundable. 0 or null keeps nothing.</p>
     *
     * @param by who cancelled; null when the system did (payment failed, hold expired)
     */
    public void cancel(CancellationReasonCode code, String note, BigDecimal retainedPercent, UUID by, Instant now) {
        java.util.Objects.requireNonNull(code, "code");
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        // ck_order_cancellation_code says it again in the table.
        if (code == CancellationReasonCode.OTHER && trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cancellation for OTHER must say why in the note");
        }
        if (retainedPercent != null && (retainedPercent.signum() < 0 || retainedPercent.compareTo(HUNDRED) > 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The retained share is between 0 and 100 percent");
        }
        if (!status.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order %s is %s and can no longer be cancelled; raise a return instead".formatted(orderNumber, status));
        }
        OrderStatus previous = status;
        int scale = total().currency().getDefaultFractionDigits();
        BigDecimal retained = retainedPercent == null ? BigDecimal.ZERO
                : paidAmount.multiply(retainedPercent).divide(HUNDRED, scale, RoundingMode.HALF_UP);
        this.status = OrderStatus.CANCELLED;
        this.cancellationReasonCode = code;
        // The free-text column the timeline shows: the code, and the note when there is one.
        this.cancellationReason = trimmed == null ? code.name() : code.name() + ": " + trimmed;
        this.cancellationRetainedAmount = retained;
        registerEvent(new OrderEvent.Cancelled(new OrderCancelled(id.value(), orderNumber.value(), customerId,
                previous.name(), code.name(), trimmed, paidAmount, paidAmount.subtract(retained),
                total().currency().getCurrencyCode(), by, now)));
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
    public CancellationReasonCode cancellationReasonCode() { return cancellationReasonCode; }
    public BigDecimal cancellationRetainedAmount() { return cancellationRetainedAmount; }
    public BigDecimal paidAmount() { return paidAmount; }
    public Instant paidInFullAt() { return paidInFullAt; }
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
