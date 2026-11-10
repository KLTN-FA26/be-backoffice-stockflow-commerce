package com.stockflow.order.internal.domain;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
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
 *   <li>it is CONFIRMED through its sales contract (14 BR-15, 15 BR-07): signed, and every installment on signing
 *       paid; a voided receipt that leaves it short holds it (reason PAYMENT), never silently back to unpaid (15
 *       BR-13). Its money lives on the contract's installments in payment; since 4B-10 the order has no payment terms
 *       and counts no money of its own;</li>
 *   <li>its goods leave only once the installments before delivery and on signing are paid (15 BR-07, BR-08), which
 *       the caller reads under the order's lock;</li>
 *   <li>a cancellation names a reason code; what it costs the customer is settled on the installments (4B-9).</li>
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

    /** 4B-3: the fee lines copied from the quote when the order was placed (14 BR-09, BR-17, BR-20); fixed. */
    private final List<OrderFee> fees = new ArrayList<>();
    private Instant placedAt;

    private OrderStatus status;
    private String cancellationReason;
    private CancellationReasonCode cancellationReasonCode;
    private BigDecimal cancellationRetainedAmount;
    /** Decision Q2: why and on what terms an agreed amount was kept; null when a percentage (or nothing) was. */
    private String cancellationFeeReason;
    /** Decision Q6a: the whole agreed amount; settled on the installments (4B-9): covered, or owed on a fee item. */
    private BigDecimal cancellationFeeAmount;
    private String cancellationFeeDetail;
    /**
     * When printing started for the order (audit of #109, decision 2026-10-17; 17 BR-11). Before it a cancellation is
     * refunded in full; from it Sales may keep an agreed fee. Printing is done by a producer outside the system
     * (kltn-docs 07/11): it is set once, when the first production order of the order enters In Production, whatever
     * moved it there ({@link #recordPrintingStarted}).
     */
    private Instant printingStartedAt;
    /**
     * When a package of the order was completed (03 §9: PACKED and later is not cancelled, it goes through a return).
     * Written by fulfillment in the transaction that completes the package, under this order's row lock.
     */
    private Instant packedAt;
    /**
     * SCRUM-392 (review of #162): when goods of the order first left the warehouse on a load plan's vehicle - before the
     * shipment they belong to is handed over at its last vehicle (LP-3). From then the order is no longer cancelled
     * (lead's #150 rule: cancel-with-fee only while nothing left), only its remainder closed or returned.
     */
    private Instant goodsLeftAt;
    /** 4B-5: when its sales contract was signed; null for an order placed at checkout. */
    private Instant contractSignedAt;
    /** 4B-7 (17 BR-22): the one warehouse an order of a contract ships from, chosen by Sales once it is CONFIRMED. */
    private UUID shippingWarehouseId;
    private Instant shippingWarehouseChosenAt;
    private UUID shippingWarehouseChosenBy;
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
     * 4B-9: the id of the closure {@link #closeRemainder} made in this unit of work, which the service hands payment
     * with the closure's money (a contract order's installments are settled in the same transaction). Not stored: the
     * closure's event carries it, and payment keys its settlement on it.
     */
    private UUID lastClosureId;
    /**
     * PACK-PARTIAL (lead's decision 2026-10-21): Sales decided the order may ship in parts - a packing completion that
     * leaves goods behind, or a handover of part of it. Off by default: the warehouse ships the order whole.
     */
    private boolean allowPartialShipment;
    /**
     * Review of #150 (task candidates): when fulfillment opened the order's task ({@link #startPicking}), in the same
     * transaction (A1). A published fact of the order's own: the create-task list leaves the order out from then on,
     * whatever its status does next (a mixed order printed in parallel goes back to READY_TO_FULFILL).
     */
    private Instant fulfilmentStartedAt;
    /**
     * When fulfillment opened the order's first package, in the same transaction under this order's row lock
     * ({@link #recordPackingStarted}). Every order ships as a full truckload (kltn-docs 09 BR-08): there is no
     * delivery mode to choose or lock.
     */
    private Instant packingStartedAt;
    /**
     * SCRUM-260: the return request whose accepted units this order replaces with new goods, free of charge; null for
     * an ordinary order.
     */
    private UUID replacesReturnRequestId;
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

    /**
     * What the customer pays: the total with tax (kltn-docs 14 BR-09) - the lines and the fee lines, each with its VAT.
     * An order placed without VAT (before 4B-3, or at checkout) adds none.
     */
    public Money total() {
        return netTotal().plus(taxTotal());
    }

    /** The total before tax: the lines' totals and the fee lines' amounts (14 BR-09). */
    public Money netTotal() {
        Money net = lines.stream().map(OrderLine::lineTotal).reduce(Money.zero(currency()), Money::plus);
        return fees.stream().map(OrderFee::amount).reduce(net, Money::plus);
    }

    /** The VAT of every line and fee line, each rounded to the dong (14 BR-21). */
    public Money taxTotal() {
        Money tax = lines.stream().map(OrderLine::taxAmount).filter(java.util.Objects::nonNull)
                .reduce(Money.zero(currency()), Money::plus);
        return fees.stream().map(OrderFee::taxAmount).reduce(tax, Money::plus);
    }

    /** The fee lines with their VAT. */
    private Money feesGross() {
        return fees.stream().map(OrderFee::gross).reduce(Money.zero(currency()), Money::plus);
    }

    private java.util.Currency currency() {
        return lines.getFirst().unitPrice().currency();
    }

    /**
     * The fee lines of the order, from its accepted quote (4B-3). Set once, when the order is drafted.
     */
    public void attachFees(List<OrderFee> placed) {
        if (placed == null || placed.isEmpty()) {
            return;
        }
        if (!fees.isEmpty()) {
            throw new IllegalStateException("Order " + orderNumber + " has its fee lines already");
        }
        for (OrderFee fee : placed) {
            if (!fee.amount().currency().equals(currency())) {
                throw new IllegalArgumentException("A fee is in the order's currency");
            }
        }
        fees.addAll(placed);
    }

    /** The fee lines, unmodifiable. */
    public List<OrderFee> fees() {
        return java.util.Collections.unmodifiableList(fees);
    }

    public void attachReservations(UUID lineId, List<UUID> reservationIds) {
        lineOf(lineId).attachReservations(reservationIds);
    }

    /**
     * Inventory allocated a SKU reservation of this order to bins and lots (SCRUM-152, F-FUL step 02), or a short
     * allocation again elsewhere (X-SHORT): the line holding {@code reservationId} holds {@code replacements} from
     * now on, which is what fulfillment picks and a cancellation releases. A reservation no line holds — replaced
     * already by an earlier call — is left alone, so applying the same allocation twice changes nothing.
     *
     * @return whether a line held it
     */
    public boolean replaceReservation(UUID reservationId, List<UUID> replacements) {
        return lines.stream().filter(line -> line.reservationIds().contains(reservationId)).findFirst()
                .map(line -> {
                    line.replaceHold(reservationId, replacements);
                    return true;
                })
                .orElse(false);
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
                total().currency().getCurrencyCode(),
                netTotal().amount(),
                taxTotal().amount())));
    }

    /**
     * Its contract sent to the customer (kltn-docs 14 §4.3 step 13): DRAFT → PENDING_CONTRACT. Sent again after
     * nothing changed, nothing changes.
     */
    public void sendContract(UUID by, Instant at) {
        if (status == OrderStatus.PENDING_CONTRACT) {
            return;
        }
        if (status != OrderStatus.DRAFT) {
            throw new InvalidOrderTransitionException(orderNumber, status, "send its contract");
        }
        transitionTo(OrderStatus.PENDING_CONTRACT, by, "CONTRACT_SENT", at);
    }

    /**
     * Its contract cancelled before it was signed, to be drawn up again (14 §4.3 step 16): PENDING_CONTRACT → DRAFT. A
     * contract cancelled while still a draft leaves the order as it was.
     */
    public void withdrawContract(UUID by, Instant at) {
        if (status == OrderStatus.PENDING_CONTRACT) {
            transitionTo(OrderStatus.DRAFT, by, "CONTRACT_CANCELLED", at);
        }
    }

    /**
     * Its contract signed (14 §4.3 step 15, BR-15; 15 BR-07, BR-11): held for credit when what it pays after delivery
     * does not fit - the customer is overdue or over the limit, which only accounting may then decide; waiting for an
     * installment on signing when there is one; confirmed otherwise. From here it is placed: {@code OrderPlaced}, which
     * the dashboard and the customer's e-mail count from.
     *
     * @param creditHold    whether the credit check at signing failed (OVERDUE or OVER_LIMIT)
     * @param paysOnSigning whether the contract has an installment due on signing
     */
    public void contractSigned(boolean creditHold, boolean paysOnSigning, UUID by, Instant at) {
        if (status != OrderStatus.PENDING_CONTRACT) {
            throw new InvalidOrderTransitionException(orderNumber, status, "record its contract signed");
        }
        this.contractSignedAt = java.util.Objects.requireNonNull(at, "at");
        // Placed when its contract is signed: the day the dashboard counts it from (17 §4.11) - not the day the quote
        // was converted into a draft.
        this.placedAt = at;
        if (creditHold) {
            transitionTo(OrderStatus.ON_HOLD, by, HoldReason.CREDIT.name(), at);
            // A credit hold goes nowhere back: accounting's approval decides it, a refusal cancels it.
            this.holdReason = HoldReason.CREDIT;
            this.statusBeforeHold = null;
        } else {
            transitionTo(paysOnSigning ? OrderStatus.PENDING_PAYMENT : OrderStatus.CONFIRMED, by, "CONTRACT_SIGNED",
                    at);
        }
        registerPlaced();
    }

    /**
     * Every installment its contract wants on signing is paid (4B-6, 15 BR-07, §4.2 step 7): an order waiting for it is
     * confirmed. Anything else - not an order of a contract, not waiting for that money - is left as it is.
     *
     * @return whether it was confirmed now
     */
    public boolean confirmByInstallments(Instant at) {
        if (!paidByContract() || status != OrderStatus.PENDING_PAYMENT) {
            return false;
        }
        transitionTo(OrderStatus.CONFIRMED, null, "INSTALLMENT_PAID", at);
        return true;
    }

    /** Over the limit, approved by whoever approves credit (15 BR-03): ON_HOLD → CONFIRMED. */
    public void approveCredit() {
        approveCredit(null, null);
    }

    public void approveCredit(UUID by, Instant at) {
        approveCredit(by, at, false);
    }

    /**
     * Approved by accounting (15 BR-11): ON_HOLD → PENDING_PAYMENT when its contract has an installment due on signing
     * (14 §5.3: approved, it still waits for that), CONFIRMED otherwise.
     */
    public void approveCredit(UUID by, Instant at, boolean paysOnSigning) {
        requireCreditHold();
        clearHold();
        transitionTo(paysOnSigning ? OrderStatus.PENDING_PAYMENT : OrderStatus.CONFIRMED, by, "CREDIT_APPROVED", at);
    }

    /**
     * Waiting for a credit decision: on hold for credit - an order whose contract pays after delivery and was signed by a
     * customer overdue or over the limit (4B-5), or, before 4B-10, a credit order placed over its limit at checkout.
     */
    private void requireCreditHold() {
        if (status != OrderStatus.ON_HOLD || holdReason != HoldReason.CREDIT) {
            throw new BusinessException(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD,
                    "Order %s is %s, not waiting for a credit decision".formatted(orderNumber, status));
        }
    }

    /**
     * The carrier delivered it, as the Order Coordinator records when the carrier reports back
     * (kltn-docs 09 §4.3, 17 §4.3): SHIPPED or DELIVERY_FAILED (a retry) → DELIVERED. Payment makes the contract's
     * installments paid after delivery due from the event (15 §4.1 step 2).
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
        registerDelivered(null, now);
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
        if (status == OrderStatus.DELIVERED) {
            // DELIVERED -> DELIVERY_FAILED is the warehouse manager's correction of a shipment the carrier reported
            // wrongly (correctShipmentToFailed, E17), never a failure recorded for the whole order.
            throw new InvalidOrderTransitionException(orderNumber, status, OrderStatus.DELIVERY_FAILED);
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
     * @param installmentsDue for an order of a contract, what its installments before delivery and on signing still
     *                        owe, read by the caller under the order's lock in this transaction (4B-6, 15 BR-07,
     *                        BR-08): a receipt recorded or voided a moment ago is counted. Null: nothing is wanted.
     */
    public boolean markShipped(Instant handedOverAt, UUID by, BigDecimal installmentsDue) {
        java.util.Objects.requireNonNull(handedOverAt, "handedOverAt");
        requireNoShipments();
        if (status == OrderStatus.SHIPPED) {
            return false;
        }
        requireNotCancelled("its goods are not handed over");
        requireHandoverPaid(installmentsDue);
        transitionTo(OrderStatus.SHIPPED, by, null, handedOverAt);
        this.shippedAt = handedOverAt;
        return true;
    }

    private void requireNoShipments() {
        if (!shipments.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_DELIVERY_VIA_SHIPMENT,
                    "Order %s has shipments: record the outcome on each shipment".formatted(orderNumber));
        }
    }

    /** The money ledger's fields of the event (term, money paid, days, bill, policy) are null since 4B-10. */
    private void registerDelivered(UUID shipmentId, Instant now) {
        Money total = total();
        registerEvent(new OrderEvent.Delivered(new com.stockflow.contracts.OrderDelivered(id.value(),
                orderNumber.value(), customerId, null, total.amount(), null, total.currency().getCurrencyCode(), null,
                now, shipmentId, null, null)));
    }

    /**
     * The value of the units closed out of the order (closed remainders): its total less what it is worth now. Stored as
     * {@code closed_amount}.
     */
    public BigDecimal closedAmount() {
        return total().amount().subtract(billable());
    }

    /**
     * The warehouse starts picking (kltn-docs 17 §4.2 step 6): READY_TO_FULFILL → PICKING. Only a released order -
     * its warehouse chosen (17 BR-02). An order already picking, packed or partly shipped is left as it is.
     *
     * @return whether this changed the order
     */
    public boolean startPicking(UUID by, Instant at) {
        boolean first = fulfilmentStartedAt == null;
        if (first) {
            this.fulfilmentStartedAt = at;
        }
        switch (status) {
            case PICKING, PACKED, PARTIALLY_FULFILLED -> {
                return first;
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
     * The order is released from a warehouse (SCRUM-423): CONFIRMED → READY_TO_FULFILL, every line picked from stock.
     * Printing is done outside the system (kltn-docs 07/11), so nothing waits for a print shop here; the goods of the
     * customer's own SKU are stock like any other (QD-16). Only a legacy line printed to a design snapshot is the release
     * service's to refuse ({@code ORDER_PRINT_GOODS_NOT_RECEIVED}).
     *
     * <p>Only a CONFIRMED order may be released - confirmed by its contract (15 BR-07). An order of a contract waiting
     * for stock is released the same way once every carton is held at its shipping warehouse:
     * AWAITING_STOCK → READY_TO_FULFILL (4A-6, 17 §5.1 "nhận đủ hàng"; the release service checks the holds).</p>
     */
    public void release(UUID warehouse, String warehouseCode, UUID by, Instant now) {
        java.util.Objects.requireNonNull(warehouse, "warehouse");
        java.util.Objects.requireNonNull(by, "by");
        if (status != OrderStatus.CONFIRMED && !(status == OrderStatus.AWAITING_STOCK && paidByContract())) {
            throw new InvalidOrderTransitionException(orderNumber, status, "only a confirmed order can be released");
        }
        if (paidByContract() && shippingWarehouseId == null) {
            throw new BusinessException(ErrorCode.ORDER_STOCK_NOT_HELD, ("Order %s has no shipping warehouse: choose it "
                    + "and hold its stock there first (17 §4.2)").formatted(orderNumber));
        }
        if (paidByContract() && !shippingWarehouseId.equals(warehouse)) {
            // 17 BR-22: one warehouse; it is released where its stock is held.
            throw new BusinessException(ErrorCode.ORDER_SHIPPING_WAREHOUSE_MISMATCH, ("Order %s ships from another "
                    + "warehouse").formatted(orderNumber));
        }
        if (paidByContract() && lines.stream().anyMatch(line -> !line.isReserved())) {
            // An order confirmed by its contract holds nothing until its shipping warehouse is chosen and its stock held
            // there (17 §4.2, 4B-7): released now, it would go to the warehouse with nothing to pick. (A checkout order
            // reserved its stock when it was placed.)
            throw new BusinessException(ErrorCode.ORDER_STOCK_NOT_HELD, ("Order %s holds no stock for some of its "
                    + "lines: choose its shipping warehouse and hold its stock first").formatted(orderNumber));
        }
        transitionTo(OrderStatus.READY_TO_FULFILL);
        this.warehouseId = warehouse;
        this.releasedAt = now;
        this.releasedBy = by;
        registerEvent(new OrderEvent.Released(new com.stockflow.contracts.OrderReleased(id.value(), warehouseCode, now)));
    }

    /**
     * Whether the order has a legacy print line - a blank with a design snapshot (QD-16) - whose printed goods were never
     * received into stock: such an order is not released ({@code ORDER_PRINT_GOODS_NOT_RECEIVED}). A line of the
     * customer's own SKU is not one: its goods arrive printed, through a production order, as stock.
     */
    public boolean hasPrintLines() {
        return lines.stream().anyMatch(OrderLine::isMadeToOrder);
    }

    /**
     * PACK-PARTIAL (lead's decision 2026-10-21): Sales decides whether the order may ship in parts - the warehouse no
     * longer does. Possible until everything left or the order is closed ({@code ORDER_PARTIAL_SHIPMENT_LOCKED});
     * turning it off on a partly fulfilled order means the rest goes in one handover.
     *
     * @return whether this changed the order
     */
    public boolean decidePartialShipment(boolean allowed) {
        boolean open = switch (status) {
            case DRAFT, PENDING_CONTRACT, PENDING_PAYMENT, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION, READY_TO_FULFILL,
                 PICKING, PARTIALLY_FULFILLED, PACKED, ON_HOLD -> true;
            case SHIPPED, DELIVERY_FAILED, DELIVERED, RETURN_REQUESTED, RETURNED, REFUNDED, PARTIALLY_REFUNDED,
                 COMPLETED, CANCELLED, CLOSED -> false;
        };
        if (!open) {
            throw new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_LOCKED,
                    "Order %s is %s: whether it ships in parts can no longer change".formatted(orderNumber, status));
        }
        if (!allowed && allowPartialShipment && goodsLeftAt != null) {
            // SCRUM-392 (review of #162): part of it already left on a load plan's vehicle - the other vehicles carry
            // the rest of a partial plan; turning the flag off would leave them unable to sign.
            throw new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_LOCKED, ("Order %s already has goods on a "
                    + "load plan's vehicle that left: partial shipment stays allowed").formatted(orderNumber));
        }
        if (allowPartialShipment == allowed) {
            return false;
        }
        this.allowPartialShipment = allowed;
        return true;
    }

    public boolean allowPartialShipment() { return allowPartialShipment; }
    public Instant fulfilmentStartedAt() { return fulfilmentStartedAt; }

    /** Rehydration of when fulfillment opened the order's task. */
    public void restoreFulfilmentStarted(Instant at) {
        this.fulfilmentStartedAt = at;
    }

    /**
     * Fulfillment opened a package of the order: packing started. Idempotent.
     *
     * @return whether this was the first package
     */
    public boolean recordPackingStarted(Instant at) {
        java.util.Objects.requireNonNull(at, "at");
        if (status == OrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_CANCELLED,
                    "Order %s is cancelled; nothing of it is packed".formatted(orderNumber));
        }
        if (packingStartedAt != null) {
            return false;
        }
        packingStartedAt = at;
        return true;
    }

    public Instant packingStartedAt() { return packingStartedAt; }

    /** Rehydration of when packing started. */
    public void restorePackingStarted(Instant packingStarted) {
        this.packingStartedAt = packingStarted;
    }

    /** Rehydration of the partial-shipment decision. */
    public void restorePartialShipment(boolean allowed) {
        this.allowPartialShipment = allowed;
    }

    /** Rehydration of the release, from the row. */
    public void restoreRelease(UUID warehouse, Instant released, UUID releasedByUser) {
        this.warehouseId = warehouse;
        this.releasedAt = released;
        this.releasedBy = releasedByUser;
    }

    /** Rehydration of how the order was cancelled, from the row. */
    public void restoreCancellation(CancellationReasonCode code, BigDecimal retained) {
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
     * Resolve the hold the coordinator raised (17 §5): back to the status it was held from, then on with
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
        resumeFromHold(note, by, at);
    }

    private void resumeFromHold(String note, UUID by, Instant at) {
        String trimmed = trimmed(note);
        if (trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Resolving a hold must say how in the note");
        }
        OrderStatus back = statusBeforeHold;
        HoldReason was = holdReason;
        // A hold for payment is resolved by money, not by a note: an order of a contract by its installments on signing,
        // which the caller checks (4B-6, 15 BR-13). An order without a contract has no money in the system (4B-10).
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
     * <p>{@code retainedPercent} is the share of the money collected kept for work already done - a print run under way
     * (17 §4.8 step 30); the rest is refundable. 0 or null keeps nothing. Payment applies it to the installments in the
     * caller's transaction (4B-9).</p>
     *
     * @param by who cancelled; null when the system did (payment failed, hold expired)
     */
    public void cancel(CancellationReasonCode code, String note, BigDecimal retainedPercent, UUID by, Instant now) {
        cancel(code, note, retainedPercent, null, by, now);
    }

    /**
     * The same, keeping either a percentage or an amount agreed with the customer (decision Q2: Sales enters it,
     * nothing computes it), whole units of the currency, more than zero, at most what the order is now worth, with its
     * reason and detail.
     *
     * <p>4B-9, 4B-10: the order holds no money of its own - it is paid on its contract's installments - so the share and
     * the agreed amount are settled by payment against them, in the caller's transaction
     * ({@code PaymentService.settleCancellation}): the money collected pays the fee first, the rest is refunded, a
     * shortfall is owed on a fee item. The order keeps nothing ({@code cancellationRetainedAmount} 0) and announces no
     * money in {@code OrderCancelled}.</p>
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
        // 4B-9: two decimals at most, as payment keeps the share of a contract order (order_settlement.fee_percent) -
        // refused here, before any stock or contract is touched.
        if (retainedPercent != null && (retainedPercent.signum() < 0 || retainedPercent.compareTo(HUNDRED) > 0
                || retainedPercent.stripTrailingZeros().scale() > 2)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The retained share is between 0 and 100 percent, to two decimals");
        }
        // SCRUM-260: a replacement ships goods a resolved return promised. Cancelled any other way, the return would
        // stay "replaced" while the customer got neither the goods nor the money - so only its withdrawal through the
        // return cancels it, and that credits the units in the same transaction (ck_order_replacement_cancellation).
        if ((replacesReturnRequestId != null) != (code == CancellationReasonCode.REPLACEMENT_WITHDRAWN)) {
            throw replacesReturnRequestId != null
                    ? new BusinessException(ErrorCode.RETURN_REPLACEMENT_ORDER_LOCKED,
                            ("Order %s ships replacements for a return: withdraw it through the return, which credits "
                                    + "the units instead").formatted(orderNumber))
                    : new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "Only a replacement order is withdrawn through its return");
        }
        if (!status.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order %s is %s and can no longer be cancelled; raise a return instead".formatted(orderNumber, status));
        }
        requireNotPacked();
        // Decision 2026-10-17: once printing started, a person decides - the fee, the blanks, the printed goods - so
        // the system never cancels such an order on its own.
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
        this.cancellationFeeAmount = agreed;
        this.cancellationFeeReason = agreedFee == null ? null : agreedFee.reason();
        this.cancellationFeeDetail = agreedFee == null ? null : agreedFee.detail();
        // The free-text column the timeline shows: the code, and the note when there is one.
        String reasonText = trimmed == null ? code.name() : code.name() + ": " + trimmed;
        transitionTo(OrderStatus.CANCELLED, by, reasonText, now);
        clearHold();
        this.cancellationReasonCode = code;
        this.cancellationReason = reasonText;
        // ck_order_cancellation_fee / _retained compare this with paid_amount, which stays 0 since 4B-10.
        this.cancellationRetainedAmount = BigDecimal.ZERO;
        registerEvent(new OrderEvent.Cancelled(new OrderCancelled(id.value(), orderNumber.value(), customerId,
                previous.name(), code.name(), trimmed, BigDecimal.ZERO, BigDecimal.ZERO,
                total().currency().getCurrencyCode(), by, now, BigDecimal.ZERO)));
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
        // Decision 2026-10-17: above what was paid the rest is owed, but never above what the order is worth - less
        // the units already closed (review 2026-10-25 of S6).
        if (fee.amount().compareTo(billable()) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The agreed amount is at most what the order is "
                    + "worth, %s %s".formatted(billable().stripTrailingZeros().toPlainString(),
                    total().currency().getCurrencyCode()));
        }
        if (fee.reason() == null || fee.reason().isEmpty() || fee.reason().length() > 500
                || fee.detail() == null || fee.detail().isEmpty() || fee.detail().length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An agreed amount needs its reason (at most 500 characters) and its detail (at most 2000)");
        }
    }

    /** The agreed amount of a closure: after printing started, more than zero, whole units, at most the prints closed. */
    private BigDecimal requireRemainderFee(com.stockflow.order.api.CancellationFee fee, BigDecimal closedPrints) {
        if (printingStartedAt == null) {
            throw new BusinessException(ErrorCode.CANCELLATION_FEE_BEFORE_PRINTING,
                    "Order %s has not started printing: its rest is closed without a fee".formatted(orderNumber));
        }
        int digits = total().currency().getDefaultFractionDigits();
        if (fee.amount().signum() <= 0 || fee.amount().stripTrailingZeros().scale() > digits) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The agreed amount is more than zero, in whole %s"
                    .formatted(digits == 0 ? total().currency().getCurrencyCode() : "units of " + total().currency()));
        }
        if (fee.amount().compareTo(closedPrints) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("The agreed amount is at most the value of the "
                    + "print units closed, %s %s").formatted(closedPrints.stripTrailingZeros().toPlainString(),
                    total().currency().getCurrencyCode()));
        }
        if (fee.reason() == null || fee.reason().isEmpty() || fee.reason().length() > 500
                || fee.detail() == null || fee.detail().isEmpty() || fee.detail().length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An agreed amount needs its reason (at most 500 characters) and its detail (at most 2000)");
        }
        return fee.amount().setScale(digits);
    }

    // ---- split shipments (decision G4-Q3) ----------------------------------------------------------------------

    /** What made the order look at its shipments again. */
    private enum ShipmentChange { HANDED_OVER, OUTCOME, RETURNED, CLOSED, RESUMED }

    /**
     * Fulfillment handed a shipment of the order to the carrier (kltn-docs 09 §4.1 step 3; G4-2a calls this in its
     * handover transaction, A1). The order records which units went and what they are worth with their VAT (and the fee
     * lines, with the first shipment out), then moves: PACKED / PICKING /
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

    /**
     * Whether goods of the order may leave the warehouse now - the gate of {@link #recordHandover} without recording
     * anything (SCRUM-392 LP-3: a vehicle of a load plan leaves with part of a shipment, which is handed over only with
     * its last vehicle). Refused while the order is cancelled, on hold or not yet picking, and when its terms want money
     * before the goods leave that has not all arrived ({@code ORDER_BALANCE_NOT_PAID}, G3-Q4).
     *
     * @param installmentsDue as for {@link #markShipped(Instant, UUID, BigDecimal)}
     */
    public void requireHandoverAllowed(BigDecimal installmentsDue) {
        requireNotCancelled("its goods are not handed over");
        if (status != OrderStatus.PICKING && status != OrderStatus.PACKED && status != OrderStatus.PARTIALLY_FULFILLED) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    status == OrderStatus.ON_HOLD ? "resolve the hold before handing goods over"
                            : "only a picking, packed or partly shipped order hands goods over");
        }
        requireHandoverPaid(installmentsDue);
    }

    /** @param installmentsDue as for {@link #markShipped(Instant, UUID, BigDecimal)} */
    public boolean recordHandover(ShipmentHandover handover, BigDecimal installmentsDue) {
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
        requireHandoverAllowed(installmentsDue);
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
        }
        Money value = worthWith(quantities);
        int leaving = quantities.values().stream().mapToInt(Integer::intValue).sum();
        boolean everythingGoes = outstandingUnits() == leaving;
        if (status == OrderStatus.PICKING && everythingGoes) {
            throw new InvalidOrderTransitionException(orderNumber, status, "pack it before handing everything over");
        }
        // PACK-PARTIAL (lead's decision 2026-10-21): goods left behind - not in this shipment nor in the others signed
        // over with it - make this a partial shipment, which Sales decides on the order, not the warehouse.
        int leavingWith = quantitiesOf(handover.leavingWith()).values().stream().mapToInt(Integer::intValue).sum();
        if (!allowPartialShipment && outstandingUnits() > leaving + leavingWith) {
            throw new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED,
                    "Order %s does not allow partial shipments: %d unit(s) would stay behind. Hand everything over "
                            .formatted(orderNumber, outstandingUnits() - leaving - leavingWith)
                            + "together, or ask Sales to allow partial shipment on the order");
        }
        shipments.add(OrderShipment.handedOver(handover.shipmentId(), quantities, value.amount(),
                handover.handedOverAt(), handover.handedOverBy(), handover.carriage()));
        // Goods handed over were packed: from here the order goes through a return, never a cancellation.
        recordPacked(handover.handedOverAt());
        followShipments(ShipmentChange.HANDED_OVER, handover.handedOverAt(), handover.handedOverBy());
        return true;
    }

    /**
     * What a shipment leaving with {@code quantities} is worth (4B-3): what the shipments still out - with the carrier
     * or delivered - are worth once it has left, less what they carry already. Out, they are worth each line's units
     * out as a share of the line with tax (14 BR-21), and the fee lines, once (14 BR-09): the fee lines carry no units
     * and go with the first shipment. A shipment that came back took its value with it, so the next one carries it
     * again - the fee lines too - and the rounded shares of a line still add up to the line, whatever order the
     * shipments went and came back in. On an order without VAT or fee lines this is the units at their price.
     */
    private Money worthWith(Map<UUID, Integer> quantities) {
        Money worth = feesGross();
        for (OrderLine line : lines) {
            worth = worth.plus(line.grossOf(shippedUnits(line.id(), false) + quantities.getOrDefault(line.id(), 0)));
        }
        BigDecimal carried = shipments.stream().filter(s -> !s.isReturned()).map(OrderShipment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return worth.minus(new Money(carried, worth.currency()));
    }

    private static Map<UUID, Integer> quantitiesOf(List<ShipmentHandover.Line> lines) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (ShipmentHandover.Line line : lines) {
            if (line.orderLineId() != null && line.quantity() > 0) {
                quantities.merge(line.orderLineId(), line.quantity(), Integer::sum);
            }
        }
        return quantities;
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
     * The carrier delivered one shipment (09 §4.3 step 1; the Order Coordinator records it, BR-02). The order is
     * DELIVERED once every shipment arrived; {@code OrderDelivered} names the shipment. Nothing is billed on delivery
     * since 4B-10: what is paid after delivery is the contract's installments.
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
        shipment.delivered(at, by, BigDecimal.ZERO);
        registerDelivered(shipmentId, at);
        followShipments(ShipmentChange.OUTCOME, at, by);
        return true;
    }

    /**
     * A delivery the carrier reported wrongly, corrected to a failure by the warehouse manager (09 §4.3 step 5, E17;
     * 17 §5.1): the shipment DELIVERY_FAILED and the order following it, while the order is not completed. A delivery
     * that billed the customer in the money ledger (before 4B-10) is not corrected here.
     *
     * @return whether this changed the order
     * @throws BusinessException {@code ORDER_DELIVERY_NOT_CORRECTABLE} for an order past its deliveries or a billed
     *         delivery; {@code ORDER_SHIPMENT_INVALID_TRANSITION} for a shipment not delivered
     */
    public boolean correctShipmentToFailed(UUID shipmentId, DeliveryFailureReason reason, String note, Instant at,
                                           UUID by) {
        java.util.Objects.requireNonNull(reason, "reason");
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        if (reason == DeliveryFailureReason.OTHER && trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A failure for another reason says what it was");
        }
        OrderShipment shipment = requireShipment(shipmentId);
        if (shipment.status() == OrderShipmentStatus.DELIVERY_FAILED) {
            return false;
        }
        if (status != OrderStatus.DELIVERED && status != OrderStatus.SHIPPED && status != OrderStatus.DELIVERY_FAILED
                && status != OrderStatus.PARTIALLY_FULFILLED) {
            throw new BusinessException(ErrorCode.ORDER_DELIVERY_NOT_CORRECTABLE, ("Order %s is %s: its deliveries "
                    + "are no longer corrected").formatted(orderNumber, status));
        }
        if (shipment.billedAmount().signum() > 0) {
            throw new BusinessException(ErrorCode.ORDER_DELIVERY_NOT_CORRECTABLE, ("Shipment %s of order %s billed the "
                    + "customer %s on delivery: the receivable must be taken back first").formatted(shipmentId,
                    orderNumber, shipment.billedAmount().toPlainString()));
        }
        if (!shipment.correctedToFailed(reason, trimmed, at, by)) {
            return false;
        }
        followShipments(ShipmentChange.OUTCOME, at, by);
        if (status != OrderStatus.DELIVERED) {
            // No longer delivered: the time it was shows nowhere.
            this.deliveredAt = null;
        }
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
     * {@code OrderRemainderClosed} tells fulfillment to stop the rest; the money is settled on the contract's installments
     * by payment in the caller's transaction (4B-9). Only from PARTIALLY_FULFILLED: an order on hold is resolved first.
     *
     * @return the value of the units closed
     */
    public BigDecimal closeRemainder(CancellationReasonCode code, String note, UUID by, Instant at) {
        return closeRemainder(code, note, null, by, at);
    }

    /**
     * The same, keeping an amount agreed with the customer for print lines closed after printing started (review of
     * #150; decisions 2026-10-17 / 2026-10-19: cancelling after printing is allowed with a printing fee; 17 edge "khách
     * huỷ khi đang In Production (đã in)"). The fee is at most the value of the print units closed. Payment settles it
     * on the installments (4B-9): what was paid above the order's new worth pays it first, the rest is refunded, a
     * shortfall is owed on a fee item. Before printing nothing is kept ({@code CANCELLATION_FEE_BEFORE_PRINTING}).
     */
    public BigDecimal closeRemainder(CancellationReasonCode code, String note,
                                     com.stockflow.order.api.CancellationFee agreedFee, UUID by, Instant at) {
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
        // A free replacement's rest is closed only by withdrawing it through its return, which credits the units the
        // customer will not get (review 2026-10-25); closed any other way, they would be lost to the customer.
        if ((replacesReturnRequestId != null) != (code == CancellationReasonCode.REPLACEMENT_WITHDRAWN)) {
            throw new BusinessException(replacesReturnRequestId != null ? ErrorCode.RETURN_REPLACEMENT_ORDER_LOCKED
                    : ErrorCode.VALIDATION_FAILED, replacesReturnRequestId != null
                    ? ("Order %s replaces goods of a return: withdraw the rest of it through that return, which credits "
                            + "the units").formatted(orderNumber)
                    : "Only a replacement order is closed as withdrawn");
        }
        // Validated before anything changes: a refused fee leaves the order as it was.
        Money closedPrints = Money.zero(total().currency());
        for (OrderLine line : lines) {
            int left = line.quantity() - line.cancelledQuantity() - shippedUnits(line.id(), false);
            if (left > 0 && line.printedToCustomerDesign()) {
                int open = line.quantity() - line.cancelledQuantity();
                closedPrints = closedPrints.plus(line.grossBetween(open - left, open));
            }
        }
        BigDecimal fee = agreedFee == null ? BigDecimal.ZERO : requireRemainderFee(agreedFee, closedPrints.amount());
        Money closed = Money.zero(total().currency());
        List<com.stockflow.contracts.OrderRemainderClosed.Line> closedLines = new ArrayList<>();
        for (OrderLine line : lines) {
            int left = line.quantity() - line.cancelledQuantity() - shippedUnits(line.id(), false);
            if (left > 0) {
                int open = line.quantity() - line.cancelledQuantity();
                line.closeUnits(left);
                closed = closed.plus(line.grossBetween(open - left, open));
                closedLines.add(new com.stockflow.contracts.OrderRemainderClosed.Line(line.id(), line.sku().code(), left));
            }
        }
        String reason = withNote("REMAINDER_CLOSED_" + code.name(), trimmed);
        if (fee.signum() > 0) {
            // The fee and why, in the order's history (the event carries amounts only); the column holds 500.
            reason = reason + " (fee " + fee.stripTrailingZeros().toPlainString() + ": " + agreedFee.reason() + ")";
            reason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        }
        followShipments(ShipmentChange.CLOSED, at, by, reason);
        this.lastClosureId = UUID.randomUUID();
        // The money is the installments', settled by payment in this transaction (OrderServiceImpl.closeRemainder): the
        // event carries no money of the order's own since 4B-10 (paid, refundable, retained and owed are 0).
        registerEvent(new OrderEvent.RemainderClosed(new com.stockflow.contracts.OrderRemainderClosed(id.value(),
                orderNumber.value(), customerId, code.name(), closedLines, closed.amount(), billable(), BigDecimal.ZERO,
                BigDecimal.ZERO, total().currency().getCurrencyCode(), by, at, lastClosureId, fee, BigDecimal.ZERO,
                BigDecimal.ZERO)));
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
    /** Units of the line neither shipped nor closed: what is still to come of it. */
    public int unshippedUnits(UUID lineId) {
        OrderLine line = lines.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No line " + lineId + " on " + orderNumber));
        return line.quantity() - line.cancelledQuantity() - shippedUnits(lineId, false);
    }

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
     * still owed a delivery until they are closed - shipped again, or closed and so refunded - so they stay in it. What
     * a closure takes off the installments is the difference (4B-9).
     */
    public BigDecimal billable() {
        // Each line's units not closed with their VAT (4B-3), and the fee lines: closing units never closes a fee.
        return lines.stream().map(l -> l.grossOf(l.quantity() - l.cancelledQuantity()).amount())
                .reduce(feesGross().amount(), BigDecimal::add);
    }

    /**
     * What must be paid before the goods may leave (G3-Q4: blocked at the handover, warned at packing), as far as the
     * order knows without its installments: nothing. An order of a contract is gated by what its installments before
     * delivery and on signing still owe (15 BR-07, BR-08), which the caller reads under the order's lock and hands to
     * {@link #recordHandover(ShipmentHandover, BigDecimal)}; an order without a contract has no money in the system since
     * the money ledger went (4B-10), so nothing holds its goods back.
     */
    public BigDecimal amountDueBeforeHandover() {
        return BigDecimal.ZERO;
    }

    /**
     * Every installment of its contract before delivery and on signing (still owing {@code installmentsDue}), tied to a
     * delivery batch or not, until the handover goes by batch (4B-8).
     */
    private void requireHandoverPaid(BigDecimal installmentsDue) {
        BigDecimal due = paidByContract() && installmentsDue != null ? installmentsDue : BigDecimal.ZERO;
        if (due.signum() > 0) {
            throw new BusinessException(ErrorCode.ORDER_BALANCE_NOT_PAID,
                    "Order %s still owes %s %s before its goods may leave".formatted(orderNumber,
                            due.stripTrailingZeros().toPlainString(), total().currency().getCurrencyCode()));
        }
    }

    // ---- after delivery ------------------------------------------------------------------------------------------

    /**
     * Delivered, settled and past the return window (kltn-docs 17 §4.6 step 21; BR-ORD-005): DELIVERED → COMPLETED. An
     * order whose installments still owe stays DELIVERED (every installment of the contract PAID, 17 step 21), so does
     * one with a return request open. The window is counted in calendar days of Asia/Ho_Chi_Minh, exactly as a return
     * is ({@link ReturnPolicy#endOfDays}): the order never completes while its customer may still claim.
     *
     * @param owing whether the order's installments still owe something, as payment reads them (4B-10: the order keeps
     *              no money of its own); false for an order without installments
     * @return whether this changed the order
     */
    public boolean complete(Instant now, java.time.Duration returnWindow, boolean hasOpenReturn, boolean owing) {
        if (status != OrderStatus.DELIVERED || deliveredAt == null || hasOpenReturn
                || ReturnPolicy.endOfDays(deliveredAt, returnWindow).isAfter(now) || owing) {
            return false;
        }
        transitionTo(OrderStatus.COMPLETED, null, "RETURN_WINDOW_CLOSED", now);
        this.completedAt = now;
        return true;
    }

    /**
     * Closed for good a while after it was completed or refunded (G3-Q1, default 30 days): BR-05, nothing changes it
     * any more. Never while the customer still owes on it: its installments still owe, and closing it would put the debt
     * out of the order's sight for good - COMPLETED already waits for the money ({@link #complete}), so REFUNDED and
     * PARTIALLY_REFUNDED wait too.
     *
     * @param owing as for {@link #complete}
     * @return whether this changed the order
     */
    public boolean close(Instant now, java.time.Duration after, boolean owing) {
        Instant since = switch (status) {
            case COMPLETED -> completedAt;
            case REFUNDED, PARTIALLY_REFUNDED -> refundedAt;
            default -> null;
        };
        if (since == null || since.plus(after).isAfter(now) || owing) {
            return false;
        }
        transitionTo(OrderStatus.CLOSED, null, "CLOSED_AFTER_SETTLEMENT", now);
        this.closedAt = now;
        return true;
    }

    /** The customer asked to return delivered goods (RMA, SCRUM-243): DELIVERED → RETURN_REQUESTED. */
    public void requestReturn(UUID by, Instant at) {
        requestReturn(by, at, null);
    }

    /**
     * A return request of the order was opened (SCRUM-243): DELIVERED → RETURN_REQUESTED, which keeps it from
     * completing while the claim is decided (BR-ORD-005). Also from COMPLETED (a late claim Sales accepted) and from
     * REFUNDED / PARTIALLY_REFUNDED (a further claim after a return gave money back), so the order shows the open claim
     * and is settled again by it. An order already RETURN_REQUESTED - another of its returns is open - stays as it is,
     * and so does a CLOSED one: BR-05, nothing changes it; the return goes on without moving it.
     *
     * @return whether this changed the order
     */
    public boolean requestReturn(UUID by, Instant at, String reason) {
        if (status == OrderStatus.RETURN_REQUESTED || !status.canTransitionTo(OrderStatus.RETURN_REQUESTED)) {
            return false;
        }
        transitionTo(OrderStatus.RETURN_REQUESTED, by, reason, at);
        return true;
    }

    /**
     * Every return of the order ended without money back - refused, withdrawn, expired, or made good with new goods
     * only (lead's decision 2026-10-21 for a refusal; the same for the others, since the customer keeps what was
     * delivered and owes what they owed): RETURN_REQUESTED → DELIVERED, back to waiting for its return window and its
     * money before it completes. The delivery date is unchanged, so the window is not reopened.
     *
     * @return whether this changed the order
     */
    public boolean withdrawReturn(UUID by, Instant at, String reason) {
        if (status != OrderStatus.RETURN_REQUESTED) {
            return false;
        }
        transitionTo(OrderStatus.DELIVERED, by, reason, at);
        return true;
    }

    /**
     * Every return of the order ended and at least one gave money back (SCRUM-260; 15 §4.4: refunded, or the
     * receivable reduced): RETURN_REQUESTED → RETURNED → REFUNDED when what was given back covers what the order is
     * worth, PARTIALLY_REFUNDED otherwise. Both steps are kept in the history; from here the order closes after
     * {@code close-after-days}.
     *
     * @return whether this changed the order
     */
    public boolean settleReturn(boolean full, UUID by, Instant at, String reason) {
        if (status != OrderStatus.RETURN_REQUESTED) {
            return false;
        }
        transitionTo(OrderStatus.RETURNED, by, reason, at);
        markRefunded(!full, by, at);
        return true;
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

    // ---- a replacement for returned goods ------------------------------------------------------------------------------

    /**
     * SCRUM-260: this order ships new goods for units a return accepted, at no charge. Set on the draft, before it is
     * submitted; {@code ck_order_replacement_free} says again that such an order costs nothing.
     */
    public void replacesReturn(UUID returnRequestId) {
        if (status != OrderStatus.DRAFT) {
            throw new IllegalStateException("Only a draft becomes a replacement order");
        }
        if (total().amount().signum() != 0) {
            throw new IllegalStateException("A replacement order of %s costs nothing, not %s".formatted(orderNumber,
                    total()));
        }
        this.replacesReturnRequestId = java.util.Objects.requireNonNull(returnRequestId, "returnRequestId");
    }

    /**
     * A replacement order is placed (SCRUM-260): every line holds its stock, there is nothing to pay - so it is
     * CONFIRMED at once ({@code ck_order_confirmed_basis} names the replacement), ready for the coordinator to release it
     * to a warehouse, as any order. Announced as {@code OrderPlaced}. The confirmation names {@code by}, who settled the return: nobody
     * checked out this order, so the history says who caused it.
     */
    public void submitReplacement(UUID by, Instant now) {
        if (replacesReturnRequestId == null) {
            throw new IllegalStateException("Order %s replaces no return".formatted(orderNumber));
        }
        requireEveryLineReserved();
        transitionTo(OrderStatus.CONFIRMED, by, "REPLACEMENT_FOR_RETURN", now);
        registerPlaced();
    }

    /** Rehydration of the return a replacement order makes good. */
    public void restoreReplacement(UUID returnRequestId) {
        this.replacesReturnRequestId = returnRequestId;
    }

    /** The return this order ships replacements for; null for an ordinary order. */
    public UUID replacesReturnRequestId() { return replacesReturnRequestId; }

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

    /** Rehydration of the hold and the milestones, from the row. */
    public void restoreLifecycle(HoldReason hold, OrderStatus before, String note, Instant shipped, Instant delivered,
                                 DeliveryFailureReason failure, Instant completed, Instant refunded, Instant closed) {
        this.holdReason = hold;
        this.statusBeforeHold = before;
        this.holdNote = note;
        this.shippedAt = shipped;
        this.deliveredAt = delivered;
        this.deliveryFailureReason = failure;
        this.completedAt = completed;
        this.refundedAt = refunded;
        this.closedAt = closed;
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

    /** Rehydration of the printing and fulfilment facts the cancellation rules read. */
    public void restoreFulfilmentFacts(Instant printingStarted, Instant packed) {
        this.printingStartedAt = printingStarted;
        this.packedAt = packed;
    }

    public Instant printingStartedAt() { return printingStartedAt; }
    public Instant packedAt() { return packedAt; }

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

    /** 03 §9: a packed order is not cancelled - by anyone, on any path; it goes through a return (RMA). */
    public void requireNotPacked() {
        if (packedAt != null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order %s is packed; it can no longer be cancelled - raise a return instead".formatted(orderNumber));
        }
    }

    /** Rehydration of when goods first left on a load plan's vehicle (SCRUM-392). */
    public void restoreGoodsLeft(Instant at) {
        this.goodsLeftAt = at;
    }

    public Instant goodsLeftAt() { return goodsLeftAt; }

    public void restoreContractSigned(Instant at) {
        this.contractSignedAt = at;
    }

    public void restoreShippingWarehouse(UUID warehouse, Instant chosenAt, UUID chosenBy) {
        this.shippingWarehouseId = warehouse;
        this.shippingWarehouseChosenAt = chosenAt;
        this.shippingWarehouseChosenBy = chosenBy;
    }

    /**
     * 4B-7 (kltn-docs 17 §4.2 step 2, BR-22): the one warehouse this order of a contract ships from, chosen by Sales once
     * it is CONFIRMED. Its stock is held there only (20 BR-17). It changes only while nothing is held for the order - a
     * hold already taken ties it to its warehouse; the same warehouse again changes nothing. An order AWAITING_STOCK
     * (4A-6) may choose its warehouse again, which holds what arrived; another one is refused by the production order
     * it has sent (the service's check, BR-22).
     *
     * @param holdsStock whether any stock is held for the order now
     * @return whether it changed
     * @throws BusinessException {@code ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED} for an order not of a contract or neither
     *         CONFIRMED nor AWAITING_STOCK; {@code ORDER_SHIPPING_WAREHOUSE_LOCKED} for another warehouse while stock is
     *         held
     */
    public boolean chooseShippingWarehouse(UUID warehouse, boolean holdsStock, UUID by, Instant at) {
        java.util.Objects.requireNonNull(warehouse, "warehouse");
        if (!paidByContract() || status != OrderStatus.CONFIRMED && status != OrderStatus.AWAITING_STOCK) {
            throw new BusinessException(ErrorCode.ORDER_SHIPPING_WAREHOUSE_NOT_ALLOWED, ("Order %s is %s: the shipping "
                    + "warehouse is chosen for a confirmed order of a contract").formatted(orderNumber, status));
        }
        if (warehouse.equals(shippingWarehouseId)) {
            return false;
        }
        if (shippingWarehouseId != null && holdsStock) {
            throw new BusinessException(ErrorCode.ORDER_SHIPPING_WAREHOUSE_LOCKED, ("Order %s holds stock at its "
                    + "shipping warehouse; release those holds before choosing another").formatted(orderNumber));
        }
        this.shippingWarehouseId = warehouse;
        this.shippingWarehouseChosenAt = java.util.Objects.requireNonNull(at, "at");
        this.shippingWarehouseChosenBy = java.util.Objects.requireNonNull(by, "by");
        return true;
    }

    /** 4B-7: more cartons held for a line at the shipping warehouse - stock that arrived after the first holds. */
    public void holdMore(UUID lineId, List<UUID> reservationIds) {
        lineOf(lineId).holdMore(reservationIds);
    }

    /**
     * 4A-6 (kltn-docs 17 §4.2 step 5, §4.3 step 7, BR-04): its next delivery batch is short at its shipping warehouse and
     * a production order for that batch was sent to a producer - CONFIRMED → AWAITING_STOCK. The caller decides those two
     * facts; the order checks it is one of a contract. An order already waiting, or past it, is left as it is. An order
     * on hold from CONFIRMED goes back to AWAITING_STOCK when the hold is resolved (17 §4.9 step 36: back where it was,
     * then on with what happened meanwhile); a credit hold has no status to go back to and is left as it is.
     *
     * @return whether this changed the order
     */
    public boolean awaitStock(UUID by, Instant at) {
        if (!paidByContract()) {
            return false;
        }
        if (status == OrderStatus.CONFIRMED) {
            transitionTo(OrderStatus.AWAITING_STOCK, by, "PRODUCTION_ORDER_SENT", at);
            return true;
        }
        if (status == OrderStatus.ON_HOLD && holdReason != HoldReason.CREDIT
                && statusBeforeHold == OrderStatus.CONFIRMED) {
            this.statusBeforeHold = OrderStatus.AWAITING_STOCK;
            return true;
        }
        return false;
    }

    /**
     * 17 BR-11, §4.8 step 30: the first production order of the order entered In Production - mass printing started, so
     * from now on a cancellation is staff's to decide and may keep an agreed fee. Written once: a later production order
     * starting changes nothing. A cancelled or closed order is left as it is - it was settled without it, and a
     * production order still printing for it is closed by the cancellation's cascade.
     *
     * @return whether this changed the order
     */
    public boolean recordPrintingStarted(Instant at) {
        java.util.Objects.requireNonNull(at, "at");
        if (printingStartedAt != null || status.isTerminal()) {
            return false;
        }
        this.printingStartedAt = at;
        return true;
    }

    public UUID shippingWarehouseId() { return shippingWarehouseId; }
    public Instant shippingWarehouseChosenAt() { return shippingWarehouseChosenAt; }
    public UUID shippingWarehouseChosenBy() { return shippingWarehouseChosenBy; }

    /** 4B-5: when its sales contract was signed - what confirms it, not a payment its own terms ask for. */
    public Instant contractSignedAt() { return contractSignedAt; }

    /**
     * 4B-5: paid by its contract's installments. Since 4B-10 every order placed is: an order without a signed contract
     * (drafted, or one of checkout's before) has no money in the system at all.
     */
    public boolean paidByContract() { return contractSignedAt != null; }

    /** The closure {@link #closeRemainder} made in this unit of work; null when none was made (4B-9). */
    public UUID lastClosureId() { return lastClosureId; }

    /**
     * Goods of the order leave on a load plan's vehicle (SCRUM-392, LP-3) - part of a shipment that is handed over only
     * with its last vehicle. The gate of {@link #recordHandover} runs (cancelled, on hold, not picking, money due before
     * the goods leave) and the plan must carry everything the order still wants unless it allows partial shipments
     * ({@link #requirePlanCarriesTheRest}). The first time, {@code goodsLeftAt} is set: from then the order is no longer
     * cancelled (review of #162).
     *
     * @param inPlan the units of the order's parcels in the plan not handed over yet, per order line; null when its
     *               parcels record no contents (not checked, as everywhere)
     * @return whether this changed the order
     */
    public boolean recordGoodsLeft(Instant at, Map<UUID, Integer> inPlan, BigDecimal installmentsDue) {
        java.util.Objects.requireNonNull(at, "at");
        requireHandoverAllowed(installmentsDue);
        requirePlanCarriesTheRest(inPlan);
        if (goodsLeftAt != null) {
            return false;
        }
        goodsLeftAt = at;
        return true;
    }

    /**
     * PACK-PARTIAL at a load plan (review of #162): an order that does not allow partial shipments leaves whole with
     * the plan - checked when a vehicle starts loading and when it leaves, so no vehicle is stuck half way.
     *
     * @param inPlan as for {@link #recordGoodsLeft}; null: not checked
     */
    public void requirePlanCarriesTheRest(Map<UUID, Integer> inPlan) {
        requireNotCancelled("its goods are not loaded");
        if (inPlan == null || allowPartialShipment) {
            return;
        }
        int carried = inPlan.values().stream().mapToInt(Integer::intValue).sum();
        if (outstandingUnits() > carried) {
            throw new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED, ("Order %s does not allow partial "
                    + "shipments: %d unit(s) are not in this load plan. Put them in the plan, or ask Sales to allow "
                    + "partial shipment on the order").formatted(orderNumber, outstandingUnits() - carried));
        }
    }

    /** Whether a package of the order binds it to a return instead of a cancellation (03 §9). */
    public boolean packedForGood() {
        return packedAt != null;
    }

    /** Rehydration of the agreed amount's terms. */
    public void restoreCancellationFee(BigDecimal amount, String reason, String detail) {
        this.cancellationFeeAmount = amount;
        this.cancellationFeeReason = reason;
        this.cancellationFeeDetail = detail;
    }
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
