package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ReturnApproved;
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

    private final List<ReturnEvidence> evidence = new ArrayList<>();
    private final List<ReturnDiscrepancy> discrepancies = new ArrayList<>();
    /** The confirmed return receipts that brought its goods, the first first (WF-05 TH-3). */
    private final List<ReturnParcel> parcels = new ArrayList<>();
    /** Children added since the request was loaded; drained by the repository, which only ever inserts them. */
    private final List<ReturnEvidence> newEvidence = new ArrayList<>();
    private final List<ReturnDiscrepancy> newDiscrepancies = new ArrayList<>();
    private final List<ReturnParcel> newParcels = new ArrayList<>();
    private final List<ReturnTransition> transitions = new ArrayList<>();

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
        requireReturnable(order, shipment, now, policy, channel, override);
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
            if (openElsewhere != null && openElsewhere.contains(line.id())) {
                throw new BusinessException(ErrorCode.RETURN_LINE_ALREADY_OPEN,
                        "Line %s of order %s already has an open return".formatted(line.sku().code(),
                                order.orderNumber()));
            }
            int delivered = line.quantity() - line.cancelledQuantity();
            int claimed = claimedElsewhere == null ? 0 : claimedElsewhere.getOrDefault(line.id(), 0);
            if (claim.quantity() > delivered - claimed) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITY_EXCEEDED,
                        "%s: %d unit(s) delivered, %d already claimed by other returns; %d cannot be returned"
                                .formatted(line.sku().code(), delivered, claimed, claim.quantity()));
            }
            if (shipment != null) {
                int onShipment = shipment.quantities().getOrDefault(line.id(), 0);
                int claimedThere = claimedOnShipment == null ? 0 : claimedOnShipment.getOrDefault(line.id(), 0);
                if (claim.quantity() > onShipment - claimedThere) {
                    throw new BusinessException(ErrorCode.RETURN_QUANTITY_EXCEEDED, ("%s: %d unit(s) delivered on "
                            + "this shipment, %d already claimed by its other returns; %d cannot be returned")
                            .formatted(line.sku().code(), onShipment, claimedThere, claim.quantity()));
                }
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
     * delivery; or, for Sales with a reason, after it. A further claim on an order a return already refunded goes
     * through Sales; a CLOSED order takes none (BR-05).
     *
     * <p>While batches are still to come the order is PARTIALLY_FULFILLED, SHIPPED, DELIVERY_FAILED, ON_HOLD - or
     * PICKING / PACKED for the next batch - and a delivered shipment of it is returnable all the same (WF-05 TH-1:
     * Điều 313, each delivery is dealt with on its own). Without a delivered shipment those statuses still mean nothing
     * was received.</p>
     */
    private static void requireReturnable(Order order, OrderShipment shipment, Instant now, ReturnPolicy policy,
                                          ReturnChannel channel, String override) {
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
            case REFUNDED, PARTIALLY_REFUNDED -> sales ? null : new BusinessException(
                    ErrorCode.RETURN_ORDER_NOT_RETURNABLE,
                    "Order %s was already settled by a return: ask Sales to open a further claim"
                            .formatted(order.orderNumber()));
            case CLOSED -> new BusinessException(ErrorCode.RETURN_ORDER_NOT_RETURNABLE,
                    "Order %s is closed for good (BR-05): it takes no return".formatted(order.orderNumber()));
            // Batches still to come: one already delivered is returned on its own (WF-05 TH-1).
            case PICKING, PARTIALLY_FULFILLED, PACKED, ON_HOLD, SHIPPED, DELIVERY_FAILED -> shipment == null
                    ? notDelivered : null;
            // RETURNED as an order status is goods that came back undelivered (RTO), or a return being refunded.
            case DRAFT, PENDING_CONTRACT, PENDING_PAYMENT, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION, READY_TO_FULFILL,
                 RETURNED -> notDelivered;
        };
        if (refusal != null) {
            throw refusal;
        }
        // Điều 318: the period runs from the delivery the goods came on.
        Instant deliveredAt = shipment != null ? shipment.outcomeAt() : order.deliveredAt();
        if (deliveredAt == null) {
            throw new BusinessException(ErrorCode.RETURN_ORDER_NOT_DELIVERED,
                    "Order %s has no delivery recorded".formatted(order.orderNumber()));
        }
        if (override == null && !policy.windowClosesAt(deliveredAt).isAfter(now)) {
            throw windowClosed(order, policy, sales);
        }
    }

    private static BusinessException windowClosed(Order order, ReturnPolicy policy, boolean sales) {
        return new BusinessException(ErrorCode.RETURN_WINDOW_CLOSED,
                "Order %s was delivered more than %d day(s) ago (BR-RMA-001)%s".formatted(order.orderNumber(),
                        policy.window().toDays(), sales ? "; say why to accept a late claim" : ""));
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
     * inspection to photograph.
     */
    public void requireEvidenceAllowed(ReturnEvidence.Stage stage, ReturnPolicy policy) {
        boolean open = switch (Objects.requireNonNull(stage, "stage")) {
            case CLAIM -> status == ReturnStatus.REQUESTED || status == ReturnStatus.AWAITING_RETURN;
            case RECEIPT, INSPECTION -> false;
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
     * The customer (or Sales for them) withdraws the return before the goods are back: REQUESTED or AWAITING_RETURN →
     * CANCELLED. Goods a return receipt already opened for it brings in anyway ({@link #goodsReceived}).
     */
    public void cancel(UUID by, Instant now, String why, boolean byCustomer) {
        Objects.requireNonNull(by, "by");
        if (status != ReturnStatus.REQUESTED && status != ReturnStatus.AWAITING_RETURN) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "Return %s is %s: it is withdrawn only before its goods are back".formatted(rmaNumber, status));
        }
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
        if (anyParcel()) {
            // Recorded under who took the last parcel in: the sweep is nobody, and a discrepancy names its recorder.
            recordDiscrepancies(shortfalls(receivedBy, now, "Goods deadline passed"));
            transitionTo(ReturnStatus.RECEIVED, null, "GOODS_DEADLINE_PASSED", now);
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
     *   <li>Its goods back already (RECEIVED, SETTLEMENT_PENDING, RESOLVED): a parcel whose receipt was opened while the
     *       return awaited it and confirmed after Sales said the goods were complete. Its units are added and its
     *       discrepancies recorded; the status stays, and a refund proposed or made stays as decided. Refused instead,
     *       the event would be retried for ever over goods already in quarantine.</li>
     * </ul>
     * Units beyond those approved and cartons of a SKU the return does not claim are discrepancies of the parcel; units
     * short of those approved are one once no more is awaited - for Sales to follow up with the customer outside the
     * system (03 edge cases).
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
        Set<String> claimed = new HashSet<>();
        for (ReturnLine line : lines) {
            claimed.add(line.sku());
            int before = received(line);
            int total = before + bySku.getOrDefault(line.sku(), 0);
            int approvedUnits = approved(line);
            line.receive(total);
            int excess = Math.max(0, total - approvedUnits) - Math.max(0, before - approvedUnits);
            if (excess > 0) {
                found.add(new ReturnDiscrepancy(Identifiers.newId(), line.id(), ReturnDiscrepancy.Kind.EXCESS,
                        line.sku(), excess, source, by, now));
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
        // The first receipt stays the one the return names (it never changes, check_return_request_goods_receipt); who,
        // when and where follow the latest parcel.
        if (goodsReceiptId == null) {
            this.goodsReceiptId = receiptId;
        }
        this.receivedBy = by;
        this.receivedAt = now;
        this.receivingLocation = at;
        if (reopening) {
            found.addAll(shortfalls(by, now, source));
            String why = status == ReturnStatus.EXPIRED ? "RECEIVED_AFTER_DEADLINE" : "RECEIVED_AFTER_WITHDRAWAL";
            this.closedAt = null;
            this.cancelledBy = null;
            this.cancellationReason = null;
            transitionTo(ReturnStatus.RECEIVED, by, why + " " + receiptNumber, now);
        } else if (status == ReturnStatus.AWAITING_RETURN && lines.stream().allMatch(l -> received(l) >= approved(l))) {
            boolean clean = found.isEmpty() && discrepancies.isEmpty();
            transitionTo(ReturnStatus.RECEIVED, by, (clean ? "RECEIVED " : "RECEIVED_WITH_DISCREPANCY ")
                    + receiptNumber, now);
        }
        recordDiscrepancies(found);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnReceived(id, rmaNumber, orderId, orderNumber,
                customerId, at, lines.stream().map(l -> new ReturnReceived.Line(l.orderLineId(), l.sku(), l.sku(),
                        approved(l), received(l))).toList(), !found.isEmpty(), by, now, parcel)));
        return true;
    }

    /**
     * Sales say the goods of the return are all here (WF-05 TH-3): fewer than approved came back - the customer sent
     * what they had, or sent another SKU by mistake - and no more is coming. AWAITING_RETURN → RECEIVED with the units
     * the parcels brought, the units short recorded; the refund is then decided on those, or the return closed without
     * one. Without it such a return would wait for its deadline. A parcel still on its way is taken all the same
     * ({@link #goodsReceived}).
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} for a return not awaiting its goods, or one no parcel
     *         of which came back yet - nothing is here to decide on; withdraw it, or let it expire
     */
    public void markGoodsComplete(UUID by, Instant now, String note) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.AWAITING_RETURN, "have its goods marked complete");
        if (!anyParcel()) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, ("Nothing of return %s came back yet: "
                    + "wait for its goods, or withdraw it").formatted(rmaNumber));
        }
        String text = limited(note, REASON_LIMIT, "note");
        recordDiscrepancies(shortfalls(by, now, "Goods marked complete"));
        transitionTo(ReturnStatus.RECEIVED, by, text == null ? "GOODS_COMPLETE" : "GOODS_COMPLETE: " + text, now);
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
     * refunded - at most those approved and back - worth what the order line charged for them with its VAT
     * ({@link OrderLine#grossOf}). Every refund waits for a second person (RECEIVED → SETTLEMENT_PENDING).
     *
     * @param units per line of the return, the units refunded; null or empty refunds every unit approved and back, a
     *              line left out of a list refunds nothing. At least one unit is refunded: goods taken back for a defect
     *              Sales approved are made good, at least in part
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_QUANTITIES_INVALID}
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
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(order, "order");
        requireStatus(ReturnStatus.RECEIVED, "have a refund proposed");
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
        if (refunded.values().stream().mapToInt(Integer::intValue).sum() == 0) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                    lines.stream().allMatch(line -> line.refundableUnits() == 0)
                            ? "Nothing approved of %s came back usable: close it without a refund".formatted(rmaNumber)
                            : "Refund at least one unit of %s that came back".formatted(rmaNumber));
        }
        BigDecimal value = BigDecimal.ZERO;
        for (ReturnLine line : lines) {
            int count = refunded.getOrDefault(line.id(), 0);
            BigDecimal amount = count == 0 || nothingPaid ? BigDecimal.ZERO : order.findLine(line.orderLineId())
                    .orElseThrow(() -> new IllegalStateException("Return %s names line %s that order %s does not have"
                            .formatted(rmaNumber, line.orderLineId(), order.orderNumber())))
                    .grossOf(count).amount();
            line.refund(count, amount);
            value = value.add(amount);
        }
        this.settlementAmount = value;
        // ck_return_request_money_mode: a settlement of nothing has no money mode.
        this.moneyMode = value.signum() > 0 ? ReturnResolution.REFUND : null;
        this.proposedBy = by;
        this.proposedAt = now;
        this.resolutionNote = limited(note, NOTE_LIMIT, "note");
        transitionTo(ReturnStatus.SETTLEMENT_PENDING, by, "REFUND_PROPOSED %s %s%s".formatted(plain(value), currency,
                nothingPaid ? " NOTHING_PAID" : ""), now);
    }

    /**
     * Four eyes on every refund: approved by someone other than who proposed it. Checked before anything is settled,
     * so the refusal is the answer and not whatever a settlement would have failed with.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_SELF_APPROVAL}
     */
    public void requireSettlementApprover(UUID by) {
        requireStatus(ReturnStatus.SETTLEMENT_PENDING, "have its refund approved");
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
        lines.forEach(line -> line.refund(0, BigDecimal.ZERO));
        this.settlementAmount = BigDecimal.ZERO;
        this.moneyMode = null;
        this.proposedBy = null;
        this.proposedAt = null;
        this.resolutionNote = null;
        transitionTo(ReturnStatus.RECEIVED, by, "REFUND_SENT_BACK: " + text, now);
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
        this.vatAdjustmentRequired = settlementAmount.signum() > 0;
        close(ReturnStatus.RESOLVED, by, "RESOLVED_REFUND %s %s".formatted(plain(settlementAmount), currency), now);
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
     *         {@code RETURN_QUANTITIES_INVALID} while something approved came back and is to be refunded
     */
    public void closeWithoutRefund(UUID by, Instant now, String note) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.RECEIVED, "be closed without a refund");
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

    /** {@link ReturnResolved}: what came back of each line, what was refunded, and the delivery it was against. */
    private void announceResolved(UUID by, Instant now) {
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnResolved(id, rmaNumber, orderId, orderNumber,
                customerId, null, null, money.offset(), money.refunded(), BigDecimal.ZERO, currency,
                vatAdjustmentRequired, lines.stream().map(l -> new ReturnResolved.Line(l.orderLineId(), l.sku(),
                        l.receivedQuantity() == null ? 0 : l.receivedQuantity(), 0, l.refundQuantity(),
                        l.refundNetAmount())).toList(), by, now, orderId, shipmentId)));
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
    public List<ReturnEvidence> evidence() { return java.util.Collections.unmodifiableList(evidence); }
    public List<ReturnDiscrepancy> discrepancies() { return java.util.Collections.unmodifiableList(discrepancies); }
}
