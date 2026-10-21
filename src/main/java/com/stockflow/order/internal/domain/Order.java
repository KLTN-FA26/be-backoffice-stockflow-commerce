package com.stockflow.order.internal.domain;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShipmentHandover;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>it is CONFIRMED only once the prepayment, or the deposit, is in (kltn-docs 15 BR-02). The money received
 *       grows with every payment and falls only by a reversal the accountant made (G2-3, 15 BR-06): a confirmed order
 *       left short of its terms by one goes ON_HOLD (reason PAYMENT), never silently back to unpaid;</li>
 *   <li>its goods leave only once the money its terms want before the handover is in (G3-Q4, G2-3): everything for a
 *       prepaid order, the balance for a deposit order paid before delivery, the deposit for one the carrier
 *       collects;</li>
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
    /** Decision Q2: why and on what terms an agreed amount was kept; null when a percentage (or nothing) was. */
    private String cancellationFeeReason;
    /** Decision Q6a: the whole agreed amount; above {@code paidAmount} the rest is owed (a receivable). */
    private BigDecimal cancellationFeeAmount;
    private String cancellationFeeDetail;
    /**
     * When the first production order of the order started printing - in house, or sent to a subcontractor (audit of
     * #109, decision 2026-10-17). Before it a cancellation is refunded in full; from it Sales may keep an agreed fee.
     * Written by production in its own transaction, under this order's row lock, so a cancellation sees it or not.
     */
    private Instant printingStartedAt;
    /**
     * When a package of the order was completed (03 §9: PACKED and later is not cancelled, it goes through a return).
     * Written by fulfillment in the transaction that completes the package, under this order's row lock.
     */
    private Instant packedAt;
    private PaymentTerm paymentTerm = PaymentTerm.PREPAID;
    private java.math.BigDecimal depositRequired;
    private Instant depositReceivedAt;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private Instant paidInFullAt;
    private Integer creditTermDays;
    /** G2-3: how a deposit order's balance is collected; null for other terms. */
    private BalancePolicy balancePolicy;
    /** G2-3: what the carrier collects on a whole-order handover (ON_DELIVERY, no shipments recorded). */
    private BigDecimal codAmount = BigDecimal.ZERO;
    private UUID warehouseId;
    private Instant releasedAt;
    private UUID releasedBy;
    /** Why it is ON_HOLD, and the status it goes back to (null for a credit hold: decided by credit approval). */
    private HoldReason holdReason;
    private OrderStatus statusBeforeHold;
    private String holdNote;
    /** When the order last became SHIPPED / DELIVERED / COMPLETED / REFUNDED / CLOSED (G3-2). */
    private Instant shippedAt;
    private Instant deliveredAt;
    private Instant completedAt;
    private Instant refundedAt;
    private Instant closedAt;
    /** Set while DELIVERY_FAILED: the reason of the (first) failed delivery. */
    private DeliveryFailureReason deliveryFailureReason;
    /**
     * What deliveries of this order billed as receivables (a credit order), and how much of {@code paidAmount} came as
     * payments of those receivables - together they say how much of the order is still to bill (credit exposure).
     */
    private BigDecimal billedAmount = BigDecimal.ZERO;
    private BigDecimal receivablePaidAmount = BigDecimal.ZERO;
    /** Refunds asked for when a remainder was closed, so a second closure asks only for the difference. */
    private BigDecimal remainderRefundAmount = BigDecimal.ZERO;
    private final List<OrderShipment> shipments = new ArrayList<>();
    /** Status changes not saved yet, with who and why; drained by the repository. */
    private final List<OrderTransition> transitions = new ArrayList<>();
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
     * Holds production hands to a line after checkout (reprints, subcontracted children); see
     * {@code OrderReservations}. Idempotent.
     *
     * @return whether a hold was added
     */
    public boolean addProductionHolds(UUID lineId, List<UUID> reservationIds) {
        boolean known = lines.stream().anyMatch(line -> line.id().equals(lineId));
        if (!known) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "Line %s does not belong to order %s".formatted(lineId, orderNumber));
        }
        return lineOf(lineId).addHolds(reservationIds);
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
        requireEveryLineReserved();
        transitionTo(OrderStatus.PENDING_PAYMENT);
        registerPlaced();
    }

    private void requireEveryLineReserved() {
        List<OrderLine> unreserved = lines.stream()
                .filter(line -> !line.isReserved())
                .toList();
        if (!unreserved.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot submit order %s: %d line(s) hold no stock reservation"
                            .formatted(orderNumber, unreserved.size()));
        }
    }

    private void registerPlaced() {
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

    /** {@link #applyTerms(PaymentTerm, BigDecimal, Integer)} for prepaid and deposit orders. */
    public void applyTerms(PaymentTerm term, BigDecimal depositPercent) {
        applyTerms(term, depositPercent, null);
    }

    /** A deposit order pays its balance before delivery (G2-3), unless {@link #applyTerms(PaymentTerm, BigDecimal,
     *  Integer, BalancePolicy)} says otherwise. */
    public void applyTerms(PaymentTerm term, BigDecimal depositPercent, Integer creditTermDays) {
        applyTerms(term, depositPercent, creditTermDays, null);
    }

    /**
     * The payment terms the order is sold on, fixed before it is submitted (kltn-docs 15 §3: the
     * term of the order, and the deposit percentage of the customer's terms or the quote). Whether
     * the customer may use the term (15 BR-01) is the caller's to check — it needs their terms.
     *
     * <p>A deposit is {@code depositPercent} of the total, rounded to the currency's whole unit. A
     * credit order keeps {@code creditTermDays}, the days it has to be paid after delivery (SCRUM-427). A deposit order
     * keeps {@code balancePolicy}, how its balance is collected (G2-3; null: before delivery). Whether the customer may
     * use the policy is the caller's to check, as for the term.</p>
     */
    public void applyTerms(PaymentTerm term, BigDecimal depositPercent, Integer creditTermDays,
                           BalancePolicy balancePolicy) {
        if (status != OrderStatus.DRAFT) {
            throw new IllegalStateException("Terms are fixed when the order is placed");
        }
        assignTerms(term, depositPercent, creditTermDays, balancePolicy);
    }

    /** Validates everything first, then assigns: a refused change leaves the order as it was. */
    private void assignTerms(PaymentTerm term, BigDecimal depositPercent, Integer creditTermDays,
                             BalancePolicy policy) {
        PaymentTerm chosen = term == null ? PaymentTerm.PREPAID : term;
        if ((chosen == PaymentTerm.CREDIT) != (creditTermDays != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only a credit order has days to pay");
        }
        if (chosen != PaymentTerm.DEPOSIT && policy != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only a deposit order has a balance policy");
        }
        BigDecimal deposit = null;
        if (chosen == PaymentTerm.DEPOSIT) {
            if (depositPercent == null || depositPercent.signum() <= 0 || depositPercent.compareTo(HUNDRED) >= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A deposit order needs a deposit percentage above 0 and below 100");
            }
            Money total = total();
            deposit = total.amount().multiply(depositPercent)
                    .divide(HUNDRED, total.currency().getDefaultFractionDigits(), RoundingMode.HALF_UP);
        } else if (depositPercent != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only a deposit order takes a deposit percentage");
        }
        this.paymentTerm = chosen;
        this.depositRequired = deposit;
        this.creditTermDays = creditTermDays;
        this.balancePolicy = chosen == PaymentTerm.DEPOSIT
                ? (policy == null ? BalancePolicy.BEFORE_DELIVERY : policy) : null;
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
        return recordPayment(amount, now, false);
    }

    /**
     * @param ofReceivable the money paid a receivable of this order (payment's allocation): it pays what a delivery
     *                     billed, so it does not lower what the order still has to bill (credit exposure)
     */
    public boolean recordPayment(Money amount, Instant now, boolean ofReceivable) {
        java.util.Objects.requireNonNull(amount, "amount");
        if (!amount.currency().equals(total().currency())) {
            throw new IllegalArgumentException("Order %s is in %s, not %s"
                    .formatted(orderNumber, total().currency(), amount.currency()));
        }
        if (amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("A payment adds money");
        }
        this.paidAmount = paidAmount.add(amount.amount());
        if (ofReceivable) {
            this.receivablePaidAmount = receivablePaidAmount.add(amount.amount());
        }
        if (status == OrderStatus.CANCELLED && cancellationFeeAmount != null) {
            // Q6a: money arriving after a cancellation with an agreed fee pays that fee first - the receivable its
            // shortfall became, or a late transfer - so what is kept stays LEAST(fee, paid), as
            // ck_order_cancellation_fee says. Anything above the fee is the customer's, settled as before.
            this.cancellationRetainedAmount = cancellationFeeAmount.min(paidAmount);
        }
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

    /**
     * The accountant took back money an allocation had brought (G2-3, 15 BR-06: a reversal row in payment's ledger),
     * as payment reports it once per reversal. What was paid falls; "paid in full" and "deposit received" are no
     * longer true when the money no longer says so.
     *
     * <p>A confirmed order whose terms are no longer met - a prepaid order not paid in full, a deposit order without
     * its deposit - goes ON_HOLD with reason PAYMENT and the accountant's reason, so the coordinator sees it: it is never
     * moved back to PENDING_PAYMENT, and never left looking paid. An order already on hold stays on hold; resolving it
     * then waits for the money ({@link #resolveHold}). An order still waiting for payment simply waits for more; one
     * whose goods left owes it again ({@link #amountDue()}).</p>
     *
     * @param ofReceivable the money had paid a receivable of the order
     * @return whether the order was put on hold
     */
    public boolean reversePayment(Money amount, boolean ofReceivable, String reason, UUID by, Instant now) {
        java.util.Objects.requireNonNull(amount, "amount");
        if (!amount.currency().equals(total().currency()) || amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("A reversal takes back money in the order's currency");
        }
        BigDecimal back = amount.amount().min(paidAmount);
        this.paidAmount = paidAmount.subtract(back);
        if (ofReceivable) {
            this.receivablePaidAmount = receivablePaidAmount.subtract(back).max(BigDecimal.ZERO);
        }
        if (paidInFullAt != null && paidAmount.compareTo(total().amount()) < 0) {
            this.paidInFullAt = null;
        }
        if (paymentTerm == PaymentTerm.DEPOSIT && depositReceivedAt != null
                && paidAmount.compareTo(depositRequired) < 0) {
            this.depositReceivedAt = null;
        }
        // A reversal that raced a cancellation or a closure (the accountant reversed, the order was cancelled before it
        // heard of it): what was kept, and what was asked back, never exceed what is left of the money (BR-05,
        // ck_order_cancellation_retained, ck_order_billing). The rest is the accountant's to settle by hand; the
        // event is consumed, not retried forever.
        if (cancellationRetainedAmount != null && cancellationRetainedAmount.compareTo(paidAmount) > 0) {
            this.cancellationRetainedAmount = paidAmount;
        }
        if (remainderRefundAmount.compareTo(paidAmount) > 0) {
            this.remainderRefundAmount = paidAmount;
        }
        if (termsMet() || !status.mayBePutOnHold()) {
            return false;
        }
        putOnHold(HoldReason.PAYMENT, "Payment reversed: " + (reason == null || reason.isBlank() ? "no reason given"
                : reason.trim()), by, now);
        return true;
    }

    /**
     * Whether the money its terms want before the order may go on is in (15 BR-02), measured on what the order is worth
     * now (units closed out of it are not paid for); a credit order needs none.
     */
    public boolean termsMet() {
        BigDecimal threshold = threshold();
        return threshold == null || paidAmount.compareTo(threshold) >= 0;
    }

    /** Everything for a prepaid order, the deposit for a deposit order - both of what it is worth now. */
    private BigDecimal threshold() {
        return switch (paymentTerm) {
            case PREPAID -> billable();
            case DEPOSIT -> depositRequired.min(billable());
            case CREDIT -> null;
        };
    }

    /**
     * What a confirmed order must keep received to stay confirmed - its total if prepaid, its deposit if a deposit
     * order - while it is between confirmation and the handover; null when taking money back cannot un-confirm it
     * (G2-3: payment asks the accountant to agree to a hold first).
     */
    public BigDecimal confirmationThreshold() {
        boolean confirmedStage = status.mayBePutOnHold()
                || status == OrderStatus.ON_HOLD && holdReason != HoldReason.CREDIT;
        if (!confirmedStage) {
            return null;
        }
        return threshold();
    }

    /**
     * Submit a credit order (kltn-docs 15 §4.3, 17 §5; SCRUM-427): it skips PENDING_PAYMENT and is
     * CONFIRMED within the customer's limit, or ON_HOLD until whoever approves credit decides.
     * Whether it is within the limit is the caller's to decide — it needs the exposure.
     */
    public void submitOnCredit(boolean withinLimit) {
        if (paymentTerm != PaymentTerm.CREDIT) {
            throw new IllegalStateException("Order %s is not a credit order".formatted(orderNumber));
        }
        requireEveryLineReserved();
        transitionTo(withinLimit ? OrderStatus.CONFIRMED : OrderStatus.ON_HOLD, null,
                withinLimit ? null : HoldReason.CREDIT.name(), null);
        if (!withinLimit) {
            // A credit hold goes nowhere back: credit approval confirms it, a refusal cancels or re-terms it.
            this.holdReason = HoldReason.CREDIT;
            this.statusBeforeHold = null;
        }
        registerPlaced();
    }

    /** Over the limit, approved by whoever approves credit (15 BR-03): ON_HOLD → CONFIRMED. */
    public void approveCredit() {
        approveCredit(null, null);
    }

    public void approveCredit(UUID by, Instant at) {
        requireCreditHold();
        clearHold();
        transitionTo(OrderStatus.CONFIRMED, by, "CREDIT_APPROVED", at);
    }

    /**
     * Over the limit and refused, but the customer pays another way (15 §4.3): the order leaves the
     * hold for PENDING_PAYMENT on its new terms, like any prepaid or deposit order.
     */
    public void switchTermsAfterCreditRefusal(PaymentTerm term, BigDecimal depositPercent) {
        switchTermsAfterCreditRefusal(term, depositPercent, null);
    }

    /** @param balancePolicy for DEPOSIT, the customer's (G2-3); null means before delivery */
    public void switchTermsAfterCreditRefusal(PaymentTerm term, BigDecimal depositPercent,
                                              BalancePolicy balancePolicy) {
        requireCreditHold();
        if (term == null || term == PaymentTerm.CREDIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Switch to PREPAID or DEPOSIT");
        }
        assignTerms(term, depositPercent, null, term == PaymentTerm.DEPOSIT ? balancePolicy : null);
        clearHold();
        transitionTo(OrderStatus.PENDING_PAYMENT, null, "CREDIT_REJECTED", null);
    }

    private void requireCreditHold() {
        if (status != OrderStatus.ON_HOLD || paymentTerm != PaymentTerm.CREDIT) {
            throw new BusinessException(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD,
                    "Order %s is %s on %s terms, not waiting for a credit decision"
                            .formatted(orderNumber, status, paymentTerm));
        }
    }

    /**
     * The carrier delivered it, as the Order Coordinator records when the carrier reports back
     * (kltn-docs 09 §4.3, 17 §4.3): SHIPPED or DELIVERY_FAILED (a retry) → DELIVERED. Payment opens the receivable of
     * a credit order from the event (15 §4.3 step 2), for what is still unbilled.
     *
     * <p>The whole order at once: only for an order whose goods went out without shipments recorded on it (G4-Q5). An
     * order with shipments is delivered shipment by shipment ({@link #recordShipmentDelivered}).</p>
     */
    public void markDelivered(Instant now) {
        markDelivered(now, null);
    }

    public void markDelivered(Instant now, UUID by) {
        requireNoShipments();
        transitionTo(OrderStatus.DELIVERED, by, null, now);
        this.deliveryFailureReason = null;
        this.deliveredAt = now;
        BigDecimal billed = carrierCollects() ? billCarrier(codAmount) : billNow(billable());
        registerDelivered(null, billed, now);
    }

    /**
     * The carrier reported the order not delivered (09 §4.3 step 2): SHIPPED → DELIVERY_FAILED, with the reason and,
     * for OTHER, a note. The whole order at once, as {@link #markDelivered}; idempotent for the same reason.
     *
     * @return whether this changed the order
     */
    public boolean markDeliveryFailed(DeliveryFailureReason reason, String note, Instant now, UUID by) {
        java.util.Objects.requireNonNull(reason, "reason");
        String trimmed = trimmed(note);
        if (reason == DeliveryFailureReason.OTHER && trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A failed delivery for OTHER must say why in the note");
        }
        requireNoShipments();
        if (status == OrderStatus.DELIVERY_FAILED) {
            return false;
        }
        transitionTo(OrderStatus.DELIVERY_FAILED, by, withNote(reason.name(), trimmed), now);
        this.deliveryFailureReason = reason;
        return true;
    }

    /**
     * The goods of a failed delivery came back to the warehouse (09 §4.3 step 3, RTO through the returns receipt):
     * DELIVERY_FAILED → RETURNED. Nothing was billed, so nothing is owed for them. The whole order at once.
     *
     * @return whether this changed the order
     */
    public boolean markReturnedToOrigin(Instant now, UUID by) {
        requireNoShipments();
        if (status == OrderStatus.RETURNED) {
            return false;
        }
        transitionTo(OrderStatus.RETURNED, by, "RETURNED_TO_ORIGIN", now);
        this.deliveryFailureReason = null;
        return true;
    }

    /**
     * Everything was handed to the carrier at once, with no shipment recorded on the order (09 §4.1 step 3, BR-03):
     * PACKED → SHIPPED. Fulfillment's handover calls {@link #recordHandover} instead once it records shipments.
     *
     * @return whether this changed the order
     */
    public boolean markShipped(Instant handedOverAt, UUID by) {
        return markShipped(handedOverAt, by, null);
    }

    /**
     * @param ledger the money payment's ledger holds for the order, read under the order's lock in this transaction
     *               (G2-3): it counts an allocation committed a moment ago that the order has not heard of yet, so the
     *               carrier is never told to collect money already paid. Null: the order's own count.
     */
    public boolean markShipped(Instant handedOverAt, UUID by, LedgerMoney ledger) {
        java.util.Objects.requireNonNull(handedOverAt, "handedOverAt");
        requireNoShipments();
        if (status == OrderStatus.SHIPPED) {
            return false;
        }
        requireNotCancelled("its goods are not handed over");
        requireHandoverPaid(ledger);
        transitionTo(OrderStatus.SHIPPED, by, null, handedOverAt);
        this.shippedAt = handedOverAt;
        // G2-3: the carrier collects what is left on delivery - fixed now, as the label and the manifest print it.
        this.codAmount = carrierCollects() ? billable().subtract(paid(ledger)).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        return true;
    }

    private void requireNoShipments() {
        if (!shipments.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_DELIVERY_VIA_SHIPMENT,
                    "Order %s has shipments: record the outcome on each shipment".formatted(orderNumber));
        }
    }

    private void registerDelivered(UUID shipmentId, BigDecimal billed, Instant now) {
        Money total = total();
        registerEvent(new OrderEvent.Delivered(new com.stockflow.contracts.OrderDelivered(id.value(),
                orderNumber.value(), customerId, paymentTerm.name(), total.amount(), paidAmount,
                total.currency().getCurrencyCode(), creditTermDays, now, shipmentId, billed,
                balancePolicy == null ? null : balancePolicy.name())));
    }

    /** kltn-docs 15 §5.2, derived from the term and the money received. */
    public PaymentStatus paymentStatus() {
        return PaymentStatus.of(paymentTerm, paidAmount, paidInFullAt);
    }

    /**
     * The warehouse starts picking (kltn-docs 17 §4.2 step 6): READY_TO_FULFILL → PICKING. Only a released order -
     * its warehouse chosen (17 BR-02). Picking the stock lines of a mixed order while it is printed changes nothing
     * (17 §4.2 step 5): IN_PRODUCTION stays. An order already picking, packed or partly shipped is left as it is.
     *
     * @return whether this changed the order
     */
    public boolean startPicking(UUID by, Instant at) {
        switch (status) {
            case PICKING, PACKED, PARTIALLY_FULFILLED, IN_PRODUCTION -> {
                return false;
            }
            case READY_TO_FULFILL -> {
                transitionTo(OrderStatus.PICKING, by, null, at);
                return true;
            }
            default -> throw new InvalidOrderTransitionException(orderNumber, status, OrderStatus.PICKING);
        }
    }

    /**
     * Everything still to ship is packed (kltn-docs 08, 17 §5): READY_TO_FULFILL (printed goods only, or stock picked
     * while printing), PICKING, or PARTIALLY_FULFILLED (the rest of a split order) → PACKED. Records that a package was
     * completed, the fact the cancellation rules read. Refused while the order is on hold: resolve it first.
     *
     * @return whether this changed the order
     */
    public boolean markPacked(Instant at, UUID by) {
        java.util.Objects.requireNonNull(at, "at");
        if (status == OrderStatus.PACKED) {
            return recordPacked(at);
        }
        requireNotCancelled("its package is not completed");
        if (status == OrderStatus.ON_HOLD) {
            // ON_HOLD → PACKED is the resolution of a hold placed at PACKED, not packing: resolve the hold first.
            throw new InvalidOrderTransitionException(orderNumber, status, "resolve the hold before packing");
        }
        if (status == OrderStatus.PARTIALLY_FULFILLED && outstandingUnits() == 0) {
            throw new InvalidOrderTransitionException(orderNumber, status, "nothing of it is left to pack");
        }
        transitionTo(OrderStatus.PACKED, by, null, at);
        recordPacked(at);
        return true;
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
        boolean heldInProduction = status == OrderStatus.ON_HOLD && statusBeforeHold == OrderStatus.IN_PRODUCTION;
        if (status != OrderStatus.IN_PRODUCTION && !heldInProduction) {
            return false;
        }
        boolean allDone = printLines().stream()
                .allMatch(line -> producedByLine.getOrDefault(line.id(), 0) >= line.quantity());
        if (!allDone) {
            return false;
        }
        if (heldInProduction) {
            // Production finished while the order was held: resolving the hold takes it to READY_TO_FULFILL, not back
            // to a production that is over (review of G3-2). Nothing else moves while it is held.
            this.statusBeforeHold = OrderStatus.READY_TO_FULFILL;
            registerEvent(new OrderEvent.Released(new com.stockflow.contracts.OrderReleased(id.value(), warehouseCode,
                    now)));
            return true;
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
        restorePaymentAndCancellation(paid, paidInFull, code, retained, null);
    }

    public void restorePaymentAndCancellation(BigDecimal paid, Instant paidInFull, CancellationReasonCode code,
                                              BigDecimal retained, Integer creditDays) {
        this.creditTermDays = creditDays;
        this.paidAmount = paid == null ? BigDecimal.ZERO : paid;
        this.paidInFullAt = paidInFull;
        this.cancellationReasonCode = code;
        this.cancellationRetainedAmount = retained;
    }

    /**
     * Put the order on hold (kltn-docs 17 §5, edge cases; 09 edge "sai địa chỉ"): from any status up to PACKED, and a
     * partly shipped order, remembering the status it goes back to. OTHER needs a note. A second hold is refused: the
     * order is resolved first.
     */
    public void putOnHold(HoldReason reason, String note, UUID by, Instant at) {
        java.util.Objects.requireNonNull(reason, "reason");
        if (reason == HoldReason.CREDIT) {
            throw new IllegalArgumentException("A credit hold is placed by the credit check at checkout");
        }
        String trimmed = trimmed(note);
        if (reason == HoldReason.OTHER && trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A hold for OTHER must say why in the note");
        }
        if (!status.mayBePutOnHold()) {
            throw new InvalidOrderTransitionException(orderNumber, status, OrderStatus.ON_HOLD);
        }
        OrderStatus before = status;
        transitionTo(OrderStatus.ON_HOLD, by, withNote(reason.name(), trimmed), at);
        this.holdReason = reason;
        this.statusBeforeHold = before;
        this.holdNote = trimmed;
    }

    /**
     * Resolve the hold the coordinator or QC raised (17 §5): back to the status it was held from, then on with
     * whatever its shipments reported meanwhile. A credit hold is decided through credit approval
     * ({@code ORDER_CREDIT_HOLD_USE_APPROVAL}); an order not on hold is {@code ORDER_NOT_ON_HOLD}.
     */
    public void resolveHold(String note, UUID by, Instant at) {
        if (status != OrderStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.ORDER_NOT_ON_HOLD,
                    "Order %s is %s, not on hold".formatted(orderNumber, status));
        }
        if (holdReason == HoldReason.CREDIT || statusBeforeHold == null) {
            throw new BusinessException(ErrorCode.ORDER_CREDIT_HOLD_USE_APPROVAL,
                    "Order %s waits for a credit decision; decide it through credit approval".formatted(orderNumber));
        }
        if (holdReason == HoldReason.DESIGN_MISMATCH) {
            // The package is held too: QC or the warehouse manager reverify it, which resolves both together.
            throw new BusinessException(ErrorCode.ORDER_DESIGN_HOLD_USE_REVERIFICATION,
                    "Order %s waits for a design reverification at packing".formatted(orderNumber));
        }
        resumeFromHold(note, by, at);
    }

    /**
     * QC reverified the printed design (BR-DSG-003): a DESIGN_MISMATCH hold is resolved back where it was held from.
     *
     * @return false when the order was not on a design hold (nothing to resolve on the order)
     */
    public boolean resolveDesignHold(String note, UUID by, Instant at) {
        if (status != OrderStatus.ON_HOLD) {
            return false;
        }
        if (holdReason != HoldReason.DESIGN_MISMATCH) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    "it is held for %s: resolve that hold first".formatted(holdReason));
        }
        resumeFromHold(note, by, at);
        return true;
    }

    private void resumeFromHold(String note, UUID by, Instant at) {
        String trimmed = trimmed(note);
        if (trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Resolving a hold must say how in the note");
        }
        OrderStatus back = statusBeforeHold;
        HoldReason was = holdReason;
        // G2-3: a hold the payment reversal raised is resolved by money, not by a note; and production never resumes
        // for a deposit order whose deposit is gone (ck_order_deposit_before_production says so too).
        boolean depositGone = back == OrderStatus.IN_PRODUCTION && paymentTerm == PaymentTerm.DEPOSIT
                && depositReceivedAt == null;
        if ((was == HoldReason.PAYMENT || depositGone) && !termsMet()) {
            throw new BusinessException(ErrorCode.ORDER_BALANCE_NOT_PAID,
                    "Order %s is short of what its %s terms want; record the payment before resolving the hold"
                            .formatted(orderNumber, paymentTerm));
        }
        clearHold();
        transitionTo(back, by, withNote("HOLD_RESOLVED_" + was.name(), trimmed), at);
        followShipments(ShipmentChange.RESUMED, at, by);
    }

    private void clearHold() {
        this.holdReason = null;
        this.statusBeforeHold = null;
        this.holdNote = null;
    }

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
        cancel(code, note, retainedPercent, null, by, now);
    }

    /**
     * The same, keeping either a percentage or an amount agreed with the customer (decision Q2: Sales enters it,
     * nothing computes it), whole units of the currency, more than zero, with its reason and detail. The money
     * received pays it first: what is left of it is refunded (Q6b), and what it does not cover is owed — the
     * cancellation announces it and payment records a receivable (Q6a). Nothing is collected or marked paid.
     */
    public void cancel(CancellationReasonCode code, String note, BigDecimal retainedPercent,
                       com.stockflow.order.api.CancellationFee agreedFee, UUID by, Instant now) {
        java.util.Objects.requireNonNull(code, "code");
        if (agreedFee != null) {
            requireAgreedFee(agreedFee, retainedPercent);
        }
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
        requireNotPacked();
        // Decision 2026-10-17: once printing started, a person decides - the fee, the blanks, the printed goods - so
        // the system never cancels such an order on its own (referCancellationToStaff is its way instead).
        if (by == null && printingStartedAt != null) {
            throw new BusinessException(ErrorCode.CANCELLATION_NEEDS_STAFF,
                    "Order %s started printing: staff decide its cancellation".formatted(orderNumber));
        }
        // Decision 2026-10-17 (03 §9, BR-ORD-004): before printing nothing is kept - neither an agreed amount nor a
        // share; from the first print run Sales may keep what was agreed with the customer.
        boolean keepsSomething = agreedFee != null || retainedPercent != null && retainedPercent.signum() > 0;
        if (keepsSomething && printingStartedAt == null) {
            throw new BusinessException(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING,
                    "Order %s has not started printing: it is refunded in full and no fee is kept".formatted(orderNumber));
        }
        OrderStatus previous = status;
        int scale = total().currency().getDefaultFractionDigits();
        BigDecimal agreed = agreedFee == null ? null : agreedFee.amount().setScale(scale);
        // Q6a/Q6b: what was received pays the agreed amount first; the rest is refunded, a shortfall is owed.
        BigDecimal retained = agreed != null ? agreed.min(paidAmount)
                : retainedPercent == null ? BigDecimal.ZERO
                : paidAmount.multiply(retainedPercent).divide(HUNDRED, scale, RoundingMode.HALF_UP);
        this.cancellationFeeAmount = agreed;
        this.cancellationFeeReason = agreedFee == null ? null : agreedFee.reason();
        this.cancellationFeeDetail = agreedFee == null ? null : agreedFee.detail();
        // The free-text column the timeline shows: the code, and the note when there is one.
        String reasonText = trimmed == null ? code.name() : code.name() + ": " + trimmed;
        transitionTo(OrderStatus.CANCELLED, by, reasonText, now);
        clearHold();
        this.cancellationReasonCode = code;
        this.cancellationReason = reasonText;
        this.cancellationRetainedAmount = retained;
        registerEvent(new OrderEvent.Cancelled(new OrderCancelled(id.value(), orderNumber.value(), customerId,
                previous.name(), code.name(), trimmed, paidAmount, paidAmount.subtract(retained),
                total().currency().getCurrencyCode(), by, now,
                agreed == null ? BigDecimal.ZERO : agreed.subtract(retained))));
    }

    private void requireAgreedFee(com.stockflow.order.api.CancellationFee fee, BigDecimal retainedPercent) {
        if (retainedPercent != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Keep a percentage or an agreed amount, not both");
        }
        int digits = total().currency().getDefaultFractionDigits();
        if (fee.amount().signum() <= 0 || fee.amount().stripTrailingZeros().scale() > digits) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The agreed amount is more than zero, in whole %s"
                    .formatted(digits == 0 ? total().currency().getCurrencyCode() : "units of " + total().currency()));
        }
        // Decision 2026-10-17: above what was paid the rest is owed, but never above what the order is worth.
        if (fee.amount().compareTo(total().amount()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The agreed amount is at most the order total, %s %s"
                    .formatted(total().amount().stripTrailingZeros().toPlainString(), total().currency().getCurrencyCode()));
        }
        if (fee.reason() == null || fee.reason().isEmpty() || fee.reason().length() > 500
                || fee.detail() == null || fee.detail().isEmpty() || fee.detail().length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An agreed amount needs its reason (at most 500 characters) and its detail (at most 2000)");
        }
    }

    // ---- split shipments (decision G4-Q3) ----------------------------------------------------------------------

    /** What made the order look at its shipments again. */
    private enum ShipmentChange { HANDED_OVER, OUTCOME, RETURNED, CLOSED, RESUMED }

    /**
     * Fulfillment handed a shipment of the order to the carrier (kltn-docs 09 §4.1 step 3; G4-2a calls this in its
     * handover transaction, A1). The order records which units went, at its prices, then moves: PACKED / PICKING /
     * PARTIALLY_FULFILLED → PARTIALLY_FULFILLED while some units are still to go, → SHIPPED once every unit is
     * shipped or closed (09 BR-03, unless split - here split is allowed). The same shipment again changes nothing.
     *
     * <p>Refused while the order is on hold, cancelled, or not yet picking; refused when the order's terms want money
     * before the goods leave and it has not all arrived ({@code ORDER_BALANCE_NOT_PAID}, G3-Q4); refused when more
     * units would leave than the line has left ({@code ORDER_SHIPMENT_QUANTITY_EXCEEDED}). Everything handed over at
     * once from PICKING is refused too: the order is packed first.</p>
     *
     * @return whether this changed the order
     */
    public boolean recordHandover(ShipmentHandover handover) {
        return recordHandover(handover, null);
    }

    /** @param ledger as for {@link #markShipped(Instant, UUID, LedgerMoney)} */
    public boolean recordHandover(ShipmentHandover handover, LedgerMoney ledger) {
        java.util.Objects.requireNonNull(handover, "handover");
        Optional<OrderShipment> known = shipment(handover.shipmentId());
        Map<UUID, Integer> quantities = quantitiesOf(handover);
        if (known.isPresent()) {
            if (!known.get().quantities().equals(quantities)) {
                throw new BusinessException(ErrorCode.ORDER_SHIPMENT_CONFLICT,
                        "Shipment %s of order %s was handed over with other goods".formatted(handover.shipmentId(),
                                orderNumber));
            }
            return false;
        }
        requireNotCancelled("its goods are not handed over");
        if (status != OrderStatus.PICKING && status != OrderStatus.PACKED && status != OrderStatus.PARTIALLY_FULFILLED) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    status == OrderStatus.ON_HOLD ? "resolve the hold before handing goods over"
                            : "only a picking, packed or partly shipped order hands goods over");
        }
        requireHandoverPaid(ledger);
        Money value = Money.zero(total().currency());
        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            OrderLine line = findLine(entry.getKey()).orElseThrow(() -> new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "Line %s is not on order %s".formatted(entry.getKey(), orderNumber)));
            // Units that came back count as left to ship: they may be shipped again (split shipments, G4-Q3), and
            // outstandingUnits / markPacked count them the same way.
            int left = line.quantity() - line.cancelledQuantity() - shippedUnits(line.id(), false);
            if (entry.getValue() > left) {
                throw new BusinessException(ErrorCode.ORDER_SHIPMENT_QUANTITY_EXCEEDED,
                        "Line %s of order %s has %d unit(s) left to ship, not %d".formatted(line.sku().code(),
                                orderNumber, left, entry.getValue()));
            }
            value = value.plus(line.unitPrice().times(entry.getValue()));
        }
        boolean everythingGoes = outstandingUnits() == quantities.values().stream().mapToInt(Integer::intValue).sum();
        if (status == OrderStatus.PICKING && everythingGoes) {
            throw new InvalidOrderTransitionException(orderNumber, status, "pack it before handing everything over");
        }
        // G2-3: what the carrier collects for this shipment, fixed at the handover (15 BR-08): its value less the money
        // paid ahead that the shipments before it have not used.
        BigDecimal cod = carrierCollects() ? value.amount().subtract(availablePaidAhead(ledger)).max(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        shipments.add(OrderShipment.handedOver(handover.shipmentId(), quantities, value.amount(),
                handover.handedOverAt(), handover.handedOverBy(), cod));
        // Goods handed over were packed: from here the order goes through a return, never a cancellation.
        recordPacked(handover.handedOverAt());
        followShipments(ShipmentChange.HANDED_OVER, handover.handedOverAt(), handover.handedOverBy());
        return true;
    }

    private static Map<UUID, Integer> quantitiesOf(ShipmentHandover handover) {
        if (handover.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A shipment carries at least one line");
        }
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (ShipmentHandover.Line line : handover.lines()) {
            if (line.orderLineId() == null || line.quantity() <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Every shipped line names its line and units");
            }
            if (quantities.putIfAbsent(line.orderLineId(), line.quantity()) != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A shipment names each line once");
            }
        }
        return quantities;
    }

    /**
     * The carrier delivered one shipment (09 §4.3 step 1; the Order Coordinator records it, BR-02). A credit order
     * bills what the shipment carried, less what the customer paid ahead and has not been billed against
     * ({@code OrderDelivered}, one receivable per shipment). The order is DELIVERED once every shipment arrived.
     *
     * @return whether this changed the order
     */
    public boolean recordShipmentDelivered(UUID shipmentId, Instant at, UUID by) {
        OrderShipment shipment = requireShipment(shipmentId);
        if (shipment.status() == OrderShipmentStatus.DELIVERED) {
            return false;
        }
        if (!shipment.status().canTransitionTo(OrderShipmentStatus.DELIVERED)) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION,
                    "Shipment %s is %s and cannot become DELIVERED".formatted(shipmentId, shipment.status()));
        }
        BigDecimal billed = carrierCollects() ? billCarrier(shipment.codAmount()) : billNow(shipment.amount());
        shipment.delivered(at, by, billed);
        registerDelivered(shipmentId, billed, at);
        followShipments(ShipmentChange.OUTCOME, at, by);
        return true;
    }

    /** The carrier could not deliver one shipment (09 §4.3 step 2). OTHER needs a note. */
    public boolean recordShipmentDeliveryFailed(UUID shipmentId, DeliveryFailureReason reason, String note, Instant at,
                                                UUID by) {
        java.util.Objects.requireNonNull(reason, "reason");
        String trimmed = trimmed(note);
        if (reason == DeliveryFailureReason.OTHER && trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A failed delivery for OTHER must say why in the note");
        }
        if (!requireShipment(shipmentId).failed(reason, trimmed, at, by)) {
            return false;
        }
        followShipments(ShipmentChange.OUTCOME, at, by);
        return true;
    }

    /**
     * One failed shipment came back to the warehouse (09 §4.3 step 3, through the returns receipt): its units are no
     * longer with the customer, and were never billed. With others delivered the order is partial again; with all of
     * it back, RETURNED.
     */
    public boolean recordShipmentReturned(UUID shipmentId, Instant at, UUID by) {
        if (!requireShipment(shipmentId).returned(at, by)) {
            return false;
        }
        followShipments(ShipmentChange.RETURNED, at, by);
        return true;
    }

    /**
     * Staff close what is left of a partly fulfilled order (17 edge "short pick: phần còn lại huỷ + hoàn tiền phần
     * thiếu"; 17 §5 "Partially Fulfilled → chốt"): the units not shipped - never picked, or come back - will not be
     * delivered. The order then shows its shipments (SHIPPED, DELIVERY_FAILED, DELIVERED or RETURNED);
     * {@code OrderRemainderClosed} tells payment what to refund of the money received above what the order is now
     * worth, and fulfillment to stop the rest. Only from PARTIALLY_FULFILLED: an order on hold is resolved first.
     *
     * @return the value of the units closed
     */
    public BigDecimal closeRemainder(CancellationReasonCode code, String note, UUID by, Instant at) {
        java.util.Objects.requireNonNull(code, "code");
        String trimmed = trimmed(note);
        if (code == CancellationReasonCode.OTHER && trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Closing the rest for OTHER must say why in the note");
        }
        if (status != OrderStatus.PARTIALLY_FULFILLED) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    status == OrderStatus.ON_HOLD ? "resolve the hold before closing the rest"
                            : "only a partly fulfilled order has a rest to close");
        }
        Money closed = Money.zero(total().currency());
        List<com.stockflow.contracts.OrderRemainderClosed.Line> closedLines = new ArrayList<>();
        for (OrderLine line : lines) {
            int left = line.quantity() - line.cancelledQuantity() - shippedUnits(line.id(), false);
            if (left > 0) {
                line.closeUnits(left);
                closed = closed.plus(line.unitPrice().times(left));
                closedLines.add(new com.stockflow.contracts.OrderRemainderClosed.Line(line.id(), line.sku().code(), left));
            }
        }
        BigDecimal refundable = paidAmount.subtract(billable()).max(BigDecimal.ZERO).subtract(remainderRefundAmount)
                .max(BigDecimal.ZERO);
        this.remainderRefundAmount = remainderRefundAmount.add(refundable);
        String reason = withNote("REMAINDER_CLOSED_" + code.name(), trimmed);
        followShipments(ShipmentChange.CLOSED, at, by, reason);
        registerEvent(new OrderEvent.RemainderClosed(new com.stockflow.contracts.OrderRemainderClosed(id.value(),
                orderNumber.value(), customerId, code.name(), closedLines, closed.amount(), billable(), paidAmount,
                refundable, total().currency().getCurrencyCode(), by, at, UUID.randomUUID())));
        return closed.amount();
    }

    private void followShipments(ShipmentChange change, Instant at, UUID by) {
        followShipments(change, at, by, null);
    }

    /**
     * The order's status from its shipments, after one of them changed (see {@link OrderStatus}): an order with units
     * neither shipped nor closed is partial (or stays PACKED while those are packed and nothing came back); one with
     * every unit shipped or closed is in transit while a shipment is, failed once all have an outcome and one failed,
     * delivered once all arrived, returned when everything came back. On hold it waits: resolving looks again.
     */
    private void followShipments(ShipmentChange change, Instant at, UUID by, String reason) {
        if (shipments.isEmpty() || status == OrderStatus.ON_HOLD) {
            return;
        }
        OrderStatus target;
        if (outstandingUnits() > 0) {
            boolean allBack = shipments.stream().allMatch(OrderShipment::isReturned);
            if (allBack && unshippedUnits() == 0) {
                target = OrderStatus.RETURNED;
            } else if (change == ShipmentChange.HANDED_OVER || change == ShipmentChange.RETURNED
                    || status != OrderStatus.PACKED && status != OrderStatus.PICKING
                    && status != OrderStatus.PARTIALLY_FULFILLED) {
                target = OrderStatus.PARTIALLY_FULFILLED;
            } else {
                target = status;
            }
        } else if (shipments.stream().anyMatch(s -> s.status() == OrderShipmentStatus.HANDED_OVER)) {
            target = OrderStatus.SHIPPED;
        } else if (shipments.stream().anyMatch(s -> s.status() == OrderShipmentStatus.DELIVERY_FAILED)) {
            target = OrderStatus.DELIVERY_FAILED;
        } else if (shipments.stream().anyMatch(s -> s.status() == OrderShipmentStatus.DELIVERED)) {
            target = OrderStatus.DELIVERED;
        } else {
            target = OrderStatus.RETURNED;
        }
        if (target == status) {
            return;
        }
        if (reason == null && target == OrderStatus.DELIVERY_FAILED) {
            reason = shipments.stream().filter(s -> s.status() == OrderShipmentStatus.DELIVERY_FAILED)
                    .map(s -> s.failureReason().name()).findFirst().orElse(null);
        }
        transitionTo(target, by, reason, at);
        Instant lastHandover = shipments.stream().map(OrderShipment::handedOverAt).max(Instant::compareTo).orElse(at);
        switch (target) {
            case SHIPPED -> this.shippedAt = lastHandover;
            case DELIVERY_FAILED -> {
                this.shippedAt = shippedAt == null ? lastHandover : shippedAt;
                this.deliveryFailureReason = shipments.stream()
                        .filter(s -> s.status() == OrderShipmentStatus.DELIVERY_FAILED)
                        .map(OrderShipment::failureReason).findFirst().orElse(DeliveryFailureReason.OTHER);
            }
            case DELIVERED -> {
                this.shippedAt = shippedAt == null ? lastHandover : shippedAt;
                this.deliveredAt = at;
            }
            default -> { }
        }
        if (target != OrderStatus.DELIVERY_FAILED) {
            this.deliveryFailureReason = null;
        }
    }

    private Optional<OrderShipment> shipment(UUID shipmentId) {
        return shipments.stream().filter(s -> s.id().equals(shipmentId)).findFirst();
    }

    private OrderShipment requireShipment(UUID shipmentId) {
        return shipment(shipmentId).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_SHIPMENT_NOT_FOUND,
                "Order %s has no shipment %s handed over".formatted(orderNumber, shipmentId)));
    }

    /** Units of a line in shipments, counting the ones that came back or not. */
    private int shippedUnits(UUID lineId, boolean includingReturned) {
        return shipments.stream().filter(s -> includingReturned || !s.isReturned())
                .mapToInt(s -> s.quantityOf(lineId)).sum();
    }

    /** Units neither closed nor in a shipment that is still with the carrier or the customer. */
    public int outstandingUnits() {
        return lines.stream().mapToInt(l -> l.quantity() - l.cancelledQuantity() - shippedUnits(l.id(), false)).sum();
    }

    /** Units neither closed nor ever handed over. */
    private int unshippedUnits() {
        return lines.stream()
                .mapToInt(l -> Math.max(0, l.quantity() - l.cancelledQuantity() - shippedUnits(l.id(), true))).sum();
    }

    // ---- money of a partly fulfilled order -----------------------------------------------------------------------

    /**
     * What the order is worth to the customer now: its total, less the units staff closed. Units that came back are
     * still owed a delivery until they are closed - shipped again, or closed and so refunded - so they stay in it.
     */
    public BigDecimal billable() {
        BigDecimal closed = lines.stream().map(l -> l.unitPrice().times(l.cancelledQuantity()).amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total().amount().subtract(closed);
    }

    /** What the customer still owes on the order (never below zero; above, a refund is the payment module's). */
    public BigDecimal amountDue() {
        return billable().subtract(paidAmount).max(BigDecimal.ZERO);
    }

    /**
     * What must be paid before the goods may leave (G3-Q4: blocked at the handover, warned at packing; G2-3). A prepaid
     * order owes everything before (15 §4.1); a credit order owes on its due date (15 §4.3). A deposit order paid
     * before delivery owes its balance before (15 §4.2 step 3); one whose carrier collects the balance owes only the
     * deposit (normally in long before: it was confirmed by it).
     */
    public BigDecimal amountDueBeforeHandover() {
        return amountDueBeforeHandover(null);
    }

    private BigDecimal amountDueBeforeHandover(LedgerMoney ledger) {
        BigDecimal paid = paid(ledger);
        BigDecimal due = billable().subtract(paid).max(BigDecimal.ZERO);
        return switch (paymentTerm) {
            case PREPAID -> due;
            case DEPOSIT -> carrierCollects() ? depositRequired.subtract(paid).max(BigDecimal.ZERO).min(due) : due;
            case CREDIT -> BigDecimal.ZERO;
        };
    }

    /**
     * The money payment's ledger holds for the order (G2-3), read synchronously by the caller under the order's lock.
     *
     * @param collected net allocations and earlier payments, less refunds asked for
     * @param paidAhead the part of it not paid against a receivable
     */
    public record LedgerMoney(BigDecimal collected, BigDecimal paidAhead) {
    }

    /**
     * What was paid, as the order counted it or as the ledger has it, whichever is more: the ledger is ahead of the
     * order by the events not yet delivered; the order is ahead of the ledger only for money recorded outside it.
     */
    private BigDecimal paid(LedgerMoney ledger) {
        return ledger == null || ledger.collected() == null ? paidAmount : paidAmount.max(ledger.collected());
    }

    /** What carriers are to collect on delivery (G2-3): the balance not yet paid of an ON_DELIVERY order, else 0. */
    public BigDecimal amountDueOnDelivery() {
        return carrierCollects() ? amountDue() : BigDecimal.ZERO;
    }

    /**
     * What carriers are collecting now for goods they carry (G2-3): the COD of shipments handed over and not yet
     * delivered or returned, or of a whole-order handover still in transit. A transfer must not pay it as well.
     */
    public BigDecimal codInTransit() {
        if (!carrierCollects()) {
            return BigDecimal.ZERO;
        }
        if (shipments.isEmpty()) {
            return status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERY_FAILED ? codAmount : BigDecimal.ZERO;
        }
        return shipments.stream()
                .filter(s -> s.status() == OrderShipmentStatus.HANDED_OVER
                        || s.status() == OrderShipmentStatus.DELIVERY_FAILED)
                .map(OrderShipment::codAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean carrierCollects() {
        return paymentTerm == PaymentTerm.DEPOSIT && balancePolicy == BalancePolicy.ON_DELIVERY;
    }

    /**
     * Money paid ahead (not against a receivable) that no shipment still out or delivered has counted on (G2-3,
     * ON_DELIVERY): it pays the earliest shipments first, as for credit orders (G3-2). A shipment that came back
     * gives back what it counted on.
     */
    private BigDecimal availablePaidAhead(LedgerMoney ledger) {
        BigDecimal paidAhead = paidAmount.subtract(receivablePaidAmount).max(BigDecimal.ZERO);
        if (ledger != null && ledger.paidAhead() != null) {
            paidAhead = paidAhead.max(ledger.paidAhead());
        }
        BigDecimal counted = shipments.stream().filter(s -> !s.isReturned())
                .map(s -> s.amount().subtract(s.codAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return paidAhead.subtract(counted).max(BigDecimal.ZERO);
    }

    /** A delivery the carrier collected for bills exactly what it collected (09 BR-04): payment opens its receivable. */
    private BigDecimal billCarrier(BigDecimal collected) {
        BigDecimal billed = collected == null ? BigDecimal.ZERO : collected;
        this.billedAmount = billedAmount.add(billed);
        return billed;
    }

    private void requireHandoverPaid(LedgerMoney ledger) {
        BigDecimal due = amountDueBeforeHandover(ledger);
        if (due.signum() > 0) {
            throw new BusinessException(ErrorCode.ORDER_BALANCE_NOT_PAID,
                    "Order %s still owes %s %s before its goods may leave".formatted(orderNumber,
                            due.stripTrailingZeros().toPlainString(), total().currency().getCurrencyCode()));
        }
    }

    /**
     * Bills a delivery of goods worth {@code worth} (a credit order only, kltn-docs 15 §4.3 step 2): at most what the
     * order has left to bill, which money paid ahead - not against a receivable - already covers.
     */
    private BigDecimal billNow(BigDecimal worth) {
        if (paymentTerm != PaymentTerm.CREDIT) {
            return BigDecimal.ZERO;
        }
        // Money paid ahead pays the earliest deliveries first: a later shipment that comes back, and is then closed,
        // must not leave an earlier one billed for money the customer already paid (review of G3-2).
        BigDecimal billed = worth.subtract(unusedPaidAhead()).max(BigDecimal.ZERO);
        this.billedAmount = billedAmount.add(billed);
        return billed;
    }

    /** Money paid outside receivables (ahead of delivery) that no delivered shipment has used yet. */
    private BigDecimal unusedPaidAhead() {
        BigDecimal paidAhead = paidAmount.subtract(receivablePaidAmount).max(BigDecimal.ZERO);
        BigDecimal usedByDeliveries = deliveredValue().subtract(billedAmount).max(BigDecimal.ZERO);
        return paidAhead.subtract(usedByDeliveries).max(BigDecimal.ZERO);
    }

    /** The value of the goods in delivered shipments. */
    private BigDecimal deliveredValue() {
        return shipments.stream().filter(s -> s.status() == OrderShipmentStatus.DELIVERED).map(OrderShipment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * What is still to bill: the goods not delivered yet (closed units excluded, units that came back included - they
     * may ship again), less the money paid ahead that no delivery has used. The credit exposure query computes the
     * same from the row ({@code total - closed - billed - paid ahead}).
     */
    public BigDecimal unbilled() {
        return billable().subtract(deliveredValue()).subtract(unusedPaidAhead()).max(BigDecimal.ZERO);
    }

    // ---- after delivery ------------------------------------------------------------------------------------------

    /**
     * Delivered, settled and past the return window (kltn-docs 17 §4.3 step 10; BR-ORD-005): DELIVERED → COMPLETED.
     * A credit order still owing stays DELIVERED (17 edge), so does one with a return request open.
     *
     * @return whether this changed the order
     */
    public boolean complete(Instant now, java.time.Duration returnWindow, boolean hasOpenReturn) {
        if (status != OrderStatus.DELIVERED || deliveredAt == null || hasOpenReturn
                || deliveredAt.plus(returnWindow).isAfter(now) || amountDue().signum() > 0) {
            return false;
        }
        transitionTo(OrderStatus.COMPLETED, null, "RETURN_WINDOW_CLOSED", now);
        this.completedAt = now;
        return true;
    }

    /**
     * Closed for good a while after it was completed or refunded (G3-Q1, default 30 days): BR-05, nothing changes it
     * any more.
     *
     * @return whether this changed the order
     */
    public boolean close(Instant now, java.time.Duration after) {
        Instant since = switch (status) {
            case COMPLETED -> completedAt;
            case REFUNDED, PARTIALLY_REFUNDED -> refundedAt;
            default -> null;
        };
        if (since == null || since.plus(after).isAfter(now)) {
            return false;
        }
        transitionTo(OrderStatus.CLOSED, null, "CLOSED_AFTER_SETTLEMENT", now);
        this.closedAt = now;
        return true;
    }

    /** The customer asked to return delivered goods (RMA, SCRUM-243): DELIVERED → RETURN_REQUESTED. */
    public void requestReturn(UUID by, Instant at) {
        transitionTo(OrderStatus.RETURN_REQUESTED, by, null, at);
    }

    /** The returned goods arrived (RMA received, 17 §4.6 step 19): RETURN_REQUESTED → RETURNED. */
    public void markReturned(UUID by, Instant at) {
        if (status != OrderStatus.RETURN_REQUESTED) {
            throw new InvalidOrderTransitionException(orderNumber, status, OrderStatus.RETURNED);
        }
        transitionTo(OrderStatus.RETURNED, by, null, at);
    }

    /** The refund of returned goods is done (15 §4.4): RETURNED → REFUNDED or PARTIALLY_REFUNDED. */
    public void markRefunded(boolean partial, UUID by, Instant at) {
        transitionTo(partial ? OrderStatus.PARTIALLY_REFUNDED : OrderStatus.REFUNDED, by, null, at);
        this.refundedAt = at;
    }

    private void requireNotCancelled(String what) {
        if (status == OrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_CANCELLED,
                    "Order %s is cancelled; %s".formatted(orderNumber, what));
        }
    }

    private static String trimmed(String note) {
        return note == null || note.isBlank() ? null : note.trim();
    }

    private static String withNote(String code, String note) {
        return note == null ? code : code + ": " + note;
    }

    // ---- rehydration and reading of the lifecycle ------------------------------------------------------------------

    /** Rehydration of the hold, the milestones and the money billed, from the row. */
    public void restoreLifecycle(HoldReason hold, OrderStatus before, String note, Instant shipped, Instant delivered,
                                 DeliveryFailureReason failure, Instant completed, Instant refunded, Instant closed,
                                 BigDecimal billed, BigDecimal paidOnReceivables, BigDecimal remainderRefund) {
        this.holdReason = hold;
        this.statusBeforeHold = before;
        this.holdNote = note;
        this.shippedAt = shipped;
        this.deliveredAt = delivered;
        this.deliveryFailureReason = failure;
        this.completedAt = completed;
        this.refundedAt = refunded;
        this.closedAt = closed;
        this.billedAmount = billed == null ? BigDecimal.ZERO : billed;
        this.receivablePaidAmount = paidOnReceivables == null ? BigDecimal.ZERO : paidOnReceivables;
        this.remainderRefundAmount = remainderRefund == null ? BigDecimal.ZERO : remainderRefund;
    }

    /** Rehydration of the shipments, from their rows. */
    public void restoreShipments(List<OrderShipment> recorded) {
        shipments.clear();
        shipments.addAll(recorded);
    }

    public HoldReason holdReason() { return holdReason; }
    public OrderStatus statusBeforeHold() { return statusBeforeHold; }
    public String holdNote() { return holdNote; }
    public Instant shippedAt() { return shippedAt; }
    public Instant deliveredAt() { return deliveredAt; }
    public DeliveryFailureReason deliveryFailureReason() { return deliveryFailureReason; }
    public Instant completedAt() { return completedAt; }
    public Instant refundedAt() { return refundedAt; }
    public Instant closedAt() { return closedAt; }
    public BigDecimal billedAmount() { return billedAmount; }
    public BigDecimal receivablePaidAmount() { return receivablePaidAmount; }
    public BigDecimal remainderRefundAmount() { return remainderRefundAmount; }
    public List<OrderShipment> shipments() { return java.util.Collections.unmodifiableList(shipments); }

    /** Every live hold this order is carrying, across all of its lines. */
    public List<UUID> reservationIds() {
        return lines.stream().flatMap(line -> line.reservationIds().stream()).toList();
    }

    /** Forget the holds after inventory has released them, so nothing releases them twice. */
    public void clearReservations() {
        lines.forEach(OrderLine::clearReservations);
    }

    private void transitionTo(OrderStatus target) {
        transitionTo(target, null, null, null);
    }

    /**
     * The one place the status moves: along an edge of {@link OrderStatus#canTransitionTo}, remembered with who made
     * the change and why for the history (SCRUM-247).
     */
    private void transitionTo(OrderStatus target, UUID actor, String reason, Instant at) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidOrderTransitionException(orderNumber, status, target);
        }
        OrderStatus from = status;
        this.status = target;
        transitions.add(new OrderTransition(from, target, actor, reason, at));
    }

    /** The status changes made since the order was loaded, oldest first; the repository calls this once per save. */
    public List<OrderTransition> pullTransitions() {
        List<OrderTransition> drained = List.copyOf(transitions);
        transitions.clear();
        return drained;
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
    public String cancellationFeeReason() { return cancellationFeeReason; }
    public BigDecimal cancellationFeeAmount() { return cancellationFeeAmount; }
    public String cancellationFeeDetail() { return cancellationFeeDetail; }

    /** Rehydration of the production and fulfilment facts the cancellation rules read. */
    public void restoreFulfilmentFacts(Instant printingStarted, Instant packed) {
        this.printingStartedAt = printingStarted;
        this.packedAt = packed;
    }

    public Instant printingStartedAt() { return printingStartedAt; }
    public Instant packedAt() { return packedAt; }

    /**
     * Printing of the order started (production, its own transaction, holding this row's lock). Once is enough; a
     * cancelled order prints nothing more.
     *
     * @return whether this changed the order
     */
    public boolean recordPrintingStarted(Instant at) {
        java.util.Objects.requireNonNull(at, "at");
        if (status == OrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_CANCELLED,
                    "Order %s is cancelled; nothing more is printed for it".formatted(orderNumber));
        }
        if (printingStartedAt != null) {
            return false;
        }
        printingStartedAt = at;
        return true;
    }

    /**
     * A package of the order was completed (fulfillment, the completing transaction, holding this row's lock). From
     * here the order is not cancelled: it goes through a return (03 §9).
     *
     * @return whether this changed the order
     */
    public boolean recordPacked(Instant at) {
        java.util.Objects.requireNonNull(at, "at");
        if (status == OrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_CANCELLED,
                    "Order %s is cancelled; its package is not completed".formatted(orderNumber));
        }
        if (packedAt != null) {
            return false;
        }
        packedAt = at;
        return true;
    }

    /**
     * Decision 2026-10-17: the system would cancel this order (a payment failed, an unpaid hold ran out) but printing
     * started, so it does not - it hands the order to staff, who settle fee, blanks and printed goods by hand.
     */
    public void referCancellationToStaff(CancellationReasonCode wouldHaveBeen, Instant now) {
        registerEvent(new OrderEvent.CancellationReferred(new com.stockflow.contracts.OrderCancellationReferred(
                id.value(), orderNumber.value(), wouldHaveBeen.name(), now)));
    }

    /** 03 §9: a packed order is not cancelled - by anyone, on any path; it goes through a return (RMA). */
    public void requireNotPacked() {
        if (packedAt != null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order %s is packed; it can no longer be cancelled - raise a return instead".formatted(orderNumber));
        }
    }

    /** Rehydration of the agreed amount's terms. */
    public void restoreCancellationFee(BigDecimal amount, String reason, String detail) {
        this.cancellationFeeAmount = amount;
        this.cancellationFeeReason = reason;
        this.cancellationFeeDetail = detail;
    }
    /** Rehydration of the balance policy and a whole-order handover's COD (G2-3). */
    public void restoreBalance(BalancePolicy policy, BigDecimal cod) {
        this.balancePolicy = policy;
        this.codAmount = cod == null ? BigDecimal.ZERO : cod;
    }

    public BalancePolicy balancePolicy() { return balancePolicy; }
    public BigDecimal codAmount() { return codAmount; }
    public BigDecimal paidAmount() { return paidAmount; }
    public Instant paidInFullAt() { return paidInFullAt; }
    public Integer creditTermDays() { return creditTermDays; }
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
