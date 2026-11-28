package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.payment.api.FeeBearer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a cancellation, a closure of the rest, or a return did with a contract order's money (4B-9; kltn-docs 15 §4.5, §4.6,
 * BR-14..BR-16; 17 §4.8 steps 30 and 33). Worked out here, from the order's installments locked by the caller, and
 * never changed after ({@code tg_order_settlement}).
 *
 * <p><b>Cancellation</b> ({@link #cancel}): the installments not paid in full are cancelled; every dong collected on
 * them is surplus. <b>Closure</b> ({@link #closeRemainder}): what the goods closed were worth comes off what the
 * installments still owe - those tied to the delivery batches the closure closed first, then the latest, after delivery
 * before before delivery before on signing (§4.6 step 19) - and what cannot come off, because it was paid already, is
 * surplus. Either way the surplus pays the fee first (BR-15); the rest is refundable; what it did not cover is owed on a
 * cancellation fee item, due from that day. A closure also carries onto that fee item what the installments before
 * delivery of the batches it closed whole still owe - their share of the fee lines - since their milestone, the batch's
 * release, never comes (audit r3 LM-3, {@link #carriedToFee}). <b>Return</b> ({@link #returned}, team plan 4D-3): what
 * the goods refunded were worth comes off what the customer still owes for goods they have - not what a batch still to
 * come owes for its own - and the rest is refundable, with no fee. <b>Shipment returned</b> ({@link #shipmentReturned}, WF-05 Q-2): the
 * units of a shipment the carrier brought back, or lost, settled by the return's rule - the shipment's own batch pays
 * first, then the older debt, then the refund, the batches still to come last - with no fee.</p>
 *
 * <p>The arithmetic is stated a second time in {@code ck_order_settlement_*}: a settlement row that does not add up
 * cannot be written.</p>
 */
public final class OrderSettlement {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 17 §4.8: the two ways money is settled before an order ends; and, after it was delivered, goods that came back on
     * a return its Sales and a second person decided to refund (17 §4.10 step 40, 15 §4.5 step 15 - team plan 4D-3);
     * and the units of a shipment the carrier brought back or lost, settled once the warehouse confirmed the goods back
     * or Sales closed the lost parcel (WF-05 Q-2, Q-7, Q-12).
     */
    public enum Kind { ORDER_CANCELLED, REMAINDER_CLOSED, GOODS_RETURNED, SHIPMENT_RETURNED }

    /**
     * The fee kept (17 §4.8 step 30): a share of the money collected, or an amount agreed with the customer with its
     * reason (at most 500 characters) and detail (at most 2,000) - never both - or none. A fee has its bearer (D46,
     * D50), chosen by Sales and never defaulted: the customer is charged it, or the company bears it and the customer
     * is charged nothing ({@link #chargedOn}); no fee has none.
     */
    public record Fee(BigDecimal percent, BigDecimal amount, String reason, String detail, FeeBearer bearer) {

        public static Fee none() {
            return new Fee(null, null, null, null, null);
        }

        /**
         * @throws BusinessException {@code VALIDATION_FAILED} for a share outside (0, 100], an amount not a positive
         *         whole dong, an amount without its reason and detail, or both a share and an amount
         */
        public Fee {
            reason = reason == null || reason.isBlank() ? null : reason.strip();
            detail = detail == null || detail.isBlank() ? null : detail.strip();
            if (percent != null && amount != null) {
                throw invalid("Keep a percentage or an agreed amount, not both", "refusal.settlement.feeBoth");
            }
            if (percent != null && (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0
                    || percent.stripTrailingZeros().scale() > 2)) {
                throw invalid("The share kept is more than 0 and at most 100 percent, to two decimals",
                        "refusal.settlement.feePercent");
            }
            if (amount != null && (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0)) {
                throw invalid("The agreed amount is more than zero, in whole dong", "refusal.settlement.feeAmount");
            }
            if (amount != null && (reason == null || reason.length() > 500 || detail == null
                    || detail.length() > 2000)) {
                throw invalid("An agreed amount needs its reason (at most 500 characters) and its detail (at most "
                        + "2000)", "refusal.settlement.feeReason", 500, 2000);
            }
            if (amount == null && (reason != null || detail != null)) {
                throw invalid("A reason and a detail go with an agreed amount",
                        "refusal.settlement.feeReasonWithoutAmount");
            }
            if ((percent != null || amount != null) && bearer == null) {
                throw invalid("A fee needs its bearer, the customer or the company", "refusal.settlement.feeBearer");
            }
            if (percent == null && amount == null && bearer != null) {
                throw invalid("No fee is kept, so no one bears one", "refusal.settlement.feeBearerWithoutFee");
            }
        }

        boolean any() {
            return percent != null || amount != null;
        }

        boolean borneByCompany() {
            return bearer == FeeBearer.COMPANY;
        }

        /** What the customer is charged on {@code collected}: the fee, or nothing when the company bears it (D50). */
        BigDecimal chargedOn(BigDecimal collected) {
            return borneByCompany() ? BigDecimal.ZERO : on(collected);
        }

        /** The fee on {@code collected}: the agreed amount, or the share rounded to the dong (half up). */
        BigDecimal on(BigDecimal collected) {
            if (amount != null) {
                return amount.setScale(0, RoundingMode.UNNECESSARY);
            }
            return percent == null ? BigDecimal.ZERO
                    : collected.multiply(percent).divide(HUNDRED, 0, RoundingMode.HALF_UP);
        }
    }

    /** What a closure took off one installment. */
    public record Reduction(UUID installmentId, BigDecimal amount) {
    }

    /**
     * A settlement and what it changed: the installments cancelled or lowered, and the fee item it opened (null when
     * nothing is owed). The caller saves the fee item before the settlement, which names it.
     */
    public record Outcome(OrderSettlement settlement, List<Installment> changed, Installment feeItem) {
    }

    private final UUID id;
    private final UUID orderId;
    private final UUID contractId;
    private final UUID customerId;
    private final String orderNumber;
    private final String contractNumber;
    private final Kind kind;
    private final UUID sourceId;
    private final String currency;
    private final BigDecimal collected;
    private final BigDecimal closed;
    private final BigDecimal reduced;
    private final BigDecimal surplus;
    private final BigDecimal fee;
    private final Fee feeTerms;
    /** D50: the fee the company bears (the customer is charged {@link #fee} = 0 then); 0 when the customer does. */
    private final BigDecimal companyFee;
    private final BigDecimal feeCovered;
    private final BigDecimal refundable;
    private final BigDecimal feeOwed;
    private final UUID feeItemId;
    private final UUID settledBy;
    private final Instant settledAt;
    private final List<Reduction> reductions;
    private final UUID shipmentId;
    private final BigDecimal shippingFeeWaived;
    private final BigDecimal carriedToFee;

    /** Restores one that is not a shipment's: no shipment named, no shipping fee waived. */
    public OrderSettlement(UUID id, UUID orderId, UUID contractId, UUID customerId, String orderNumber,
                           String contractNumber, Kind kind, UUID sourceId, String currency, BigDecimal collected,
                           BigDecimal closed, BigDecimal reduced, BigDecimal surplus, BigDecimal fee, Fee feeTerms,
                           BigDecimal feeCovered, BigDecimal refundable, BigDecimal feeOwed, UUID feeItemId,
                           UUID settledBy, Instant settledAt, List<Reduction> reductions) {
        this(id, orderId, contractId, customerId, orderNumber, contractNumber, kind, sourceId, currency, collected,
                closed, reduced, surplus, fee, feeTerms, feeCovered, refundable, feeOwed, feeItemId, settledBy,
                settledAt, reductions, null, BigDecimal.ZERO);
    }

    /**
     * Restores one as stored (and is how the factories below make one).
     *
     * @param shipmentId        the shipment a {@link Kind#SHIPMENT_RETURNED} settlement closed; null for every other kind
     *                          ({@code ck_order_settlement_shipment} says it again)
     * @param shippingFeeWaived the part of {@code closed} that is the order's shipping fee waived (WF-05 Q-19): a
     *                          {@link Kind#SHIPMENT_RETURNED} settlement's share, or what a {@link Kind#REMAINDER_CLOSED}
     *                          one waived when it left the order with nothing delivered; zero for the other kinds
     */
    public OrderSettlement(UUID id, UUID orderId, UUID contractId, UUID customerId, String orderNumber,
                           String contractNumber, Kind kind, UUID sourceId, String currency, BigDecimal collected,
                           BigDecimal closed, BigDecimal reduced, BigDecimal surplus, BigDecimal fee, Fee feeTerms,
                           BigDecimal feeCovered, BigDecimal refundable, BigDecimal feeOwed, UUID feeItemId,
                           UUID settledBy, Instant settledAt, List<Reduction> reductions, UUID shipmentId,
                           BigDecimal shippingFeeWaived) {
        this(id, orderId, contractId, customerId, orderNumber, contractNumber, kind, sourceId, currency, collected,
                closed, reduced, surplus, fee, feeTerms, feeCovered, refundable, feeOwed, feeItemId, settledBy,
                settledAt, reductions, shipmentId, shippingFeeWaived, BigDecimal.ZERO);
    }

    /**
     * Restores one as stored, with what a closure of the rest carried onto its fee item.
     *
     * @param carriedToFee what a {@link Kind#REMAINDER_CLOSED} settlement moved off the installments before delivery of
     *                     the batches it closed - what they still owed above the goods closed, their share of the fee
     *                     lines (audit r3 LM-3) - onto its fee item; part of {@link #reduced}, never of what the goods
     *                     closed took off. Zero for every other kind ({@code ck_order_settlement_carried})
     */
    public OrderSettlement(UUID id, UUID orderId, UUID contractId, UUID customerId, String orderNumber,
                           String contractNumber, Kind kind, UUID sourceId, String currency, BigDecimal collected,
                           BigDecimal closed, BigDecimal reduced, BigDecimal surplus, BigDecimal fee, Fee feeTerms,
                           BigDecimal feeCovered, BigDecimal refundable, BigDecimal feeOwed, UUID feeItemId,
                           UUID settledBy, Instant settledAt, List<Reduction> reductions, UUID shipmentId,
                           BigDecimal shippingFeeWaived, BigDecimal carriedToFee) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.contractId = Objects.requireNonNull(contractId, "contractId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        this.contractNumber = Objects.requireNonNull(contractNumber, "contractNumber");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.collected = Objects.requireNonNull(collected, "collected");
        this.closed = Objects.requireNonNull(closed, "closed");
        this.reduced = Objects.requireNonNull(reduced, "reduced");
        this.surplus = Objects.requireNonNull(surplus, "surplus");
        this.fee = Objects.requireNonNull(fee, "fee");
        this.feeTerms = feeTerms == null ? Fee.none() : feeTerms;
        // ck_order_settlement_fee_bearer: whom the fee is charged to - the company's share is the customer's nothing.
        this.companyFee = this.feeTerms.borneByCompany() ? this.feeTerms.on(collected) : BigDecimal.ZERO;
        if (this.feeTerms.borneByCompany() && fee.signum() != 0) {
            throw new IllegalArgumentException("A fee the company bears charges the customer nothing");
        }
        this.feeCovered = Objects.requireNonNull(feeCovered, "feeCovered");
        this.refundable = Objects.requireNonNull(refundable, "refundable");
        this.feeOwed = Objects.requireNonNull(feeOwed, "feeOwed");
        this.feeItemId = feeItemId;
        this.settledBy = settledBy;
        this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
        this.reductions = List.copyOf(reductions);
        this.shipmentId = shipmentId;
        this.shippingFeeWaived = shippingFeeWaived == null ? BigDecimal.ZERO : shippingFeeWaived;
        this.carriedToFee = carriedToFee == null ? BigDecimal.ZERO : carriedToFee;
        // ck_order_settlement_shipment: a shipment's settlement names its shipment, no other kind does; only it, and a
        // closure of the rest that left the order with nothing delivered (Q-19 edge), waive a shipping fee, never more
        // than they closed.
        if ((kind == Kind.SHIPMENT_RETURNED) != (shipmentId != null)) {
            throw new IllegalArgumentException("Only a shipment's settlement names a shipment");
        }
        if (this.shippingFeeWaived.signum() < 0 || this.shippingFeeWaived.compareTo(closed) > 0
                || kind != Kind.SHIPMENT_RETURNED && kind != Kind.REMAINDER_CLOSED
                && this.shippingFeeWaived.signum() != 0) {
            throw new IllegalArgumentException("Only a shipment's settlement, or a closure of the rest, waives a "
                    + "shipping fee, within what it closed");
        }
        // ck_order_settlement_carried: only a closure of the rest carries, a whole amount, within what it took off.
        if (this.carriedToFee.signum() < 0 || this.carriedToFee.stripTrailingZeros().scale() > 0
                || this.carriedToFee.compareTo(reduced) > 0
                || kind != Kind.REMAINDER_CLOSED && this.carriedToFee.signum() != 0) {
            throw new IllegalArgumentException("Only a closure of the rest carries what installments owed onto its fee "
                    + "item, a whole amount within what it took off them");
        }
    }

    /**
     * The order was cancelled (15 §4.6 step 18, BR-15): its installments not paid in full are cancelled, the money
     * collected on them pays {@code fee} first, the rest is refundable, a shortfall is owed on a fee item due today.
     *
     * @param ofOrder the order's installments, locked by the caller; at least one
     */
    public static Outcome cancel(List<Installment> ofOrder, Fee fee, UUID by, Instant at, LocalDate day) {
        Installment first = contractRow(ofOrder);
        Fee terms = fee == null ? Fee.none() : fee;
        BigDecimal collected = collected(ofOrder);
        List<Installment> changed = new ArrayList<>();
        for (Installment installment : ofOrder) {
            if (installment.cancel(at)) {
                changed.add(installment);
            }
        }
        BigDecimal amount = terms.chargedOn(collected);
        return settle(first, ofOrder, Kind.ORDER_CANCELLED, first.orderId(), collected, BigDecimal.ZERO, List.of(),
                collected, amount, terms, by, at, day, "Cancellation fee", changed);
    }

    /**
     * The rest of the order was closed (15 §4.6 step 19, BR-16): {@code closed} - what the goods closed were worth with
     * their VAT - comes off what the installments still owe, those tied to a delivery batch not reached first, then
     * after delivery before before delivery before on signing, the latest of each first. What cannot come off was paid
     * above the order's new worth: it pays {@code fee} first, the rest is refundable, a shortfall is owed on a fee item.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a share (a closure keeps an agreed amount only, 17 §4.8
     *         step 33) or an amount above the goods closed
     */
    public static Outcome closeRemainder(List<Installment> ofOrder, UUID closureId, BigDecimal closed, Fee fee, UUID by,
                                         Instant at, LocalDate day) {
        return closeRemainder(ofOrder, closureId, closed, fee, by, at, day, null);
    }

    /**
     * As {@link #closeRemainder(List, UUID, BigDecimal, Fee, UUID, Instant, LocalDate)}, knowing which delivery batches
     * had goods handed over ({@code handedOverBatches}; WF-05 TT-5). A closure closes what has not left, so the batches
     * it closed are the others: the installments tied to them take the value first - in {@link #CLOSURE_ORDER} among
     * themselves - and only what they cannot take comes off the rest, in that order (15 §4.6 step 19: the installments
     * of the batches closed first, then the latest). Without this, batch 1 failing and closed while batch 2 was
     * delivered lowered batch 2's installment - the latest - and left the customer owing batch 1's for goods they never
     * received. Null: not known, the closure's order alone, as before.
     */
    public static Outcome closeRemainder(List<Installment> ofOrder, UUID closureId, BigDecimal closed, Fee fee, UUID by,
                                         Instant at, LocalDate day, java.util.Set<UUID> handedOverBatches) {
        return closeRemainder(ofOrder, closureId, closed, fee, by, at, day, handedOverBatches, BigDecimal.ZERO);
    }

    /**
     * As {@link #closeRemainder(List, UUID, BigDecimal, Fee, UUID, Instant, LocalDate, java.util.Set)}, waiving
     * {@code shippingFeeWaived} of the order's shipping fee with the goods ({@link #shippingFeeShare}): WF-05 Q-19 edge,
     * as the owner decided on 2026-11-23 - the closure that leaves an order whose shipments came back with nothing
     * delivered waives what they left of the fee, the order being a whole return. It comes off the installments, and is
     * refunded, as the goods closed do; it is part of {@link #closed} and stated apart beside it. The agreed fee is still
     * bounded by the goods closed alone: the shipping fee waived is no goods made to the customer's design.
     *
     * @param closed            what the goods closed were worth with their VAT; a positive whole amount
     * @param shippingFeeWaived zero or more, whole
     */
    public static Outcome closeRemainder(List<Installment> ofOrder, UUID closureId, BigDecimal closed, Fee fee, UUID by,
                                         Instant at, LocalDate day, java.util.Set<UUID> handedOverBatches,
                                         BigDecimal shippingFeeWaived) {
        return closeRemainder(ofOrder, closureId, closed, fee, by, at, day, handedOverBatches, shippingFeeWaived, null);
    }

    /**
     * As {@link #closeRemainder(List, UUID, BigDecimal, Fee, UUID, Instant, LocalDate, java.util.Set, BigDecimal)},
     * knowing which of the batches with nothing handed over still have goods to hand over after the closure
     * ({@code deliveringBatches}: labelled cartons the closure keeps, WF4-CLOSE-REST) - so the others are closed whole.
     *
     * <p><b>What an installment before delivery of a batch closed whole still owes is carried onto the fee item</b>
     * (audit r3 LM-3; 15 §4.6 steps 18-19, §5.1). Tied installments share the whole total by batch value, the plate and
     * shipping fee lines included ({@code ContractTerms.suggestTied}), so a batch's installment before delivery is above
     * its goods. Closing that batch takes the goods off it (step 19) and leaves its share of the fee lines, which stay
     * owed (D30, D32) - on an installment whose milestone is the batch's release and whose purpose is to gate its
     * handover: neither ever comes. It stayed NOT_DUE for good, with no due day ({@code ck_installment_due_date}), never
     * overdue, outside the exposure and the aged receivables (BR-10 counts after delivery and fee items), and the order,
     * owing, never completed. It is taken off that installment whole (PAID if it was paid something, else CANCELLED) and
     * owed on the closure's fee item instead - due from the closure's day, as 15 §5.1 gives every fee item a closure
     * leaves, and counted where fee items are: the credit check, the receivables, the overdue scan. The customer owes not
     * a dong more or less; only where and from when it is owed changes.</p>
     *
     * <p>Not carried: a batch still delivering (its installment still gates the labelled cartons' handover, BR-08); an
     * installment tied to no batch, or to a batch that left (it was due before its handover); one after delivery (its
     * batch closed, it falls due with the whole order, {@code InstallmentService.follow}). Null {@code deliveringBatches}
     * or {@code handedOverBatches}: not known, nothing is carried, as before.</p>
     *
     * @param deliveringBatches the batches with goods still to hand over after this closure; empty for none
     */
    public static Outcome closeRemainder(List<Installment> ofOrder, UUID closureId, BigDecimal closed, Fee fee, UUID by,
                                         Instant at, LocalDate day, java.util.Set<UUID> handedOverBatches,
                                         BigDecimal shippingFeeWaived, java.util.Set<UUID> deliveringBatches) {
        Installment first = contractRow(ofOrder);
        Objects.requireNonNull(closureId, "closureId");
        Objects.requireNonNull(closed, "closed");
        if (closed.signum() <= 0 || closed.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A closure closes goods worth a positive whole amount, not " + closed);
        }
        BigDecimal waived = shippingFeeWaived == null ? BigDecimal.ZERO : shippingFeeWaived;
        if (waived.signum() < 0 || waived.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A shipping fee waived is zero or more, whole, not " + waived);
        }
        Fee terms = fee == null ? Fee.none() : fee;
        if (terms.percent() != null) {
            throw invalid("Closing the rest keeps an agreed amount, not a share", "refusal.settlement.closureShare");
        }
        if (terms.amount() != null && terms.amount().compareTo(closed) > 0) {
            throw invalid("The agreed amount is at most the value of the goods closed, %s"
                    .formatted(closed.toPlainString()), "refusal.settlement.closureAmountAbove", closed);
        }
        BigDecimal collected = collected(ofOrder);
        List<Reduction> reductions = new ArrayList<>();
        List<Installment> changed = new ArrayList<>();
        BigDecimal value = closed.add(waived);
        BigDecimal left = takeOff(ofOrder.stream().filter(i -> stillToCome(i, handedOverBatches)).toList(), value, at,
                reductions, changed);
        left = takeOff(ofOrder, left, at, reductions, changed);
        BigDecimal carried = BigDecimal.ZERO;
        if (handedOverBatches != null && deliveringBatches != null) {
            List<Installment> stranded = ofOrder.stream()
                    .filter(i -> i.milestone() == InstallmentMilestone.BEFORE_DELIVERY
                            && stillToCome(i, handedOverBatches) && !deliveringBatches.contains(i.deliveryBatchId())
                            && i.status().takesMoney() && i.outstanding().signum() > 0)
                    .sorted(Comparator.comparing(Installment::seq)).toList();
            for (Installment installment : stranded) {
                BigDecimal owes = installment.outstanding();
                installment.reduce(owes, at);
                reductions.add(new Reduction(installment.id(), owes));
                changed.add(installment);
                carried = carried.add(owes);
            }
        }
        BigDecimal agreed = terms.chargedOn(collected);
        // What the fee item is for, as the installment lists show it beside its milestone.
        String name = carried.signum() == 0 ? "Fee for the rest closed"
                : agreed.signum() == 0 ? "Fee lines of the batches closed"
                : "Fee for the rest closed, with the fee lines of the batches closed";
        return settle(first, ofOrder, Kind.REMAINDER_CLOSED, closureId, collected, value, merged(reductions), left,
                agreed, terms, by, at, day, name, distinct(changed), null, waived, carried);
    }

    /**
     * One reduction per installment ({@code uk_installment_reduction}): an installment a closure lowered for the goods
     * closed and then carried onto its fee item is taken off once, by the sum, in the order first met.
     */
    private static List<Reduction> merged(List<Reduction> reductions) {
        java.util.Map<UUID, BigDecimal> byInstallment = new java.util.LinkedHashMap<>();
        reductions.forEach(r -> byInstallment.merge(r.installmentId(), r.amount(), BigDecimal::add));
        return byInstallment.entrySet().stream().map(e -> new Reduction(e.getKey(), e.getValue())).toList();
    }

    /**
     * Whether {@code installment} is tied to a delivery batch none of whose goods were handed over: a batch still to
     * come - for a closure, one it closed. False when nothing is known of the batches.
     */
    private static boolean stillToCome(Installment installment, java.util.Set<UUID> handedOverBatches) {
        return handedOverBatches != null && installment.deliveryBatchId() != null
                && !handedOverBatches.contains(installment.deliveryBatchId());
    }

    /**
     * Goods that came back on a return are refunded (17 §4.10 step 40; 15 §4.5 step 15, BR-14; team plan 4D-3):
     * {@code returned} - what the goods refunded were worth with their VAT - comes off what the installments still
     * owe, in the closure's order (after delivery first, the latest first), so the customer never pays for goods they
     * gave back; what cannot come off was paid already and is refundable. No fee: a return is taken only for a
     * defect (17 §4.10), never charged for. The same arithmetic as {@link #closeRemainder} with no fee, kept apart so
     * the settlement says what it was for and is made once per return.
     *
     * @param returnRequestId the return whose goods are refunded: one settlement per return
     */
    public static Outcome returned(List<Installment> ofOrder, UUID returnRequestId, BigDecimal returned, UUID by,
                                   Instant at, LocalDate day) {
        return returned(ofOrder, returnRequestId, null, returned, by, at, day);
    }

    /**
     * As {@link #returned(List, UUID, BigDecimal, UUID, Instant, LocalDate)}, for goods that came on the delivery batch
     * {@code deliveryBatchId} (WF-05 TH-1): the installments tied to that batch take the value first, in
     * {@link #CLOSURE_ORDER}; whatever they cannot take comes off the rest in that order, as before. A return is of one
     * delivery: taking it off the latest batch first, as a closure does, would lower what a batch still on its way owes
     * for its own goods - its installment before delivery, which gates its handover - for goods another batch
     * brought. Null: the order's installments as a whole.
     */
    public static Outcome returned(List<Installment> ofOrder, UUID returnRequestId, UUID deliveryBatchId,
                                   BigDecimal returned, UUID by, Instant at, LocalDate day) {
        return returned(ofOrder, returnRequestId, deliveryBatchId, returned, null, BigDecimal.ZERO, by, at, day);
    }

    /**
     * As {@link #returned(List, UUID, UUID, BigDecimal, UUID, Instant, LocalDate)}, knowing which delivery batches had
     * goods handed over ({@code handedOverBatches}) and what the order's earlier settlements made refundable already
     * ({@code refundableBefore}). The rule a return follows (WF-05 TT-5; 15 §4.5 step 15, BR-14; 17 §4.10 step 40):
     * <ol>
     *   <li>what the return's own batch's installments still owe ({@code deliveryBatchId});</li>
     *   <li>then what the customer still owes for goods they have or that are on their way - every installment but those
     *       tied to a batch still to come - in {@link #CLOSURE_ORDER}: a refund is set off against the customer's debt
     *       first, and only what is left is money to hand back;</li>
     *   <li>then refundable, up to the money collected that no earlier settlement made refundable
     *       ({@code collected - refundableBefore}; BR-14: never more than was paid);</li>
     *   <li>only past that - the customer paid less than the goods they gave back are worth - off the installments of
     *       the batches still to come, in the closure's order, so the order still adds up.</li>
     * </ol>
     * Before step 3, what the return's batch could not take came off the latest batch first: a batch still to come had
     * its installment before delivery - its handover's gate - lowered for goods another batch brought, while the
     * customer, who had paid for the goods given back, got nothing back. {@code handedOverBatches} null: not known,
     * every installment is the customer's debt and nothing is set apart, as before.
     */
    public static Outcome returned(List<Installment> ofOrder, UUID returnRequestId, UUID deliveryBatchId,
                                   BigDecimal returned, java.util.Set<UUID> handedOverBatches,
                                   BigDecimal refundableBefore, UUID by, Instant at, LocalDate day) {
        Objects.requireNonNull(returnRequestId, "returnRequestId");
        Objects.requireNonNull(returned, "returned");
        if (returned.signum() <= 0 || returned.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A return refunds goods worth a positive whole amount, not " + returned);
        }
        return givenBack(ofOrder, Kind.GOODS_RETURNED, returnRequestId, deliveryBatchId, returned, handedOverBatches,
                refundableBefore, by, at, day, null, BigDecimal.ZERO);
    }

    /**
     * The units of a shipment the carrier brought back - settled when the warehouse confirmed their return receipt
     * (WF-05 Q-7) - or lost - settled when Sales closed the lost parcel (Q-12) - were closed: settled by the return's
     * rule (Q-2; {@link #returned(List, UUID, UUID, BigDecimal, java.util.Set, BigDecimal, UUID, Instant, LocalDate)}
     * steps 1-4) with no fee. Settled as a closure of the rest before ({@link #closeRemainder}), the value came off the
     * latest batch first: a carrier bringing back batch 1 lowered batches 2 and 3 - their installment before delivery,
     * the gate of their handover, included - and left batch 1 owing for goods that came back (audit M-2).
     *
     * <p>{@code shippingFeeWaived} is the part of the order's shipping fee given up with it ({@link #shippingFeeShare};
     * Q-19: the failed trip's shipping is not charged, the plate fee is kept): it comes off the installments, and is
     * refunded, as the goods do; it is part of {@link #closed} and stated apart beside it.</p>
     *
     * @param closureId         the closure's id: one settlement per closure ({@code uk_order_settlement})
     * @param shipmentId        the shipment whose units were closed: one settlement per shipment
     *                          ({@code uk_order_settlement_shipment})
     * @param deliveryBatchId   the batch the shipment carried: its installments take the value first; null without a
     *                          schedule
     * @param goodsValue        what the closed units were worth with their VAT; zero or more
     * @param shippingFeeWaived zero or more; with the goods, a positive whole amount
     */
    public static Outcome shipmentReturned(List<Installment> ofOrder, UUID closureId, UUID shipmentId,
                                           UUID deliveryBatchId, BigDecimal goodsValue, BigDecimal shippingFeeWaived,
                                           java.util.Set<UUID> handedOverBatches, BigDecimal refundableBefore, UUID by,
                                           Instant at, LocalDate day) {
        Objects.requireNonNull(closureId, "closureId");
        Objects.requireNonNull(shipmentId, "shipmentId");
        Objects.requireNonNull(goodsValue, "goodsValue");
        BigDecimal waived = shippingFeeWaived == null ? BigDecimal.ZERO : shippingFeeWaived;
        if (goodsValue.signum() < 0 || waived.signum() < 0 || waived.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A shipment's goods, and the fee waived with them in whole units, are "
                    + "worth zero or more");
        }
        BigDecimal closed = goodsValue.add(waived);
        if (closed.signum() <= 0 || closed.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A shipment's settlement closes a positive whole amount, not " + closed);
        }
        return givenBack(ofOrder, Kind.SHIPMENT_RETURNED, closureId, deliveryBatchId, closed, handedOverBatches,
                refundableBefore, by, at, day, shipmentId, waived);
    }

    /**
     * WF-05 Q-19, as the owner decided on 2026-11-23: the part of the order's shipping fee waived by one settlement - a
     * shipment the carrier brought back or lost ({@code returnedGoods} its goods), or a closure of the rest
     * ({@code returnedGoods} null).
     *
     * <p><b>Something was delivered, or still may be: the share.</b> A shipment brought back waives the fee in
     * proportion to the goods of the order that never reached the customer this way. One shipment of three equal ones
     * brought back waives a third of the fee; all three, the whole fee; none, nothing. The share of the goods delivered
     * is kept, which the trips that delivered them earned; a closure of the rest waives nothing - the closed part
     * follows the closing rule and the fee lines stay owed (D30, D32).</p>
     *
     * <p><b>Nothing delivered: the whole fee</b> (the Q-19 edge, decided by the owner on 2026-11-23). When the
     * settlement leaves an order with nothing delivered and nothing more to deliver ({@code endsWithNothingDelivered})
     * after a carrier return - this one, or an earlier one of the order - it waives all that is left of the fee, even
     * when part of the order was closed before it shipped and so was never "brought back". The order is a whole return,
     * as Q-6 liquidates its contract. Whichever comes last, the last shipment back or the closure of the rest, takes
     * what the earlier shipments left: a first shipment brought back while the rest was still to ship waived its share,
     * the closure that then ends the order waives the remainder, and the waivers add up to the fee, exactly, once.
     * Without a carrier return (shipments cancelled before they left, say) a closure still waives nothing: no trip
     * failed. Why the order says whether it ends with nothing delivered rather than payment reading it:
     * {@link com.stockflow.payment.api.SettleShipmentReturnCommand.ShippingFee}.</p>
     *
     * <p><b>Rounding, and where the remainder goes.</b> The fee waived by the order's shipments so far is worked out from
     * their goods together, rounded to the whole unit half up - the rounding of every other share of money here (the
     * cancellation fee's share of what was collected, {@code ContractTerms}' installments, {@code FeeAllocation}'s
     * shipping share on the invoices) - and this shipment waives that less what the earlier settlements waived. So the
     * rounding never accumulates: the shipments' waivers add up to the rounded share of all of them to the dong, and
     * the shipment that brings the last of the order's goods back waives exactly what is left of the fee. Never below
     * zero, never above what is left, so a settlement made after the whole fee was waived waives nothing.</p>
     *
     * <p>Measured against the order's goods as signed, not as later closures left them: a whole that shrank with every
     * closure would make the earlier shipments' shares wrong after the fact. Goods closed in other ways (a rest closed
     * before it left, a customer's return) count for no share: Q-19 is about the trip that failed.</p>
     *
     * @param fee                      the order's shipping fee line with its VAT, whole; zero for none
     * @param orderGoods               what the order's goods lines are worth with their VAT, as signed; positive when
     *                                 {@code fee} is and a share is to be worked out
     * @param endsWithNothingDelivered once this settlement is made, no shipment of the order is delivered and none will be
     * @param ofOrder                  the order's settlements so far: its earlier shipments' goods and every earlier
     *                                 waiver are read from them
     * @param returnedGoods            what this shipment's goods are worth with their VAT; null for a closure of the
     *                                 rest
     * @return the fee waived with this settlement, whole, from zero to what the earlier settlements left of the fee
     */
    public static BigDecimal shippingFeeShare(BigDecimal fee, BigDecimal orderGoods, boolean endsWithNothingDelivered,
                                              List<OrderSettlement> ofOrder, BigDecimal returnedGoods) {
        if (fee == null || fee.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal goodsBefore = BigDecimal.ZERO;
        BigDecimal waivedBefore = BigDecimal.ZERO;
        boolean broughtBackBefore = false;
        for (OrderSettlement earlier : ofOrder) {
            waivedBefore = waivedBefore.add(earlier.shippingFeeWaived());
            if (earlier.kind() == Kind.SHIPMENT_RETURNED) {
                broughtBackBefore = true;
                goodsBefore = goodsBefore.add(earlier.closed().subtract(earlier.shippingFeeWaived()));
            }
        }
        BigDecimal whole = fee.setScale(0, RoundingMode.HALF_UP);
        BigDecimal left = whole.subtract(waivedBefore).max(BigDecimal.ZERO);
        if (endsWithNothingDelivered && (returnedGoods != null || broughtBackBefore)) {
            return left;
        }
        if (returnedGoods == null) {
            return BigDecimal.ZERO;
        }
        if (orderGoods == null || orderGoods.signum() <= 0) {
            throw new IllegalArgumentException("A shipping fee is charged for goods worth more than zero");
        }
        BigDecimal through = goodsBefore.add(returnedGoods.max(BigDecimal.ZERO));
        BigDecimal waivedThrough = through.compareTo(orderGoods) >= 0 ? whole
                : whole.multiply(through).divide(orderGoods, 0, RoundingMode.HALF_UP);
        return waivedThrough.subtract(waivedBefore).max(BigDecimal.ZERO).min(left);
    }

    /** The return's four steps, for a customer's return and a shipment the carrier brought back or lost alike. */
    private static Outcome givenBack(List<Installment> ofOrder, Kind kind, UUID sourceId, UUID deliveryBatchId,
                                     BigDecimal value, java.util.Set<UUID> handedOverBatches,
                                     BigDecimal refundableBefore, UUID by, Instant at, LocalDate day, UUID shipmentId,
                                     BigDecimal shippingFeeWaived) {
        Installment first = contractRow(ofOrder);
        BigDecimal collected = collected(ofOrder);
        List<Reduction> reductions = new ArrayList<>();
        List<Installment> changed = new ArrayList<>();
        BigDecimal left = value;
        if (deliveryBatchId != null) {
            left = takeOff(ofOrder.stream().filter(i -> deliveryBatchId.equals(i.deliveryBatchId())).toList(), left, at,
                    reductions, changed);
        }
        left = takeOff(ofOrder.stream().filter(i -> !stillToCome(i, handedOverBatches)).toList(), left, at, reductions,
                changed);
        BigDecimal before = refundableBefore == null ? BigDecimal.ZERO : refundableBefore;
        BigDecimal refund = handedOverBatches == null ? BigDecimal.ZERO
                : left.min(collected.subtract(before).max(BigDecimal.ZERO));
        left = takeOff(ofOrder, left.subtract(refund), at, reductions, changed);
        return settle(first, ofOrder, kind, sourceId, collected, value, reductions, refund.add(left), BigDecimal.ZERO,
                Fee.none(), by, at, day, "Fee", distinct(changed), shipmentId, shippingFeeWaived);
    }

    /**
     * Takes {@code amount} off what the installments still owe, in {@link #CLOSURE_ORDER}; collects each cut in
     * {@code reductions} and each installment lowered in {@code changed}.
     *
     * @return what could not come off: it was paid already
     */
    private static BigDecimal takeOff(List<Installment> ofOrder, BigDecimal amount, Instant at,
                                      List<Reduction> reductions, List<Installment> changed) {
        BigDecimal left = amount;
        List<Installment> owing = ofOrder.stream()
                .filter(i -> !i.isFeeItem() && i.status().takesMoney() && i.outstanding().signum() > 0)
                .sorted(CLOSURE_ORDER).toList();
        for (Installment installment : owing) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal taken = left.min(installment.outstanding());
            installment.reduce(taken, at);
            reductions.add(new Reduction(installment.id(), taken));
            changed.add(installment);
            left = left.subtract(taken);
        }
        return left;
    }

    /** Each installment lowered once in the list, however many cuts it took: it is saved once. */
    private static List<Installment> distinct(List<Installment> changed) {
        List<Installment> once = new ArrayList<>();
        for (Installment installment : changed) {
            if (once.stream().noneMatch(seen -> seen == installment)) {
                once.add(installment);
            }
        }
        return once;
    }

    /**
     * 15 §4.6 step 19: an installment tied to a delivery batch first, the latest batch first - a closure closes what is
     * left, so the last batches are the ones closed; then after delivery, before delivery, on signing; the latest of
     * each first.
     *
     * <p>Which batches the closure actually closed is not known here: the order closes its rest as one, and an
     * installment after delivery tied to a batch still falls due with the whole order (4B-8 releases and hands over by
     * batch, but does not hand the closed batches to the closure yet). Ordering
     * on "milestone not reached" instead would take off an after-delivery installment of a batch already delivered
     * (it is reached only once the whole order is) before a before-delivery one of the batch closed. Handing the closed
     * batches in and ranking on them is left open (reports/C3.md).</p>
     *
     * <p>Which batches a closure closed is handed in now (WF-05 TT-5, review F11): those with no goods handed over,
     * whose installments go before this order applies to the rest
     * ({@link #closeRemainder(List, UUID, BigDecimal, Fee, UUID, Instant, LocalDate, java.util.Set)}). Batch 1 failing
     * and cancelled while batch 2 was delivered lowers batch 1's installment, not batch 2's.</p>
     */
    static final Comparator<Installment> CLOSURE_ORDER = Comparator
            .comparing((Installment i) -> i.deliveryBatchSeq() == null ? Integer.MIN_VALUE : i.deliveryBatchSeq(),
                    Comparator.reverseOrder())
            .thenComparing(i -> switch (i.milestone()) {
                case AFTER_DELIVERY -> 0;
                case BEFORE_DELIVERY -> 1;
                case ON_SIGNING -> 2;
                case CANCELLATION_FEE, FAILED_TRIP_FEE -> 3;
            })
            .thenComparing(Installment::seq, Comparator.reverseOrder());

    private static Outcome settle(Installment first, List<Installment> ofOrder, Kind kind, UUID sourceId,
                                  BigDecimal collected, BigDecimal closed, List<Reduction> reductions,
                                  BigDecimal surplus, BigDecimal fee, Fee terms, UUID by, Instant at, LocalDate day,
                                  String feeItemName, List<Installment> changed) {
        return settle(first, ofOrder, kind, sourceId, collected, closed, reductions, surplus, fee, terms, by, at, day,
                feeItemName, changed, null, BigDecimal.ZERO);
    }

    private static Outcome settle(Installment first, List<Installment> ofOrder, Kind kind, UUID sourceId,
                                  BigDecimal collected, BigDecimal closed, List<Reduction> reductions,
                                  BigDecimal surplus, BigDecimal fee, Fee terms, UUID by, Instant at, LocalDate day,
                                  String feeItemName, List<Installment> changed, UUID shipmentId,
                                  BigDecimal shippingFeeWaived) {
        return settle(first, ofOrder, kind, sourceId, collected, closed, reductions, surplus, fee, terms, by, at, day,
                feeItemName, changed, shipmentId, shippingFeeWaived, BigDecimal.ZERO);
    }

    /**
     * The surplus pays the fee first, the rest is refundable, and the fee item owes what it did not cover plus
     * {@code carried} - what a closure moved onto it from installments that can no longer fall due
     * ({@link #closeRemainder(List, UUID, BigDecimal, Fee, UUID, Instant, LocalDate, java.util.Set, BigDecimal,
     * java.util.Set)}).
     */
    private static Outcome settle(Installment first, List<Installment> ofOrder, Kind kind, UUID sourceId,
                                  BigDecimal collected, BigDecimal closed, List<Reduction> reductions,
                                  BigDecimal surplus, BigDecimal fee, Fee terms, UUID by, Instant at, LocalDate day,
                                  String feeItemName, List<Installment> changed, UUID shipmentId,
                                  BigDecimal shippingFeeWaived, BigDecimal carried) {
        BigDecimal covered = fee.min(surplus);
        BigDecimal refundable = surplus.subtract(covered);
        BigDecimal owed = fee.subtract(covered);
        Installment feeItem = null;
        if (owed.add(carried).signum() > 0) {
            int seq = ofOrder.stream().filter(Installment::isFeeItem).mapToInt(Installment::seq)
                    .max().orElse(Installment.FIRST_FEE_SEQ - 1) + 1;
            if (seq > Installment.LAST_FEE_SEQ) {
                throw new IllegalStateException("Contract %s has no room for another fee item"
                        .formatted(first.contractNumber()));
            }
            feeItem = Installment.openFee(first, seq, feeItemName, owed.add(carried), at, day);
        }
        BigDecimal reduced = reductions.stream().map(Reduction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        OrderSettlement settlement = new OrderSettlement(Identifiers.newId(), first.orderId(), first.contractId(),
                first.customerId(), first.orderNumber(), first.contractNumber(), kind, sourceId, first.currency(),
                collected, closed, reduced, surplus, fee, terms.any() ? terms : Fee.none(), covered, refundable, owed,
                feeItem == null ? null : feeItem.id(), by, at, reductions, shipmentId, shippingFeeWaived, carried);
        return new Outcome(settlement, changed, feeItem);
    }

    /** 15 BR-14: what the order's plan installments were paid - a fee item's money is the fee's, never refunded. */
    private static BigDecimal collected(List<Installment> ofOrder) {
        return ofOrder.stream().filter(i -> !i.isFeeItem()).map(Installment::paidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Installment contractRow(List<Installment> ofOrder) {
        if (ofOrder == null || ofOrder.isEmpty()) {
            throw new IllegalArgumentException("A contract order has installments to settle");
        }
        return ofOrder.stream().filter(i -> !i.isFeeItem()).findFirst().orElse(ofOrder.getFirst());
    }

    /** A refusal Sales read: {@code message} for the log, {@code key} in their language. */
    private static BusinessException invalid(String message, String key, Object... arguments) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message).localized(key, arguments);
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public UUID contractId() { return contractId; }
    public UUID customerId() { return customerId; }
    public String orderNumber() { return orderNumber; }
    public String contractNumber() { return contractNumber; }
    public Kind kind() { return kind; }
    public UUID sourceId() { return sourceId; }
    public String currency() { return currency; }
    public BigDecimal collected() { return collected; }
    public BigDecimal closed() { return closed; }
    public BigDecimal reduced() { return reduced; }
    public BigDecimal surplus() { return surplus; }
    public BigDecimal fee() { return fee; }
    public Fee feeTerms() { return feeTerms; }
    /** D46, D50: who bears the fee entered; null when none was. */
    public FeeBearer feeBearer() { return feeTerms.bearer(); }
    /** D50: the fee the company bears - its cost, never charged nor owed; 0 when the customer bears it or none was kept. */
    public BigDecimal companyFee() { return companyFee; }
    public BigDecimal feeCovered() { return feeCovered; }
    public BigDecimal refundable() { return refundable; }
    public BigDecimal feeOwed() { return feeOwed; }
    public UUID feeItemId() { return feeItemId; }
    public UUID settledBy() { return settledBy; }
    public Instant settledAt() { return settledAt; }
    public List<Reduction> reductions() { return reductions; }
    /** The shipment a {@link Kind#SHIPMENT_RETURNED} settlement closed; null for the other kinds. */
    public UUID shipmentId() { return shipmentId; }
    /**
     * The part of {@link #closed} that is the order's shipping fee waived (Q-19): a shipment's share, or what a closure
     * of the rest waived when it left the order with nothing delivered; zero for the other kinds.
     */
    public BigDecimal shippingFeeWaived() { return shippingFeeWaived; }
    /**
     * What a {@link Kind#REMAINDER_CLOSED} settlement carried onto its fee item from the installments before delivery
     * of the batches it closed (audit r3 LM-3): part of {@link #reduced}, owed on the fee item beside
     * {@link #feeOwed}; zero for the other kinds.
     */
    public BigDecimal carriedToFee() { return carriedToFee; }
}
