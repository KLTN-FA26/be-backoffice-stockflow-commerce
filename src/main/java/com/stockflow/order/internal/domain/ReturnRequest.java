package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ReturnApproved;
import com.stockflow.contracts.ReturnClosed;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnRejected;
import com.stockflow.contracts.ReturnReplacementWithdrawn;
import com.stockflow.contracts.ReturnRequested;
import com.stockflow.contracts.ReturnResolved;
import com.stockflow.order.api.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * <b>A return request (RMA, "đổi trả"; SCRUM-243 / SCRUM-260, 03-state-machines §12).</b> The aggregate root of the
 * after-sales claim on a delivered order: what the customer says is wrong, what Sales approved, what the warehouse
 * counted in, what QC accepted, and how the accepted units were made good. The order is its own aggregate; the
 * application service moves it alongside (RETURN_REQUESTED while a return is open, then DELIVERED again or
 * (PARTIALLY_)REFUNDED, 03 §9), under the order's row lock.
 *
 * <p>Invariants it protects - each stated again as a {@code CHECK} in {@code V20261028020000}:</p>
 * <ul>
 *   <li><b>BR-RMA-001</b>: opened only on a delivered order, within the return window after delivery (calendar days of
 *       Asia/Ho_Chi_Minh) - or outside it when Sales accept a late claim and say why;</li>
 *   <li><b>BR-RMA-002</b>: goods printed to the customer's design come back only when defective - a change of mind is
 *       refused for them, and they never go back on sale (OQ-12); nor does anything QC has not called plain stock,
 *       since a design is not part of a SKU;</li>
 *   <li>never more units of a line than were delivered, counting what the line's other returns still claim, and one
 *       open return per order line;</li>
 *   <li>units only narrow: approved ≤ asked for, accepted ≤ approved; every unit received - in one parcel or several,
 *       on time or late - is accepted or rejected. Accepted units go back on sale or to scrap; rejected units are the
 *       customer's and go back to them, scrapped only with their recorded consent; every accepted unit is replaced or
 *       credited;</li>
 *   <li>a claim is approved on evidence (a photo of the defect, or of the delivery note for a short delivery), and the
 *       goods stay with the customer only below a value threshold or when there is nothing to send;</li>
 *   <li>four eyes on everything of value: a claim Sales opened is approved by another person, and every resolution -
 *       money, or free replacement goods - by someone other than who proposed it (BR-PAY-003, in the absence of
 *       approver limits); so is withdrawing a replacement for credit;</li>
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

    /** What arrived for one line: {@code quantity} units of {@code sku} (null: the line's own SKU), of lot {@code lotNumber}. */
    public record Receipt(UUID lineId, int quantity, String sku, String lotNumber) {
    }

    /** Something in the parcel that belongs to no line of the return, as the warehouse read it. */
    public record Unexpected(String sku, int quantity, String note) {
    }

    /**
     * QC's verdict on one line: of the units received, {@code accepted} hold the claim and {@code rejected} do not.
     * Accepted units go back on sale ({@code restock}, at {@code restockLocation}) or to scrap; rejected units are the
     * customer's and go back to them ({@code customerReturn}), any scrapped instead only with {@code disposalConsent}
     * (how and when the customer agreed). {@code plainStock}: QC saw the units going back on sale are plain goods with
     * no customer print; null takes it for the line's own SKU on a line that was not printed.
     */
    public record Inspection(UUID lineId, int accepted, int rejected, ReturnItemCondition condition, int restock,
                             int scrap, int customerReturn, String restockLocation, String note, Boolean plainStock,
                             String disposalConsent) {

        /** Without the plain-stock call and a disposal consent: the defaults above, nothing of the customer's scrapped. */
        public Inspection(UUID lineId, int accepted, int rejected, ReturnItemCondition condition, int restock, int scrap,
                          int customerReturn, String restockLocation, String note) {
            this(lineId, accepted, rejected, condition, restock, scrap, customerReturn, restockLocation, note, null,
                    null);
        }
    }

    /** Where withdrawing a resolved return's replacement order stands (SCRUM-260): proposed, approved, or sent back. */
    public enum WithdrawalStatus { PENDING, APPROVED, REJECTED }

    /** How one line's accepted units are made good: {@code replacement} new units, {@code credit} units of money. */
    public record Remedy(UUID lineId, int replacement, int credit) {
    }

    /**
     * What payment booked for the credited value (V20261028020100): set off against the order's receivables, refunded
     * (PENDING), kept as the customer's credit; and the ledger entry of the credit, when one was made.
     */
    public record MoneyOutcome(BigDecimal offset, BigDecimal refunded, BigDecimal credited, UUID creditTransferId) {

        public static final MoneyOutcome NONE = new MoneyOutcome(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null);

        public MoneyOutcome {
            offset = offset == null ? BigDecimal.ZERO : offset;
            refunded = refunded == null ? BigDecimal.ZERO : refunded;
            credited = credited == null ? BigDecimal.ZERO : credited;
        }

        public BigDecimal total() {
            return offset.add(refunded).add(credited);
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
    private int parcelCount;
    private String receivingLocation;
    private String receiptNote;
    private UUID inspectedBy;
    private Instant inspectedAt;
    private String inspectionNote;
    private ReturnResolution moneyMode;
    private BigDecimal settlementAmount = BigDecimal.ZERO;
    private UUID proposedBy;
    private Instant proposedAt;
    private String resolutionNote;
    private UUID settledBy;
    private Instant settledAt;
    private MoneyOutcome money = MoneyOutcome.NONE;
    private UUID replacementOrderId;
    private boolean vatAdjustmentRequired;
    private UUID cancelledBy;
    private String cancellationReason;
    private Instant closedAt;
    private WithdrawalStatus withdrawalStatus;
    private String withdrawalReason;
    private BigDecimal withdrawalAmount = BigDecimal.ZERO;
    private UUID withdrawalProposedBy;
    private Instant withdrawalProposedAt;
    private UUID withdrawalDecidedBy;
    private Instant withdrawalDecidedAt;
    private String withdrawalDecisionNote;
    private MoneyOutcome withdrawalMoney = MoneyOutcome.NONE;

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

    /**
     * The customer (or Sales for them) claims {@code claims} of {@code order} (03 §12: — → REQUESTED), within the
     * return window, at the order's own prices.
     *
     * @see #open(String, UUID, Order, ReturnChannel, ReturnReason, String, ReturnResolution, List, Map, Set, UUID,
     *      Instant, ReturnPolicy, String, Map)
     */
    public static ReturnRequest open(String rmaNumber, UUID requestId, Order order, ReturnChannel channel,
                                     ReturnReason reason, String description, ReturnResolution requested,
                                     List<Claim> claims, Map<UUID, Integer> claimedElsewhere,
                                     Set<UUID> openElsewhere, UUID requestedBy, Instant now, ReturnPolicy policy) {
        return open(rmaNumber, requestId, order, channel, reason, description, requested, claims, claimedElsewhere,
                openElsewhere, requestedBy, now, policy, null, Map.of());
    }

    /**
     * The customer (or Sales for them) claims {@code claims} of {@code order} (03 §12: — → REQUESTED).
     *
     * @param claimedElsewhere per order line, the units the line's other returns still claim
     * @param openElsewhere    the order lines that already have an open return
     * @param windowOverride   why Sales accept the claim outside the return window - goods that came late, a second
     *                         defect found after the window, goodwill; back office only, and approved by someone else
     *                         like every claim staff open. Null: the window applies
     * @param unitPrices       per order line, the price credited when it is not the line's own: a free replacement
     *                         order's lines cost nothing, but the units they replaced were paid at the original order's
     *                         prices, and a defective reprint is credited at those
     * @throws BusinessException {@code ORDER_CANCELLED}, {@code RETURN_ORDER_NOT_DELIVERED},
     *         {@code RETURN_ORDER_NOT_RETURNABLE}, {@code RETURN_WINDOW_CLOSED} (BR-RMA-001);
     *         {@code RETURN_LINE_ALREADY_OPEN}; {@code RETURN_QUANTITY_EXCEEDED};
     *         {@code RETURN_CUSTOM_ITEM_NOT_DEFECTIVE} (BR-RMA-002); {@code RETURN_REASON_NOT_ACCEPTED} (SCRUM-243);
     *         {@code RETURN_QUANTITIES_INVALID}, {@code VALIDATION_FAILED}
     */
    public static ReturnRequest open(String rmaNumber, UUID requestId, Order order, ReturnChannel channel,
                                     ReturnReason reason, String description, ReturnResolution requested,
                                     List<Claim> claims, Map<UUID, Integer> claimedElsewhere,
                                     Set<UUID> openElsewhere, UUID requestedBy, Instant now, ReturnPolicy policy,
                                     String windowOverride, Map<UUID, BigDecimal> unitPrices) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(now, "now");
        String override = limited(windowOverride, REASON_LIMIT, "late-claim reason");
        if (override != null && channel != ReturnChannel.BACK_OFFICE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Only Sales accept a claim outside the return window");
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
            if (!reason.isNamedFault() && line.isMadeToOrder()) {
                throw new BusinessException(ErrorCode.RETURN_CUSTOM_ITEM_NOT_DEFECTIVE, ("%s was printed to the "
                        + "customer's design: it is returned only for a named defect, not %s (BR-RMA-002)")
                        .formatted(line.sku().code(), reason));
            }
            BigDecimal price = unitPrices == null ? null : unitPrices.get(line.id());
            lines.add(new ReturnLine(Identifiers.newId(), line.id(), line.sku().code(), line.isMadeToOrder(),
                    price == null ? line.unitPrice().amount() : price, claim.quantity()));
        }
        if (reason == ReturnReason.CHANGED_MIND) {
            if (!policy.changeOfMindForStockItems()) {
                throw new BusinessException(ErrorCode.RETURN_REASON_NOT_ACCEPTED,
                        "Returns are taken for defects and delivery faults, not a change of mind (SCRUM-243)");
            }
            if (!requested.isMoney()) {
                throw new BusinessException(ErrorCode.RETURN_REASON_NOT_ACCEPTED,
                        "Goods returned on a change of mind are refunded or credited, not exchanged");
            }
        }
        ReturnRequest request = new ReturnRequest(Identifiers.newId(), rmaNumber, order.id().value(),
                order.orderNumber().value(), order.customerId(), requestId, channel, reason, text, requested,
                requestedBy, now, order.total().currency().getCurrencyCode(), lines, override, ReturnStatus.REQUESTED,
                0L);
        request.transitions.add(new ReturnTransition(null, ReturnStatus.REQUESTED, requestedBy,
                override == null ? reason.name() : reason.name() + " LATE_CLAIM_ACCEPTED: " + override, now));
        request.registerEvent(new OrderEvent.ReturnChanged(request.id, new ReturnRequested(request.id, rmaNumber,
                request.orderId, request.orderNumber, request.customerId, reason.name(), requested.name(),
                channel.name(), request.lines.stream()
                        .map(l -> new ReturnRequested.Line(l.orderLineId(), l.sku(), l.requestedQuantity())).toList(),
                requestedBy, now)));
        return request;
    }

    /**
     * BR-RMA-001: a delivered order, within the window after delivery - or, for Sales with a reason, after it. A further
     * claim on an order a return already settled goes through Sales (03 §12); a CLOSED order takes none (BR-05).
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
            // RETURNED as an order status is goods that came back undelivered (RTO), or a return being settled.
            case DRAFT, PENDING_PAYMENT, CONFIRMED, IN_PRODUCTION, READY_TO_FULFILL, PICKING, PARTIALLY_FULFILLED,
                 PACKED, ON_HOLD, SHIPPED, DELIVERY_FAILED, RETURNED -> new BusinessException(
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
     * Keep a photo with the return: the customer's while the claim is open and the goods not yet back, the warehouse's
     * at receipt, QC's at inspection.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} for a stage the return is past (or not at);
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
     * photo leaves nothing behind in storage. The limit is per stage: the customer's photos never use up the room the
     * warehouse and QC need for theirs.
     */
    public void requireEvidenceAllowed(ReturnEvidence.Stage stage, ReturnPolicy policy) {
        boolean open = switch (Objects.requireNonNull(stage, "stage")) {
            case CLAIM -> status == ReturnStatus.REQUESTED || status == ReturnStatus.AWAITING_RETURN;
            case RECEIPT -> status == ReturnStatus.AWAITING_RETURN || status == ReturnStatus.RECEIVED;
            case INSPECTION -> status == ReturnStatus.RECEIVED || status == ReturnStatus.INSPECTED;
        };
        if (!open) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "Return %s is %s: it takes no %s photo now".formatted(rmaNumber, status, stage));
        }
        long atStage = evidence.stream().filter(photo -> photo.stage() == stage).count();
        if (atStage >= policy.maxEvidence()) {
            throw new BusinessException(ErrorCode.RETURN_EVIDENCE_LIMIT_REACHED,
                    "Return %s already holds %d %s photo(s)".formatted(rmaNumber, atStage, stage));
        }
    }

    // ================================================================================================= review

    /**
     * Sales approve the claim (03 §12: REQUESTED → AWAITING_RETURN, or INSPECTED when the goods stay). A claim Sales
     * opened themselves is approved by another person: opening and approving a claim worth anything is never one
     * person's decision.
     *
     * @param approved       units approved per line of the return; a line left out is approved in full, 0 approves
     *                       none of it (a partial approval). At least one unit is approved, or the return is rejected
     * @param returnRequired whether the goods come back; null: they do, unless there is nothing to send (a short
     *                       delivery)
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_SELF_APPROVAL};
     *         {@code RETURN_EVIDENCE_REQUIRED}; {@code RETURN_GOODS_REQUIRED} for goods above the no-return value, or a
     *         change of mind; {@code RETURN_QUANTITIES_INVALID}
     */
    public void approve(UUID by, Instant now, Map<UUID, Integer> approved, Boolean returnRequired, String note,
                        ReturnPolicy policy) {
        approve(by, now, approved, returnRequired, note, policy, BigDecimal.ZERO);
    }

    /**
     * The same, knowing what the order's other returns already left with the customer on photos alone
     * ({@code leftWithCustomerElsewhere}, at the order's prices): the limit is the order's, so a large claim split into
     * small returns still comes back to the warehouse.
     */
    public void approve(UUID by, Instant now, Map<UUID, Integer> approved, Boolean returnRequired, String note,
                        ReturnPolicy policy, BigDecimal leftWithCustomerElsewhere) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.REQUESTED, "be approved");
        if (channel == ReturnChannel.BACK_OFFICE && by.equals(requestedBy)) {
            throw new BusinessException(ErrorCode.RETURN_SELF_APPROVAL,
                    "Return %s was opened by the same person: someone else approves it".formatted(rmaNumber));
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
        // Every claim, a short delivery's too: there it is the delivery note the carrier signed with the shortage.
        if (policy.evidenceRequired() && evidence.stream().noneMatch(e -> e.stage() == ReturnEvidence.Stage.CLAIM)) {
            throw new BusinessException(ErrorCode.RETURN_EVIDENCE_REQUIRED, reason.hasGoodsToReturn()
                    ? "Return %s has no photo of the problem".formatted(rmaNumber)
                    : "Return %s has no photo of the delivery note with the shortage on it".formatted(rmaNumber));
        }
        boolean goodsBack = returnRequired == null ? reason.hasGoodsToReturn() : returnRequired;
        if (goodsBack && !reason.hasGoodsToReturn()) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                    "A short delivery has nothing to send back");
        }
        if (!goodsBack && reason.hasGoodsToReturn()) {
            if (!reason.isSellerFault()) {
                throw new BusinessException(ErrorCode.RETURN_GOODS_REQUIRED,
                        "Goods returned on a change of mind come back before anything is credited");
            }
            BigDecimal value = lines.stream().map(l -> l.unitPrice().multiply(BigDecimal.valueOf(units.get(l.id()))))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal elsewhere = leftWithCustomerElsewhere == null ? BigDecimal.ZERO : leftWithCustomerElsewhere;
            if (value.add(elsewhere).compareTo(policy.noReturnMaxValue()) > 0) {
                throw new BusinessException(ErrorCode.RETURN_GOODS_REQUIRED, ("Return %s is worth %s %s and the order's "
                        + "other returns left %s %s with the customer already, above the %s %s accepted on photos "
                        + "alone per order: the goods come back").formatted(rmaNumber, plain(value), currency,
                        plain(elsewhere), currency, plain(policy.noReturnMaxValue()), currency));
            }
        }
        lines.forEach(line -> line.approve(units.get(line.id())));
        this.decidedBy = by;
        this.decidedAt = now;
        this.decisionNote = limited(note, NOTE_LIMIT, "note");
        this.returnRequired = goodsBack;
        if (goodsBack) {
            this.returnDeadline = policy.returnDeadlineFrom(now);
            transitionTo(ReturnStatus.AWAITING_RETURN, by, "APPROVED", now);
        } else {
            // The photos were the inspection: every approved unit is accepted, nothing comes back to the warehouse.
            lines.forEach(line -> line.inspect(line.approvedQuantity(), 0, null, null, 0, 0, 0, null, null, null));
            this.inspectedBy = by;
            this.inspectedAt = now;
            this.inspectionNote = "Accepted on the evidence; the goods stay with the customer";
            transitionTo(ReturnStatus.INSPECTED, by, "APPROVED_WITHOUT_RETURN", now);
        }
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnApproved(id, rmaNumber, orderId, orderNumber,
                customerId, goodsBack, returnDeadline, lines.stream().map(l -> new ReturnApproved.Line(
                        l.orderLineId(), l.sku(), l.requestedQuantity(), l.approvedQuantity())).toList(), by, now)));
    }

    /** Sales refuse the claim, saying why (03 §12: REQUESTED → REJECTED, outside policy). */
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
     * CANCELLED. Goods that arrive anyway are still counted in ({@link #receive}).
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
     * The approved goods did not arrive by the deadline (03 §12: AWAITING_RETURN → EXPIRED), as the nightly sweep finds
     * it. Goods that arrive afterwards are still counted in ({@link #receive}).
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

    // ================================================================================================= receipt

    /**
     * Whether goods counted in now would bring this return back from where it ended: approved goods that arrived after
     * the deadline (EXPIRED), or after the customer withdrew an approved return (CANCELLED with goods to come back).
     */
    public boolean receivingReopens() {
        return status == ReturnStatus.EXPIRED
                || status == ReturnStatus.CANCELLED && Boolean.TRUE.equals(returnRequired) && decidedAt != null;
    }

    /**
     * The warehouse counts a parcel in at {@code location}, a quarantine area (03 §12: AWAITING_RETURN → RECEIVED;
     * OQ-12: never straight into sellable stock). A B2B customer often ships in more than one consignment: a later
     * parcel is counted in while the return is RECEIVED and not yet inspected, at the same location, so the goods of one
     * return wait for QC together. Goods are physically here whatever the return's state, so a parcel that comes after
     * the deadline or after the customer withdrew an approved return is counted in too, and brings the return back to
     * RECEIVED - Sales and QC then decide on it as on any other.
     *
     * <p>Discrepancies are recorded as facts of each parcel: another SKU than the line's (WRONG_ITEM), more units than
     * approved (EXCESS, as the count passes it), something of no line (UNEXPECTED_ITEM). A shortfall is known only when
     * no more parcels come, so QC records it at inspection.</p>
     *
     * @param receipts what this parcel held per line of the return; a line left out received nothing in it
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_RECEIVING_LOCATION_INVALID} for a later
     *         parcel at another location; {@code RETURN_QUANTITIES_INVALID} when nothing of the return arrived, or a
     *         later parcel brings other goods for a line than the first did (record them as an unexpected item)
     */
    public void receive(UUID by, Instant now, String location, List<Receipt> receipts, List<Unexpected> unexpected,
                        String note) {
        Objects.requireNonNull(by, "by");
        boolean reopening = receivingReopens();
        if (status != ReturnStatus.AWAITING_RETURN && status != ReturnStatus.RECEIVED && !reopening) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "Return %s is %s and takes no goods now".formatted(rmaNumber, status));
        }
        String at = trimmed(location);
        if (at == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the location the goods are received into");
        }
        if (status == ReturnStatus.RECEIVED && !at.equals(receivingLocation)) {
            throw new BusinessException(ErrorCode.RETURN_RECEIVING_LOCATION_INVALID,
                    "The goods of %s wait for QC at %s: count this parcel in there".formatted(rmaNumber,
                            receivingLocation));
        }
        Map<UUID, Receipt> byLine = new HashMap<>();
        for (Receipt receipt : receipts == null ? List.<Receipt>of() : receipts) {
            if (receipt == null || receipt.lineId() == null || byLine.putIfAbsent(receipt.lineId(), receipt) != null) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID, "Count each line of the return once");
            }
            line(receipt.lineId());
            if (receipt.quantity() < 0) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID, "A count is not negative");
            }
        }
        if (byLine.values().stream().mapToInt(Receipt::quantity).sum() == 0) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                    "Nothing of return %s arrived: record the parcel when its goods do".formatted(rmaNumber));
        }
        // Validated whole before anything changes: a later parcel brings for each line what the first one did.
        for (ReturnLine line : lines) {
            Receipt receipt = byLine.get(line.id());
            if (receipt == null || receipt.quantity() == 0 || !line.hasReceived()) {
                continue;
            }
            String sku = trimmed(receipt.sku());
            if (sku != null && !sku.equals(line.receivedSkuOrOwn())) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: this return already holds %s for the line; record %s as an unexpected item".formatted(
                                line.sku(), line.receivedSkuOrOwn(), sku));
            }
            if (!Objects.equals(trimmed(receipt.lotNumber()), line.lotNumber())) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: this return already holds lot %s for the line; record lot %s as an unexpected item"
                                .formatted(line.sku(), line.lotNumber(), trimmed(receipt.lotNumber())));
            }
        }
        int parcel = parcelCount + 1;
        List<ReturnDiscrepancy> found = new ArrayList<>();
        for (ReturnLine line : lines) {
            Receipt receipt = byLine.get(line.id());
            int units = receipt == null ? 0 : receipt.quantity();
            if (units == 0) {
                continue;
            }
            int approvedUnits = line.approvedQuantity() == null ? 0 : line.approvedQuantity();
            int before = line.receivedQuantity() == null ? 0 : line.receivedQuantity();
            if (line.hasReceived()) {
                line.receiveParcel(null, units, null);
            } else {
                String sku = trimmed(receipt.sku());
                String receivedSku = sku != null && !sku.equals(line.sku()) ? sku : null;
                line.receiveParcel(receivedSku, units, trimmed(receipt.lotNumber()));
                if (receivedSku != null) {
                    found.add(new ReturnDiscrepancy(Identifiers.newId(), line.id(), ReturnDiscrepancy.Kind.WRONG_ITEM,
                            receivedSku, units, "Expected %s".formatted(line.sku()), by, now));
                }
            }
            int excess = Math.max(0, before + units - approvedUnits) - Math.max(0, before - approvedUnits);
            if (excess > 0) {
                found.add(new ReturnDiscrepancy(Identifiers.newId(), line.id(), ReturnDiscrepancy.Kind.EXCESS,
                        line.receivedSkuOrOwn(), excess, parcel > 1 ? "Parcel %d".formatted(parcel) : null, by, now));
            }
        }
        for (Unexpected item : unexpected == null ? List.<Unexpected>of() : unexpected) {
            if (item == null || item.quantity() < 1 || (trimmed(item.sku()) == null && trimmed(item.note()) == null)) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "An unexpected item names what it is and how many arrived");
            }
            found.add(new ReturnDiscrepancy(Identifiers.newId(), null, ReturnDiscrepancy.Kind.UNEXPECTED_ITEM,
                    limited(item.sku(), 64, "sku"), item.quantity(), limited(item.note(), REASON_LIMIT, "note"), by,
                    now));
        }
        discrepancies.addAll(found);
        newDiscrepancies.addAll(found);
        String text = limited(note, NOTE_LIMIT, "note");
        if (parcelCount == 0) {
            // The first parcel names the stock layer: every later one is counted into the same quarantine layer.
            this.receivedBy = by;
            this.receivedAt = now;
            this.receivingLocation = at;
        }
        this.parcelCount = parcel;
        if (text != null) {
            this.receiptNote = text;
        }
        if (status != ReturnStatus.RECEIVED) {
            String why = reopening
                    ? (status == ReturnStatus.EXPIRED ? "RECEIVED_AFTER_DEADLINE" : "RECEIVED_AFTER_WITHDRAWAL")
                    : found.isEmpty() ? "RECEIVED" : "RECEIVED_WITH_DISCREPANCY";
            if (reopening) {
                this.closedAt = null;
                this.cancelledBy = null;
                this.cancellationReason = null;
            }
            transitionTo(ReturnStatus.RECEIVED, by, why, now);
        }
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnReceived(id, rmaNumber, orderId, orderNumber,
                customerId, at, lines.stream().map(l -> new ReturnReceived.Line(l.orderLineId(), l.sku(),
                        l.receivedSkuOrOwn(), l.approvedQuantity() == null ? 0 : l.approvedQuantity(),
                        l.receivedQuantity() == null ? 0 : l.receivedQuantity())).toList(), !found.isEmpty(), by, now,
                parcel)));
    }

    // ================================================================================================= inspection

    /**
     * QC decides every received unit (03 §12: RECEIVED → INSPECTED): accepted when the claim holds, rejected when it
     * does not (no defect, misuse, more than approved); and where it goes. Accepted units are ours once made good: back
     * on sale - plain stock only, never goods printed to a customer's design (OQ-12, BR-RMA-002), at a bin of the
     * warehouse - or scrapped. Rejected units are the customer's: they go back to them, and are scrapped only with
     * the customer's recorded consent. When nothing received is accepted the return is rejected (RECEIVED → REJECTED,
     * 03 §12 "goods not as described"). Lines that came back short of what was approved are recorded now, when no more
     * parcels are coming.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_QUANTITIES_INVALID};
     *         {@code RETURN_RESTOCK_NOT_ALLOWED} (BR-RMA-002, or goods QC has not called plain stock);
     *         {@code RETURN_DISPOSAL_CONSENT_REQUIRED}
     */
    public void inspect(UUID by, Instant now, List<Inspection> inspections, String note) {
        inspect(by, now, inspections, note, false);
    }

    /**
     * The same, saying whether more parcels are coming. With units approved and not arrived, QC inspects only once
     * {@code noMoreParcels} says the rest is not coming - the customer said so, or it is too late: the shortfall is then
     * recorded and the return goes on without them. Otherwise a second consignment would find the return closed and
     * have nowhere to be counted in.
     *
     * @throws BusinessException as {@link #inspect(UUID, Instant, List, String)}; {@code RETURN_PARCELS_OUTSTANDING}
     *         while units are still expected
     */
    public void inspect(UUID by, Instant now, List<Inspection> inspections, String note, boolean noMoreParcels) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.RECEIVED, "be inspected");
        int outstanding = lines.stream().mapToInt(l -> Math.max(0, (l.approvedQuantity() == null ? 0
                : l.approvedQuantity()) - (l.receivedQuantity() == null ? 0 : l.receivedQuantity()))).sum();
        if (outstanding > 0 && !noMoreParcels) {
            throw new BusinessException(ErrorCode.RETURN_PARCELS_OUTSTANDING, ("%d approved unit(s) of %s have not "
                    + "arrived: wait for the next parcel, or inspect saying no more parcels are coming")
                    .formatted(outstanding, rmaNumber));
        }
        Map<UUID, Inspection> byLine = new HashMap<>();
        for (Inspection inspection : inspections == null ? List.<Inspection>of() : inspections) {
            if (inspection == null || inspection.lineId() == null
                    || byLine.putIfAbsent(inspection.lineId(), inspection) != null) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID, "Inspect each line of the return once");
            }
            line(inspection.lineId());
        }
        // Validated whole before anything changes: a refused line leaves the return as it was.
        for (ReturnLine line : lines) {
            Inspection inspection = byLine.get(line.id());
            int received = line.receivedQuantity() == null ? 0 : line.receivedQuantity();
            if (inspection == null) {
                if (received > 0) {
                    throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                            "%s: %d unit(s) arrived and are not inspected".formatted(line.sku(), received));
                }
                continue;
            }
            int approvedUnits = line.approvedQuantity() == null ? 0 : line.approvedQuantity();
            if (inspection.accepted() < 0 || inspection.rejected() < 0 || inspection.restock() < 0
                    || inspection.scrap() < 0 || inspection.customerReturn() < 0) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID, "Counts are not negative");
            }
            if (inspection.accepted() + inspection.rejected() != received) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: %d unit(s) arrived; accepted and rejected add up to %d".formatted(line.sku(), received,
                                inspection.accepted() + inspection.rejected()));
            }
            if (inspection.accepted() > approvedUnits) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: at most the %d approved unit(s) are accepted".formatted(line.sku(), approvedUnits));
            }
            if (inspection.restock() + inspection.scrap() + inspection.customerReturn() != received) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: every one of the %d unit(s) received goes back on sale, to scrap or to the customer"
                                .formatted(line.sku(), received));
            }
            if (inspection.restock() > inspection.accepted()) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: only accepted units go back on sale; rejected ones are the customer's".formatted(
                                line.sku()));
            }
            if (inspection.customerReturn() > inspection.rejected()) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: only rejected units go back to the customer".formatted(line.sku()));
            }
            int customersScrapped = inspection.rejected() - inspection.customerReturn();
            if (customersScrapped > 0 && trimmed(inspection.disposalConsent()) == null) {
                throw new BusinessException(ErrorCode.RETURN_DISPOSAL_CONSENT_REQUIRED,
                        ("%s: %d rejected unit(s) are the customer's goods: send them back, or record how the customer "
                                + "agreed to their disposal").formatted(line.sku(), customersScrapped));
            }
            if (inspection.restock() > 0 && line.receivedPrintedGoods()) {
                throw new BusinessException(ErrorCode.RETURN_RESTOCK_NOT_ALLOWED,
                        "%s was printed to the customer's design and never goes back on sale (BR-RMA-002, OQ-12)"
                                .formatted(line.sku()));
            }
            if (inspection.restock() > 0 && !plainStock(line, inspection)) {
                throw new BusinessException(ErrorCode.RETURN_RESTOCK_NOT_ALLOWED,
                        ("%s: what came back is not known to be plain stock - QC says so before it goes back on sale "
                                + "(a design is not part of a SKU)").formatted(line.receivedSkuOrOwn()));
            }
            if ((inspection.restock() > 0) != (trimmed(inspection.restockLocation()) != null)) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: units back on sale name the bin they go to, and only they do".formatted(line.sku()));
            }
            if (inspection.restock() > 0 && inspection.condition() != ReturnItemCondition.GOOD
                    && inspection.condition() != ReturnItemCondition.UNOPENED) {
                throw new BusinessException(ErrorCode.RETURN_RESTOCK_NOT_ALLOWED,
                        "%s: QC graded the units %s; only GOOD or UNOPENED units go back on sale".formatted(line.sku(),
                                inspection.condition() == null ? "nothing" : inspection.condition()));
            }
        }
        List<ReturnDiscrepancy> found = new ArrayList<>();
        for (ReturnLine line : lines) {
            Inspection inspection = byLine.get(line.id());
            if (inspection == null) {
                line.inspect(0, 0, null, null, 0, 0, 0, null, null, null);
            } else {
                int customersScrapped = inspection.rejected() - inspection.customerReturn();
                line.inspect(inspection.accepted(), inspection.rejected(), inspection.condition(),
                        limited(inspection.note(), NOTE_LIMIT, "note"), inspection.restock(), inspection.scrap(),
                        inspection.customerReturn(), trimmed(inspection.restockLocation()),
                        inspection.restock() > 0 ? Boolean.TRUE : inspection.plainStock(),
                        customersScrapped > 0 ? limited(inspection.disposalConsent(), REASON_LIMIT, "consent") : null);
            }
            int approvedUnits = line.approvedQuantity() == null ? 0 : line.approvedQuantity();
            int received = line.receivedQuantity() == null ? 0 : line.receivedQuantity();
            if (received < approvedUnits) {
                found.add(new ReturnDiscrepancy(Identifiers.newId(), line.id(), ReturnDiscrepancy.Kind.SHORT,
                        line.sku(), approvedUnits - received, null, by, now));
            }
        }
        discrepancies.addAll(found);
        newDiscrepancies.addAll(found);
        String text = limited(note, NOTE_LIMIT, "note");
        if (acceptedUnits() == 0) {
            // The customer reads the reason; QC's own words stay in the inspection note, for staff.
            this.rejectionReason = "NO_DEFECT_FOUND";
            this.inspectionNote = text;
            close(ReturnStatus.REJECTED, by, text == null ? rejectionReason : rejectionReason + ": " + text, now);
            registerEvent(new OrderEvent.ReturnChanged(id, new ReturnRejected(id, rmaNumber, orderId, orderNumber,
                    customerId, "INSPECTION", by, now)));
            return;
        }
        this.inspectedBy = by;
        this.inspectedAt = now;
        this.inspectionNote = text;
        transitionTo(ReturnStatus.INSPECTED, by, "INSPECTED", now);
    }

    /** QC's call, or the default: the line's own SKU, on a line that was not printed, is plain stock. */
    private static boolean plainStock(ReturnLine line, Inspection inspection) {
        return inspection.plainStock() != null ? inspection.plainStock()
                : !line.customPrinted() && line.receivedSku() == null;
    }

    /** The inventory adjustments asked for the line's scrapped units and units going back to the customer. */
    public void recordStockAdjustments(UUID lineId, UUID scrapAdjustment, UUID customerReturnAdjustment) {
        line(lineId).stockAdjustments(scrapAdjustment, customerReturnAdjustment);
    }

    // ================================================================================================= resolution

    /**
     * Sales say how the accepted units are made good (SCRUM-260): per line, replaced by new goods or credited at the
     * price paid; the credited value is refunded or credited against what the customer owes ({@code moneyMode}). Every
     * resolution waits for a second person (INSPECTED → SETTLEMENT_PENDING): free replacement goods are worth as much as
     * the money they stand for, so neither leaves on one person's word.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_QUANTITIES_INVALID} unless every
     *         accepted unit is replaced or credited; {@code RETURN_REASON_NOT_ACCEPTED} for an exchange on a change of
     *         mind; {@code VALIDATION_FAILED} for money without a mode
     */
    public void proposeResolution(UUID by, Instant now, List<Remedy> remedies, ReturnResolution mode, String note) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.INSPECTED, "be resolved");
        Map<UUID, Remedy> byLine = new HashMap<>();
        for (Remedy remedy : remedies == null ? List.<Remedy>of() : remedies) {
            if (remedy == null || remedy.lineId() == null || byLine.putIfAbsent(remedy.lineId(), remedy) != null) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID, "Resolve each line of the return once");
            }
            line(remedy.lineId());
        }
        BigDecimal value = BigDecimal.ZERO;
        int replaced = 0;
        for (ReturnLine line : lines) {
            Remedy remedy = byLine.get(line.id());
            int accepted = line.acceptedQuantity() == null ? 0 : line.acceptedQuantity();
            int replacement = remedy == null ? 0 : remedy.replacement();
            int credit = remedy == null ? 0 : remedy.credit();
            if (replacement < 0 || credit < 0 || replacement + credit != accepted) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                        "%s: each of the %d accepted unit(s) is replaced or credited".formatted(line.sku(), accepted));
            }
            value = value.add(line.unitPrice().multiply(BigDecimal.valueOf(credit)));
            replaced += replacement;
        }
        if (replaced > 0 && !reason.isSellerFault()) {
            throw new BusinessException(ErrorCode.RETURN_REASON_NOT_ACCEPTED,
                    "Goods returned on a change of mind are refunded or credited, not exchanged");
        }
        if (value.signum() > 0 && (mode == null || !mode.isMoney())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Credited units are refunded or credited against what the customer owes: choose REFUND or "
                            + "CREDIT_NOTE");
        }
        for (ReturnLine line : lines) {
            Remedy remedy = byLine.get(line.id());
            line.remedy(remedy == null ? 0 : remedy.replacement(), remedy == null ? 0 : remedy.credit());
        }
        // Non-null exactly when money moves: the check above refused a positive value without a money mode.
        ReturnResolution money = value.signum() > 0 ? mode : null;
        this.settlementAmount = value;
        this.moneyMode = money;
        this.proposedBy = by;
        this.proposedAt = now;
        this.resolutionNote = limited(note, NOTE_LIMIT, "note");
        transitionTo(ReturnStatus.SETTLEMENT_PENDING, by,
                "SETTLEMENT_PROPOSED_" + (money == null ? ReturnResolution.REPLACEMENT : money).name(), now);
    }

    /**
     * Four eyes on every resolution: approved by someone other than who proposed it. Checked before anything is booked
     * or placed, so the refusal is the answer and not whatever a booking would have failed with.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION}; {@code RETURN_SELF_APPROVAL}
     */
    public void requireSettlementApprover(UUID by) {
        requireStatus(ReturnStatus.SETTLEMENT_PENDING, "have its settlement approved");
        if (Objects.equals(by, proposedBy)) {
            throw new BusinessException(ErrorCode.RETURN_SELF_APPROVAL,
                    "The settlement of %s was proposed by the same person".formatted(rmaNumber));
        }
    }

    /** The approver sends the settlement back, saying why: SETTLEMENT_PENDING → INSPECTED, the proposal cleared. */
    public void rejectSettlement(UUID by, Instant now, String why) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.SETTLEMENT_PENDING, "have its settlement sent back");
        String text = required(why, REASON_LIMIT, "A settlement sent back says why");
        lines.forEach(line -> line.remedy(0, 0));
        this.settlementAmount = BigDecimal.ZERO;
        this.moneyMode = null;
        this.proposedBy = null;
        this.proposedAt = null;
        this.resolutionNote = null;
        transitionTo(ReturnStatus.INSPECTED, by, "SETTLEMENT_SENT_BACK: " + text, now);
    }

    /**
     * The approved resolution was carried out (03 §12: SETTLEMENT_PENDING → RESOLVED, by the approver): the replacement
     * order placed (its lines named per return line), the money booked by payment.
     *
     * @throws IllegalStateException when payment booked another amount than the settlement - a defect, never a refusal
     */
    public void resolve(UUID by, Instant now, UUID replacementOrder, String replacementOrderNumber,
                        Map<UUID, UUID> replacementLines, MoneyOutcome outcome) {
        resolve(by, now, replacementOrder, replacementOrderNumber, replacementLines, outcome, orderId);
    }

    /**
     * The same, naming the order whose money settled it: {@code orderId}, or for a return on a free replacement order the
     * order whose units that replaced.
     */
    public void resolve(UUID by, Instant now, UUID replacementOrder, String replacementOrderNumber,
                        Map<UUID, UUID> replacementLines, MoneyOutcome outcome, UUID paidOrderId) {
        Objects.requireNonNull(by, "by");
        MoneyOutcome booked = outcome == null ? MoneyOutcome.NONE : outcome;
        requireSettlementApprover(by);
        if (booked.total().compareTo(settlementAmount) != 0) {
            throw new IllegalStateException("Return %s settles %s but %s was booked".formatted(rmaNumber,
                    plain(settlementAmount), plain(booked.total())));
        }
        if ((replacementOrder == null) != (replacementUnits() == 0)) {
            throw new IllegalStateException("Return %s replaces %d unit(s) but names replacement order %s"
                    .formatted(rmaNumber, replacementUnits(), replacementOrder));
        }
        for (ReturnLine line : lines) {
            UUID replacement = replacementLines == null ? null : replacementLines.get(line.id());
            if ((replacement == null) != (line.replacementQuantity() == 0)) {
                throw new IllegalStateException("Line %s of %s replaces %d unit(s) but names order line %s"
                        .formatted(line.id(), rmaNumber, line.replacementQuantity(), replacement));
            }
            line.replacedBy(replacement);
        }
        this.replacementOrderId = replacementOrder;
        this.money = booked;
        this.settledBy = by;
        this.settledAt = now;
        this.vatAdjustmentRequired = settlementAmount.signum() > 0;
        close(ReturnStatus.RESOLVED, by, resolutionSummary(), now);
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnResolved(id, rmaNumber, orderId, orderNumber,
                customerId, replacementOrder, replacementOrderNumber, booked.offset(), booked.refunded(),
                booked.credited(), currency,
                vatAdjustmentRequired, lines.stream().map(l -> new ReturnResolved.Line(l.orderLineId(), l.sku(),
                        l.acceptedQuantity() == null ? 0 : l.acceptedQuantity(), l.replacementQuantity(),
                        l.creditQuantity(), l.creditAmount())).toList(), by, now,
                paidOrderId == null ? orderId : paidOrderId)));
    }

    private String resolutionSummary() {
        int replaced = replacementUnits();
        if (settlementAmount.signum() == 0) {
            return "RESOLVED_REPLACEMENT %d".formatted(replaced);
        }
        return "RESOLVED_%s %s %s%s".formatted(moneyMode.name(), plain(settlementAmount), currency,
                replaced > 0 ? ", replacement %d".formatted(replaced) : "");
    }

    // ================================================================================================= replacement withdrawal

    /**
     * Sales propose to withdraw the free replacement order of a resolved return before it ships - the customer would
     * rather have the money, or the goods cannot be made again - and credit the replaced units at the return's prices
     * instead. It waits for a second person ({@link #approveReplacementWithdrawal}); the return stays RESOLVED, and the
     * replacement order is left as it is until then. A proposal sent back may be made again.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} unless the return is RESOLVED with a replacement that
     *         is not withdrawn or waiting to be; {@code VALIDATION_FAILED} without a reason
     */
    public void proposeReplacementWithdrawal(UUID by, Instant now, String why) {
        Objects.requireNonNull(by, "by");
        requireStatus(ReturnStatus.RESOLVED, "withdraw its replacement");
        if (replacementOrderId == null) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "Return %s replaced nothing".formatted(rmaNumber));
        }
        if (withdrawalStatus == WithdrawalStatus.PENDING || withdrawalStatus == WithdrawalStatus.APPROVED) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "The replacement of %s is %s".formatted(rmaNumber, withdrawalStatus == WithdrawalStatus.PENDING
                            ? "already waiting to be withdrawn" : "withdrawn already"));
        }
        String text = required(why, REASON_LIMIT, "A withdrawn replacement says why");
        this.withdrawalStatus = WithdrawalStatus.PENDING;
        this.withdrawalReason = text;
        this.withdrawalAmount = lines.stream()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.replacementQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.withdrawalProposedBy = by;
        this.withdrawalProposedAt = now;
        this.withdrawalDecidedBy = null;
        this.withdrawalDecidedAt = null;
        this.withdrawalDecisionNote = null;
        this.withdrawalMoney = MoneyOutcome.NONE;
    }

    /**
     * Four eyes on the withdrawal: approved by someone other than who proposed it.
     *
     * @throws BusinessException {@code RETURN_INVALID_TRANSITION} with nothing waiting; {@code RETURN_SELF_APPROVAL}
     */
    public void requireWithdrawalApprover(UUID by) {
        if (withdrawalStatus != WithdrawalStatus.PENDING) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "No withdrawal of the replacement of %s waits for approval".formatted(rmaNumber));
        }
        if (Objects.equals(by, withdrawalProposedBy)) {
            throw new BusinessException(ErrorCode.RETURN_SELF_APPROVAL,
                    "The withdrawal of the replacement of %s was proposed by the same person".formatted(rmaNumber));
        }
    }

    /**
     * The withdrawal was carried out by the approver: the replacement order cancelled and the replaced units' value
     * credited - set off against the original order's receivables, the rest kept as the customer's credit. Nothing is
     * refunded in cash here: the credit is the customer's, and paying it out is the accountant's (SCRUM-220).
     *
     * @throws IllegalStateException when payment booked another amount, or a refund - a defect, never a refusal
     */
    public void approveReplacementWithdrawal(UUID by, Instant now, MoneyOutcome booked, UUID paidOrderId) {
        Map<UUID, Integer> all = new HashMap<>();
        lines.stream().filter(l -> l.replacementQuantity() > 0).forEach(l -> all.put(l.id(), l.replacementQuantity()));
        approveReplacementWithdrawal(by, now, booked, paidOrderId, all);
    }

    /**
     * What withdrawing {@code withdrawnByLine} units of the replacement (per return line) is worth at the return's
     * prices: all of them while nothing shipped; for a replacement that shipped in part, the units not shipped.
     */
    public BigDecimal withdrawalAmountFor(Map<UUID, Integer> withdrawnByLine) {
        return lines.stream().map(l -> l.unitPrice().multiply(BigDecimal.valueOf(
                        Math.min(l.replacementQuantity(), withdrawnByLine.getOrDefault(l.id(), 0)))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The withdrawal carried out for {@code withdrawnByLine} units per return line - every unit of a replacement that
     * shipped nothing, or the rest of one that shipped in part, whose shipped units the customer keeps. The amount
     * credited is theirs at the return's prices, and the money given back flags the adjustment invoice.
     */
    public void approveReplacementWithdrawal(UUID by, Instant now, MoneyOutcome booked, UUID paidOrderId,
                                             Map<UUID, Integer> withdrawnByLine) {
        Objects.requireNonNull(by, "by");
        requireWithdrawalApprover(by);
        if (withdrawnByLine.values().stream().mapToInt(Integer::intValue).sum() <= 0) {
            throw new BusinessException(ErrorCode.RETURN_REPLACEMENT_NOT_WITHDRAWABLE,
                    "Every unit of the replacement of %s has shipped: nothing is left to withdraw".formatted(rmaNumber));
        }
        this.withdrawalAmount = withdrawalAmountFor(withdrawnByLine);
        MoneyOutcome outcome = booked == null ? MoneyOutcome.NONE : booked;
        if (outcome.refunded().signum() != 0 || outcome.total().compareTo(withdrawalAmount) != 0) {
            throw new IllegalStateException("The withdrawal of %s credits %s but %s was booked".formatted(rmaNumber,
                    plain(withdrawalAmount), plain(outcome.total())));
        }
        this.withdrawalStatus = WithdrawalStatus.APPROVED;
        this.withdrawalDecidedBy = by;
        this.withdrawalDecidedAt = now;
        this.withdrawalMoney = outcome;
        // Money given back for goods sold: accounting issues the adjustment invoice, as for a refund or credit.
        this.vatAdjustmentRequired = vatAdjustmentRequired || outcome.total().signum() > 0;
        registerEvent(new OrderEvent.ReturnChanged(id, new ReturnReplacementWithdrawn(id, rmaNumber, orderId,
                orderNumber, customerId, replacementOrderId, outcome.offset(), outcome.credited(), currency,
                lines.stream().filter(l -> withdrawnByLine.getOrDefault(l.id(), 0) > 0)
                        .map(l -> {
                            int units = Math.min(l.replacementQuantity(), withdrawnByLine.get(l.id()));
                            return new ReturnReplacementWithdrawn.Line(l.orderLineId(), l.sku(), units,
                                    l.unitPrice().multiply(BigDecimal.valueOf(units)));
                        }).toList(),
                paidOrderId == null ? orderId : paidOrderId, by, now)));
    }

    /** The approver sends the withdrawal back, saying why: the replacement ships as decided. */
    public void rejectReplacementWithdrawal(UUID by, Instant now, String why) {
        Objects.requireNonNull(by, "by");
        if (withdrawalStatus != WithdrawalStatus.PENDING) {
            throw new BusinessException(ErrorCode.RETURN_INVALID_TRANSITION,
                    "No withdrawal of the replacement of %s waits for approval".formatted(rmaNumber));
        }
        this.withdrawalDecisionNote = required(why, REASON_LIMIT, "A withdrawal sent back says why");
        this.withdrawalStatus = WithdrawalStatus.REJECTED;
        this.withdrawalDecidedBy = by;
        this.withdrawalDecidedAt = now;
    }

    // ================================================================================================= reading

    /** Units accepted across the lines: what the claim was found to hold for. */
    public int acceptedUnits() {
        return lines.stream().mapToInt(l -> l.acceptedQuantity() == null ? 0 : l.acceptedQuantity()).sum();
    }

    /** Units the resolution replaces with new goods. */
    public int replacementUnits() {
        return lines.stream().mapToInt(ReturnLine::replacementQuantity).sum();
    }

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

    /** Rehydration of the review, receipt and inspection. */
    public ReturnRequest restoreProgress(UUID decidedBy, Instant decidedAt, String decisionNote, String rejectionReason,
                                         Boolean returnRequired, Instant returnDeadline, UUID receivedBy,
                                         Instant receivedAt, String receivingLocation, String receiptNote,
                                         UUID inspectedBy, Instant inspectedAt, String inspectionNote,
                                         int parcelCount) {
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
        this.rejectionReason = rejectionReason;
        this.returnRequired = returnRequired;
        this.returnDeadline = returnDeadline;
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
        this.receivingLocation = receivingLocation;
        this.receiptNote = receiptNote;
        this.inspectedBy = inspectedBy;
        this.inspectedAt = inspectedAt;
        this.inspectionNote = inspectionNote;
        this.parcelCount = parcelCount;
        return this;
    }

    /** Rehydration of the withdrawal of a replacement, when one was proposed. */
    public ReturnRequest restoreWithdrawal(WithdrawalStatus status, String reason, BigDecimal amount, UUID proposedBy,
                                           Instant proposedAt, UUID decidedBy, Instant decidedAt, String decisionNote,
                                           MoneyOutcome money) {
        this.withdrawalStatus = status;
        this.withdrawalReason = reason;
        this.withdrawalAmount = amount == null ? BigDecimal.ZERO : amount;
        this.withdrawalProposedBy = proposedBy;
        this.withdrawalProposedAt = proposedAt;
        this.withdrawalDecidedBy = decidedBy;
        this.withdrawalDecidedAt = decidedAt;
        this.withdrawalDecisionNote = decisionNote;
        this.withdrawalMoney = money == null ? MoneyOutcome.NONE : money;
        return this;
    }

    /** Rehydration of the resolution and the end. */
    public ReturnRequest restoreResolution(ReturnResolution moneyMode, BigDecimal settlementAmount, UUID proposedBy,
                                           Instant proposedAt, String resolutionNote, UUID settledBy, Instant settledAt,
                                           MoneyOutcome money, UUID replacementOrderId, boolean vatAdjustmentRequired,
                                           UUID cancelledBy, String cancellationReason, Instant closedAt) {
        this.moneyMode = moneyMode;
        this.settlementAmount = settlementAmount == null ? BigDecimal.ZERO : settlementAmount;
        this.proposedBy = proposedBy;
        this.proposedAt = proposedAt;
        this.resolutionNote = resolutionNote;
        this.settledBy = settledBy;
        this.settledAt = settledAt;
        this.money = money == null ? MoneyOutcome.NONE : money;
        this.replacementOrderId = replacementOrderId;
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
    public int parcelCount() { return parcelCount; }
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
    public String receivingLocation() { return receivingLocation; }
    public String receiptNote() { return receiptNote; }
    public UUID inspectedBy() { return inspectedBy; }
    public Instant inspectedAt() { return inspectedAt; }
    public String inspectionNote() { return inspectionNote; }
    public ReturnResolution moneyMode() { return moneyMode; }
    public BigDecimal settlementAmount() { return settlementAmount; }
    public UUID proposedBy() { return proposedBy; }
    public Instant proposedAt() { return proposedAt; }
    public String resolutionNote() { return resolutionNote; }
    public UUID settledBy() { return settledBy; }
    public Instant settledAt() { return settledAt; }
    public MoneyOutcome money() { return money; }
    public UUID replacementOrderId() { return replacementOrderId; }
    public boolean vatAdjustmentRequired() { return vatAdjustmentRequired; }
    public UUID cancelledBy() { return cancelledBy; }
    public String cancellationReason() { return cancellationReason; }
    public Instant closedAt() { return closedAt; }
    public WithdrawalStatus withdrawalStatus() { return withdrawalStatus; }
    public String withdrawalReason() { return withdrawalReason; }
    public BigDecimal withdrawalAmount() { return withdrawalAmount; }
    public UUID withdrawalProposedBy() { return withdrawalProposedBy; }
    public Instant withdrawalProposedAt() { return withdrawalProposedAt; }
    public UUID withdrawalDecidedBy() { return withdrawalDecidedBy; }
    public Instant withdrawalDecidedAt() { return withdrawalDecidedAt; }
    public String withdrawalDecisionNote() { return withdrawalDecisionNote; }
    public MoneyOutcome withdrawalMoney() { return withdrawalMoney; }
    public List<ReturnEvidence> evidence() { return java.util.Collections.unmodifiableList(evidence); }
    public List<ReturnDiscrepancy> discrepancies() { return java.util.Collections.unmodifiableList(discrepancies); }
}
