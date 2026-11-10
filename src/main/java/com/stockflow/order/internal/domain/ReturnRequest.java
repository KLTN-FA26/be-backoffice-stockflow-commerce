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
 * after-sales claim on a delivered order: what the customer says is wrong, what Sales approved, what came back on the
 * return receipt, and what was refunded. The order is its own aggregate; the application service moves it alongside
 * (RETURN_REQUESTED while a return is open, RETURNED once goods are back, REFUNDED / PARTIALLY_REFUNDED once refunded
 * in full), under the order's row lock.
 *
 * <p>The goods always come back (17 BR-18): the warehouse receives them on a return receipt into quarantine
 * ({@code procurement}, 03 §4.2), and that receipt, confirmed, is what moves the return to RECEIVED
 * ({@link #goodsReceived}). What happens to the goods next is a warehouse manager's decision on each carton
 * (20 §4.8), not the return's. The return decides the money: Sales propose a refund, a second person approves it,
 * payment settles it against the order's installments (15 §4.5). There is no QC step, no replacement order and no
 * credit kept for the customer any more (team plan 4D-3; 15 BR-05).</p>
 *
 * <p>Invariants it protects - each stated again as a {@code CHECK} ({@code V20261101020000}, {@code V20261114000100}):</p>
 * <ul>
 *   <li><b>BR-RMA-001</b>: opened only on a delivered order, within the return window after delivery (calendar days of
 *       Asia/Ho_Chi_Minh) - or outside it when Sales accept a late claim and say why;</li>
 *   <li><b>BR-RMA-002</b>: goods printed to the customer's design come back only for a named defect;</li>
 *   <li>never more units of a line than were delivered, counting what the line's other returns still claim, and one
 *       open return per order line;</li>
 *   <li>units only narrow: approved ≤ asked for, refunded ≤ approved and ≤ what came back;</li>
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

    /** One carton that came back on the return receipt: its SKU and the units in it. */
    public record ReturnedCarton(String sku, int quantity) {

        public ReturnedCarton {
            Objects.requireNonNull(sku, "sku");
            if (quantity < 1) {
                throw new IllegalArgumentException("A carton holds at least one unit");
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
    /** Children added since the request was loaded; drained by the repository, which only ever inserts them. */
    private final List<ReturnEvidence> newEvidence = new ArrayList<>();
    private final List<ReturnDiscrepancy> newDiscrepancies = new ArrayList<>();
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
     * The customer (or Sales for them) claims {@code claims} of {@code order} (17 §4.10 step 37: — → REQUESTED), at the
     * order's own prices.
     *
     * @param requested        what the customer would like; null reads as {@link ReturnResolution#REFUND}, the only
     *                         one there is
     * @param claimedElsewhere per order line, the units the line's other returns still claim
     * @param openElsewhere    the order lines that already have an open return
     * @param windowOverride   why Sales accept the claim outside the return window - goods that came late, a second
     *                         defect found after the window, goodwill; back office only, and approved by someone else
     *                         like every claim staff open. Null: the window applies
     * @throws BusinessException {@code ORDER_CANCELLED}, {@code RETURN_ORDER_NOT_DELIVERED},
     *         {@code RETURN_ORDER_NOT_RETURNABLE}, {@code RETURN_WINDOW_CLOSED} (BR-RMA-001);
     *         {@code RETURN_LINE_ALREADY_OPEN}; {@code RETURN_QUANTITY_EXCEEDED};
     *         {@code RETURN_CUSTOM_ITEM_NOT_DEFECTIVE} (BR-RMA-002); {@code RETURN_REASON_NOT_ACCEPTED} for a change of
     *         mind the policy refuses, and for a short delivery - nothing to send back, so no return;
     *         {@code RETURN_QUANTITIES_INVALID}, {@code VALIDATION_FAILED}
     */
    public static ReturnRequest open(String rmaNumber, UUID requestId, Order order, ReturnChannel channel,
                                     ReturnReason reason, String description, ReturnResolution requested,
                                     List<Claim> claims, Map<UUID, Integer> claimedElsewhere,
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
        requireReturnable(order, now, policy, channel, override);
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
     * BR-RMA-001: a delivered order, within the window after delivery - or, for Sales with a reason, after it. A further
     * claim on an order a return already refunded goes through Sales; a CLOSED order takes none (BR-05).
     */
    private static void requireReturnable(Order order, Instant now, ReturnPolicy policy, ReturnChannel channel,
                                          String override) {
        OrderStatus status = order.status();
        boolean sales = channel == ReturnChannel.BACK_OFFICE;
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
            // RETURNED as an order status is goods that came back undelivered (RTO), or a return being refunded.
            case DRAFT, PENDING_CONTRACT, PENDING_PAYMENT, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION, READY_TO_FULFILL,
                 PICKING, PARTIALLY_FULFILLED, PACKED, ON_HOLD, SHIPPED, DELIVERY_FAILED, RETURNED -> new BusinessException(
                    ErrorCode.RETURN_ORDER_NOT_DELIVERED,
                    "Order %s is %s: only a delivered order can be returned".formatted(order.orderNumber(), status));
        };
        if (refusal != null) {
            throw refusal;
        }
        if (order.deliveredAt() == null) {
            throw new BusinessException(ErrorCode.RETURN_ORDER_NOT_DELIVERED,
                    "Order %s has no delivery recorded".formatted(order.orderNumber()));
        }
        if (override == null && !policy.windowClosesAt(order.deliveredAt()).isAfter(now)) {
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
     * The approved goods did not arrive by the deadline (AWAITING_RETURN → EXPIRED), as the nightly sweep finds it.
     * Goods a return receipt already opened for it brings in anyway ({@link #goodsReceived}).
     *
     * @return whether it expired; false while the deadline has not passed, or once the return moved on
     */
    public boolean expire(Instant now) {
        if (status != ReturnStatus.AWAITING_RETURN || returnDeadline == null || returnDeadline.isAfter(now)) {
            return false;
        }
        close(ReturnStatus.EXPIRED, null, "RETURN_DEADLINE_PASSED", now);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnClosed(id, rmaNumber, orderId, orderNumber, customerId,
                "EXPIRED", false, null, now)));
        return true;
    }

    // ================================================================================================= goods back

    /**
     * The goods came back (17 §4.10 step 39; 03 §4.2 step 12): the warehouse confirmed return receipt
     * {@code receiptId}, which scanned {@code cartons} of the order into the quarantine area {@code location}. Each line
     * counts the units of its SKU among them; fewer or more than approved, and a carton of a SKU the return does not
     * claim, are recorded as discrepancies for Sales to follow up (03 edge cases: the receipt is confirmed with the
     * cartons that came, the rest is settled outside the system). AWAITING_RETURN → RECEIVED; an EXPIRED return, or one
     * withdrawn after approval, is brought back too: the goods are here, and a return receipt was opened for them while
     * the return awaited them.
     *
     * @return false when this receipt was taken already (the event delivered again): nothing changes
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} for a return that does not take goods now, or took
     *         them on another receipt
     */
    public boolean goodsReceived(UUID receiptId, String receiptNumber, String location, List<ReturnedCarton> cartons,
                                 UUID by, Instant now) {
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(by, "by");
        if (receiptId.equals(goodsReceiptId)) {
            return false;
        }
        boolean reopening = status == ReturnStatus.EXPIRED
                || status == ReturnStatus.CANCELLED && Boolean.TRUE.equals(returnRequired) && decidedAt != null;
        if (goodsReceiptId != null || status != ReturnStatus.AWAITING_RETURN && !reopening) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION, goodsReceiptId != null
                    ? "Return %s took its goods on another return receipt".formatted(rmaNumber)
                    : "Return %s is %s and takes no goods now".formatted(rmaNumber, status));
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
        List<ReturnDiscrepancy> found = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        for (ReturnLine line : lines) {
            claimed.add(line.sku());
            int units = bySku.getOrDefault(line.sku(), 0);
            int approvedUnits = line.approvedQuantity() == null ? 0 : line.approvedQuantity();
            line.receive(units);
            if (units < approvedUnits) {
                found.add(new ReturnDiscrepancy(Identifiers.newId(), line.id(), ReturnDiscrepancy.Kind.SHORT,
                        line.sku(), approvedUnits - units, "Receipt %s".formatted(receiptNumber), by, now));
            } else if (units > approvedUnits) {
                found.add(new ReturnDiscrepancy(Identifiers.newId(), line.id(), ReturnDiscrepancy.Kind.EXCESS,
                        line.sku(), units - approvedUnits, "Receipt %s".formatted(receiptNumber), by, now));
            }
        }
        bySku.forEach((sku, units) -> {
            if (!claimed.contains(sku)) {
                found.add(new ReturnDiscrepancy(Identifiers.newId(), null, ReturnDiscrepancy.Kind.UNEXPECTED_ITEM,
                        sku, units, "Receipt %s: a SKU the return does not claim".formatted(receiptNumber), by, now));
            }
        });
        discrepancies.addAll(found);
        newDiscrepancies.addAll(found);
        this.receivedBy = by;
        this.receivedAt = now;
        this.receivingLocation = at;
        this.goodsReceiptId = receiptId;
        String why = reopening
                ? (status == ReturnStatus.EXPIRED ? "RECEIVED_AFTER_DEADLINE" : "RECEIVED_AFTER_WITHDRAWAL")
                : found.isEmpty() ? "RECEIVED" : "RECEIVED_WITH_DISCREPANCY";
        if (reopening) {
            this.closedAt = null;
            this.cancelledBy = null;
            this.cancellationReason = null;
        }
        transitionTo(ReturnStatus.RECEIVED, by, why + " " + receiptNumber, now);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnReceived(id, rmaNumber, orderId, orderNumber,
                customerId, at, lines.stream().map(l -> new ReturnReceived.Line(l.orderLineId(), l.sku(), l.sku(),
                        l.approvedQuantity() == null ? 0 : l.approvedQuantity(),
                        l.receivedQuantity() == null ? 0 : l.receivedQuantity())).toList(), !found.isEmpty(), by, now,
                1)));
        return true;
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
                    "Refund at least one unit of %s that came back".formatted(rmaNumber));
        }
        BigDecimal value = BigDecimal.ZERO;
        for (ReturnLine line : lines) {
            int count = refunded.getOrDefault(line.id(), 0);
            BigDecimal amount = count == 0 ? BigDecimal.ZERO : order.findLine(line.orderLineId())
                    .orElseThrow(() -> new IllegalStateException("Return %s names line %s that order %s does not have"
                            .formatted(rmaNumber, line.orderLineId(), order.orderNumber())))
                    .grossOf(count).amount();
            line.refund(count, amount);
            value = value.add(amount);
        }
        this.settlementAmount = value;
        this.moneyMode = ReturnResolution.REFUND;
        this.proposedBy = by;
        this.proposedAt = now;
        this.resolutionNote = limited(note, NOTE_LIMIT, "note");
        transitionTo(ReturnStatus.SETTLEMENT_PENDING, by, "REFUND_PROPOSED %s %s".formatted(plain(value), currency),
                now);
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
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnResolved(id, rmaNumber, orderId, orderNumber,
                customerId, null, null, booked.offset(), booked.refunded(), BigDecimal.ZERO, currency,
                vatAdjustmentRequired, lines.stream().map(l -> new ReturnResolved.Line(l.orderLineId(), l.sku(),
                        l.receivedQuantity() == null ? 0 : l.receivedQuantity(), 0, l.refundQuantity(),
                        l.refundNetAmount())).toList(), by, now, orderId)));
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

    /** Rehydration of the photos and the discrepancies, as stored. */
    public ReturnRequest restoreRecords(Collection<ReturnEvidence> photos, Collection<ReturnDiscrepancy> found) {
        evidence.clear();
        evidence.addAll(photos);
        discrepancies.clear();
        discrepancies.addAll(found);
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
    /** The return receipt that brought the goods back; null until it was confirmed. */
    public UUID goodsReceiptId() { return goodsReceiptId; }
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
