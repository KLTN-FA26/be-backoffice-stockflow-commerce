package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ReturnApproved;
import com.stockflow.contracts.ReturnAwaitingRefund;
import com.stockflow.contracts.ReturnClosed;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnRejected;
import com.stockflow.contracts.ReturnRequested;
import com.stockflow.contracts.ReturnResolved;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <b>A return request (RMA, "đổi trả"; kltn-docs 17 §4.10, BR-18; team plan 4D-3).</b> The aggregate root of the
 * after-sales claim on a delivery: what the customer says is wrong, what Sales approved, what came back on the return
 * receipt, and what was refunded. The order is its own aggregate; the application service moves it alongside
 * (RETURN_REQUESTED while a return is open, RETURNED once goods are back, REFUNDED / PARTIALLY_REFUNDED once refunded
 * in full), under the order's row lock - once the order is DELIVERED. While batches of the order are still to come its
 * status is its deliveries', and a return lives on its own.
 *
 * <p><b>One return, one delivered shipment</b> (WF-05 TH-1; Luật Thương mại 2005 Điều 44, 313, 318). Each delivery
 * has its own handover record and its own e-invoice, a partial delivery is dealt with per delivery, and the period to
 * complain runs from that delivery. So a return names the shipment it is against ({@link #shipmentId}): its units are
 * bounded by what that shipment delivered, its window runs from that shipment's delivery, its goods are that shipment's
 * cartons, and its refund comes off that delivery's installments and e-invoice first. Only an order delivered without
 * shipments (before split shipments) is claimed as a whole, from its delivery date.</p>
 *
 * <p>The goods always come back (17 BR-18): the warehouse receives them on return receipts into quarantine
 * ({@code procurement}, 03 §4.2) - one per parcel the customer sent (WF-05 TH-3) - and the receipts, confirmed, are
 * what move the return to RECEIVED ({@link #goodsReceived}): once everything approved is back, once Sales say no more is
 * coming ({@link #markGoodsComplete}), or at the deadline with what came. What happens to the goods next is a warehouse
 * manager's decision on each carton
 * (20 §4.8), not the return's. The return decides the money: Sales propose a refund, a second person approves it,
 * payment settles it against the order's installments (15 §4.5). There is no QC step, no replacement order and no
 * credit kept for the customer any more (team plan 4D-3; 15 BR-05).</p>
 *
 * <p>Invariants it protects - each stated again as a {@code CHECK} ({@code V20261101020000}, {@code V20261114000100}):</p>
 * <ul>
 *   <li><b>BR-RMA-001</b>: opened only on a delivered shipment (or a delivered order without shipments), within the
 *       return window after that delivery (calendar days of Asia/Ho_Chi_Minh; the contract's period, else the
 *       configured one) - or outside it when Sales accept a late claim and say why;</li>
 *   <li><b>BR-RMA-002</b>: goods printed to the customer's design come back only for a named defect;</li>
 *   <li>never more units of a line than its shipment delivered, counting what the other returns on that shipment line
 *       still claim - nor more than the line delivered in all - and one open return per order line and shipment;</li>
 *   <li>units only narrow: approved ≤ asked for, refunded ≤ approved and ≤ what came back - the units counted inside
 *       the cartons and loose, not their labels; a return whose goods came back with nothing usable is closed with no
 *       refund ({@link #closeWithoutRefund});</li>
 *   <li>a claim is approved on evidence (a photo of the defect), and its goods come back;</li>
 *   <li>four eyes: a claim Sales opened is approved by another person, and every refund by someone other than who
 *       proposed it;</li>
 *   <li>status moves only along {@link ReturnStatus#canTransitionTo}, each change kept with who and why.</li>
 * </ul>
 */
public final class ReturnRequest extends AggregateRoot {

    private static final int NOTE_LIMIT = 1000;
    private static final int DESCRIPTION_LIMIT = 2000;
    private static final int REASON_LIMIT = 500;
    /** {@code refusal_note}: what the customer reads beside the refusal reason. */
    public static final int REFUSAL_NOTE_LIMIT = 500;

    /** Units of one order line the customer claims. */
    public record Claim(UUID orderLineId, int quantity) {
    }

    /**
     * Units of one SKU that came back on the return receipt: what was counted inside one carton - none, for a carton
     * that came back empty (WF-05 TH-4) - or units that came back loose, with no carton label.
     */
    public record ReturnedCarton(String sku, int quantity) {

        public ReturnedCarton {
            Objects.requireNonNull(sku, "sku");
            if (quantity < 0) {
                throw new IllegalArgumentException("Nothing holds fewer than no units");
            }
        }
    }

    /**
     * What payment settled for the refunded value (team plan 4D-3): {@code offset} taken off what the order's
     * installments still owed, {@code refunded} paid already and to be refunded by the accountant.
     */
    public record MoneyOutcome(BigDecimal offset, BigDecimal refunded) {

        public static final MoneyOutcome NONE = new MoneyOutcome(BigDecimal.ZERO, BigDecimal.ZERO);

        public MoneyOutcome {
            offset = offset == null ? BigDecimal.ZERO : offset;
            refunded = refunded == null ? BigDecimal.ZERO : refunded;
        }

        public BigDecimal total() {
            return offset.add(refunded);
        }
    }

    private final UUID id;
    private final String rmaNumber;
    private final UUID orderId;
    private final String orderNumber;
    private final UUID customerId;
    private final UUID requestId;
    private final ReturnChannel channel;
    private final ReturnReason reason;
    private final String description;
    private final ReturnResolution requestedResolution;
    private final UUID requestedBy;
    private final Instant requestedAt;
    private final String currency;
    private final List<ReturnLine> lines;
    private final String windowOverride;
    private final long version;
    /** The delivered shipment the return is against; null only for an order delivered without shipments. */
    private UUID shipmentId;

    private ReturnStatus status;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private String rejectionReason;
    private Boolean returnRequired;
    private Instant returnDeadline;
    private UUID receivedBy;
    private Instant receivedAt;
    private String receivingLocation;
    private UUID goodsReceiptId;
    private ReturnResolution moneyMode;
    private BigDecimal settlementAmount = BigDecimal.ZERO;
    private UUID proposedBy;
    private Instant proposedAt;
    private String resolutionNote;
    private UUID settledBy;
    private Instant settledAt;
    private MoneyOutcome money = MoneyOutcome.NONE;
    private boolean vatAdjustmentRequired;
    private UUID cancelledBy;
    private String cancellationReason;
    private Instant closedAt;
    /** Why units that came back are withheld (WF-05 Q-4); null when everything approved and back is refunded. */
    private ReturnRefusalReason refusalReason;
    /** The public note shown to the customer with {@link #refusalReason}. */
    private String refusalNote;
    /** The DRAFT return receipt counting a parcel of this return now (WF-05 BE-23); null when none. */
    private UUID openDraftReceiptId;
    /**
     * What the customer chose for the cartons of this return held for them (WF-05 Q-4: those the refund did not take
     * back, per SKU since audit round 3), as Sales recorded it; null until they did, and while none is held.
     */
    private ReturnCustodyChoice custodyChoice;
    /** The record the choice rests on: required for a destruction on the customer's behalf (their written consent). */
    private String custodyChoiceNote;
    private UUID custodyChoiceBy;
    private Instant custodyChoiceAt;

    private final List<ReturnEvidence> evidence = new ArrayList<>();
    private final List<ReturnDiscrepancy> discrepancies = new ArrayList<>();
    /** The confirmed return receipts that brought its goods, the first first (WF-05 TH-3). */
    private final List<ReturnParcel> parcels = new ArrayList<>();
    /** Children added since the request was loaded; drained by the repository, which only ever inserts them. */
    private final List<ReturnEvidence> newEvidence = new ArrayList<>();
    private final List<ReturnDiscrepancy> newDiscrepancies = new ArrayList<>();
    private final List<ReturnParcel> newParcels = new ArrayList<>();
    private final List<ReturnTransition> transitions = new ArrayList<>();
    /** How each custody note's goods end (WF-05 Q-11, audit round 3 RAD-3), by the note's id; none until chosen. */
    private final Map<UUID, CustodyOutcome> custodyOutcomes = new LinkedHashMap<>();
    /** Outcomes chosen or ended since the request was loaded; drained by the repository, which writes them. */
    private final List<CustodyOutcome> changedCustodyOutcomes = new ArrayList<>();

    public ReturnRequest(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId, UUID requestId,
                         ReturnChannel channel, ReturnReason reason, String description,
                         ReturnResolution requestedResolution, UUID requestedBy, Instant requestedAt, String currency,
                         List<ReturnLine> lines, String windowOverride, ReturnStatus status, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.rmaNumber = Objects.requireNonNull(rmaNumber, "rmaNumber");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.description = trimmed(description);
        this.requestedResolution = Objects.requireNonNull(requestedResolution, "requestedResolution");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.lines = new ArrayList<>(lines == null ? List.of() : lines);
        this.windowOverride = trimmed(windowOverride);
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        if (this.lines.isEmpty()) {
            throw new IllegalArgumentException("A return has at least one line");
        }
    }

    // ================================================================================================= opening

    /** Within the return window. */
    public static ReturnRequest open(String rmaNumber, UUID requestId, Order order, ReturnChannel channel,
                                     ReturnReason reason, String description, ReturnResolution requested,
                                     List<Claim> claims, Map<UUID, Integer> claimedElsewhere,
                                     Set<UUID> openElsewhere, UUID requestedBy, Instant now, ReturnPolicy policy) {
        return open(rmaNumber, requestId, order, channel, reason, description, requested, claims, claimedElsewhere,
                openElsewhere, requestedBy, now, policy, null);
    }

    /**
     * Against the order's only delivered shipment, or the order itself when it was delivered without shipments - what
     * every claim was before returns were taken per shipment. {@code claimedElsewhere} and {@code openElsewhere} are read
     * as the other returns on that same shipment, and {@code claimedElsewhere} as the line's in all too.
     */
    public static ReturnRequest open(String rmaNumber, UUID requestId, Order order, ReturnChannel channel,
                                     ReturnReason reason, String description, ReturnResolution requested,
                                     List<Claim> claims, Map<UUID, Integer> claimedElsewhere,
                                     Set<UUID> openElsewhere, UUID requestedBy, Instant now, ReturnPolicy policy,
                                     String windowOverride) {
        return open(rmaNumber, requestId, order, null, channel, reason, description, requested, claims,
                claimedElsewhere, claimedElsewhere, openElsewhere, requestedBy, now, policy, windowOverride);
    }

    /**
     * The delivered shipment a claim is against: the one named, or - none named - the order's only delivered one. Null
     * for an order delivered without shipments, which is claimed as a whole.
     *
     * @throws BusinessException {@code RETURN_SHIPMENT_NOT_DELIVERED} for a shipment that is not the order's or not
     *         delivered; {@code RETURN_SHIPMENT_REQUIRED} when none is named and several were delivered;
     *         {@code RETURN_ORDER_NOT_DELIVERED} when none of the order's shipments was delivered
     */
    public static OrderShipment deliveredShipment(Order order, UUID shipmentId) {
        Objects.requireNonNull(order, "order");
        if (order.shipments().isEmpty()) {
            if (shipmentId != null) {
                throw new BusinessException(ErrorCode.RETURN_SHIPMENT_NOT_DELIVERED,
                        "Order %s has no shipment %s".formatted(order.orderNumber(), shipmentId));
            }
            return null;
        }
        if (shipmentId != null) {
            OrderShipment named = order.shipments().stream().filter(s -> s.id().equals(shipmentId)).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RETURN_SHIPMENT_NOT_DELIVERED,
                            "Order %s has no shipment %s".formatted(order.orderNumber(), shipmentId)));
            if (named.status() != OrderShipmentStatus.DELIVERED) {
                throw new BusinessException(ErrorCode.RETURN_SHIPMENT_NOT_DELIVERED, ("Shipment %s of order %s is %s: "
                        + "only goods the customer received come back").formatted(shipmentId, order.orderNumber(),
                        named.status()));
            }
            return named;
        }
        List<OrderShipment> delivered = order.shipments().stream()
                .filter(s -> s.status() == OrderShipmentStatus.DELIVERED).toList();
        if (delivered.isEmpty()) {
            throw new BusinessException(ErrorCode.RETURN_ORDER_NOT_DELIVERED,
                    "No shipment of order %s has been delivered yet".formatted(order.orderNumber()));
        }
        if (delivered.size() > 1) {
            throw new BusinessException(ErrorCode.RETURN_SHIPMENT_REQUIRED, ("Order %s was delivered in %d shipments: "
                    + "name the one the goods came on").formatted(order.orderNumber(), delivered.size()));
        }
        return delivered.getFirst();
    }

    /**
     * The customer (or Sales for them) claims {@code claims} of one delivery of {@code order} (17 §4.10 step 37: — →
     * REQUESTED), at the order's own prices.
     *
     * @param shipmentId        the delivered shipment the goods came on; null names the order's only delivered one, or
     *                          none for an order delivered without shipments ({@link #deliveredShipment})
     * @param requested         what the customer would like; null reads as {@link ReturnResolution#REFUND}, the only
     *                          one there is
     * @param claimedElsewhere  per order line, the units the line's other returns still claim, whatever delivery they
     *                          are against
     * @param claimedOnShipment per order line, the units the other returns on the same shipment still claim
     * @param openElsewhere     the order lines that already have an open return on the same shipment
     * @param policy            the return rules, its window the order's own (the contract's period, else the
     *                          configured one)
     * @param windowOverride    why Sales accept the claim outside the return window - goods that came late, a second
     *                          defect found after the window, goodwill; back office only, and approved by someone else
     *                          like every claim staff open. Null: the window applies
     * @throws BusinessException {@code ORDER_CANCELLED}, {@code RETURN_ORDER_NOT_DELIVERED},
     *         {@code RETURN_ORDER_NOT_RETURNABLE}, {@code RETURN_WINDOW_CLOSED} (BR-RMA-001);
     *         {@code RETURN_SHIPMENT_NOT_DELIVERED}, {@code RETURN_SHIPMENT_REQUIRED};
     *         {@code RETURN_LINE_ALREADY_OPEN}; {@code RETURN_QUANTITY_EXCEEDED};
     *         {@code RETURN_CUSTOM_ITEM_NOT_DEFECTIVE} (BR-RMA-002); {@code RETURN_REASON_NOT_ACCEPTED} for a change of
     *         mind the policy refuses, and for a short delivery - nothing to send back, so no return;
     *         {@code RETURN_QUANTITIES_INVALID}, {@code VALIDATION_FAILED}
     */
    public static ReturnRequest open(String rmaNumber, UUID requestId, Order order, UUID shipmentId,
                                     ReturnChannel channel, ReturnReason reason, String description,
                                     ReturnResolution requested, List<Claim> claims,
                                     Map<UUID, Integer> claimedElsewhere, Map<UUID, Integer> claimedOnShipment,
                                     Set<UUID> openElsewhere, UUID requestedBy, Instant now, ReturnPolicy policy,
                                     String windowOverride) {
        return open(rmaNumber, requestId, order, shipmentId, channel, reason, description, requested, claims,
                claimedElsewhere, claimedOnShipment, openElsewhere, null, requestedBy, now, policy, windowOverride);
    }

    /**
     * As the overload without {@code refundedShipments}, the portal's rule read per delivery (WF-05 Q-15).
     *
     * @param refundedShipments the deliveries a return of the order already refunded units of
     *                          ({@link ReturnClaims#refundedShipments}): the portal takes no claim on one of them
     *                          ({@code RETURN_SHIPMENT_NOT_RETURNABLE}), and takes one on any other delivered and in
     *                          its window, whatever the order's status. Null when not known: the order's status alone
     *                          decides, as before.
     */
    public static ReturnRequest open(String rmaNumber, UUID requestId, Order order, UUID shipmentId,
                                     ReturnChannel channel, ReturnReason reason, String description,
                                     ReturnResolution requested, List<Claim> claims,
                                     Map<UUID, Integer> claimedElsewhere, Map<UUID, Integer> claimedOnShipment,
                                     Set<UUID> openElsewhere, Set<UUID> refundedShipments, UUID requestedBy,
                                     Instant now, ReturnPolicy policy, String windowOverride) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(now, "now");
        ReturnResolution wanted = requested == null ? ReturnResolution.REFUND : requested;
        String override = limited(windowOverride, REASON_LIMIT, "late-claim reason");
        if (override != null && channel != ReturnChannel.BACK_OFFICE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Only Sales accept a claim outside the return window");
        }
        if (!reason.hasGoodsToReturn()) {
            // 17 BR-18: a return brings its goods back through a return receipt. A shortfall has nothing to bring back;
            // it is the delivery's to correct (09: the delivery outcome, or closing the rest), not a return.
            throw new BusinessException(ErrorCode.RETURN_REASON_NOT_ACCEPTED,
                    "A short delivery has no goods to send back: correct the delivery instead of opening a return");
        }
        // The delivery first: the order's status is read against it, and the window runs from it.
        OrderShipment shipment = deliveredShipment(order, shipmentId);
        requireReturnable(order, shipment, now, policy, channel, override, refundedShipments);
        String text = trimmed(description);
        if (text != null && text.length() > DESCRIPTION_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Describe the problem in at most %d characters".formatted(DESCRIPTION_LIMIT));
        }
        if (reason == ReturnReason.OTHER && text == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A return for OTHER must describe the problem");
        }
        if (claims == null || claims.isEmpty()) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID, "A return claims at least one line");
        }
        Set<UUID> seen = new HashSet<>();
        List<ReturnLine> lines = new ArrayList<>();
        for (Claim claim : claims) {
            if (claim == null || claim.orderLineId() == null || !seen.add(claim.orderLineId())) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "Every claimed line names a distinct order line");
            }
            OrderLine line = order.findLine(claim.orderLineId()).orElseThrow(() -> new BusinessException(
                    ErrorCode.RETURN_QUANTITIES_INVALID,
                    "Line %s is not a line of order %s".formatted(claim.orderLineId(), order.orderNumber())));
            if (claim.quantity() < 1) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "Claim at least one unit of %s".formatted(line.sku().code()));
            }
            LineAllowance allowance = LineAllowance.of(line, shipment, claimedElsewhere, claimedOnShipment,
                    openElsewhere);
            if (allowance.alreadyOpen()) {
                throw new BusinessException(ErrorCode.RETURN_LINE_ALREADY_OPEN,
                        "Line %s of order %s already has an open return".formatted(line.sku().code(),
                                order.orderNumber()));
            }
            if (claim.quantity() > allowance.delivered() - allowance.claimed()) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITY_EXCEEDED,
                        "%s: %d unit(s) delivered, %d already claimed by other returns; %d cannot be returned"
                                .formatted(line.sku().code(), allowance.delivered(), allowance.claimed(),
                                        claim.quantity()));
            }
            if (allowance.onShipment() != null
                    && claim.quantity() > allowance.onShipment() - allowance.claimedOnShipment()) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITY_EXCEEDED, ("%s: %d unit(s) delivered on "
                        + "this shipment, %d already claimed by its other returns; %d cannot be returned")
                        .formatted(line.sku().code(), allowance.onShipment(), allowance.claimedOnShipment(),
                                claim.quantity()));
            }
            if (!reason.isNamedFault() && line.printedToCustomerDesign()) {
                throw new BusinessException(ErrorCode.RETURN_CUSTOM_ITEM_NOT_DEFECTIVE, ("%s was printed to the "
                        + "customer's design: it is returned only for a named defect, not %s (BR-RMA-002)")
                        .formatted(line.sku().code(), reason));
            }
            lines.add(new ReturnLine(Identifiers.newId(), line.id(), line.sku().code(), line.printedToCustomerDesign(),
                    line.unitPrice().amount(), claim.quantity()));
        }
        if (reason == ReturnReason.CHANGED_MIND && !policy.changeOfMindForStockItems()) {
            throw new BusinessException(ErrorCode.RETURN_REASON_NOT_ACCEPTED,
                    "Returns are taken for defects and delivery faults, not a change of mind (SCRUM-243)");
        }
        ReturnRequest request = new ReturnRequest(Identifiers.newId(), rmaNumber, order.id().value(),
                order.orderNumber().value(), order.customerId(), requestId, channel, reason, text, wanted,
                requestedBy, now, order.total().currency().getCurrencyCode(), lines, override, ReturnStatus.REQUESTED,
                0L);
        request.shipmentId = shipment == null ? null : shipment.id();
        request.transitions.add(new ReturnTransition(null, ReturnStatus.REQUESTED, requestedBy,
                override == null ? reason.name() : reason.name() + " LATE_CLAIM_ACCEPTED: " + override, now));
        request.registerEvent(new OrderEvent.ReturnChanged(request.id, new ReturnRequested(request.id, rmaNumber,
                request.orderId, request.orderNumber, request.customerId, reason.name(), wanted.name(),
                channel.name(), request.lines.stream()
                        .map(l -> new ReturnRequested.Line(l.orderLineId(), l.sku(), l.requestedQuantity())).toList(),
                requestedBy, now)));
        return request;
    }

    /**
     * BR-RMA-001: a delivered shipment - or a delivered order, when it has no shipments - within the window after that
     * delivery; or, for Sales with a reason, after it. A further claim on a delivery a return already refunded goes
     * through Sales (WF-05 Q-15: decided per delivery, not by the order's status); a CLOSED order takes none (BR-05).
     *
     * <p>While batches are still to come the order is PARTIALLY_FULFILLED, SHIPPED, DELIVERY_FAILED, ON_HOLD - or
     * PICKING / PACKED for the next batch - and a delivered shipment of it is returnable all the same (WF-05 TH-1:
     * Điều 313, each delivery is dealt with on its own). Without a delivered shipment those statuses still mean nothing
     * was received.</p>
     */
    private static void requireReturnable(Order order, OrderShipment shipment, Instant now, ReturnPolicy policy,
                                          ReturnChannel channel, String override, Set<UUID> refundedShipments) {
        BusinessException refusal = returnRefusal(order, shipment, now, policy, channel, override, refundedShipments);
        if (refusal != null) {
            throw refusal;
        }
    }

    /**
     * {@link #requireReturnable} as an answer rather than a throw: the refusal opening a return of {@code shipment}
     * would meet, or null when the delivery is returnable now. Shared by {@link #claimable}, so what the customer is
     * shown as returnable is decided by the very rule that decides the claim.
     *
     * <p>WF-05 Q-15 (Luật Thương mại 2005 Điều 318: the period runs from each delivery; NĐ 254/2026/NĐ-CP Điều 9 khoản 3:
     * each delivery has its own invoice): the order's status decided whether the portal took a claim, and it says
     * nothing reliable about one delivery - REFUNDED / PARTIALLY_REFUNDED may come from a refund of another delivery or
     * from a shipment the carrier brought back, RETURNED from another return's goods being back. So with
     * {@code refundedShipments} known, those statuses take a portal claim on any delivered shipment in its window, and
     * every status refuses one on a delivery a return already refunded ({@code RETURN_SHIPMENT_NOT_RETURNABLE}): that
     * claim was answered, a further one is Sales'.</p>
     */
    private static BusinessException returnRefusal(Order order, OrderShipment shipment, Instant now,
                                                   ReturnPolicy policy, ReturnChannel channel, String override,
                                                   Set<UUID> refundedShipments) {
        OrderStatus status = order.status();
        boolean sales = channel == ReturnChannel.BACK_OFFICE;
        BusinessException notDelivered = new BusinessException(ErrorCode.RETURN_ORDER_NOT_DELIVERED,
                "Order %s is %s: only a delivered order can be returned".formatted(order.orderNumber(), status));
        // A switch expression, so a new order status is a compile error here rather than a silent "returnable".
        BusinessException refusal = switch (status) {
            case DELIVERED, RETURN_REQUESTED -> null;
            // Its window has passed by definition: only a late claim Sales accepted gets through below.
            case COMPLETED -> sales ? null : windowClosed(order, policy, false);
            case CANCELLED -> new BusinessException(ErrorCode.ORDER_CANCELLED,
                    "Order %s is cancelled; nothing was delivered to return".formatted(order.orderNumber()));
            // Settled by a refund - of this delivery or another one, by a return or by the carrier bringing a shipment
            // back. Per delivery when it is known which were refunded (below); else the order as a whole, as before.
            case REFUNDED, PARTIALLY_REFUNDED -> sales || refundedShipments != null && shipment != null ? null
                    : new BusinessException(ErrorCode.RETURN_ORDER_NOT_RETURNABLE,
                    "Order %s was already settled by a return: ask Sales to open a further claim"
                            .formatted(order.orderNumber()));
            case CLOSED -> new BusinessException(ErrorCode.RETURN_ORDER_NOT_RETURNABLE,
                    "Order %s is closed for good (BR-05): it takes no return".formatted(order.orderNumber()));
            // Batches still to come: one already delivered is returned on its own (WF-05 TH-1).
            case PICKING, PARTIALLY_FULFILLED, PACKED, ON_HOLD, SHIPPED, DELIVERY_FAILED -> shipment == null
                    ? notDelivered : null;
            // RETURNED as an order status is goods that came back undelivered (RTO), or another return's goods being
            // back while its refund is decided: a delivered shipment named is returnable all the same (WF-05 Q-15).
            case RETURNED -> shipment == null || refundedShipments == null ? notDelivered : null;
            case DRAFT, PENDING_CONTRACT, PENDING_PAYMENT, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION, READY_TO_FULFILL ->
                    notDelivered;
        };
        if (refusal != null) {
            return refusal;
        }
        if (!sales && shipment != null && refundedShipments != null && refundedShipments.contains(shipment.id())) {
            return new BusinessException(ErrorCode.RETURN_SHIPMENT_NOT_RETURNABLE, ("Shipment %s of order %s was "
                    + "already settled by a return: ask Sales to open a further claim").formatted(shipment.id(),
                    order.orderNumber()));
        }
        // Điều 318: the period runs from the delivery the goods came on.
        Instant deliveredAt = shipment != null ? shipment.outcomeAt() : order.deliveredAt();
        if (deliveredAt == null) {
            return new BusinessException(ErrorCode.RETURN_ORDER_NOT_DELIVERED,
                    "Order %s has no delivery recorded".formatted(order.orderNumber()));
        }
        if (override == null && !policy.windowClosesAt(deliveredAt).isAfter(now)) {
            return windowClosed(order, policy, sales);
        }
        return null;
    }

    private static BusinessException windowClosed(Order order, ReturnPolicy policy, boolean sales) {
        return new BusinessException(ErrorCode.RETURN_WINDOW_CLOSED,
                "Order %s was delivered more than %d day(s) ago (BR-RMA-001)%s".formatted(order.orderNumber(),
                        policy.window().toDays(), sales ? "; say why to accept a late claim" : ""));
    }

    /**
     * How many units of one order line a new return on one delivery may claim, by the bounds {@link #open} enforces:
     * what the line delivered in all less what every other return of it claims, and - against a shipment - what that
     * shipment delivered of it less what the shipment's other returns claim; none while a return of the line is open
     * on the same delivery ({@code RETURN_LINE_ALREADY_OPEN}).
     *
     * @param delivered         the line's units, less those cancelled
     * @param claimed           the units every other return of the line claims, whatever delivery
     * @param onShipment        the units the shipment delivered of the line; null against an order delivered without
     *                          shipments, where the line's own total is the only bound
     * @param claimedOnShipment the units the shipment's other returns claim of the line
     * @param alreadyOpen       a return of the line is still open on the same delivery
     */
    record LineAllowance(int delivered, int claimed, Integer onShipment, int claimedOnShipment,
                         boolean alreadyOpen) {

        static LineAllowance of(OrderLine line, OrderShipment shipment, Map<UUID, Integer> claimedElsewhere,
                                Map<UUID, Integer> claimedOnShipment, Set<UUID> openElsewhere) {
            return new LineAllowance(line.quantity() - line.cancelledQuantity(),
                    claimedElsewhere == null ? 0 : claimedElsewhere.getOrDefault(line.id(), 0),
                    shipment == null ? null : shipment.quantities().getOrDefault(line.id(), 0),
                    claimedOnShipment == null ? 0 : claimedOnShipment.getOrDefault(line.id(), 0),
                    openElsewhere != null && openElsewhere.contains(line.id()));
        }

        /** The most a new return may claim: 0 while one is open, never below 0. */
        int returnable() {
            if (alreadyOpen) {
                return 0;
            }
            int left = delivered - claimed;
            if (onShipment != null) {
                left = Math.min(left, onShipment - claimedOnShipment);
            }
            return Math.max(0, left);
        }
    }

    /**
     * One line of one delivery, as the customer reads it: what the delivery brought of the order line, and how many of
     * those units a return opened now on the portal may claim.
     *
     * @param quantity   the units of the line the delivery carried (the line less its cancelled units, for an order
     *                   delivered without shipments)
     * @param returnable 0 when nothing of it can be claimed now: the delivery is not delivered (yet), its return window
     *                   has closed, the order takes no return on the portal any more, a return of the line is already
     *                   open on the delivery, or every unit is claimed
     */
    public record ClaimableLine(UUID orderLineId, String sku, int quantity, int returnable) {
    }

    /**
     * What each delivery of an order brought, line by line, and what of it is returnable now.
     *
     * @param byShipment the lines of each shipment of the order, by shipment id, in the order the shipment lists them
     * @param wholeOrder the lines of an order delivered without shipments, claimed as a whole; empty for an order that
     *                   has shipments
     */
    public record Claimable(Map<UUID, List<ClaimableLine>> byShipment, List<ClaimableLine> wholeOrder) {

        public static final Claimable NONE = new Claimable(Map.of(), List.of());

        public Claimable {
            byShipment = byShipment == null ? Map.of() : Map.copyOf(byShipment);
            wholeOrder = wholeOrder == null ? List.of() : List.copyOf(wholeOrder);
        }

        /** The lines of shipment {@code shipmentId}; of the order as a whole for null. Empty for one not known. */
        public List<ClaimableLine> of(UUID shipmentId) {
            return shipmentId == null ? wholeOrder : byShipment.getOrDefault(shipmentId, List.of());
        }
    }

    /**
     * What each delivery of {@code order} brought and how many units of each line the customer may still claim in a
     * return of it opened on the portal now - by the rules {@link #open} applies to such a claim, not a copy of them:
     * the delivery must be delivered and the order returnable on the portal within that delivery's window
     * ({@link #returnRefusal}, BR-RMA-001; Sales' late claims are theirs, not the portal's), and each line is bounded
     * by {@link LineAllowance}. The rules that depend on what the claim says are left to it: a line printed to the
     * customer's design comes back only for a named defect (BR-RMA-002), a change of mind and a short delivery are
     * refused whatever the line - so a positive figure is the most a claim may ask, not a promise that any reason will
     * do. The customer's account must also be ACTIVE to open on the portal ({@code RETURN_CUSTOMER_NOT_ACTIVE}); that is
     * the customer's, not the delivery's: the service that reads this for the portal applies it.
     *
     * @param claims what the order's returns already claim ({@code ReturnRequestRepository.claimsOf}), read once
     * @param policy the order's own return rules (its contract's window, else the configured one)
     */
    public static Claimable claimable(Order order, ReturnClaims claims, Instant now, ReturnPolicy policy) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(policy, "policy");
        ReturnClaims taken = claims == null ? ReturnClaims.NONE : claims;
        Map<UUID, Integer> inAll = taken.inAll();
        Set<UUID> refunded = taken.refundedShipments();
        if (order.shipments().isEmpty()) {
            boolean returnable = returnRefusal(order, null, now, policy, ReturnChannel.PORTAL, null, refunded) == null;
            Map<UUID, Integer> onOrder = taken.onShipment(null);
            Set<UUID> open = taken.openOn(null);
            List<ClaimableLine> lines = new ArrayList<>();
            for (OrderLine line : order.lines()) {
                int delivered = line.quantity() - line.cancelledQuantity();
                if (delivered > 0) {
                    lines.add(new ClaimableLine(line.id(), line.sku().code(), delivered, !returnable ? 0
                            : LineAllowance.of(line, null, inAll, onOrder, open).returnable()));
                }
            }
            return new Claimable(Map.of(), lines);
        }
        Map<UUID, List<ClaimableLine>> byShipment = new LinkedHashMap<>();
        for (OrderShipment shipment : order.shipments()) {
            // RETURN_SHIPMENT_NOT_DELIVERED first, as deliveredShipment refuses it before the order's own rules.
            boolean returnable = shipment.status() == OrderShipmentStatus.DELIVERED
                    && returnRefusal(order, shipment, now, policy, ReturnChannel.PORTAL, null, refunded) == null;
            Map<UUID, Integer> onShipment = taken.onShipment(shipment.id());
            Set<UUID> open = taken.openOn(shipment.id());
            List<ClaimableLine> lines = new ArrayList<>();
            shipment.quantities().forEach((lineId, carried) -> {
                if (carried == null || carried <= 0) {
                    return;
                }
                order.findLine(lineId).ifPresent(line -> lines.add(new ClaimableLine(line.id(), line.sku().code(),
                        carried, !returnable ? 0
                        : LineAllowance.of(line, shipment, inAll, onShipment, open).returnable())));
            });
            byShipment.put(shipment.id(), lines);
        }
        return new Claimable(byShipment, List.of());
    }

    // ================================================================================================= evidence

    /**
     * Keep a photo of the problem with the claim, while it is open and its goods not yet back.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} for a stage the return is past or that is gone;
     *         {@code RETURN_EVIDENCE_LIMIT_REACHED}
     */
    public void addEvidence(ReturnEvidence photo, ReturnPolicy policy) {
        Objects.requireNonNull(photo, "photo");
        requireEvidenceAllowed(photo.stage(), policy);
        evidence.add(photo);
        newEvidence.add(photo);
    }

    /**
     * Whether a photo of {@code stage} is taken now and fits; the service asks before it stores the file, so a refused
     * photo leaves nothing behind in storage. Only the claim's photos are the return's since team plan 4D-3: photos of
     * the goods as they came back are the return receipt's ({@code procurement.goods_receipt_photos}), and there is no
     * inspection to photograph. The custody note (WF-05 Q-11) is the exception: goods that came back and that no
     * receipt takes - they are not the return's - are recorded here, on the claim they came with.
     */
    public void requireEvidenceAllowed(ReturnEvidence.Stage stage, ReturnPolicy policy) {
        boolean open = switch (Objects.requireNonNull(stage, "stage")) {
            case CLAIM -> status == ReturnStatus.REQUESTED || status == ReturnStatus.AWAITING_RETURN;
            case RECEIPT, INSPECTION -> false;
            // WF-05 Q-11: goods the return does not take may turn up at any time - sent with the claim, more than was
            // approved, after it was settled or refused - and are noted on it whatever its status.
            case CUSTODY -> true;
        };
        if (!open) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, stage == ReturnEvidence.Stage.CLAIM
                    ? "Return %s is %s: it takes no photo of the claim now".formatted(rmaNumber, status)
                    : "A return keeps the photos of its claim only; photos of the goods that came back are kept on "
                            + "their return receipt");
        }
        long atStage = evidence.stream().filter(photo -> photo.stage() == stage).count();
        if (atStage >= policy.maxEvidence()) {
            throw new BusinessException(ErrorCode.RETURN_EVIDENCE_LIMIT_REACHED,
                    "Return %s already holds %d %s photo(s)".formatted(rmaNumber, atStage, stage));
        }
    }

    // ================================================================================================= review

    /**
     * Sales approve the claim (17 §4.10 step 38: REQUESTED → AWAITING_RETURN, the RMA): the customer sends the approved
     * goods back to the order's warehouse by the deadline. A claim Sales opened themselves is approved by another
     * person: opening and approving a claim worth anything is never one person's decision.
     *
     * @param approved       units approved per line of the return; a line left out is approved in full, 0 approves
     *                       none of it (a partial approval). At least one unit is approved, or the return is rejected
     * @param returnRequired whether the goods come back: null or true. They always do (17 BR-18); false is refused
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_SELF_APPROVAL};
     *         {@code RETURN_EVIDENCE_REQUIRED}; {@code RETURN_GOODS_REQUIRED} when asked to leave the goods with the
     *         customer; {@code RETURN_QUANTITIES_INVALID}
     */
    public void approve(UUID by, Instant now, Map<UUID, Integer> approved, Boolean returnRequired, String note,
                        ReturnPolicy policy) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.REQUESTED, "be approved");
        if (channel == ReturnChannel.BACK_OFFICE && by.equals(requestedBy)) {
            throw new BusinessException(ErrorCode.RETURN_SELF_APPROVAL,
                    "Return %s was opened by the same person: someone else approves it".formatted(rmaNumber));
        }
        if (Boolean.FALSE.equals(returnRequired)) {
            throw new BusinessException(ErrorCode.RETURN_GOODS_REQUIRED, ("The goods of %s come back through a return "
                    + "receipt before anything is refunded (17 BR-18)").formatted(rmaNumber));
        }
        Map<UUID, Integer> units = new HashMap<>();
        for (ReturnLine line : lines) {
            units.put(line.id(), line.requestedQuantity());
        }
        if (approved != null) {
            for (Map.Entry<UUID, Integer> entry : approved.entrySet()) {
                ReturnLine line = line(entry.getKey());
                int value = entry.getValue() == null ? line.requestedQuantity() : entry.getValue();
                if (value < 0 || value > line.requestedQuantity()) {
                    throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                            "%s: approve between 0 and the %d unit(s) asked for".formatted(line.sku(),
                                    line.requestedQuantity()));
                }
                units.put(line.id(), value);
            }
        }
        if (units.values().stream().mapToInt(Integer::intValue).sum() == 0) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                    "Approve at least one unit, or reject the return");
        }
        if (policy.evidenceRequired() && evidence.stream().noneMatch(e -> e.stage() == ReturnEvidence.Stage.CLAIM)) {
            throw new BusinessException(ErrorCode.RETURN_EVIDENCE_REQUIRED,
                    "Return %s has no photo of the problem".formatted(rmaNumber));
        }
        lines.forEach(line -> line.approve(units.get(line.id())));
        this.decidedBy = by;
        this.decidedAt = now;
        this.decisionNote = limited(note, NOTE_LIMIT, "note");
        this.returnRequired = true;
        this.returnDeadline = policy.returnDeadlineFrom(now);
        transitionTo(ReturnStatus.AWAITING_RETURN, by, "APPROVED", now);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnApproved(id, rmaNumber, orderId, orderNumber,
                customerId, true, returnDeadline, lines.stream().map(l -> new ReturnApproved.Line(
                        l.orderLineId(), l.sku(), l.requestedQuantity(), l.approvedQuantity())).toList(), by, now)));
    }

    /** Sales refuse the claim, saying why (17 §4.10 step 38: REQUESTED → REJECTED). */
    public void reject(UUID by, Instant now, String why) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.REQUESTED, "be rejected");
        String text = required(why, REASON_LIMIT, "A rejected return says why");
        this.decidedBy = by;
        this.decidedAt = now;
        this.rejectionReason = text;
        close(ReturnStatus.REJECTED, by, text, now);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnRejected(id, rmaNumber, orderId, orderNumber,
                customerId, "REVIEW", by, now)));
    }

    // ================================================================================================= withdrawal

    /**
     * The customer (or Sales for them) withdraws the return before any of its goods is back: REQUESTED or
     * AWAITING_RETURN → CANCELLED. Goods a return receipt already opened for it brings in anyway ({@link #goodsReceived}).
     *
     * <p>Not once a parcel came back (WF-05 TH-3). A return sent in several parcels is still AWAITING_RETURN after the
     * first one, and withdrawing it then would leave those goods in quarantine with nothing refunded, while the order
     * went back to DELIVERED and on to completion. Sales mark the goods complete instead ({@link #markGoodsComplete}),
     * and the refund is decided - or the return closed without one - on what came. The database refuses the same step
     * ({@code tg_return_request_no_cancel_after_parcel}).</p>
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} past AWAITING_RETURN;
     *         {@code RETURN_GOODS_PARTLY_RECEIVED} once a parcel of its goods came back
     */
    public void cancel(UUID by, Instant now, String why, boolean byCustomer) {
        Objects.requireNonNull(by, "by");
        if (status != ReturnStatus.REQUESTED && status != ReturnStatus.AWAITING_RETURN) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "Return %s is %s: it is withdrawn only before its goods are back".formatted(rmaNumber, status));
        }
        if (anyParcel()) {
            throw new BusinessException(ErrorCode.RETURN_GOODS_PARTLY_RECEIVED, ("Return %s has %d parcel(s) of its "
                    + "goods back: mark its goods complete instead of withdrawing it").formatted(rmaNumber,
                    Math.max(parcels.size(), 1)));
        }
        // WF-05 BE-23: a parcel being counted at the dock is goods back as much as a confirmed one. Withdrawn now, the
        // order would go back to DELIVERED and could complete before the receipt is confirmed and reopens the return.
        requireNoDraftReceipt("be withdrawn");
        String text = trimmed(why) == null ? "WITHDRAWN" : limited(why, REASON_LIMIT, "reason");
        this.cancelledBy = by;
        this.cancellationReason = text;
        close(ReturnStatus.CANCELLED, by, text, now);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnClosed(id, rmaNumber, orderId, orderNumber, customerId,
                "CANCELLED", byCustomer, by, now)));
    }

    /**
     * The deadline for the approved goods passed, as the nightly sweep finds it. Nothing came back: AWAITING_RETURN →
     * EXPIRED; goods a return receipt already opened for it brings in anyway ({@link #goodsReceived}). Some parcels came
     * and not all that was approved (WF-05 TH-3): no more is awaited, AWAITING_RETURN → RECEIVED with what came, and the
     * units short are recorded - the refund is decided on the goods that are here.
     *
     * @return whether it moved; false while the deadline has not passed, or once the return moved on
     */
    public boolean expire(Instant now) {
        if (status != ReturnStatus.AWAITING_RETURN || returnDeadline == null || returnDeadline.isAfter(now)) {
            return false;
        }
        if (openDraftReceiptId != null) {
            // WF-05 BE-23: a parcel is being counted at the dock. Expired now, the return would end - its order back to
            // DELIVERED and on to completion - and the receipt confirmed afterwards would reopen it. The sweep takes it
            // up again once the receipt is confirmed or cancelled.
            return false;
        }
        if (anyParcel()) {
            // Recorded under who took the last parcel in: the sweep is nobody, and a discrepancy names its recorder.
            recordDiscrepancies(shortfalls(receivedBy, now, "Goods deadline passed"));
            transitionTo(ReturnStatus.RECEIVED, null, "GOODS_DEADLINE_PASSED", now);
            announceAwaitingRefund("GOODS_DEADLINE_PASSED", now);
            return true;
        }
        close(ReturnStatus.EXPIRED, null, "RETURN_DEADLINE_PASSED", now);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnClosed(id, rmaNumber, orderId, orderNumber, customerId,
                "EXPIRED", false, null, now)));
        return true;
    }

    // ================================================================================================= goods back

    /**
     * A parcel of the goods came back (17 §4.10 step 39; 03 §4.2 step 12; WF-05 TH-3): the warehouse confirmed return
     * receipt {@code receiptId}, which scanned {@code cartons} of the order into the quarantine area {@code location}.
     * A B2B customer may send the goods of one return in several parcels, each received on a receipt of its own, so each
     * line adds the units of its SKU among them to what the earlier parcels brought.
     * <ul>
     *   <li>AWAITING_RETURN: RECEIVED once every line has what was approved; until then it keeps waiting - for the next
     *       parcel, for Sales to say the goods are complete ({@link #markGoodsComplete}), or for its deadline
     *       ({@link #expire}), whichever comes first.</li>
     *   <li>EXPIRED, or withdrawn after approval: brought back, RECEIVED - the goods are here, on a receipt opened while
     *       the return awaited them, and nothing more is awaited.</li>
     *   <li>Its goods back already (RECEIVED, SETTLEMENT_PENDING): a late parcel (WF-05 Q-11 lets a receipt be opened
     *       until the refund is settled). Its units are added and its discrepancies recorded. A refund waiting for its
     *       second person was proposed without them, so it is sent back to Sales (SETTLEMENT_PENDING → RECEIVED):
     *       approved as it stood, the return would end RESOLVED with goods that came back never decided on.</li>
     *   <li>RESOLVED: only a receipt drafted before WF-05 BE-23, when nothing kept a refund from being decided while a
     *       parcel was counted. Its units are added; the refund stays as decided. Refused instead, the event would be
     *       retried for ever over goods already in quarantine.</li>
     * </ul>
     * Units beyond those approved and cartons of a SKU the return does not claim are discrepancies of the parcel; units
     * short of those approved are one once no more is awaited - for Sales to follow up with the customer outside the
     * system (03 edge cases). Units of a SKU two lines of the return share (two order lines of one SKU) fill each line up
     * to what it approved, in turn, the rest going to the last (WF-05 BE-48): added to every line of the SKU, the same
     * units were received - and refundable - twice.
     *
     * @return false when this receipt was taken already (the event delivered again): nothing changes
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} for a return that takes no goods (never approved)
     */
    public boolean goodsReceived(UUID receiptId, String receiptNumber, String location, List<ReturnedCarton> cartons,
                                 UUID by, Instant now) {
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(by, "by");
        if (receiptId.equals(goodsReceiptId) || parcels.stream().anyMatch(p -> p.goodsReceiptId().equals(receiptId))) {
            return false;
        }
        boolean reopening = status == ReturnStatus.EXPIRED
                || status == ReturnStatus.CANCELLED && Boolean.TRUE.equals(returnRequired) && decidedAt != null;
        if (status != ReturnStatus.AWAITING_RETURN && !reopening && !status.goodsBack()) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "Return %s is %s and takes no goods now".formatted(rmaNumber, status));
        }
        String at = trimmed(location);
        if (at == null) {
            throw new IllegalArgumentException("Returned goods are received into a location");
        }
        Map<String, Integer> bySku = new LinkedHashMap<>();
        for (ReturnedCarton carton : cartons == null ? List.<ReturnedCarton>of() : cartons) {
            bySku.merge(carton.sku(), carton.quantity(), Integer::sum);
        }
        if (bySku.isEmpty()) {
            throw new IllegalArgumentException("A confirmed return receipt received at least one carton");
        }
        String source = "Receipt %s".formatted(receiptNumber);
        List<ReturnDiscrepancy> found = new ArrayList<>();
        Map<String, List<ReturnLine>> linesBySku = new LinkedHashMap<>();
        lines.forEach(line -> linesBySku.computeIfAbsent(line.sku(), sku -> new ArrayList<>()).add(line));
        Set<String> claimed = linesBySku.keySet();
        for (Map.Entry<String, List<ReturnLine>> sameSku : linesBySku.entrySet()) {
            int arriving = bySku.getOrDefault(sameSku.getKey(), 0);
            List<ReturnLine> sharing = sameSku.getValue();
            for (int i = 0; i < sharing.size(); i++) {
                ReturnLine line = sharing.get(i);
                int before = received(line);
                int approvedUnits = approved(line);
                int taken = i == sharing.size() - 1 ? arriving : Math.min(arriving, Math.max(0, approvedUnits - before));
                arriving -= taken;
                int total = before + taken;
                line.receive(total);
                int excess = Math.max(0, total - approvedUnits) - Math.max(0, before - approvedUnits);
                if (excess > 0) {
                    found.add(new ReturnDiscrepancy(Identifiers.newId(), line.id(), ReturnDiscrepancy.Kind.EXCESS,
                            line.sku(), excess, source, by, now));
                }
            }
        }
        bySku.forEach((sku, units) -> {
            if (!claimed.contains(sku) && units > 0) {
                found.add(new ReturnDiscrepancy(Identifiers.newId(), null, ReturnDiscrepancy.Kind.UNEXPECTED_ITEM,
                        sku, units, "%s: a SKU the return does not claim".formatted(source), by, now));
            }
        });
        int parcel = Math.max(parcels.size(), goodsReceiptId == null ? 0 : 1) + 1;
        ReturnParcel taken = new ReturnParcel(receiptId, parcel, receiptNumber, at, by, now);
        parcels.add(taken);
        newParcels.add(taken);
        // Audit round 2 M1: the draft is let go of when its goods are taken, not before - the two in one step, so no
        // refund is decided on a return whose parcel is confirmed but not yet counted here.
        if (receiptId.equals(openDraftReceiptId)) {
            this.openDraftReceiptId = null;
        }
        // The first receipt stays the one the return names (it never changes, check_return_request_goods_receipt); who,
        // when and where follow the latest parcel.
        if (goodsReceiptId == null) {
            this.goodsReceiptId = receiptId;
        }
        this.receivedBy = by;
        this.receivedAt = now;
        this.receivingLocation = at;
        String awaiting = null;
        if (reopening) {
            found.addAll(shortfalls(by, now, source));
            String why = status == ReturnStatus.EXPIRED ? "RECEIVED_AFTER_DEADLINE" : "RECEIVED_AFTER_WITHDRAWAL";
            this.closedAt = null;
            this.cancelledBy = null;
            this.cancellationReason = null;
            transitionTo(ReturnStatus.RECEIVED, by, why + " " + receiptNumber, now);
            awaiting = why;
        } else if (status == ReturnStatus.AWAITING_RETURN && lines.stream().allMatch(l -> received(l) >= approved(l))) {
            boolean clean = found.isEmpty() && discrepancies.isEmpty();
            transitionTo(ReturnStatus.RECEIVED, by, (clean ? "RECEIVED " : "RECEIVED_WITH_DISCREPANCY ")
                    + receiptNumber, now);
            awaiting = "GOODS_BACK";
        } else if (status == ReturnStatus.SETTLEMENT_PENDING) {
            clearProposal();
            transitionTo(ReturnStatus.RECEIVED, by, "REFUND_SENT_BACK: goods of receipt %s arrived"
                    .formatted(receiptNumber), now);
            awaiting = "LATE_PARCEL";
        }
        recordDiscrepancies(found);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnReceived(id, rmaNumber, orderId, orderNumber,
                customerId, at, lines.stream().map(l -> new ReturnReceived.Line(l.orderLineId(), l.sku(), l.sku(),
                        approved(l), received(l))).toList(), !found.isEmpty(), by, now, parcel, status.name(),
                receiptNumber)));
        if (awaiting != null) {
            announceAwaitingRefund(awaiting, now);
        }
        return true;
    }

    /**
     * Sales say the goods of the return are all here (WF-05 TH-3): fewer than approved came back - the customer sent
     * what they had, or sent another SKU by mistake - and no more is coming. AWAITING_RETURN → RECEIVED with the units
     * the parcels brought, the units short recorded; the refund is then decided on those, or the return closed without
     * one. Without it such a return would wait for its deadline. A parcel still on its way is taken all the same
     * ({@link #goodsReceived}).
     *
     * <p>The only way to settle such a return before its deadline: once a parcel is in, it is no longer withdrawn
     * ({@link #cancel}).</p>
     *
     * <p>Never while a parcel of it is being counted at the dock (WF-05 BE-23, {@code RETURN_RECEIPT_DRAFT_OPEN}): the
     * goods are not complete while some of them are on a draft receipt, and once the refund was settled on what came
     * before, that parcel's units would never be refunded.</p>
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} for a return not awaiting its goods, or one no parcel
     *         of which came back yet - nothing is here to decide on; withdraw it, or let it expire;
     *         {@code RETURN_RECEIPT_DRAFT_OPEN}
     */
    public void markGoodsComplete(UUID by, Instant now, String note) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.AWAITING_RETURN, "have its goods marked complete");
        requireNoDraftReceipt("have its goods marked complete");
        if (!anyParcel()) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, ("Nothing of return %s came back yet: "
                    + "wait for its goods, or withdraw it").formatted(rmaNumber));
        }
        String text = limited(note, REASON_LIMIT, "note");
        recordDiscrepancies(shortfalls(by, now, "Goods marked complete"));
        transitionTo(ReturnStatus.RECEIVED, by, text == null ? "GOODS_COMPLETE" : "GOODS_COMPLETE: " + text, now);
        announceAwaitingRefund("GOODS_COMPLETE", now);
    }

    /** Whether a parcel of the goods came back: a confirmed return receipt brought some. */
    private boolean anyParcel() {
        return goodsReceiptId != null || !parcels.isEmpty();
    }

    /** One SHORT discrepancy per line that has fewer units back than approved, once no more is awaited. */
    private List<ReturnDiscrepancy> shortfalls(UUID by, Instant now, String note) {
        List<ReturnDiscrepancy> found = new ArrayList<>();
        for (ReturnLine line : lines) {
            int missing = approved(line) - received(line);
            if (missing > 0) {
                found.add(new ReturnDiscrepancy(Identifiers.newId(), line.id(), ReturnDiscrepancy.Kind.SHORT,
                        line.sku(), missing, note, by, now));
            }
        }
        return found;
    }

    private void recordDiscrepancies(List<ReturnDiscrepancy> found) {
        discrepancies.addAll(found);
        newDiscrepancies.addAll(found);
    }

    private static int approved(ReturnLine line) {
        return line.approvedQuantity() == null ? 0 : line.approvedQuantity();
    }

    private static int received(ReturnLine line) {
        return line.receivedQuantity() == null ? 0 : line.receivedQuantity();
    }

    // ================================================================================================= refund

    /**
     * Sales propose the refund of the goods that came back (17 §4.10 step 40; 15 §4.5 step 15): per line, the units
     * refunded - at most those approved and back - worth what the order line charged for them with its VAT. Every
     * refund waits for a second person (RECEIVED → SETTLEMENT_PENDING).
     *
     * @param units per line of the return, the units refunded; null or empty refunds every unit approved and back, a
     *              line left out of a list refunds nothing
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_QUANTITIES_INVALID};
     *         {@code RETURN_REFUSAL_REASON_REQUIRED} for nothing refunded of what came back usable;
     *         {@code RETURN_RECEIPT_DRAFT_OPEN}
     */
    public void proposeRefund(UUID by, Instant now, Map<UUID, Integer> units, Order order, String note) {
        proposeRefund(by, now, units, order, note, false);
    }

    /**
     * As {@link #proposeRefund(UUID, Instant, Map, Order, String)}; {@code nothingPaid} for an order with no
     * installments at all (WF-05 TH-2: a sample order, an order of before contracts). Money is recorded in the system
     * only against a contract's installments (15 BR-01, BR-05), so nothing was paid on such an order that a refund could
     * hand back or set off: the units are refunded worth nothing, the settlement is 0 - and still approved by a second
     * person, since it decides that the goods given back are not paid for. Refused before, such a return could never
     * leave RECEIVED and kept its order RETURNED for good.
     */
    public void proposeRefund(UUID by, Instant now, Map<UUID, Integer> units, Order order, String note,
                              boolean nothingPaid) {
        proposeRefund(by, now, units, order, note, nothingPaid, null, null, Map.of());
    }

    /** {@link #proposeRefund(UUID, Instant, Map, Order, String, boolean, ReturnRefusalReason, String, Map, Map)}, every line
     * invoiced at its own rate. */
    public void proposeRefund(UUID by, Instant now, Map<UUID, Integer> units, Order order, String note,
                              boolean nothingPaid, ReturnRefusalReason refusal, String refusalNote,
                              Map<UUID, Integer> refundedBefore) {
        proposeRefund(by, now, units, order, note, nothingPaid, refusal, refusalNote, refundedBefore, Map.of());
    }

    /**
     * The whole proposal: what is refunded, why anything that came back is withheld, and where on each order line the
     * refunded units sit.
     *
     * <p><b>A refusal</b> (WF-05 Q-4; Luật Thương mại 2005 Điều 318, 319): goods can come back usable and still not be
     * refunded - the customer damaged them, no defect was found, the claim is outside the policy. Sales then propose
     * nothing for them and say why, from a fixed list ({@link ReturnRefusalReason}), with an optional note the customer
     * reads; the accountant approves it as any refund ({@link #requireSettlementApprover}: someone else), and the return
     * ends RESOLVED with nothing settled and no invoice adjusted. Before, every unit that came back usable had to be
     * refunded at least in part, and a return whose goods the customer had damaged could never end. Withholding only
     * some units may say why too; withholding all of them must ({@code RETURN_REFUSAL_REASON_REQUIRED};
     * {@code check_return_request_refund} again).</p>
     *
     * <p><b>The value</b> (WF-05 BE-50): the units are valued where they sit on their order line's cumulative scale -
     * {@link OrderLine#grossBetween} from the units other returns of the line already refunded - not by rounding their
     * own share of the line's gross. Several returns and closures of one line then add up to the line's gross to the
     * dong; rounded each on its own they drifted by up to a dong a time, enough to leave an order owing 1 VND for ever
     * or a settlement refunding more than was collected. Closures take units from the top of the line
     * ({@code Order.closeUnitsOf}), refunds from the bottom, so the two never overlap. Approval values them again on
     * what was settled by then ({@link #revalue}), since returns of one line may be approved in another order than
     * proposed.</p>
     *
     * <p><b>At the rate they were invoiced at</b> (WF-05 O-1, decided 24/11): the refund of units that were invoiced is
     * what the return's adjustment invoice takes off for them - the adjustment is the legal basis of the refund
     * (TT 91/2026 Điều 10 khoản 5 điểm c.1). An order line keeps the rate its contract fixed, but its goods are invoiced at
     * the law's rate of the day they were delivered (NĐ 174/2025: 8% where the contract said 10%), so the line's own
     * scale would refund 10% for goods the buyer was invoiced and charged 8% for: 1,122,000 against an adjustment of
     * 1,101,600 on the same 850 cups. {@code invoicedRates} - what the invoice of the return's shipment carries for each
     * line, {@code DeliveryInvoices.invoicedRates} - decides the rate; a line invoiced at its own rate stays on the
     * cumulative scale above ({@link OrderLine#grossAt}). The difference between the contract and the invoice stays the
     * accountant's ({@code TAX_RATE_DIFFERS_FROM_CONTRACT}).</p>
     *
     * @param refusal        why units that came back usable are withheld; required when every one of them is, refused
     *                       when none is
     * @param refusalNote    the note the customer reads beside the reason (at most {@value #REFUSAL_NOTE_LIMIT}
     *                       characters); required for {@link ReturnRefusalReason#OTHER}
     * @param refundedBefore per order line, the units other returns of it already refunded (RESOLVED)
     * @param invoicedRates  per order line, the VAT rate the return's shipment invoiced its goods at; a line left out
     *                       is valued at its own rate
     */
    public void proposeRefund(UUID by, Instant now, Map<UUID, Integer> units, Order order, String note,
                              boolean nothingPaid, ReturnRefusalReason refusal, String refusalNote,
                              Map<UUID, Integer> refundedBefore, Map<UUID, BigDecimal> invoicedRates) {
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(order, "order");
        requireStatus(ReturnStatus.RECEIVED, "have a refund proposed");
        requireNoDraftReceipt("have a refund proposed");
        Map<UUID, Integer> refunded = new HashMap<>();
        if (units == null || units.isEmpty()) {
            lines.forEach(line -> refunded.put(line.id(), line.refundableUnits()));
        } else {
            for (Map.Entry<UUID, Integer> entry : units.entrySet()) {
                ReturnLine line = line(entry.getKey());
                int value = entry.getValue() == null ? 0 : entry.getValue();
                if (value < 0 || value > line.refundableUnits()) {
                    throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID, ("%s: refund between 0 and the %d "
                            + "unit(s) approved and back").formatted(line.sku(), line.refundableUnits()));
                }
                refunded.put(line.id(), value);
            }
        }
        int total = refunded.values().stream().mapToInt(Integer::intValue).sum();
        int refundable = lines.stream().mapToInt(ReturnLine::refundableUnits).sum();
        String publicNote = limited(refusalNote, REFUSAL_NOTE_LIMIT, "refusal note");
        if (total == 0 && refundable == 0) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                    "Nothing approved of %s came back usable: close it without a refund".formatted(rmaNumber));
        }
        if (total == 0 && refusal == null) {
            throw new BusinessException(ErrorCode.RETURN_REFUSAL_REASON_REQUIRED, ("%d unit(s) of %s came back and none "
                    + "is refunded: say why").formatted(refundable, rmaNumber));
        }
        if (refusal == null && publicNote != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A note for the customer goes with a refusal reason");
        }
        if (refusal != null && total == refundable) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Every unit of %s that came back is refunded: "
                    + "there is nothing to give a refusal reason for").formatted(rmaNumber));
        }
        if (refusal == ReturnRefusalReason.OTHER && publicNote == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A refusal for another reason says what it is, for the customer");
        }
        BigDecimal value = BigDecimal.ZERO;
        for (ReturnLine line : lines) {
            int count = refunded.getOrDefault(line.id(), 0);
            BigDecimal amount = nothingPaid ? BigDecimal.ZERO
                    : valueOf(order, line, count, refundedBefore, invoicedRates);
            line.refund(count, amount);
            value = value.add(amount);
        }
        this.settlementAmount = value;
        // ck_return_request_money_mode: a settlement of nothing has no money mode.
        this.moneyMode = value.signum() > 0 ? ReturnResolution.REFUND : null;
        this.proposedBy = by;
        this.proposedAt = now;
        this.resolutionNote = limited(note, NOTE_LIMIT, "note");
        this.refusalReason = refusal;
        this.refusalNote = refusal == null ? null : publicNote;
        transitionTo(ReturnStatus.SETTLEMENT_PENDING, by, "REFUND_PROPOSED %s %s%s%s".formatted(plain(value), currency,
                nothingPaid ? " NOTHING_PAID" : "", refusal == null ? "" : " REFUSED " + refusal.name()), now);
    }

    /**
     * The proposed refund valued again where its units sit on each order line now (WF-05 BE-50): called by the
     * approval, under the order's lock, with what the line's other returns refunded by then. Returns of one line
     * approved in another order than they were proposed would otherwise value overlapping ranges of the line, and
     * their amounts would no longer add up to it. A settlement of nothing (a refusal, an order with nothing paid) has
     * nothing to value.
     *
     * <p>At the rates read now (O-1): the invoice of the shipment may have been issued, or replaced, since the proposal.</p>
     *
     * @return whether the amount changed
     */
    public boolean revalue(Order order, Map<UUID, Integer> refundedBefore) {
        return revalue(order, refundedBefore, Map.of());
    }

    /** {@link #revalue(Order, Map)} with the rates the return's shipment invoiced each line at (O-1). */
    public boolean revalue(Order order, Map<UUID, Integer> refundedBefore, Map<UUID, BigDecimal> invoicedRates) {
        Objects.requireNonNull(order, "order");
        requireStatus(ReturnStatus.SETTLEMENT_PENDING, "have its refund valued");
        if (settlementAmount.signum() == 0) {
            return false;
        }
        BigDecimal value = BigDecimal.ZERO;
        for (ReturnLine line : lines) {
            BigDecimal amount = valueOf(order, line, line.refundQuantity(), refundedBefore, invoicedRates);
            line.refund(line.refundQuantity(), amount);
            value = value.add(amount);
        }
        boolean changed = value.compareTo(settlementAmount) != 0;
        this.settlementAmount = value;
        return changed;
    }

    /**
     * {@code count} units of {@code line} refunded after the {@code refundedBefore} its other returns refunded, at the
     * rate its shipment invoiced them at (O-1).
     */
    private BigDecimal valueOf(Order order, ReturnLine line, int count, Map<UUID, Integer> refundedBefore,
                               Map<UUID, BigDecimal> invoicedRates) {
        if (count == 0) {
            return BigDecimal.ZERO;
        }
        OrderLine sold = order.findLine(line.orderLineId()).orElseThrow(() -> new IllegalStateException(
                "Return %s names line %s that order %s does not have".formatted(rmaNumber, line.orderLineId(),
                        order.orderNumber())));
        int from = refundedBefore == null ? 0 : refundedBefore.getOrDefault(line.orderLineId(), 0);
        if (from < 0 || from + count > sold.quantity()) {
            throw new IllegalStateException(("Return %s refunds %d unit(s) of %s after %d refunded already: more than "
                    + "its %d").formatted(rmaNumber, count, line.sku(), from, sold.quantity()));
        }
        return sold.grossAt(from, count, invoicedRates == null ? null : invoicedRates.get(line.orderLineId()))
                .amount();
    }

    /**
     * Four eyes on every refund: approved by someone other than who proposed it. Checked before anything is settled,
     * so the refusal is the answer and not whatever a settlement would have failed with.
     *
     * Never while a parcel of it is being counted (WF-05 BE-23): its units would come after the refund was settled.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_SELF_APPROVAL};
     *         {@code RETURN_RECEIPT_DRAFT_OPEN}
     */
    public void requireSettlementApprover(UUID by) {
        requireStatus(ReturnStatus.SETTLEMENT_PENDING, "have its refund approved");
        requireNoDraftReceipt("have its refund approved");
        if (Objects.equals(by, proposedBy)) {
            throw new BusinessException(ErrorCode.RETURN_SELF_APPROVAL,
                    "The refund of %s was proposed by the same person".formatted(rmaNumber));
        }
    }

    /** The approver sends the refund back, saying why: SETTLEMENT_PENDING → RECEIVED, the proposal cleared. */
    public void rejectSettlement(UUID by, Instant now, String why) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.SETTLEMENT_PENDING, "have its refund sent back");
        String text = required(why, REASON_LIMIT, "A refund sent back says why");
        clearProposal();
        transitionTo(ReturnStatus.RECEIVED, by, "REFUND_SENT_BACK: " + text, now);
        announceAwaitingRefund("REFUND_SENT_BACK", now);
    }

    /** The proposal is gone: nothing refunded, no amount, no reason, nobody proposed it. */
    private void clearProposal() {
        lines.forEach(line -> line.refund(0, BigDecimal.ZERO));
        this.settlementAmount = BigDecimal.ZERO;
        this.moneyMode = null;
        this.proposedBy = null;
        this.proposedAt = null;
        this.resolutionNote = null;
        this.refusalReason = null;
        this.refusalNote = null;
    }

    /**
     * The approved refund was settled (SETTLEMENT_PENDING → RESOLVED, by the approver): payment took the value off what
     * the order's installments owed and made the rest refundable, in the same transaction.
     *
     * @throws IllegalStateException when payment settled another amount than the refund - a defect, never a refusal
     */
    public void resolve(UUID by, Instant now, MoneyOutcome outcome) {
        Objects.requireNonNull(by, "by");
        MoneyOutcome booked = outcome == null ? MoneyOutcome.NONE : outcome;
        requireSettlementApprover(by);
        if (booked.total().compareTo(settlementAmount) != 0) {
            throw new IllegalStateException("Return %s refunds %s but %s was settled".formatted(rmaNumber,
                    plain(settlementAmount), plain(booked.total())));
        }
        this.money = booked;
        this.settledBy = by;
        this.settledAt = now;
        // WF-05 BE-44: the invoice follows the goods refunded, whatever they were worth in money - units refunded worth
        // nothing (an order with nothing paid, TH-2) were still invoiced. A refusal refunds no unit and adjusts nothing.
        this.vatAdjustmentRequired = lines.stream().anyMatch(line -> line.refundQuantity() > 0);
        close(ReturnStatus.RESOLVED, by, !vatAdjustmentRequired && refusalReason != null
                ? "RESOLVED_REFUSED " + refusalReason.name()
                : "RESOLVED_REFUND %s %s".formatted(plain(settlementAmount), currency), now);
        announceResolved(by, now);
    }

    /**
     * The goods came back with nothing usable in them - every carton counted empty, or only goods the return did not
     * claim (WF-05 TH-4) - so nothing approved can be refunded: RECEIVED → RESOLVED with no refund, by one person. No
     * money moves and no invoice is adjusted, so there is nothing for a second person to approve
     * ({@code ck_return_request_four_eyes} lets a zero settlement through); without this the return could never leave
     * RECEIVED, since a refund proposes at least one unit. The goods themselves are the warehouse's to write off.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} for a return not RECEIVED;
     *         {@code RETURN_QUANTITIES_INVALID} while something approved came back and is to be refunded - or refused
     *         with a reason, by two people ({@link #proposeRefund}, WF-05 Q-4); {@code RETURN_RECEIPT_DRAFT_OPEN}
     */
    public void closeWithoutRefund(UUID by, Instant now, String note) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.RECEIVED, "be closed without a refund");
        requireNoDraftReceipt("be closed without a refund");
        int refundable = lines.stream().mapToInt(ReturnLine::refundableUnits).sum();
        if (refundable > 0) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID, ("%d unit(s) of %s were approved and came "
                    + "back: propose their refund").formatted(refundable, rmaNumber));
        }
        lines.forEach(line -> line.refund(0, BigDecimal.ZERO));
        this.settlementAmount = BigDecimal.ZERO;
        this.moneyMode = null;
        this.proposedBy = by;
        this.proposedAt = now;
        this.resolutionNote = limited(note, NOTE_LIMIT, "note");
        this.money = MoneyOutcome.NONE;
        this.settledBy = by;
        this.settledAt = now;
        this.vatAdjustmentRequired = false;
        close(ReturnStatus.RESOLVED, by, "RESOLVED_NOTHING_USABLE", now);
        announceResolved(by, now);
    }

    /**
     * {@link ReturnResolved}: what came back of each line, what was refunded, the delivery it was against, and why
     * anything was withheld (WF-05 Q-4: the customer reads it) - its code; the public note stays on the return.
     */
    private void announceResolved(UUID by, Instant now) {
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnResolved(id, rmaNumber, orderId, orderNumber,
                customerId, null, null, money.offset(), money.refunded(), BigDecimal.ZERO, currency,
                vatAdjustmentRequired, lines.stream().map(l -> new ReturnResolved.Line(l.orderLineId(), l.sku(),
                        l.receivedQuantity() == null ? 0 : l.receivedQuantity(), 0, l.refundQuantity(),
                        l.refundNetAmount())).toList(), by, now, orderId, shipmentId,
                refusalReason == null ? null : refusalReason.name())));
    }

    /**
     * The return is RECEIVED again - its goods back, or its refund sent back - and waits for Sales to propose the
     * refund (WF-05 BE-24): announced so Sales are told, whatever moved it there. {@code cause}: GOODS_BACK,
     * GOODS_COMPLETE, GOODS_DEADLINE_PASSED, RECEIVED_AFTER_DEADLINE, RECEIVED_AFTER_WITHDRAWAL, LATE_PARCEL,
     * REFUND_SENT_BACK.
     */
    private void announceAwaitingRefund(String cause, Instant at) {
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnAwaitingRefund(id, rmaNumber, orderId, orderNumber,
                customerId, cause, at)));
    }

    // ================================================================================================= receipts

    /**
     * Whether a return receipt may be opened for this return now (WF-05 Q-11; TT 91/2026/TT-BTC Điều 10 khoản 5 điểm
     * c): it was approved and its refund is not settled - awaiting its goods, back (RECEIVED), its refund waiting for a
     * second person, expired, or withdrawn after approval. A parcel that turns up after Sales marked the goods complete
     * or after the deadline is then received on a document and decided on, instead of standing in the warehouse with
     * none. Not REQUESTED (nothing was agreed to take back), REJECTED or RESOLVED: goods arriving then are the
     * customer's, kept in custody on the return ({@link ReturnEvidence.Custody}). {@code check_return_receipt} says
     * the same.
     */
    public boolean receiptOpenable() {
        return receiptOpenable(status, returnRequired, decidedAt);
    }

    /** {@link #receiptOpenable()} from what a list row holds, so a list and the detail say the same. */
    public static boolean receiptOpenable(ReturnStatus status, Boolean returnRequired, Instant decidedAt) {
        return switch (status) {
            case AWAITING_RETURN, RECEIVED, SETTLEMENT_PENDING, EXPIRED -> true;
            case CANCELLED -> Boolean.TRUE.equals(returnRequired) && decidedAt != null;
            case REQUESTED, INSPECTED, RESOLVED, REJECTED -> false;
        };
    }

    /**
     * A return receipt was opened for a parcel of this return (WF-05 BE-23): while it is a draft the goods are not
     * marked complete, the deadline does not end the return, and no refund is proposed or approved
     * ({@code RETURN_RECEIPT_DRAFT_OPEN}). Procurement tells the return in the receipt's own transaction
     * ({@code ReturnReceiptTracking}). The same receipt again changes nothing.
     *
     * @throws BusinessException {@code RETURN_NOT_AWAITING_GOODS} when no receipt may be opened for it now
     *         ({@link #receiptOpenable}); {@code RETURN_RECEIPT_ALREADY_OPEN} while another draft counts a parcel of it
     */
    public void draftReceiptOpened(UUID receiptId) {
        Objects.requireNonNull(receiptId, "receiptId");
        if (receiptId.equals(openDraftReceiptId)) {
            return;
        }
        if (!receiptOpenable()) {
            throw new BusinessException(ErrorCode.RETURN_NOT_AWAITING_GOODS, ("Return %s is %s: its goods are received "
                    + "while it is approved and its refund not settled").formatted(rmaNumber, status));
        }
        if (openDraftReceiptId != null) {
            throw new BusinessException(ErrorCode.RETURN_RECEIPT_ALREADY_OPEN, ("A parcel of return %s is already being "
                    + "counted on another receipt; confirm or cancel it first").formatted(rmaNumber), List.of(),
                    Map.of("receiptId", openDraftReceiptId));
        }
        this.openDraftReceiptId = receiptId;
    }

    /**
     * As {@link #draftReceiptOpened(UUID)}, also against the order and what the order's other returns claim - the path
     * every receipt takes ({@code ReturnRequestService.draftOpened}, under the order's lock):
     * <ul>
     *   <li>never on a CLOSED order (audit round 2, lifecycle L-3): the return would reopen and settle money while the
     *       order stayed CLOSED; goods arriving then are kept in custody (Q-11).</li>
     *   <li>a return withdrawn after approval, or expired, takes its parcel back only while what it approved is still its
     *       own (audit round 2, M2): another return may have claimed those units since - its claim fell to what came
     *       back, 0 - and the parcel would then be refused by the line check when its goods are taken, the receipt
     *       stuck and nobody refunded. The goods are received on that other return instead ({@code claimedBy}). Once the
     *       draft is open, it claims them again ({@link ReturnLine#claimedUnits(ReturnStatus, boolean)}), so no return
     *       takes them while the parcel is counted.</li>
     * </ul>
     * {@code tg_return_request_draft_opened} says the same.
     *
     * @param others what the order's other returns claim ({@link ReturnClaims#ofOthers})
     * @throws BusinessException as {@link #draftReceiptOpened(UUID)}; {@code RETURN_NOT_AWAITING_GOODS} for a CLOSED
     *         order, or units claimed by another return ({@code details.claimedBy})
     */
    public void draftReceiptOpened(UUID receiptId, Order order, ReturnClaims others, Collection<ReturnRequest> all) {
        Objects.requireNonNull(order, "order");
        if (receiptId != null && receiptId.equals(openDraftReceiptId)) {
            return;
        }
        String refusal = receiptRefusal(order, others);
        if (refusal != null) {
            // Only a claim refusal names where the goods go instead; a status or a closed order names nobody.
            boolean claimed = receiptOpenable() && order.status() != OrderStatus.CLOSED;
            List<String> holders = all == null || !claimed ? List.<String>of() : claimHolders(all);
            throw new BusinessException(ErrorCode.RETURN_NOT_AWAITING_GOODS, refusal, List.of(),
                    holders.isEmpty() ? Map.of("returnStatus", status.name())
                            : Map.of("returnStatus", status.name(), "claimedBy", holders));
        }
        draftReceiptOpened(receiptId);
    }

    /**
     * The open returns of {@code all} claiming a line this one claims too: where goods of a withdrawn or expired return
     * whose units they took are received instead (audit round 2, M2).
     */
    public List<String> claimHolders(Collection<ReturnRequest> all) {
        return all.stream()
                .filter(other -> !other.id().equals(id) && other.status().isOpen())
                .filter(other -> other.lines().stream().anyMatch(l -> lines.stream()
                        .anyMatch(mine -> mine.orderLineId().equals(l.orderLineId()))))
                .map(ReturnRequest::rmaNumber).sorted().toList();
    }

    /**
     * Why goods may not be received for this return now, beyond its own status ({@link #receiptOpenable()}): its order
     * is CLOSED, or - withdrawn after approval or expired - what it approved is claimed by another return. Null when they
     * may; the receipt screens read it through {@code ReturnRequestLookup} so they say it before a draft is opened.
     */
    public String receiptRefusal(Order order, ReturnClaims others) {
        if (!receiptOpenable()) {
            return ("Return %s is %s: its goods are received while it is approved and its refund not settled; anything "
                    + "else that came back is kept in custody on the return request").formatted(rmaNumber, status);
        }
        if (order.status() == OrderStatus.CLOSED) {
            return ("Order %s is closed: goods of return %s arriving now are kept in custody on the return request")
                    .formatted(orderNumber, rmaNumber);
        }
        if (status != ReturnStatus.CANCELLED && status != ReturnStatus.EXPIRED) {
            return null;
        }
        OrderShipment shipment = shipmentId == null ? null : order.shipments().stream()
                .filter(s -> s.id().equals(shipmentId)).findFirst().orElse(null);
        Map<UUID, Integer> inAll = others.inAll();
        Map<UUID, Integer> onShipment = others.onShipment(shipmentId);
        for (ReturnLine line : lines) {
            OrderLine ordered = order.findLine(line.orderLineId()).orElse(null);
            if (ordered == null) {
                continue;
            }
            LineAllowance allowance = LineAllowance.of(ordered, shipment, inAll, onShipment, Set.of());
            int own = line.claimedUnits(ReturnStatus.RECEIVED);
            boolean free = own <= allowance.delivered() - allowance.claimed()
                    && (allowance.onShipment() == null || own <= allowance.onShipment() - allowance.claimedOnShipment());
            if (!free) {
                return ("Return %s is %s and %s's units it approved are claimed by another return since: receive the "
                        + "goods on that return, or keep them in custody").formatted(rmaNumber, status, line.sku());
            }
        }
        return null;
    }

    /**
     * The draft receipt {@code receiptId} was confirmed or cancelled: the return no longer waits for it. A receipt that
     * is not the open draft (told twice, or one of before WF-05 BE-23) changes nothing.
     *
     * @return whether it was the open draft
     */
    public boolean draftReceiptClosed(UUID receiptId) {
        if (receiptId == null || !receiptId.equals(openDraftReceiptId)) {
            return false;
        }
        this.openDraftReceiptId = null;
        return true;
    }

    private void requireNoDraftReceipt(String what) {
        if (openDraftReceiptId != null) {
            throw new BusinessException(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN, ("A parcel of return %s is still being "
                    + "counted on a return receipt: confirm or cancel it before the return can %s").formatted(rmaNumber,
                    what), List.of(), Map.of("receiptId", openDraftReceiptId));
        }
    }

    // ================================================================================================= custody

    /**
     * Sales record what the customer chose for their goods (WF-05 Q-4: "Sale hỏi khách, ghi lại một trong hai: gửi trả
     * (khách chịu phí), hoặc khách đồng ý để công ty huỷ hộ (biên bản)"): what came back sealed and the refund did not
     * take back is the customer's, held for them in the quarantine area; the warehouse manager carries out exactly this.
     * A destruction on their behalf rests on their written consent, which {@code note} names. Recorded again, the new
     * choice replaces the old while nothing was carried out (the warehouse says so, {@code CartonReturns#instructCustody}).
     *
     * <p>Only once the refund is decided - before, no carton is the customer's yet. Whether one is held is the
     * cartons' to say ({@code CartonReturns#instructCustody}, {@code tg_return_request_custody_held}): since audit round
     * 3 (RAD-1) the goods are settled per SKU, so a return that refunded units may hold another SKU's cartons, or whole
     * cartons beyond the refunded units, for the customer. {@code ck_return_request_custody_choice} says the rest.</p>
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} for a return not RESOLVED; {@code VALIDATION_FAILED}
     *         with no choice, or a destruction with no record
     */
    public void recordCustodyChoice(ReturnCustodyChoice choice, String note, UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        if (status != ReturnStatus.RESOLVED) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, ("Return %s is %s: the goods are the "
                    + "customer's to decide on only once its refund is decided").formatted(rmaNumber, status));
        }
        if (choice == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say what the customer chose: SEND_BACK or "
                    + "DESTROY_ON_BEHALF");
        }
        String text = limited(note, REASON_LIMIT, "note");
        if (choice == ReturnCustodyChoice.DESTROY_ON_BEHALF && text == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Goods of return %s are destroyed on the "
                    + "customer's behalf only with their written consent: name the record").formatted(rmaNumber));
        }
        this.custodyChoice = choice;
        this.custodyChoiceNote = text;
        this.custodyChoiceBy = by;
        this.custodyChoiceAt = now;
    }

    /**
     * Sales record what the customer chose for the goods of custody note {@code evidenceId} (WF-05 Q-11 hỏi thêm: "Sale
     * chọn: trả lại khách / mở yêu cầu bổ sung / khách đồng ý huỷ hộ (biên bản)"; audit round 3, RAD-3): handed back,
     * destroyed on their behalf with their written consent ({@code note} names it), or taken on the supplementary return
     * {@code takenBy} of the same customer. Whatever the return's status: such goods turn up at any time. Recorded
     * again, the new choice replaces the old while the warehouse has not carried it out.
     *
     * @param takenBy the supplementary return, for TAKEN_BY_RETURN only: one that can still receive goods - asked for
     *                (REQUESTED: its approval comes next) or approved and still taking parcels ({@link #receiptOpenable};
     *                confirmation audit CF-5). One rejected, withdrawn, expired or resolved will receive no parcel, and
     *                naming it left the goods with a note saying they left on a return that never took them
     * @throws BusinessException {@code NOT_FOUND} for no custody note of this return by that id;
     *         {@code RETURN_INVALID_TRANSITION} once the goods left, or for a supplementary return that takes nothing;
     *         {@code VALIDATION_FAILED} with no choice, a destruction with no record, or a supplementary return missing,
     *         not wanted, this one or another customer's
     */
    public void chooseForCustody(UUID evidenceId, ReturnCustodyNoteChoice choice, String note, ReturnRequest takenBy,
                                 UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        custodyNote(evidenceId);
        CustodyOutcome before = custodyOutcomes.get(evidenceId);
        if (before != null && before.ended()) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, ("The goods of custody note %s of %s were "
                    + "%s already").formatted(evidenceId, rmaNumber, before.choice()));
        }
        if (choice == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Say what the customer chose: SEND_BACK, DESTROY_ON_BEHALF or TAKEN_BY_RETURN");
        }
        String text = limited(note, REASON_LIMIT, "note");
        if (choice == ReturnCustodyNoteChoice.DESTROY_ON_BEHALF && text == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Goods kept for the customer of %s are destroyed "
                    + "on their behalf only with their written consent: name the record").formatted(rmaNumber));
        }
        if ((choice == ReturnCustodyNoteChoice.TAKEN_BY_RETURN) != (takenBy != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, takenBy == null
                    ? "Name the supplementary return that takes the goods"
                    : "Only goods taken on a supplementary return name one");
        }
        if (takenBy != null) {
            if (takenBy.id().equals(id) || !takenBy.customerId().equals(customerId)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("The goods kept on %s are taken on another "
                        + "return of the same customer").formatted(rmaNumber));
            }
            if (!canTakeGoods(takenBy)) {
                throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, "Return %s is %s: it takes no goods"
                        .formatted(takenBy.rmaNumber(), takenBy.status()));
            }
        }
        CustodyOutcome chosen = new CustodyOutcome(evidenceId, choice, text, takenBy == null ? null : takenBy.id(),
                takenBy == null ? null : takenBy.rmaNumber(), by, now, null, null, null);
        custodyOutcomes.put(evidenceId, chosen);
        changedCustodyOutcomes.add(chosen);
    }

    /**
     * The warehouse carried out the customer's choice for the goods of custody note {@code evidenceId}: handed back,
     * destroyed, or received on the supplementary return; {@code note} is the record of it (the hand-over note, the
     * destruction record, the receipt). Only as chosen - the goods are the customer's, and only they decide.
     *
     * @param takenByStatus the supplementary return's status now, for TAKEN_BY_RETURN; null otherwise
     * @param takenByGot    for TAKEN_BY_RETURN, whether the goods came back on the supplementary return: the carton the
     *                      note names was received on it, or - a note naming a SKU - it received at least the note's
     *                      units of that SKU ({@link #receivedGoodsOf}). Ended before that, the note said the goods left
     *                      while they still stood in the warehouse, final for good (confirmation audit CF-5)
     * @throws BusinessException {@code NOT_FOUND}; {@code RETURN_CUSTODY_CHOICE_REQUIRED} while no choice is recorded;
     *         {@code RETURN_INVALID_TRANSITION} once ended, when the supplementary return no longer takes goods
     *         (Sales choose again), or before it received them; {@code VALIDATION_FAILED} with no record
     */
    public CustodyOutcome endCustody(UUID evidenceId, String note, ReturnStatus takenByStatus, boolean takenByGot,
                                     UUID by, Instant now) {
        Objects.requireNonNull(by, "by");
        custodyNote(evidenceId);
        CustodyOutcome chosen = custodyOutcomes.get(evidenceId);
        if (chosen == null) {
            throw new BusinessException(ErrorCode.RETURN_CUSTODY_CHOICE_REQUIRED, ("Sales have not recorded what the "
                    + "customer chose for the goods of custody note %s of %s").formatted(evidenceId, rmaNumber));
        }
        if (chosen.ended()) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, ("The goods of custody note %s of %s were "
                    + "%s already").formatted(evidenceId, rmaNumber, chosen.choice()));
        }
        if (chosen.choice() == ReturnCustodyNoteChoice.TAKEN_BY_RETURN && takesNothing(takenByStatus)) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, ("Return %s is %s and took no goods: Sales "
                    + "record the customer's choice again").formatted(chosen.takenByRmaNumber(), takenByStatus));
        }
        if (chosen.choice() == ReturnCustodyNoteChoice.TAKEN_BY_RETURN && !takenByGot) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, ("The goods of custody note %s of %s have "
                    + "not come back on return %s yet: they are ended once its receipt takes them")
                    .formatted(evidenceId, rmaNumber, chosen.takenByRmaNumber()));
        }
        String record = required(note, REASON_LIMIT, "Name the record of the hand-over, destruction or receipt");
        CustodyOutcome ended = new CustodyOutcome(evidenceId, chosen.choice(), chosen.choiceNote(),
                chosen.takenByReturnId(), chosen.takenByRmaNumber(), chosen.chosenBy(), chosen.chosenAt(), now, by,
                record);
        custodyOutcomes.put(evidenceId, ended);
        changedCustodyOutcomes.add(ended);
        return ended;
    }

    /**
     * Whether {@code takenBy} can still receive the goods of a custody note: asked for, or approved and still taking
     * parcels ({@link #receiptOpenable}) - and neither rejected, withdrawn nor expired ({@link #takesNothing}).
     * {@code check_return_custody_outcome} says the same.
     */
    private static boolean canTakeGoods(ReturnRequest takenBy) {
        return !takesNothing(takenBy.status())
                && (takenBy.status() == ReturnStatus.REQUESTED || takenBy.receiptOpenable());
    }

    /**
     * Whether the goods {@code custody} names came back on this return (confirmation audit CF-5), by the units of their
     * SKU its receipts counted: at least the note's. A note naming a carton is judged by the carton, which only
     * inventory knows ({@code ReturnCustodyNotes}); {@code check_return_custody_outcome} says the same.
     */
    public boolean receivedGoodsOf(ReturnEvidence.Custody custody) {
        if (custody == null || custody.sku() == null) {
            return false;
        }
        int received = lines.stream().filter(line -> line.sku().equals(custody.sku()))
                .mapToInt(line -> line.receivedQuantity() == null ? 0 : line.receivedQuantity()).sum();
        return received >= custody.quantity();
    }

    /** A rejected, withdrawn or expired return takes no goods; one not known (null) neither. */
    private static boolean takesNothing(ReturnStatus status) {
        return status == null || status == ReturnStatus.REJECTED || status == ReturnStatus.CANCELLED
                || status == ReturnStatus.EXPIRED;
    }

    private ReturnEvidence custodyNote(UUID evidenceId) {
        return evidence.stream().filter(e -> e.id().equals(evidenceId) && e.stage() == ReturnEvidence.Stage.CUSTODY)
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "No custody note %s on return %s".formatted(evidenceId, rmaNumber)));
    }

    /** The outcome of custody note {@code evidenceId}; empty until Sales record the customer's choice. */
    public Optional<CustodyOutcome> custodyOutcome(UUID evidenceId) {
        return Optional.ofNullable(custodyOutcomes.get(evidenceId));
    }

    /** How a RESOLVED return ended, for the screens (WF-05 Q-4): null while it is not RESOLVED. */
    public enum Outcome {
        /** Units were refunded - worth something, or nothing on an order with nothing paid. */
        REFUNDED,
        /** Goods came back usable and none of them was refunded, for a reason ({@link #refusalReason}). */
        REFUSED,
        /** Nothing approved came back usable (WF-05 TH-4): there was nothing to refund. */
        NOTHING_TO_REFUND
    }

    /** How the return ended; null until it is RESOLVED. */
    public Outcome outcome() {
        return outcomeOf(status, lines.stream().anyMatch(line -> line.refundQuantity() > 0), refusalReason);
    }

    /** {@link #outcome} from what a list row holds: whether units were refunded, and the refusal reason. */
    public static Outcome outcomeOf(ReturnStatus status, boolean unitsRefunded, ReturnRefusalReason refusal) {
        if (status != ReturnStatus.RESOLVED) {
            return null;
        }
        return unitsRefunded ? Outcome.REFUNDED : refusal != null ? Outcome.REFUSED : Outcome.NOTHING_TO_REFUND;
    }

    // ================================================================================================= reading

    /** Whether the customer of {@code customer} owns this return (a portal caller's own). */
    public boolean belongsTo(UUID customer) {
        return customerId.equals(customer);
    }

    public Optional<ReturnLine> findLine(UUID lineId) {
        return lines.stream().filter(l -> l.id().equals(lineId)).findFirst();
    }

    private ReturnLine line(UUID lineId) {
        return findLine(lineId).orElseThrow(() -> new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                "Line %s is not a line of return %s".formatted(lineId, rmaNumber)));
    }

    /** Status changes since the request was loaded, oldest first; the repository writes them as history, once. */
    public List<ReturnTransition> pullTransitions() {
        List<ReturnTransition> drained = List.copyOf(transitions);
        transitions.clear();
        return drained;
    }

    /** Photos added since the request was loaded; the repository inserts them, once. */
    public List<ReturnEvidence> pullNewEvidence() {
        List<ReturnEvidence> drained = List.copyOf(newEvidence);
        newEvidence.clear();
        return drained;
    }

    /** Discrepancies recorded since the request was loaded; the repository inserts them, once. */
    public List<ReturnDiscrepancy> pullNewDiscrepancies() {
        List<ReturnDiscrepancy> drained = List.copyOf(newDiscrepancies);
        newDiscrepancies.clear();
        return drained;
    }

    /** The custody outcomes chosen or ended since the last save, once. */
    public List<CustodyOutcome> pullChangedCustodyOutcomes() {
        List<CustodyOutcome> changed = List.copyOf(changedCustodyOutcomes);
        changedCustodyOutcomes.clear();
        return changed;
    }

    /** Parcels taken since the request was loaded; the repository inserts them, once. */
    public List<ReturnParcel> pullNewParcels() {
        List<ReturnParcel> drained = List.copyOf(newParcels);
        newParcels.clear();
        return drained;
    }

    // ================================================================================================= helpers

    private void requireStatus(ReturnStatus expected, String what) {
        if (status != expected) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "Return %s is %s and cannot %s".formatted(rmaNumber, status, what));
        }
    }

    private void close(ReturnStatus target, UUID by, String why, Instant now) {
        transitionTo(target, by, why, now);
        this.closedAt = now;
    }

    private void transitionTo(ReturnStatus target, UUID by, String why, Instant at) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "Return %s cannot move from %s to %s".formatted(rmaNumber, status, target));
        }
        String text = why == null || why.length() <= REASON_LIMIT ? why : why.substring(0, REASON_LIMIT);
        transitions.add(new ReturnTransition(status, target, by, text, at));
        this.status = target;
    }

    private static String trimmed(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String limited(String text, int limit, String what) {
        String value = trimmed(text);
        if (value != null && value.length() > limit) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The %s is at most %d characters".formatted(what, limit));
        }
        return value;
    }

    private static String required(String text, int limit, String message) {
        String value = limited(text, limit, "reason");
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
        return value;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ================================================================================================= rehydration

    /** Rehydration of the review and of the goods coming back. */
    public ReturnRequest restoreProgress(UUID decidedBy, Instant decidedAt, String decisionNote, String rejectionReason,
                                         Boolean returnRequired, Instant returnDeadline, UUID receivedBy,
                                         Instant receivedAt, String receivingLocation, UUID goodsReceiptId) {
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
        this.rejectionReason = rejectionReason;
        this.returnRequired = returnRequired;
        this.returnDeadline = returnDeadline;
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
        this.receivingLocation = receivingLocation;
        this.goodsReceiptId = goodsReceiptId;
        return this;
    }

    /** Rehydration of the refund and the end. */
    public ReturnRequest restoreResolution(ReturnResolution moneyMode, BigDecimal settlementAmount, UUID proposedBy,
                                           Instant proposedAt, String resolutionNote, UUID settledBy, Instant settledAt,
                                           MoneyOutcome money, boolean vatAdjustmentRequired, UUID cancelledBy,
                                           String cancellationReason, Instant closedAt) {
        this.moneyMode = moneyMode;
        this.settlementAmount = settlementAmount == null ? BigDecimal.ZERO : settlementAmount;
        this.proposedBy = proposedBy;
        this.proposedAt = proposedAt;
        this.resolutionNote = resolutionNote;
        this.settledBy = settledBy;
        this.settledAt = settledAt;
        this.money = money == null ? MoneyOutcome.NONE : money;
        this.vatAdjustmentRequired = vatAdjustmentRequired;
        this.cancelledBy = cancelledBy;
        this.cancellationReason = cancellationReason;
        this.closedAt = closedAt;
        return this;
    }

    /** Rehydration of the refusal (WF-05 Q-4) and of the draft receipt counting a parcel now (WF-05 BE-23). */
    public ReturnRequest restoreRefusal(ReturnRefusalReason refusalReason, String refusalNote, UUID openDraftReceiptId) {
        this.refusalReason = refusalReason;
        this.refusalNote = refusalNote;
        this.openDraftReceiptId = openDraftReceiptId;
        return this;
    }

    /** Rehydration of the customer's choice for goods held for them (WF-05 Q-4). */
    public ReturnRequest restoreCustodyChoice(ReturnCustodyChoice choice, String note, UUID by, Instant at) {
        this.custodyChoice = choice;
        this.custodyChoiceNote = note;
        this.custodyChoiceBy = by;
        this.custodyChoiceAt = at;
        return this;
    }

    /** Rehydration of how the custody notes' goods end (audit round 3, RAD-3). */
    public ReturnRequest restoreCustodyOutcomes(Collection<CustodyOutcome> outcomes) {
        custodyOutcomes.clear();
        outcomes.forEach(outcome -> custodyOutcomes.put(outcome.evidenceId(), outcome));
        return this;
    }

    /** Rehydration of the delivered shipment the return is against; null for an order delivered without shipments. */
    public ReturnRequest restoreShipment(UUID shipmentId) {
        this.shipmentId = shipmentId;
        return this;
    }

    /** Rehydration of the photos and the discrepancies, as stored. */
    public ReturnRequest restoreRecords(Collection<ReturnEvidence> photos, Collection<ReturnDiscrepancy> found) {
        evidence.clear();
        evidence.addAll(photos);
        discrepancies.clear();
        discrepancies.addAll(found);
        return this;
    }

    /** Rehydration of the receipts that brought the goods back, the first first. */
    public ReturnRequest restoreParcels(Collection<ReturnParcel> taken) {
        parcels.clear();
        parcels.addAll(taken);
        return this;
    }

    public UUID id() { return id; }
    public String rmaNumber() { return rmaNumber; }
    public UUID orderId() { return orderId; }
    public String orderNumber() { return orderNumber; }
    public UUID customerId() { return customerId; }
    public UUID requestId() { return requestId; }
    public ReturnChannel channel() { return channel; }
    public ReturnReason reason() { return reason; }
    public String description() { return description; }
    public ReturnResolution requestedResolution() { return requestedResolution; }
    public UUID requestedBy() { return requestedBy; }
    public Instant requestedAt() { return requestedAt; }
    public String currency() { return currency; }
    public List<ReturnLine> lines() { return java.util.Collections.unmodifiableList(lines); }
    public String windowOverride() { return windowOverride; }
    /** The delivered shipment the return is against; null for an order delivered without shipments. */
    public UUID shipmentId() { return shipmentId; }
    public long version() { return version; }
    public ReturnStatus status() { return status; }
    public UUID decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionNote() { return decisionNote; }
    public String rejectionReason() { return rejectionReason; }
    public Boolean returnRequired() { return returnRequired; }
    public Instant returnDeadline() { return returnDeadline; }
    public UUID receivedBy() { return receivedBy; }
    public Instant receivedAt() { return receivedAt; }
    /** The quarantine area the goods came back into, as the return receipt named it. */
    public String receivingLocation() { return receivingLocation; }
    /** The first return receipt that brought goods back; null until one was confirmed. */
    public UUID goodsReceiptId() { return goodsReceiptId; }
    /** Every return receipt that brought goods back, the first first (WF-05 TH-3). */
    public List<ReturnParcel> parcels() { return java.util.Collections.unmodifiableList(parcels); }
    public ReturnResolution moneyMode() { return moneyMode; }
    public BigDecimal settlementAmount() { return settlementAmount; }
    public UUID proposedBy() { return proposedBy; }
    public Instant proposedAt() { return proposedAt; }
    public String resolutionNote() { return resolutionNote; }
    public UUID settledBy() { return settledBy; }
    public Instant settledAt() { return settledAt; }
    public MoneyOutcome money() { return money; }
    public boolean vatAdjustmentRequired() { return vatAdjustmentRequired; }
    public UUID cancelledBy() { return cancelledBy; }
    public String cancellationReason() { return cancellationReason; }
    public Instant closedAt() { return closedAt; }
    /** Why goods that came back are withheld (WF-05 Q-4); null when nothing is. */
    public ReturnRefusalReason refusalReason() { return refusalReason; }
    /** The note the customer reads beside {@link #refusalReason}. */
    public String refusalNote() { return refusalNote; }
    /** The draft return receipt counting a parcel now (WF-05 BE-23); null when none. */
    public UUID openDraftReceiptId() { return openDraftReceiptId; }
    public ReturnCustodyChoice custodyChoice() { return custodyChoice; }
    public String custodyChoiceNote() { return custodyChoiceNote; }
    public UUID custodyChoiceBy() { return custodyChoiceBy; }
    public Instant custodyChoiceAt() { return custodyChoiceAt; }
    public List<ReturnEvidence> evidence() { return java.util.Collections.unmodifiableList(evidence); }
    public List<ReturnDiscrepancy> discrepancies() { return java.util.Collections.unmodifiableList(discrepancies); }
}
