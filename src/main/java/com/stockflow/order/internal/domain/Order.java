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
    /**
     * The statuses the check station labels cartons in (4C-6, the station's own list): released, being picked, or
     * shipping the rest of a split order.
     */
    private static final java.util.Set<OrderStatus> LABELLABLE = java.util.EnumSet.of(OrderStatus.READY_TO_FULFILL,
            OrderStatus.PICKING, OrderStatus.PARTIALLY_FULFILLED);
    /**
     * Where Sales may change the delivery address (kltn-docs 17 §4.7 step 24): a DRAFT order is edited freely (14), and
     * from PENDING_PAYMENT to PICKING the address alone changes, until the first carton is labelled (17 BR-16, 08 BR-04).
     * An order ON_HOLD counts as the status it was held from: a wrong address is held ADDRESS, corrected, then released
     * (08 §6 edge case). PENDING_CONTRACT is not here: the contract sent carries the address, so Sales cancels it and
     * the order is a DRAFT again (14 §4.3 step 16).
     */
    private static final java.util.Set<OrderStatus> ADDRESS_CHANGEABLE = java.util.EnumSet.of(OrderStatus.DRAFT,
            OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED, OrderStatus.AWAITING_STOCK, OrderStatus.READY_TO_FULFILL,
            OrderStatus.PICKING);

    private final OrderId id;
    private final OrderNumber orderNumber;
    private final UUID customerId;
    private final String contactName;
    private final String contactEmail;
    private final String contactPhone;
    /** Changed only by {@link #changeShippingAddress}, until the first label (17 BR-16). */
    private OrderAddressSnapshot shippingAddress;
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
    /**
     * WF-05 BE-40: while a claim is open or its goods are back (RETURN_REQUESTED, RETURNED), the status the claim was
     * opened from - where the order goes back when its claims end without a refund. Null otherwise.
     */
    private OrderStatus statusBeforeReturn;
    /**
     * D23 (15 BR-09): when the rest of the order was last closed ({@link #closeRemainder}); null when it never was. One
     * of the moments {@link #deliveredInFullAt} takes the latest of.
     */
    private Instant remainderClosedAt;
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
     * 4C-6: when the order's first shipping carton was labelled at the check station, in that transaction under this
     * order's row lock ({@link #recordFirstLabel}). From then the order is not cancelled and its delivery address does
     * not change (kltn-docs 08 BR-04, 17 BR-12, BR-16): the label printed on the carton carries it.
     */
    private Instant firstLabeledAt;
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
     * Its contract cancelled before it was signed, to be drawn up again (14 §4.3 step 16): PENDING_CONTRACT → DRAFT,
     * the history saying {@code CONTRACT_CANCELLED: <reason>} so the order's timeline tells why, as every other
     * {@code CODE: note} row does. A contract cancelled while still a draft leaves the order as it was, with no history
     * row: the order's history holds status changes only ({@code ck_order_status_history_change}), and the contract
     * keeps its own reason.
     */
    public void withdrawContract(UUID by, String reason, Instant at) {
        if (status == OrderStatus.PENDING_CONTRACT) {
            String history = withNote("CONTRACT_CANCELLED", trimmed(reason));
            // The history column holds 500.
            transitionTo(OrderStatus.DRAFT, by, history.length() > 500 ? history.substring(0, 500) : history, at);
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
                    "Order %s is %s, not waiting for a credit decision".formatted(orderNumber, status))
                    .localized("refusal.credit.notHeldStatus", orderNumber.value(), status);
        }
    }

    /**
     * The carrier delivered it, as the warehouse manager records when the carrier reports back
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
     * The warehouse opens a task for the part of an AWAITING_STOCK order already held (kltn-docs 07 §4.1 step 2, 17 §4.4
     * step 15): the order stays AWAITING_STOCK (07 §4.1 step 2: "Đơn Awaiting Stock ... giữ nguyên trạng thái") and is
     * not released - it becomes READY_TO_FULFILL when its batch is held in full (17 §5.1). What is recorded is that its
     * task exists, the fact the list of orders waiting for a task reads ({@code fulfilment_started_at}).
     *
     * @return whether this changed the order
     * @throws InvalidOrderTransitionException in any other status, or once released
     */
    public boolean startPrePicking(Instant at) {
        java.util.Objects.requireNonNull(at, "at");
        if (status != OrderStatus.AWAITING_STOCK || releasedAt != null) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    "only an order awaiting stock, not released yet, is picked before its release");
        }
        if (fulfilmentStartedAt != null) {
            return false;
        }
        this.fulfilmentStartedAt = at;
        return true;
    }

    /**
     * The first partial delivery of an order picked before its release goes towards dispatch - its part held, Sales
     * having allowed a partial shipment (17 §4.5 step 18): owner decision SCOPE-PARTIAL-1105, "đợt BEFORE_DELIVERY đến
     * hạn ở lần giao đầu tiên sẵn sàng" (15 §4.1 step 2). {@code OrderReleased} names the batch, so payment makes due
     * what that batch waits for, as at a release; the order stays AWAITING_STOCK and is not released (its batch is
     * released once held in full). Payment ignores a second notice for an installment already due.
     *
     * @throws InvalidOrderTransitionException unless AWAITING_STOCK, picked before its release, allowed in parts
     */
    public void releasePartFirst(String warehouseCode, UUID deliveryBatchId, Instant at) {
        java.util.Objects.requireNonNull(at, "at");
        if (status != OrderStatus.AWAITING_STOCK || !prePicked() || !allowPartialShipment) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    "only an order awaiting stock, picked ahead and allowed to ship in parts, sends its part held first");
        }
        registerEvent(new OrderEvent.Released(new com.stockflow.contracts.OrderReleased(id.value(), warehouseCode, at,
                deliveryBatchId)));
    }

    /**
     * Whether the warehouse picks the order before its release (07 §4.1 step 2, 17 §4.4 step 15): a task was opened
     * for the part held while it was AWAITING_STOCK, and its batch was not released since.
     */
    public boolean prePicked() {
        return fulfilmentStartedAt != null && releasedAt == null;
    }

    /**
     * The warehouse starts picking (kltn-docs 17 §4.2 step 6): READY_TO_FULFILL → PICKING. Only a released order -
     * its warehouse chosen (17 BR-02). An order already picking, packed or partly shipped is left as it is. An order held
     * for stock ({@code INVENTORY}) from READY_TO_FULFILL stays held and goes back PICKING when the hold is resolved
     * (WF4-COUNT-EDGE b); held for stock from a later status it is left as it is.
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
            case ON_HOLD -> {
                // WF4-COUNT-EDGE (b): held for stock (INVENTORY) after its release - a carton it was allocated counted
                // missing before its task - the warehouse manager opens the task anyway, its line short, as a picker's
                // short report leaves a task (07 §4.4 step 9). The order stays held, and resolving the hold brings it
                // back PICKING (17 §4.9 step 36: back where it was, then on with what happened meanwhile).
                if (holdReason == HoldReason.INVENTORY && statusBeforeHold == OrderStatus.READY_TO_FULFILL) {
                    this.statusBeforeHold = OrderStatus.PICKING;
                    return true;
                }
                if (holdReason == HoldReason.INVENTORY && (statusBeforeHold == OrderStatus.PICKING
                        || statusBeforeHold == OrderStatus.PACKED
                        || statusBeforeHold == OrderStatus.PARTIALLY_FULFILLED)) {
                    return first;
                }
                throw new InvalidOrderTransitionException(orderNumber, status, OrderStatus.PICKING);
            }
            default -> throw new InvalidOrderTransitionException(orderNumber, status, OrderStatus.PICKING);
        }
    }

    /**
     * Everything still to ship is packed (kltn-docs 08, 17 §5): READY_TO_FULFILL (printed goods only, or stock picked
     * while printing), PICKING, or PARTIALLY_FULFILLED with nothing handed over yet (the rest of a partial completion at
     * the check station, 17 §5.1 "phần còn lại kiểm đủ") → PACKED. Once a part of the order was handed over - an earlier
     * delivery batch, or the part a partial completion shipped - the order stays PARTIALLY_FULFILLED while the rest is
     * picked, checked and handed over (17 §4.5 "các đợt sau được lấy hàng, kiểm thùng, bàn giao trong lúc đơn ở
     * Partially Fulfilled"; owner decision WF4-PF-NEXT, 2026-11-13); its last handover makes it SHIPPED. Records that a
     * package was completed, the fact the cancellation rules read. Refused while the order is on hold: resolve it first.
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
        if (status == OrderStatus.PARTIALLY_FULFILLED && !shipments.isEmpty()) {
            // WF4-PF-NEXT (2026-11-13, 17 §4.5): part of it left already - it stays PARTIALLY_FULFILLED until the last
            // handover, which makes it SHIPPED (followShipments).
            return recordPacked(at);
        }
        transitionTo(OrderStatus.PACKED, by, null, at);
        recordPacked(at);
        return true;
    }

    /**
     * Part of what the order wants now is packed (kltn-docs 08 §4.4 step 8; owner decision WF4-PF-STATUS, 2026-11-12):
     * the check station completed with cartons missing - removed as damaged, or never picked - and Sales allowed the
     * order to ship in parts. The order is PARTIALLY_FULFILLED from this moment, not only once that part is handed over:
     * READY_TO_FULFILL or PICKING → PARTIALLY_FULFILLED; an order already PARTIALLY_FULFILLED (the rest of a split order,
     * a later delivery batch) stays so. Handing the part over then leaves it PARTIALLY_FULFILLED while units are still to
     * go ({@link #recordHandover}); the rest packed in full makes it PACKED only while nothing was handed over yet, and
     * leaves it PARTIALLY_FULFILLED once a part left ({@link #markPacked}, WF4-PF-NEXT). Records that a package was completed, the fact the cancellation rules read. Refused while the order is
     * on hold - resolve it first - and from any status the check station does not complete in.
     *
     * @return whether this changed the order
     */
    public boolean markPartlyPacked(Instant at, UUID by) {
        java.util.Objects.requireNonNull(at, "at");
        requireNotCancelled("its package is not completed");
        switch (status) {
            case PARTIALLY_FULFILLED -> {
                return recordPacked(at);
            }
            case READY_TO_FULFILL, PICKING -> {
                transitionTo(OrderStatus.PARTIALLY_FULFILLED, by, null, at);
                recordPacked(at);
                return true;
            }
            case AWAITING_STOCK -> {
                // 17 §5.1 "Awaiting Stock → Partially Fulfilled (giao trước phần có sẵn)", §4.5 step 18: Sales allowed
                // the part already held to go first while the rest is produced (08 §4.1 step 1).
                if (!allowPartialShipment) {
                    throw new InvalidOrderTransitionException(orderNumber, status,
                            "an order awaiting stock ships its part held only when Sales allowed a partial shipment");
                }
                transitionTo(OrderStatus.PARTIALLY_FULFILLED, by, null, at);
                recordPacked(at);
                return true;
            }
            case ON_HOLD -> throw new InvalidOrderTransitionException(orderNumber, status,
                    "resolve the hold before packing");
            default -> throw new InvalidOrderTransitionException(orderNumber, status, OrderStatus.PARTIALLY_FULFILLED);
        }
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
        release(warehouse, warehouseCode, by, now, null, null);
    }

    /**
     * 4B-8 (17 §4.5, BR-03, BR-29): the first delivery batch released - the order goes to the warehouse as before, but
     * only the lines that batch carries need their stock held ({@code batchLines}; null: every line, an order without a
     * schedule). {@code OrderReleased} names the batch, so payment makes due only what that batch waits for. An order
     * picked before its release ({@link #prePicked}) whose part held was handed over first is PARTIALLY_FULFILLED when
     * its batch is held in full; it is released then and stays PARTIALLY_FULFILLED (07 §4.3 step 7).
     */
    public void release(UUID warehouse, String warehouseCode, UUID by, Instant now, UUID deliveryBatchId,
                        java.util.Set<UUID> batchLines) {
        java.util.Objects.requireNonNull(warehouse, "warehouse");
        java.util.Objects.requireNonNull(by, "by");
        // 07 §4.3 step 7, 17 §4.4 step 16: an order picked before its release whose part held left first (Sales allowed
        // it, 17 §4.5 step 18) is PARTIALLY_FULFILLED; its batch is released once held in full, and it stays so.
        boolean partShippedFirst = status == OrderStatus.PARTIALLY_FULFILLED && prePicked() && paidByContract();
        if (status != OrderStatus.CONFIRMED && !(status == OrderStatus.AWAITING_STOCK && paidByContract())
                && !partShippedFirst) {
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
        if (paidByContract() && lines.stream().filter(line -> batchLines == null || batchLines.contains(line.id()))
                .anyMatch(line -> !line.isReserved())) {
            // An order confirmed by its contract holds nothing until its shipping warehouse is chosen and its stock held
            // there (17 §4.2, 4B-7): released now, it would go to the warehouse with nothing to pick. (A checkout order
            // reserved its stock when it was placed.)
            throw new BusinessException(ErrorCode.ORDER_STOCK_NOT_HELD, ("Order %s holds no stock for some of its "
                    + "lines: choose its shipping warehouse and hold its stock first").formatted(orderNumber));
        }
        if (!partShippedFirst) {
            transitionTo(OrderStatus.READY_TO_FULFILL);
        }
        this.warehouseId = warehouse;
        this.releasedAt = now;
        this.releasedBy = by;
        registerEvent(new OrderEvent.Released(new com.stockflow.contracts.OrderReleased(id.value(), warehouseCode, now,
                deliveryBatchId)));
    }

    /**
     * 4B-8 (17 §4.5, BR-29): a later delivery batch released, once the one before it was handed over - the order is
     * PARTIALLY_FULFILLED and stays so: its status follows the order, the batch's own state is worked out (§5.5). Only
     * from the warehouse it was first released from (BR-22).
     */
    public void releaseLaterBatch(UUID warehouse, String warehouseCode, UUID deliveryBatchId, Instant now) {
        java.util.Objects.requireNonNull(deliveryBatchId, "deliveryBatchId");
        if (status != OrderStatus.PARTIALLY_FULFILLED) {
            throw new InvalidOrderTransitionException(orderNumber, status, status == OrderStatus.ON_HOLD
                    ? "resolve the hold before releasing its next delivery batch"
                    : "a later delivery batch is released once the one before it was handed over");
        }
        if (!warehouse.equals(warehouseId)) {
            throw new BusinessException(ErrorCode.ORDER_SHIPPING_WAREHOUSE_MISMATCH, ("Order %s ships from another "
                    + "warehouse").formatted(orderNumber));
        }
        registerEvent(new OrderEvent.Released(new com.stockflow.contracts.OrderReleased(id.value(), warehouseCode, now,
                deliveryBatchId)));
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
     * The check station printed the order's first shipping label (kltn-docs 08 §4.3 step 7, BR-04): from now the order
     * is not cancelled and its delivery address, printed on the label, does not change (17 BR-12, BR-16). Only an order
     * the warehouse may still work on gets a label - a cancelled one has its cartons removed instead, and a held one
     * waits for its hold to be resolved. Idempotent.
     *
     * <p>The station checks the same statuses before it takes this lock; they are checked again here, under the order's
     * row lock, because a hold put on in between - an ADDRESS hold above all (08 edge case: a wrong address is caught
     * before the first label) - must stop the label that would lock the address it was put on to correct.</p>
     *
     * @return whether this was the first label
     * @throws BusinessException {@code ORDER_CANCELLED}; {@code PACK_STATE_CONFLICT} while the order is on hold, or in
     *         any status the check station does not work in
     */
    public boolean recordFirstLabel(Instant at) {
        java.util.Objects.requireNonNull(at, "at");
        if (status == OrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_CANCELLED,
                    "Order %s is cancelled; none of its cartons is labelled".formatted(orderNumber));
        }
        if (firstLabeledAt != null && (status == OrderStatus.PACKED
                || status == OrderStatus.ON_HOLD && holdReason == HoldReason.INVENTORY)) {
            // RTM-RTDCANCEL (owner 2026-11-19; 09 §5.1, 08 §5.1): a shipment of the packed order was cancelled before
            // dispatch and its replacement cartons are labelled in the reopened check, while the order waits on the
            // shortage hold that cancellation put on it (or once it is PACKED again). The order was locked by its first
            // label already; this one changes nothing. Which held order may be labelled at all is the station's call.
            return false;
        }
        if (status == OrderStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, ("Order %s is on hold (%s); its cartons are "
                    + "labelled once the hold is resolved").formatted(orderNumber, holdReason));
        }
        // 08 §4.1 step 1: an order still AWAITING_STOCK is checked and labelled only when Sales allowed its part held to
        // go first (17 §4.5 step 18).
        boolean partFirst = status == OrderStatus.AWAITING_STOCK && allowPartialShipment;
        if (!LABELLABLE.contains(status) && !partFirst) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "Order %s is %s; its cartons are not labelled now".formatted(orderNumber, status));
        }
        if (firstLabeledAt != null) {
            return false;
        }
        firstLabeledAt = at;
        return true;
    }

    public Instant firstLabeledAt() { return firstLabeledAt; }

    /**
     * Sales change the delivery address (kltn-docs 17 §4.7 steps 23-24, BR-16; 08 BR-04), under the order's row lock -
     * the lock the first label takes ({@link #recordFirstLabel}), so no label is printed between this check and the
     * change. The same address again changes nothing.
     *
     * @return whether the address changed
     * @throws BusinessException {@code ORDER_CANCELLED}; {@code ORDER_ADDRESS_LOCKED} once a carton of the order is
     *         labelled, or in a status where the address does not change ({@link #ADDRESS_CHANGEABLE})
     */
    public boolean changeShippingAddress(OrderAddressSnapshot to) {
        java.util.Objects.requireNonNull(to, "to");
        if (status == OrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_CANCELLED,
                    "Order %s is cancelled; its delivery address does not change".formatted(orderNumber));
        }
        if (firstLabeledAt != null) {
            throw new BusinessException(ErrorCode.ORDER_ADDRESS_LOCKED, ("Order %s has a carton labelled since %s; its "
                    + "delivery address does not change (kltn-docs 08 BR-04)").formatted(orderNumber, firstLabeledAt));
        }
        OrderStatus standing = status == OrderStatus.ON_HOLD ? statusBeforeHold : status;
        if (!ADDRESS_CHANGEABLE.contains(standing)) {
            throw new BusinessException(ErrorCode.ORDER_ADDRESS_LOCKED, (standing == OrderStatus.PENDING_CONTRACT
                    ? "Order %s waits for its contract to be signed; cancel the contract to change the address"
                    : "Order %s is %s; its delivery address does not change now").formatted(orderNumber, status));
        }
        if (to.equals(shippingAddress)) {
            return false;
        }
        shippingAddress = to;
        return true;
    }

    /** Rehydration of when the first shipping label was printed. */
    public void restoreFirstLabeled(Instant at) {
        this.firstLabeledAt = at;
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
        if (holdReason == HoldReason.DELIVERY_FAILED) {
            throw new BusinessException(ErrorCode.ORDER_DELIVERY_FAILURE_USE_DECISION, ("Order %s is held for a failed "
                    + "delivery (from before RTM-RETURNED); cancel what was not delivered on its delivery failure case")
                    .formatted(orderNumber));
        }
        if (holdReason == HoldReason.CREDIT || statusBeforeHold == null) {
            throw new BusinessException(ErrorCode.ORDER_CREDIT_HOLD_USE_APPROVAL,
                    "Order %s waits for a credit decision; decide it through credit approval".formatted(orderNumber));
        }
        resumeFromHold(note, by, at);
    }

    /**
     * Sales decided the delivery failure the order was held for (WF-05 fix plan, section 2): back to
     * PARTIALLY_FULFILLED - the units that came back are owed a delivery again, or are about to be closed with the rest
     * - then on with what its other shipments reported meanwhile. Only this lifts a DELIVERY_FAILED hold
     * ({@link #resolveHold} refuses it): the hold is the decision waiting, not a note waiting.
     *
     * @throws BusinessException {@code ORDER_NOT_ON_HOLD} for an order not held for a failed delivery
     */
    public void resolveDeliveryFailure(String note, UUID by, Instant at) {
        if (status != OrderStatus.ON_HOLD || holdReason != HoldReason.DELIVERY_FAILED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_ON_HOLD,
                    "Order %s is %s, not held for a failed delivery".formatted(orderNumber, status));
        }
        resumeFromHold(note, by, at);
    }

    /** Whether the order waits for Sales' decision on a shipment that came back. */
    public boolean heldForDeliveryFailure() {
        return status == OrderStatus.ON_HOLD && holdReason == HoldReason.DELIVERY_FAILED;
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
        // SCRUM-260's free replacement orders went with team plan 4D-3 (an exchange is a new quote, 17 §4.10 step 41):
        // nothing is withdrawn through a return any more, and the code stays only for the history it names.
        if (code == CancellationReasonCode.REPLACEMENT_WITHDRAWN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "REPLACEMENT_WITHDRAWN is historical: no order replaces a return's goods any more");
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
        // Nothing is kept on the order: what a cancellation keeps of the money received is its settlement's (4B-9), and
        // the order records no money received since 4B-10 (paid_amount dropped by 4B-11). Old orders keep their value.
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

    /**
     * The agreed amount of a closure: once the goods exist - printing started, or goods of the order came back - more
     * than zero, whole units, at most the prints closed. {@code payment.check_order_settlement} says the first again.
     */
    private BigDecimal requireRemainderFee(com.stockflow.order.api.CancellationFee fee, BigDecimal closedPrints,
                                           boolean goodsMade) {
        if (!goodsMade) {
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
        requireHandoverAllowed(installmentsDue, null, null);
    }

    /**
     * 4B-8: as above, for the delivery batch in flight - its installments (those tied to it, or to no batch) and, from
     * 09 BR-04 / 17 BR-29, not before the first day of its delivery window in force.
     *
     * @param batch null: the order has no delivery schedule, or none released by batch
     * @param day   the day the goods would leave, in the business calendar
     */
    public void requireHandoverAllowed(BigDecimal installmentsDue, BatchInFlight batch, java.time.LocalDate day) {
        requireNotCancelled("its goods are not handed over");
        if (status != OrderStatus.PICKING && status != OrderStatus.PACKED && status != OrderStatus.PARTIALLY_FULFILLED) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    status == OrderStatus.ON_HOLD ? "resolve the hold before handing goods over"
                            : "only a picking, packed or partly shipped order hands goods over");
        }
        requireHandoverPaid(installmentsDue);
        if (batch != null && batch.windowStart() != null && day != null && day.isBefore(batch.windowStart())) {
            throw new BusinessException(ErrorCode.DELIVERY_BATCH_WINDOW_NOT_OPEN, ("Delivery batch %d of order %s is "
                    + "delivered from %s, not on %s; move its window if the customer takes it early")
                    .formatted(batch.seq(), orderNumber, batch.windowStart(), day));
        }
    }

    /**
     * Every reason {@link #requireHandoverAllowed(BigDecimal, BatchInFlight, java.time.LocalDate)} would refuse the
     * goods now, as error codes, in the order that method checks them (its own answer is the first). Empty when the
     * goods may leave. A vehicle's refusal lists them all, so one signing shows everything still in the way.
     */
    public java.util.List<String> handoverBlockReasons(BigDecimal installmentsDue, BatchInFlight batch,
                                                       java.time.LocalDate day) {
        java.util.List<String> reasons = new java.util.ArrayList<>();
        try {
            requireNotCancelled("its goods are not handed over");
            if (status != OrderStatus.PICKING && status != OrderStatus.PACKED
                    && status != OrderStatus.PARTIALLY_FULFILLED) {
                throw new InvalidOrderTransitionException(orderNumber, status, "goods are not handed over in this status");
            }
        } catch (BusinessException refused) {
            reasons.add(refused.errorCode().name());
        }
        try {
            requireHandoverPaid(installmentsDue);
        } catch (BusinessException refused) {
            reasons.add(refused.errorCode().name());
        }
        if (batch != null && batch.windowStart() != null && day != null && day.isBefore(batch.windowStart())) {
            reasons.add(ErrorCode.DELIVERY_BATCH_WINDOW_NOT_OPEN.name());
        }
        return reasons;
    }

    /** @param installmentsDue as for {@link #markShipped(Instant, UUID, BigDecimal)} */
    public boolean recordHandover(ShipmentHandover handover, BigDecimal installmentsDue) {
        return recordHandover(handover, installmentsDue, null, null);
    }

    /**
     * 4B-8: a shipment of the delivery batch in flight. Units of the batches not released yet are not "left behind"
     * (17 BR-15: a schedule needs no partial-shipment decision), and the shipment records its batch.
     *
     * @param day as for {@link #requireHandoverAllowed(BigDecimal, BatchInFlight, java.time.LocalDate)}
     */
    public boolean recordHandover(ShipmentHandover handover, BigDecimal installmentsDue, BatchInFlight batch,
                                  java.time.LocalDate day) {
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
        requireHandoverAllowed(installmentsDue, batch, day);
        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            OrderLine line = findLine(entry.getKey()).orElseThrow(() -> new BusinessException(
                    ErrorCode.VALIDATION_FAILED, "Line %s is not on order %s".formatted(entry.getKey(), orderNumber)));
            // Units that came back count as left to ship: they may be shipped again (split shipments, G4-Q3), and
            // outstandingUnits / markPacked count them the same way.
            int left = line.quantity() - line.cancelledQuantity() - shippedUnits(line.id());
            if (entry.getValue() > left) {
                throw new BusinessException(ErrorCode.ORDER_SHIPMENT_QUANTITY_EXCEEDED,
                        "Line %s of order %s has %d unit(s) left to ship, not %d".formatted(line.sku().code(),
                                orderNumber, left, entry.getValue()));
            }
        }
        Money value = worthWith(quantities);
        int leaving = quantities.values().stream().mapToInt(Integer::intValue).sum();
        int together = unitsDueTogether(batch);
        boolean everythingGoes = together == leaving;
        if (status == OrderStatus.PICKING && everythingGoes) {
            throw new InvalidOrderTransitionException(orderNumber, status, "pack it before handing everything over");
        }
        // PACK-PARTIAL (lead's decision 2026-10-21): goods left behind - not in this shipment nor in the others signed
        // over with it - make this a partial shipment, which Sales decides on the order, not the warehouse.
        int leavingWith = quantitiesOf(handover.leavingWith()).values().stream().mapToInt(Integer::intValue).sum();
        if (!allowPartialShipment && together > leaving + leavingWith) {
            throw new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED,
                    "Order %s does not allow partial shipments: %d unit(s) would stay behind. Hand everything over "
                            .formatted(orderNumber, together - leaving - leavingWith)
                            + "together, or ask Sales to allow partial shipment on the order");
        }
        shipments.add(OrderShipment.handedOver(handover.shipmentId(), quantities, value.amount(),
                handover.handedOverAt(), handover.handedOverBy(), handover.carriage(),
                batch == null ? null : batch.batchId()));
        // Goods handed over were packed: from here the order goes through a return, never a cancellation.
        recordPacked(handover.handedOverAt());
        followShipments(ShipmentChange.HANDED_OVER, handover.handedOverAt(), handover.handedOverBy());
        return true;
    }

    /**
     * What a shipment leaving with {@code quantities} is worth (4B-3): what the shipments still out - with the carrier
     * or delivered - are worth once it has left, less what they carry already. Out, they are worth each line's units
     * out as a share of the line with tax (14 BR-21), and the fee lines, once (14 BR-09): the fee lines carry no units
     * and go with the first shipment. A shipment whose units were closed (its return receipt confirmed, or closed as
     * lost) took its value with it, so the next one carries it again - the fee lines too - and the rounded shares of a line still add up to the line, whatever order the
     * shipments went and came back in. On an order without VAT or fee lines this is the units at their price.
     */
    private Money worthWith(Map<UUID, Integer> quantities) {
        Money worth = feesGross();
        for (OrderLine line : lines) {
            worth = worth.plus(line.grossOf(shippedUnits(line.id()) + quantities.getOrDefault(line.id(), 0)));
        }
        BigDecimal carried = shipments.stream().filter(OrderShipment::holdsUnits).map(OrderShipment::amount)
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
     * The carrier delivered one shipment (09 §4.3 step 1; the warehouse manager records it, BR-02). The order is
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
        shipment.delivered(at, by);
        registerDelivered(shipmentId, at);
        followShipments(ShipmentChange.OUTCOME, at, by);
        return true;
    }

    /**
     * A delivery the carrier reported wrongly, corrected to a failure by the warehouse manager (09 §4.3 step 5, E17;
     * 17 §5.1): the shipment DELIVERY_FAILED and the order following it, while the order is not completed. Nothing is
     * billed on delivery since 4B-10 (the money ledger went; 4B-11 dropped what a shipment billed), so no delivery is
     * held back for that.
     *
     * <p>WF-05 BE-46: also while a claim on another shipment is open or settled (RETURN_REQUESTED, RETURNED, REFUNDED,
     * PARTIALLY_REFUNDED) - a return against shipment A says nothing of whether B arrived. The caller refuses the
     * shipment a return is against. The order keeps its status there ({@link #followShipments}): the return path decides
     * where it goes next, and {@link #close} waits for the corrected shipment. Never once the order completed - a
     * COMPLETED order, or one that completed before a late claim - nor once it is closed or cancelled.</p>
     *
     * @return whether this changed the order
     * @throws BusinessException {@code ORDER_DELIVERY_NOT_CORRECTABLE} for an order past its deliveries;
     *         {@code ORDER_SHIPMENT_INVALID_TRANSITION} for a shipment not delivered
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
        if (!DELIVERIES_CORRECTABLE.contains(status) || completedAt != null) {
            throw new BusinessException(ErrorCode.ORDER_DELIVERY_NOT_CORRECTABLE, ("Order %s is %s%s: its deliveries "
                    + "are no longer corrected").formatted(orderNumber, status,
                    completedAt == null ? "" : ", completed on " + completedAt));
        }
        if (!shipment.correctedToFailed(reason, trimmed, at, by)) {
            return false;
        }
        // No longer delivered in full: followShipments takes the date away (followDeliveredAt), except where the
        // status needs one (RETURN_REQUESTED) - payment reads deliveredInFullAt, which no longer has one.
        followShipments(ShipmentChange.OUTCOME, at, by);
        return true;
    }

    /**
     * The real reason a shipment's delivery failed, as the warehouse manager learnt it (09 edge "hoàn hàng khi còn
     * Handed Over"): the shipment's reason and note change; an order waiting on that failure shows the reason of its
     * failed shipments again. OTHER needs a note.
     *
     * @return whether this changed the order
     * @throws BusinessException {@code ORDER_SHIPMENT_INVALID_TRANSITION} for a shipment neither failed nor returned
     */
    public boolean correctShipmentFailureReason(UUID shipmentId, DeliveryFailureReason reason, String note) {
        java.util.Objects.requireNonNull(reason, "reason");
        String trimmed = trimmed(note);
        if (reason == DeliveryFailureReason.OTHER && trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A failure for another reason says what it was");
        }
        OrderShipment shipment = requireShipment(shipmentId);
        if (shipment.unitsClosedAt() != null && shipment.status() == OrderShipmentStatus.DELIVERY_FAILED) {
            // Q-12: closed as lost on the carrier's confirmation - the reason is what the closure stands on.
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION, ("Shipment %s of order %s was "
                    + "closed as lost: its failure reason stays").formatted(shipmentId, orderNumber));
        }
        if (!shipment.correctFailureReason(reason, trimmed)) {
            return false;
        }
        if (status == OrderStatus.DELIVERY_FAILED) {
            this.deliveryFailureReason = shipments.stream()
                    .filter(s -> s.status() == OrderShipmentStatus.DELIVERY_FAILED && s.pending())
                    .map(OrderShipment::failureReason).findFirst().orElse(deliveryFailureReason);
        }
        return true;
    }

    /**
     * RTM-RETURNED-PARTIAL: the order's deliveries are over ({@link #deliveriesOver} - so every shipment that came back
     * has its return receipt confirmed and its units closed, Q-7), some of its shipments delivered and some came back
     * or were lost. What its refund moves on to PARTIALLY_REFUNDED ({@link #partRefunded}).
     */
    public boolean partlyReturnedAndDone() {
        return deliveriesOver()
                && shipments.stream().anyMatch(OrderShipment::endedUndelivered)
                && shipments.stream().anyMatch(s -> s.status() == OrderShipmentStatus.DELIVERED);
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
     * One failed shipment came back from the carrier (09 §4.3 step 3), as the warehouse manager or the carrier reported
     * it: the shipment is RETURNED and the order follows its shipments - RETURNED when nothing of it was delivered,
     * PARTIALLY_FULFILLED while other shipments arrived (RTM-RETURNED, owner 18-19/11; 17 §5.1).
     *
     * <p>Nothing else moves yet (WF-05 decision Q-7). The report is the carrier's word; the goods are not seen until the
     * warehouse confirms their return receipt. So the shipment keeps its units - they are on their way back, neither
     * owed again nor closed - and no money is settled: {@link #closeReturnedShipment} closes them, and payment settles
     * them, when the receipt is confirmed.</p>
     *
     * @return whether this changed the order
     */
    public boolean recordShipmentReturned(UUID shipmentId, Instant at, UUID by) {
        OrderShipment shipment = requireShipment(shipmentId);
        if (!shipment.returned(at, by)) {
            return false;
        }
        followShipments(ShipmentChange.RETURNED, at, by,
                shipment.failureReason() == null ? "RETURNED" : "RETURNED_" + shipment.failureReason().name());
        return true;
    }

    /**
     * What closing one shipment's units settled (WF-05 Q-2, Q-7, Q-12): the closure's id - the key payment settles it
     * under and {@code ShipmentReturnSettled.closureId} - and the value of the units closed with their VAT, worked out
     * with {@code OrderLine.grossBetween} so that every unit of a line closed adds up to the line. The share of the
     * shipping fee waived with them (Q-19) is payment's to work out from that value and the order's goods.
     */
    public record ShipmentClosure(UUID closureId, UUID shipmentId, UUID deliveryBatchId, BigDecimal goodsValue,
                                  Instant goodsBackAt) {
    }

    /**
     * The return receipt of a shipment the carrier brought back was confirmed (WF-05 Q-7; 03 §4.2 step 12): the goods
     * are seen, so the shipment's units are closed on their lines now - they are sent again only on a new order
     * (RTM-RETURNED) - and the caller has payment settle their value (Q-2) in this transaction, dated by its own clock
     * (BE-32). {@code ShipmentReturnSettled} tells payment and invoicing after the commit.
     *
     * <p>Once per shipment: a second receipt of the same shipment (BE-13), a replayed event, or a shipment closed before
     * the closure moved to the receipt (its units closed when it was reported, or owed again under the old hold) closes
     * nothing and announces nothing - empty.</p>
     *
     * @param goodsBackAt when the receipt was confirmed: the day installments after delivery may count from (Q-3)
     * @param settledAt   the transaction clock
     * @throws BusinessException {@code ORDER_SHIPMENT_INVALID_TRANSITION} for a shipment not RETURNED
     */
    public Optional<ShipmentClosure> closeReturnedShipment(UUID shipmentId, Instant goodsBackAt, Instant settledAt,
                                                           UUID by) {
        OrderShipment shipment = requireShipment(shipmentId);
        if (!shipment.isReturned()) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_INVALID_TRANSITION, ("Shipment %s of order %s is %s: "
                    + "only a shipment that came back is closed at its return receipt").formatted(shipmentId,
                    orderNumber, shipment.status()));
        }
        if (!shipment.holdsUnits()) {
            return Optional.empty();
        }
        return Optional.of(closeShipment(shipment, com.stockflow.contracts.ShipmentReturnSettled.Cause.CARRIER_RETURN,
                java.util.Objects.requireNonNull(goodsBackAt, "goodsBackAt"), settledAt, by, "RETURN_RECEIVED"));
    }

    /**
     * Sales close a shipment the carrier lost (WF-05 decision Q-12; 09 line 613): it failed with reason LOST and will
     * never come back, so its units are closed on their lines now and the caller has payment settle them as a carrier
     * return (Q-2), at this moment (no goods come back to be counted). The shipment stays DELIVERY_FAILED with its
     * reason; the order follows its shipments as if it had come back - PARTIALLY_FULFILLED when others arrived,
     * RETURNED when none did. Before this, such an order sat in DELIVERY_FAILED with no way out but a false RETURNED.
     *
     * <p>Goods found and brought back afterwards are owed to nobody: the shipment may still be recorded RETURNED, and
     * its receipt closes nothing again ({@link #closeReturnedShipment}). It is never delivered on this order any more
     * ({@link OrderShipment#delivered} refuses it).</p>
     *
     * <p>Also a shipment the carrier reported coming back whose goods never reached the warehouse (RETURNED, its
     * receipt never to be confirmed): lost on the way back. Without this its order waited for a receipt for good
     * (L-I6) - the carrier's word alone still closes nothing, Sales' closure with the carrier's confirmation does.</p>
     *
     * @throws BusinessException {@code ORDER_SHIPMENT_NOT_LOST_CLOSABLE} for a shipment that is neither DELIVERY_FAILED
     *         for LOST nor RETURNED awaiting its receipt, or whose units were closed already, or an order cancelled or
     *         closed; {@code VALIDATION_FAILED} without a note of at most 1000 characters
     */
    public ShipmentClosure closeLostShipment(UUID shipmentId, String note, Instant at, UUID by) {
        String trimmed = trimmed(note);
        if (trimmed == null || trimmed.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Closing a lost shipment says why, in at most "
                    + "1000 characters");
        }
        OrderShipment shipment = requireShipment(shipmentId);
        boolean lost = shipment.status() == OrderShipmentStatus.DELIVERY_FAILED
                && shipment.failureReason() == DeliveryFailureReason.LOST;
        if (!(lost || shipment.awaitingReturnReceipt()) || !shipment.holdsUnits()
                || status == OrderStatus.CANCELLED || status == OrderStatus.CLOSED) {
            throw new BusinessException(ErrorCode.ORDER_SHIPMENT_NOT_LOST_CLOSABLE, ("Shipment %s of order %s is %s%s%s: "
                    + "only a shipment failed as LOST, or reported back with no receipt, its units still on it, is "
                    + "closed as lost").formatted(
                    shipmentId, orderNumber, shipment.status(),
                    shipment.failureReason() == null ? "" : " (" + shipment.failureReason() + ")",
                    shipment.holdsUnits() ? "" : ", its units closed already"));
        }
        return closeShipment(shipment, com.stockflow.contracts.ShipmentReturnSettled.Cause.LOST, at, at, by,
                "LOST_CLOSED");
    }

    /** The closure both {@link #closeReturnedShipment} and {@link #closeLostShipment} make. */
    private ShipmentClosure closeShipment(OrderShipment shipment,
                                          com.stockflow.contracts.ShipmentReturnSettled.Cause cause,
                                          Instant goodsBackAt, Instant settledAt, UUID by, String reason) {
        BigDecimal value = closeUnitsOf(shipment, goodsBackAt);
        shipment.recordClosedValue(value);
        // One closure per shipment, so its id is the shipment's: a unit of work run again after a rollback settles under
        // the same key, and payment's idempotency on it holds whatever happened in between.
        this.lastClosureId = shipmentClosureId(shipment.id());
        followShipments(ShipmentChange.CLOSED, settledAt, by, reason);
        // The one definition of "nothing delivered" (deliveredSomething): what liquidates the contract (Q-6) and what
        // payment waives the whole shipping fee by (Q-19).
        boolean nothingDelivered = !deliveredSomething();
        registerEvent(new OrderEvent.ShipmentSettled(new com.stockflow.contracts.ShipmentReturnSettled(id.value(),
                orderNumber.value(), customerId, shipment.id(), shipment.deliveryBatchId(), lastClosureId, cause,
                settledAt, goodsBackAt, nothingDelivered, deliveriesOver())));
        return new ShipmentClosure(lastClosureId, shipment.id(), shipment.deliveryBatchId(), value, goodsBackAt);
    }

    /**
     * What payment waived of the order's shipping fee with a shipment it just settled (WF-05 Q-19): kept on the shipment,
     * once, in the transaction that closed its units - payment works the share out; invoicing reads it here.
     */
    public void recordShippingFeeWaived(UUID shipmentId, BigDecimal waived) {
        if (waived != null && waived.signum() > 0) {
            requireShipment(shipmentId).recordShippingFeeWaived(waived);
        }
    }

    /** The id of the one closure of a shipment's units: what payment settles it under. */
    public static UUID shipmentClosureId(UUID shipmentId) {
        return UUID.nameUUIDFromBytes(("shipment-units-closed:" + shipmentId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The units a shipment still holds, closed on their lines, and the shipment marked closed; their gross value. Each
     * line closes the shipment's units, never more than the line has open beside what its other shipments hold.
     */
    private BigDecimal closeUnitsOf(OrderShipment shipment, Instant at) {
        Money closed = Money.zero(total().currency());
        for (OrderLine line : lines) {
            int carried = shipment.quantityOf(line.id());
            if (carried == 0) {
                continue;
            }
            int open = line.quantity() - line.cancelledQuantity();
            int others = shippedUnits(line.id()) - (shipment.holdsUnits() ? carried : 0);
            int units = Math.min(carried, open - others);
            if (units > 0) {
                line.closeUnits(units);
                closed = closed.plus(line.grossBetween(open - units, open));
            }
        }
        shipment.closeUnits(at);
        return closed.amount();
    }

    /**
     * Whether the order's deliveries are over (WF-05 X-3; {@code ShipmentReturnSettled.nothingMoreComing}): it has
     * shipments, no unit is left to ship, and none of them is still to be decided - in transit, failed and neither
     * delivered again nor closed as lost, or reported back by the carrier with its return receipt not confirmed yet.
     */
    public boolean deliveriesOver() {
        return !shipments.isEmpty() && outstandingUnits() == 0
                && shipments.stream().noneMatch(s -> s.pending() || s.awaitingReturnReceipt());
    }

    /**
     * When the order counts as delivered in full (WF-05 decision Q-3; kltn-docs 15 BR-09, D23), from its facts and
     * whatever its status: once its deliveries are over and something of it was delivered, the latest of its last
     * delivery, the closure of a shipment that came back (its receipt confirmed) or was lost, and the closure of its
     * rest. Empty while anything is still to come, or when nothing was delivered. What payment reaches the installments
     * after delivery from ({@code InstallmentOrders.deliveredInFullAt}), and what {@link #deliveredAt} holds then.
     *
     * <p>The latest delivery, not the last one recorded (BE-04): a delivery recorded late with its real, earlier date
     * does not pull the date back, and neither does a correction back to DELIVERED. An order delivered whole, without
     * shipments (G4-Q5, historical), was delivered when it was recorded so.</p>
     */
    public Optional<Instant> deliveredInFullAt() {
        if (shipments.isEmpty()) {
            return status == OrderStatus.RETURNED || status == OrderStatus.DELIVERY_FAILED
                    ? Optional.empty() : Optional.ofNullable(deliveredAt);
        }
        if (!deliveriesOver()) {
            return Optional.empty();
        }
        Optional<Instant> lastDelivery = lastDelivery();
        if (lastDelivery.isEmpty()) {
            return Optional.empty();
        }
        Instant latest = lastDelivery.get();
        for (OrderShipment shipment : shipments) {
            if (shipment.unitsClosedAt() != null && shipment.unitsClosedAt().isAfter(latest)) {
                latest = shipment.unitsClosedAt();
            }
        }
        if (remainderClosedAt != null && remainderClosedAt.isAfter(latest)) {
            latest = remainderClosedAt;
        }
        return Optional.of(latest);
    }

    /**
     * Whether no shipment of the order is still to be decided or on its way back with its receipt not confirmed (L-I6):
     * its deliveries' money is settled. What closing the order waits for.
     */
    public boolean deliveriesSettled() {
        return shipments.stream().noneMatch(s -> s.pending() || s.awaitingReturnReceipt());
    }

    /**
     * Whether goods of the order reached the customer (WF-05 Q-6, L-I4): a shipment of it DELIVERED, or - an order
     * delivered whole before shipments were recorded (G4-Q5, historical) - its delivery date. What tells a contract to
     * complete from one to liquidate; {@code ordering.order_delivered_something} answers the same in the database.
     * The one definition of "nothing delivered" in the order module: its negation is published as
     * {@code ShipmentReturnSettled.nothingDelivered} and is what payment waives the whole shipping fee by (Q-19, owner
     * 23/11: no shipment delivered, even with the rest closed before it shipped).
     */
    public boolean deliveredSomething() {
        return lastDelivery().isPresent() || shipments.isEmpty() && deliveredAt != null;
    }

    /** The latest outcome of the shipments delivered; empty when none is. What the return windows count from. */
    public Optional<Instant> lastDeliveryAt() {
        return lastDelivery();
    }

    /** The latest outcome of the shipments delivered; empty when none is. */
    private Optional<Instant> lastDelivery() {
        return shipments.stream().filter(s -> s.status() == OrderShipmentStatus.DELIVERED)
                .map(OrderShipment::outcomeAt).filter(java.util.Objects::nonNull).max(Instant::compareTo);
    }

    /**
     * Staff close what is left of a partly fulfilled order (17 edge "short pick: phần còn lại huỷ + hoàn tiền phần
     * thiếu"; 17 §5 "Partially Fulfilled → chốt"): the units in no shipment - never picked, or owed again by a shipment
     * that came back before WF-05 Q-7 - will not be delivered. A shipment the carrier reported back still holds its
     * units until its return receipt closes them ({@link #closeReturnedShipment}): this closes none of them. The order then shows its shipments (SHIPPED, DELIVERY_FAILED, DELIVERED or RETURNED);
     * {@code OrderRemainderClosed} tells fulfillment to stop the rest; the money is settled on the contract's installments
     * by payment in the caller's transaction (4B-9). Only from PARTIALLY_FULFILLED: an order on hold is resolved first.
     * Nothing is left labelled at dispatch here; see {@link #closeRemainder(CancellationReasonCode, String,
     * com.stockflow.order.api.CancellationFee, Map, UUID, Instant)} for an order whose checked part still waits there.
     *
     * @return the value of the units closed
     */
    public BigDecimal closeRemainder(CancellationReasonCode code, String note, UUID by, Instant at) {
        return closeRemainder(code, note, null, Map.of(), by, at);
    }

    /**
     * The same, keeping an amount agreed with the customer for print lines closed after printing started (review of
     * #150; decisions 2026-10-17 / 2026-10-19: cancelling after printing is allowed with a printing fee; 17 edge "khách
     * huỷ khi đang In Production (đã in)"). The fee is at most the value of the print units closed. Payment settles it
     * on the installments (4B-9): what was paid above the order's new worth pays it first, the rest is refunded, a
     * shortfall is owed on a fee item. Before printing nothing is kept ({@code CANCELLATION_FEE_BEFORE_PRINTING}) -
     * unless goods of the order came back from a failed delivery (WF-05 fix plan, section 2): they were made to the
     * customer's design whatever the production order recorded, so their closure may keep the agreed fee too.
     */
    public BigDecimal closeRemainder(CancellationReasonCode code, String note,
                                     com.stockflow.order.api.CancellationFee agreedFee, UUID by, Instant at) {
        return closeRemainder(code, note, agreedFee, Map.of(), by, at);
    }

    /**
     * The same, for an order with cartons checked and labelled for it that were not handed over yet (owner decision
     * WF4-CLOSE-REST, 2026-11-13; 17 §4.8 step 33): those units stay on the order and are delivered - their handover
     * goes on as before, and the last of them makes the order SHIPPED; only the units neither handed over nor labelled
     * are closed, and only they come off what the installments owe (15 §4.6). Until that handover the order stays
     * PARTIALLY_FULFILLED. Refused when nothing is left to close: everything still to go is labelled already.
     *
     * @param labelled per order line, the units in labelled cartons not handed over yet (fulfillment's
     *                 {@code LabelledNotHandedOver}); a line's count above what it has left is taken as what it has left
     */
    public BigDecimal closeRemainder(CancellationReasonCode code, String note,
                                     com.stockflow.order.api.CancellationFee agreedFee, Map<UUID, Integer> labelled,
                                     UUID by, Instant at) {
        java.util.Objects.requireNonNull(code, "code");
        Map<UUID, Integer> kept = labelled == null ? Map.of() : labelled;
        String trimmed = trimmed(note);
        if (code == CancellationReasonCode.OTHER && trimmed == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Closing the rest for OTHER must say why in the note");
        }
        if (status != OrderStatus.PARTIALLY_FULFILLED) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    status == OrderStatus.ON_HOLD ? "resolve the hold before closing the rest"
                            : "only a partly fulfilled order has a rest to close");
        }
        // Historical since team plan 4D-3: no order replaces a return's goods any more.
        if (code == CancellationReasonCode.REPLACEMENT_WITHDRAWN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "REPLACEMENT_WITHDRAWN is historical: no order replaces a return's goods any more");
        }
        // Per line, the units closed: neither shipped nor in a labelled carton waiting at dispatch (WF4-CLOSE-REST).
        Map<UUID, Integer> closing = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            int left = line.quantity() - line.cancelledQuantity() - shippedUnits(line.id());
            int closes = left - Math.min(left, Math.max(0, kept.getOrDefault(line.id(), 0)));
            if (closes > 0) {
                closing.put(line.id(), closes);
            }
        }
        if (closing.isEmpty()) {
            throw new InvalidOrderTransitionException(orderNumber, status,
                    "everything it still wants is labelled and waits at dispatch: nothing is left to close");
        }
        if (shipments.isEmpty() && kept.values().stream().noneMatch(units -> units > 0)) {
            // Nothing of it left and nothing labelled waits to go: closing would leave an order with no goods and no
            // shipment to follow. Not a case the docs describe (17 §4.8 step 33 closes the rest of goods that went).
            throw new InvalidOrderTransitionException(orderNumber, status,
                    "nothing of it was handed over and nothing labelled waits at dispatch: there is no part to deliver");
        }
        // Validated before anything changes: a refused fee leaves the order as it was.
        Money closedPrints = Money.zero(total().currency());
        for (OrderLine line : lines) {
            int closes = closing.getOrDefault(line.id(), 0);
            if (closes > 0 && line.printedToCustomerDesign()) {
                int open = line.quantity() - line.cancelledQuantity();
                closedPrints = closedPrints.plus(line.grossBetween(open - closes, open));
            }
        }
        boolean goodsMade = printingStartedAt != null || shipments.stream().anyMatch(OrderShipment::isReturned);
        BigDecimal fee = agreedFee == null ? BigDecimal.ZERO
                : requireRemainderFee(agreedFee, closedPrints.amount(), goodsMade);
        Money closed = Money.zero(total().currency());
        List<com.stockflow.contracts.OrderRemainderClosed.Line> closedLines = new ArrayList<>();
        for (OrderLine line : lines) {
            int closes = closing.getOrDefault(line.id(), 0);
            if (closes > 0) {
                int open = line.quantity() - line.cancelledQuantity();
                line.closeUnits(closes);
                closed = closed.plus(line.grossBetween(open - closes, open));
                closedLines.add(new com.stockflow.contracts.OrderRemainderClosed.Line(line.id(), line.sku().code(),
                        closes));
            }
        }
        String reason = withNote("REMAINDER_CLOSED_" + code.name(), trimmed);
        if (fee.signum() > 0) {
            // The fee and why, in the order's history (the event carries amounts only); the column holds 500.
            reason = reason + " (fee " + fee.stripTrailingZeros().toPlainString() + ": " + agreedFee.reason() + ")";
            reason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        }
        // D23 (15 BR-09): the rest closed counts towards when the order was delivered in full (deliveredInFullAt).
        this.remainderClosedAt = at;
        // With labelled cartons still to hand over, the order stays PARTIALLY_FULFILLED (followShipments leaves it so,
        // or has no shipment to look at yet); their last handover makes it SHIPPED.
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

    /** The statuses a delivery of the order may be corrected to a failure in (BE-46), before it ever completed. */
    private static final java.util.Set<OrderStatus> DELIVERIES_CORRECTABLE = java.util.EnumSet.of(
            OrderStatus.SHIPPED, OrderStatus.DELIVERY_FAILED, OrderStatus.DELIVERED, OrderStatus.PARTIALLY_FULFILLED,
            OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED, OrderStatus.REFUNDED, OrderStatus.PARTIALLY_REFUNDED);

    /**
     * The statuses an order keeps whatever its shipments do (WF-05 BE-46): a claim is open or decided on it, or it
     * completed, closed or was cancelled. A delivery corrected to a failure on such an order (a shipment no return is
     * against) and what that shipment does next - delivered again, brought back, closed as lost - change its
     * shipments and {@link #deliveredAt}, never this status: the return path decides where it goes next
     * ({@link #withdrawReturn}, {@link #returnsEndedWithoutRefund}), and {@link #close} waits for the shipment.
     */
    private static final java.util.Set<OrderStatus> STATUS_OF_ITS_OWN = java.util.EnumSet.of(
            OrderStatus.RETURN_REQUESTED, OrderStatus.REFUNDED, OrderStatus.PARTIALLY_REFUNDED, OrderStatus.COMPLETED,
            OrderStatus.CLOSED, OrderStatus.CANCELLED);

    /**
     * The order's status from its shipments, after one of them changed (see {@link OrderStatus}): an order with units
     * not in any shipment and not closed is partial (or stays PACKED while those are packed and nothing came back); one
     * with every unit in a shipment or closed is in transit while a shipment is, failed once all have an outcome and one
     * failed and is still to be decided, delivered once all arrived, partly fulfilled when some arrived and others came
     * back or were lost (RTM-RETURNED-PARTIAL), returned when none arrived. On hold it waits: resolving looks again. An
     * order on its return path keeps its status ({@link #STATUS_OF_ITS_OWN}; a RETURNED order with goods delivered is
     * one too - its claim came back).
     *
     * <p>A shipment the carrier reported back still holds its units until its return receipt is confirmed (Q-7), so it
     * counts as having come back here already - the order is RETURNED or PARTIALLY_FULFILLED from the report - while
     * {@link #deliveriesOver} and the money wait for the receipt.</p>
     *
     * <p>Every time, the date it was delivered in full is worked out again from the facts ({@link #deliveredInFullAt},
     * BE-02, BE-04): set once nothing more is coming and something was delivered, whatever the status, and never
     * the date of whichever delivery happened to be recorded last.</p>
     */
    private void followShipments(ShipmentChange change, Instant at, UUID by, String reason) {
        if (shipments.isEmpty()) {
            return;
        }
        boolean anyDelivered = shipments.stream().anyMatch(s -> s.status() == OrderShipmentStatus.DELIVERED);
        if (status == OrderStatus.ON_HOLD || STATUS_OF_ITS_OWN.contains(status)
                || status == OrderStatus.RETURNED && anyDelivered) {
            followDeliveredAt();
            return;
        }
        OrderStatus target;
        if (outstandingUnits() > 0) {
            if (change == ShipmentChange.HANDED_OVER || change == ShipmentChange.RETURNED
                    || status != OrderStatus.PACKED && status != OrderStatus.PICKING
                    && status != OrderStatus.PARTIALLY_FULFILLED) {
                target = OrderStatus.PARTIALLY_FULFILLED;
            } else {
                target = status;
            }
        } else {
            target = shipmentsStatus();
        }
        if (target != status) {
            if (reason == null && target == OrderStatus.DELIVERY_FAILED) {
                reason = shipments.stream().filter(s -> s.status() == OrderShipmentStatus.DELIVERY_FAILED && s.pending())
                        .map(s -> s.failureReason().name()).findFirst().orElse(null);
            }
            transitionTo(target, by, reason, at);
            Instant lastHandover = shipments.stream().map(OrderShipment::handedOverAt).max(Instant::compareTo)
                    .orElse(at);
            switch (target) {
                case SHIPPED -> this.shippedAt = lastHandover;
                case DELIVERY_FAILED, DELIVERED -> this.shippedAt = shippedAt == null ? lastHandover : shippedAt;
                default -> { }
            }
        }
        this.deliveryFailureReason = status == OrderStatus.DELIVERY_FAILED
                ? shipments.stream().filter(s -> s.status() == OrderShipmentStatus.DELIVERY_FAILED && s.pending())
                        .map(OrderShipment::failureReason).findFirst().orElse(DeliveryFailureReason.OTHER)
                : null;
        followDeliveredAt();
    }

    /**
     * What the order's shipments say, every unit of it being in a shipment or closed: in transit while one is, failed
     * while one failed and is still to be decided, delivered when all arrived, partly fulfilled when some arrived and
     * the others came back or were lost, returned when none arrived. An order delivered whole, with no shipment, is
     * DELIVERED.
     */
    private OrderStatus shipmentsStatus() {
        if (shipments.isEmpty()) {
            return OrderStatus.DELIVERED;
        }
        if (outstandingUnits() > 0) {
            return OrderStatus.PARTIALLY_FULFILLED;
        }
        if (shipments.stream().anyMatch(s -> s.status() == OrderShipmentStatus.HANDED_OVER)) {
            return OrderStatus.SHIPPED;
        }
        if (shipments.stream().anyMatch(s -> s.status() == OrderShipmentStatus.DELIVERY_FAILED && s.pending())) {
            return OrderStatus.DELIVERY_FAILED;
        }
        if (shipments.stream().anyMatch(s -> s.status() == OrderShipmentStatus.DELIVERED)) {
            // RTM-RETURNED-PARTIAL (owner 19/11): a shipment came back (or was lost) while the others arrived - the order
            // is partly fulfilled, its part that did not arrive refunded (then PARTIALLY_REFUNDED), never DELIVERED.
            return shipments.stream().anyMatch(OrderShipment::endedUndelivered) ? OrderStatus.PARTIALLY_FULFILLED
                    : OrderStatus.DELIVERED;
        }
        return OrderStatus.RETURNED;
    }

    /**
     * {@link #deliveredAt} from the facts (BE-02, BE-04): when the order was delivered in full, else nothing. A claim
     * open or a completed order keeps the date it has ({@code ck_order_delivered_at}): a delivery of it corrected to a
     * failure meanwhile (BE-46) takes its money's date away through {@link #deliveredInFullAt}, which payment reads, not
     * through this column.
     */
    private void followDeliveredAt() {
        Optional<Instant> full = deliveredInFullAt();
        if (full.isPresent()) {
            this.deliveredAt = full.get();
        } else if (status != OrderStatus.RETURN_REQUESTED && status != OrderStatus.COMPLETED) {
            this.deliveredAt = null;
        }
    }

    private Optional<OrderShipment> shipment(UUID shipmentId) {
        return shipments.stream().filter(s -> s.id().equals(shipmentId)).findFirst();
    }

    private OrderShipment requireShipment(UUID shipmentId) {
        return shipment(shipmentId).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_SHIPMENT_NOT_FOUND,
                "Order %s has no shipment %s handed over".formatted(orderNumber, shipmentId)));
    }

    /** Units of the line neither shipped nor closed: what is still to come of it. */
    public int unshippedUnits(UUID lineId) {
        OrderLine line = lines.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No line " + lineId + " on " + orderNumber));
        return line.quantity() - line.cancelledQuantity() - shippedUnits(lineId);
    }

    /**
     * Units of a line that shipments still hold (WF-05 Q-7): with the carrier, with the customer, or on their way back
     * from a carrier's return whose receipt is not confirmed yet. A shipment whose units were closed - its return
     * receipt confirmed, or closed as lost - holds none: they are closed on the line (or, on a row from before the
     * closure moved to the receipt, owed again until Sales closed them).
     */
    private int shippedUnits(UUID lineId) {
        return shipments.stream().filter(OrderShipment::holdsUnits).mapToInt(s -> s.quantityOf(lineId)).sum();
    }

    /**
     * Whether the order still owes a unit of a line {@code shipmentId} carried: in no shipment holding units, and not
     * closed. A shipment the carrier reported back still holds its units until its receipt closes them (WF-05 Q-7), so
     * this is false for it; true only for a shipment of an order from before RTM-RETURNED whose units were owed again
     * when it came back, and Sales were to decide (BE-16). After a closure its goods, received back later, are owed to
     * nobody (WF-05 review F1).
     */
    public boolean owesUnitsOf(UUID shipmentId) {
        return requireShipment(shipmentId).quantities().keySet().stream().anyMatch(line -> unshippedUnits(line) > 0);
    }

    /** Units neither closed nor in a shipment that is still with the carrier or the customer. */
    public int outstandingUnits() {
        return lines.stream().mapToInt(l -> l.quantity() - l.cancelledQuantity() - shippedUnits(l.id())).sum();
    }

    /**
     * PACK-PARTIAL with 4B-8 (kltn-docs 17 BR-15, §4.5): the units that must leave together for a shipment of this
     * order not to be a partial shipment - every unit it still wants ({@link #outstandingUnits}), less the units of the
     * delivery batches after the one in flight. A delivery schedule is a plan of whole shipments, so a shipment of
     * exactly the batch released leaves nothing behind; only a shipment short of that batch is "in parts" and needs
     * Sales' decision ({@link #allowPartialShipment}). The one count the handover, a vehicle leaving and the load
     * plan's confirmation (through {@code OrderService.unitsDueTogether}) all compare with. Never below zero.
     *
     * @param batch the batch in flight; null for an order that does not ship by batch - everything it still wants
     */
    public int unitsDueTogether(BatchInFlight batch) {
        return Math.max(0, outstandingUnits() - (batch == null ? 0 : batch.laterUnits()));
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
     * What the installments want before the goods leave ({@code installmentsDue}): the caller reads it for the batch
     * on its way (4B-8: those on signing, and those before delivery tied to that batch or to none), or for the whole
     * order when it does not release by batch.
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
     * <p>And one whose deliveries are not all invoiced stays DELIVERED too (WF-05 plan §4, condition 5). COMPLETED says
     * the sale is finished on both sides, and it completes the contract with it. A delivery without its e-invoice is
     * not finished: the law has every delivery invoiced (NĐ 254/2026/NĐ-CP Điều 9 khoản 3), the buyer cannot book the
     * goods without it, and a draft nobody issued - or one the tax authority refused - would otherwise disappear
     * behind an order that reads as done and is closed for good thirty days later. The order does not look at
     * invoices itself (it has none): the caller asks invoicing and passes the answer, as it does for the money.</p>
     *
     * @param owing            whether the order's installments still owe something, as payment reads them (4B-10: the
     *                         order keeps no money of its own); false for an order without installments
     * @param awaitingInvoice  whether a delivered shipment of the order has no e-invoice issued yet; false for an
     *                         order delivered without shipments, which has no delivery to invoice
     * @return whether this changed the order
     */
    public boolean complete(Instant now, java.time.Duration returnWindow, boolean hasOpenReturn, boolean owing,
                            boolean awaitingInvoice) {
        if (status != OrderStatus.DELIVERED || deliveredAt == null || hasOpenReturn
                || ReturnPolicy.endOfDays(deliveredAt, returnWindow).isAfter(now) || owing || awaitingInvoice) {
            return false;
        }
        transitionTo(OrderStatus.COMPLETED, null, "RETURN_WINDOW_CLOSED", now);
        this.completedAt = now;
        return true;
    }

    /**
     * Closed for good a while after it was completed or refunded (G3-Q1, default 30 days): BR-05, nothing changes it
     * any more. So it is never closed while money is still owed on it, either way - closing it would put the debt out
     * of the order's sight for good:
     * <ul>
     *   <li>the customer's: its installments still owe. COMPLETED already waits for the money ({@link #complete}), so
     *       REFUNDED and PARTIALLY_REFUNDED wait too;</li>
     *   <li>the seller's (TT-4): a refund its settlements made refundable is not made in full - not yet recorded, or
     *       recorded and voided since. Completion does not wait for it (a refund is not the customer's to finish);
     *       closing does, and the nightly run tries again until the accountant has recorded it.</li>
     * </ul>
     * The order keeps no money of its own (4B-10): the caller asks payment for both and passes the answers.
     *
     * @param owing       as for {@link #complete}
     * @param refundOwed  whether the seller still owes the customer a refund of this order (payment's
     *                    {@code RefundProgress.outstanding} above zero); false for an order with nothing to refund
     * @return whether this changed the order
     */
    public boolean close(Instant now, java.time.Duration after, boolean owing, boolean refundOwed) {
        return close(now, after, owing, refundOwed, false, null);
    }

    /**
     * As {@link #close(Instant, java.time.Duration, boolean, boolean)}, with what else keeps an order open for good:
     * <ul>
     *   <li>a completion hold Sales placed (WF-05 Q-17, L-I3): the delivery it protects could no longer be corrected;</li>
     *   <li>a return window still open on a delivered shipment (Q-16 = b, BE-19): the refund branch never waited for the
     *       contract's days, so a customer could lose a claim the contract still grants (Luật Thương mại Điều 318) -
     *       {@code windowsCloseAt} is the latest end of those windows, null when the caller has nothing to wait for;</li>
     *   <li>a shipment still to be decided or on its way back (L-I6): a delivery corrected to a failure on a refunded
     *       order (BE-46), or a carrier's return whose receipt is not confirmed - its money is not settled yet.</li>
     * </ul>
     */
    public boolean close(Instant now, java.time.Duration after, boolean owing, boolean refundOwed, boolean held,
                         Instant windowsCloseAt) {
        Instant since = switch (status) {
            case COMPLETED -> completedAt;
            case REFUNDED, PARTIALLY_REFUNDED -> refundedAt;
            default -> null;
        };
        if (since == null || since.plus(after).isAfter(now) || owing || refundOwed || held
                || windowsCloseAt != null && windowsCloseAt.isAfter(now) || !deliveriesSettled()) {
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
     * <p>The status it was opened from is kept ({@link #statusBeforeReturn}, BE-40): a claim that ends without goods or
     * money takes the order back there - a COMPLETED order is not completed a second time, a REFUNDED one does not wait
     * for its return window again ({@link #withdrawReturn}, {@link #returnsEndedWithoutRefund}).</p>
     *
     * @return whether this changed the order
     */
    public boolean requestReturn(UUID by, Instant at, String reason) {
        if (status == OrderStatus.RETURN_REQUESTED || !status.canTransitionTo(OrderStatus.RETURN_REQUESTED)) {
            return false;
        }
        OrderStatus origin = status;
        transitionTo(OrderStatus.RETURN_REQUESTED, by, reason, at);
        this.statusBeforeReturn = origin;
        return true;
    }

    /**
     * Every return of the order ended before its goods came back - refused, withdrawn, expired (lead's decision
     * 2026-10-21 for a refusal; the same for the others, since the customer keeps what was delivered and owes what they
     * owed): RETURN_REQUESTED → the status the claim was opened from (WF-05 BE-40). DELIVERED goes back to waiting for
     * its return window and its money before it completes (17 §5.1); COMPLETED stays completed - its {@code completedAt}
     * and its contract are not done again (17 BR-19); REFUNDED / PARTIALLY_REFUNDED keep their refund and close as
     * before. The delivery date is unchanged, so the window is not reopened.
     *
     * <p>A delivery corrected to a failure while the claim was open (BE-46) is not undone by the claim ending: an order
     * opened from DELIVERED goes where its shipments say ({@link #deliveryPathStatus}) - DELIVERY_FAILED while that
     * shipment is still to be decided, PARTIALLY_FULFILLED once it came back.</p>
     *
     * @return whether this changed the order
     */
    public boolean withdrawReturn(UUID by, Instant at, String reason) {
        if (status != OrderStatus.RETURN_REQUESTED) {
            return false;
        }
        OrderStatus origin = statusBeforeReturn != null ? statusBeforeReturn
                : completedAt != null ? OrderStatus.COMPLETED : OrderStatus.DELIVERED;
        leaveReturnPath(origin == OrderStatus.DELIVERED ? deliveryPathStatus() : origin, by, reason, at);
        return true;
    }

    /**
     * X-8 (WF-05 BE-45, called by the return service once every return of a RETURNED order ended and none of them
     * settled anything): nothing was refunded, so "refunded in part" would be false. RETURNED → COMPLETED when the order
     * had completed before the claim ({@code completedAt}), else back to the delivery path - DELIVERED, from where it
     * completes and closes as an order nothing was returned from; or, when one of its deliveries was corrected to a
     * failure meanwhile (BE-46), where its shipments say ({@link #deliveryPathStatus}). History reason
     * {@code RETURN_SETTLED_WITHOUT_REFUND}.
     *
     * <p>Only an order RETURNED by a claim: one the carrier brought back delivered nothing, so its shipments keep it
     * RETURNED and this changes nothing. A claim opened from REFUNDED / PARTIALLY_REFUNDED goes back there (BE-40); one
     * whose earlier refund was voided while it was open (origin RETURNED, BE-52) stays RETURNED, waiting for it.</p>
     *
     * @return whether this changed the order
     */
    public boolean returnsEndedWithoutRefund(UUID by, Instant at) {
        if (status != OrderStatus.RETURNED) {
            return false;
        }
        // A claim opened from the refund branch goes back there (BE-40): the order was refunded before and must not
        // complete as if it never was. One whose refund was voided meanwhile (origin RETURNED, BE-52) waits for it.
        if (statusBeforeReturn == OrderStatus.RETURNED) {
            return false;
        }
        if (statusBeforeReturn == OrderStatus.REFUNDED || statusBeforeReturn == OrderStatus.PARTIALLY_REFUNDED
                || statusBeforeReturn == OrderStatus.PARTIALLY_FULFILLED) {
            leaveReturnPath(statusBeforeReturn, by, "RETURN_SETTLED_WITHOUT_REFUND", at);
            return true;
        }
        OrderStatus target = completedAt != null ? OrderStatus.COMPLETED : deliveryPathStatus();
        if (target == OrderStatus.RETURNED) {
            return false;
        }
        leaveReturnPath(target, by, "RETURN_SETTLED_WITHOUT_REFUND", at);
        return true;
    }

    /**
     * Where an order whose claim ended without money goes when it was opened from the delivery path: DELIVERED as a
     * rule; when one of its deliveries was corrected to a failure meanwhile (BE-46), what its shipments say.
     */
    private OrderStatus deliveryPathStatus() {
        return shipments.isEmpty() ? OrderStatus.DELIVERED : shipmentsStatus();
    }

    /** Off the return path to {@code target}; the milestones of the delivery path follow its shipments again. */
    private void leaveReturnPath(OrderStatus target, UUID by, String reason, Instant at) {
        transitionTo(target, by, reason, at);
        this.statusBeforeReturn = null;
        if (target == OrderStatus.DELIVERY_FAILED) {
            this.deliveryFailureReason = shipments.stream()
                    .filter(s -> s.status() == OrderShipmentStatus.DELIVERY_FAILED && s.pending())
                    .map(OrderShipment::failureReason).findFirst().orElse(DeliveryFailureReason.OTHER);
        }
        followDeliveredAt();
    }

    /**
     * The goods of a return came back (team plan 4D-3; 17 §4.10 step 39, §5.1: "Returned - hàng trả đã về kho"): a
     * return receipt took them into quarantine. RETURN_REQUESTED → RETURNED; an order RETURNED already (another return,
     * or the carrier brought the goods back) stays as it is.
     *
     * @return whether this changed the order
     */
    public boolean goodsReturned(UUID by, Instant at, String reason) {
        if (status != OrderStatus.RETURN_REQUESTED) {
            return false;
        }
        transitionTo(OrderStatus.RETURNED, by, reason, at);
        return true;
    }

    /**
     * Everything the order's settlements made refundable was refunded, and no return of it is open (team plan 4D-3; 17
     * §4.10 step 40, 15 §4.5 step 17: "hoàn đủ"): RETURNED → REFUNDED when what came back covers what the order is
     * worth, PARTIALLY_REFUNDED otherwise. From here the order closes after {@code close-after-days}, once nothing is
     * owed on it either way ({@link #close}); a refund record voided meanwhile takes it back here
     * ({@link #refundReopened}), and this moves it forward again.
     *
     * @return whether this changed the order
     */
    public boolean refunded(boolean full, UUID by, Instant at, String reason) {
        if (status == OrderStatus.PARTIALLY_FULFILLED && partlyReturnedAndDone()) {
            // RTM-RETURNED-PARTIAL (owner 19/11; 17 §5.1 edge added by docs PR #42): the part that came back is refunded.
            transitionTo(OrderStatus.PARTIALLY_REFUNDED, by, reason, at);
            this.refundedAt = at;
            return true;
        }
        if (status != OrderStatus.RETURNED) {
            return false;
        }
        transitionTo(full ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED, by, reason, at);
        this.refundedAt = at;
        return true;
    }

    /**
     * A refund record of a refunded order was voided (TT-4): the money the order counted as gone back has not, so it is
     * waiting for its refund again. REFUNDED / PARTIALLY_REFUNDED → RETURNED; from there the return request's or the
     * failed delivery's refund rule moves it forward again once the refund is recorded anew, as it did the first time.
     * Without this the order would stay REFUNDED and be closed for good with the refund still owed.
     *
     * <p>Any other order is left as it is: a RETURNED one is waiting already, a CLOSED one is never reopened (BR-05 -
     * the debt then shows only on the accountant's list of refunds still to make), and a CANCELLED one is final
     * (17 §5.1) with its refund followed by payment alone.</p>
     *
     * <p>{@link #refundedAt} is kept: like {@code completedAt} after a late return, it says when the order last became
     * refunded, RETURNED asks nothing of it ({@code ck_order_refunded_at} binds REFUNDED / PARTIALLY_REFUNDED only),
     * and {@link #refunded} sets it anew when the refund is made again - so the closing delay counts from that.</p>
     *
     * <p>An order on the return path again - a further claim opened from REFUNDED / PARTIALLY_REFUNDED is open, or its
     * goods are back (WF-05 BE-52) - does not move: the claim decides where it goes. What it goes back to when the claim
     * ends without money changes instead ({@link #statusBeforeReturn}): waiting for the refund, not refunded. The caller
     * records the void in the order's history, which no status change carries here.</p>
     *
     * @param reason the void's reason, recorded in the status history after {@code REFUND_VOIDED}; cut to fit
     * @return whether this changed the order - its status, or where its claim goes back to
     */
    public boolean refundReopened(UUID by, Instant at, String reason) {
        if ((status == OrderStatus.RETURN_REQUESTED || status == OrderStatus.RETURNED)
                && (statusBeforeReturn == OrderStatus.REFUNDED || statusBeforeReturn == OrderStatus.PARTIALLY_REFUNDED)) {
            this.statusBeforeReturn = statusBeforeReturn == OrderStatus.PARTIALLY_REFUNDED && partlyReturnedAndDone()
                    ? OrderStatus.PARTIALLY_FULFILLED : OrderStatus.RETURNED;
            return true;
        }
        if (status != OrderStatus.REFUNDED && status != OrderStatus.PARTIALLY_REFUNDED) {
            return false;
        }
        String history = withNote("REFUND_VOIDED", trimmed(reason));
        // Back to the status it was refunded from: PARTIALLY_FULFILLED for an order part of which came back while the
        // rest was delivered (RTM-RETURNED-PARTIAL, owner 19/11), RETURNED otherwise.
        OrderStatus waiting = status == OrderStatus.PARTIALLY_REFUNDED && partlyReturnedAndDone()
                ? OrderStatus.PARTIALLY_FULFILLED : OrderStatus.RETURNED;
        // The history column holds 500.
        transitionTo(waiting, by, history.length() > 500 ? history.substring(0, 500) : history, at);
        return true;
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

    /** Rehydration of the return path's origin (BE-40) and of the last closure of the rest (D23), from the row. */
    public void restoreReturnPath(OrderStatus beforeReturn, Instant remainderClosed) {
        this.statusBeforeReturn = beforeReturn;
        this.remainderClosedAt = remainderClosed;
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
    public OrderStatus statusBeforeReturn() { return statusBeforeReturn; }
    public Instant remainderClosedAt() { return remainderClosedAt; }
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
        if (target != OrderStatus.RETURN_REQUESTED && target != OrderStatus.RETURNED) {
            // BE-40: the origin of a claim means something only on the return path (ck_order_status_before_return).
            this.statusBeforeReturn = null;
        }
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

    /**
     * Kltn-docs 08 BR-04, 17 BR-12: from its first labelled carton an order is not cancelled - by anyone, on any path; it
     * goes through a return (RMA). An order the repack station packed before the check station existed is locked from
     * then (03 §9, decision 2026-10-17); V20261113000700 carried that over as its first label.
     */
    public void requireNotPacked() {
        if (packedForGood()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE, ("Order %s has a labelled carton; it can no "
                    + "longer be cancelled - raise a return instead").formatted(orderNumber));
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
        return recordGoodsLeft(at, inPlan, installmentsDue, null, null);
    }

    /** 4B-8: the same for the delivery batch in flight (see {@link #recordHandover(ShipmentHandover, BigDecimal,
     *  BatchInFlight, java.time.LocalDate)}). */
    public boolean recordGoodsLeft(Instant at, Map<UUID, Integer> inPlan, BigDecimal installmentsDue,
                                   BatchInFlight batch, java.time.LocalDate day) {
        java.util.Objects.requireNonNull(at, "at");
        requireHandoverAllowed(installmentsDue, batch, day);
        requirePlanCarriesTheRest(inPlan, batch);
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
        requirePlanCarriesTheRest(inPlan, null);
    }

    /** 4B-8: units of the batches not released yet are not the plan's to carry ({@link #unitsDueTogether}). */
    public void requirePlanCarriesTheRest(Map<UUID, Integer> inPlan, BatchInFlight batch) {
        requireNotCancelled("its goods are not loaded");
        if (inPlan == null || allowPartialShipment) {
            return;
        }
        int carried = inPlan.values().stream().mapToInt(Integer::intValue).sum();
        int expected = unitsDueTogether(batch);
        if (expected > carried) {
            throw new BusinessException(ErrorCode.ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED, ("Order %s does not allow partial "
                    + "shipments: %d unit(s) are not in this load plan. Put them in the plan, or ask Sales to allow "
                    + "partial shipment on the order").formatted(orderNumber, expected - carried));
        }
    }

    /**
     * Whether a labelled carton (08 BR-04), or a package completed by the repack station (03 §9), binds the order to a
     * return instead of a cancellation.
     */
    public boolean packedForGood() {
        return firstLabeledAt != null || packedAt != null;
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
