package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

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
 * installments still owe - those tied to a delivery batch that never came first, then the latest, after delivery before
 * before delivery before on signing (§4.6 step 19) - and what cannot come off, because it was paid already, is surplus.
 * Either way the surplus pays the fee first (BR-15); the rest is refundable; what it did not cover is owed on a
 * cancellation fee item, due from that day. <b>Return</b> ({@link #returned}, team plan 4D-3): what the goods refunded
 * were worth comes off the installments as a closure's does, with no fee; the rest is refundable.</p>
 *
 * <p>The arithmetic is stated a second time in {@code ck_order_settlement_*}: a settlement row that does not add up
 * cannot be written.</p>
 */
public final class OrderSettlement {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 17 §4.8: the two ways money is settled before an order ends; and, after it was delivered, goods that came back on
     * a return its Sales and a second person decided to refund (17 §4.10 step 40, 15 §4.5 step 15 - team plan 4D-3).
     */
    public enum Kind { ORDER_CANCELLED, REMAINDER_CLOSED, GOODS_RETURNED }

    /**
     * The fee kept (17 §4.8 step 30): a share of the money collected, or an amount agreed with the customer with its
     * reason (at most 500 characters) and detail (at most 2,000) - never both - or none.
     */
    public record Fee(BigDecimal percent, BigDecimal amount, String reason, String detail) {

        public static Fee none() {
            return new Fee(null, null, null, null);
        }

        /**
         * @throws BusinessException {@code VALIDATION_FAILED} for a share outside (0, 100], an amount not a positive
         *         whole dong, an amount without its reason and detail, or both a share and an amount
         */
        public Fee {
            reason = reason == null || reason.isBlank() ? null : reason.strip();
            detail = detail == null || detail.isBlank() ? null : detail.strip();
            if (percent != null && amount != null) {
                throw invalid("Keep a percentage or an agreed amount, not both");
            }
            if (percent != null && (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0
                    || percent.stripTrailingZeros().scale() > 2)) {
                throw invalid("The share kept is more than 0 and at most 100 percent, to two decimals");
            }
            if (amount != null && (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0)) {
                throw invalid("The agreed amount is more than zero, in whole dong");
            }
            if (amount != null && (reason == null || reason.length() > 500 || detail == null
                    || detail.length() > 2000)) {
                throw invalid("An agreed amount needs its reason (at most 500 characters) and its detail (at most "
                        + "2000)");
            }
            if (amount == null && (reason != null || detail != null)) {
                throw invalid("A reason and a detail go with an agreed amount");
            }
        }

        boolean any() {
            return percent != null || amount != null;
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
    private final BigDecimal feeCovered;
    private final BigDecimal refundable;
    private final BigDecimal feeOwed;
    private final UUID feeItemId;
    private final UUID settledBy;
    private final Instant settledAt;
    private final List<Reduction> reductions;

    /** Restores one as stored (and is how the factories below make one). */
    public OrderSettlement(UUID id, UUID orderId, UUID contractId, UUID customerId, String orderNumber,
                           String contractNumber, Kind kind, UUID sourceId, String currency, BigDecimal collected,
                           BigDecimal closed, BigDecimal reduced, BigDecimal surplus, BigDecimal fee, Fee feeTerms,
                           BigDecimal feeCovered, BigDecimal refundable, BigDecimal feeOwed, UUID feeItemId,
                           UUID settledBy, Instant settledAt, List<Reduction> reductions) {
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
        this.feeCovered = Objects.requireNonNull(feeCovered, "feeCovered");
        this.refundable = Objects.requireNonNull(refundable, "refundable");
        this.feeOwed = Objects.requireNonNull(feeOwed, "feeOwed");
        this.feeItemId = feeItemId;
        this.settledBy = settledBy;
        this.settledAt = Objects.requireNonNull(settledAt, "settledAt");
        this.reductions = List.copyOf(reductions);
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
        BigDecimal amount = terms.on(collected);
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
        Installment first = contractRow(ofOrder);
        Objects.requireNonNull(closureId, "closureId");
        Objects.requireNonNull(closed, "closed");
        if (closed.signum() <= 0 || closed.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A closure closes goods worth a positive whole amount, not " + closed);
        }
        Fee terms = fee == null ? Fee.none() : fee;
        if (terms.percent() != null) {
            throw invalid("Closing the rest keeps an agreed amount, not a share");
        }
        if (terms.amount() != null && terms.amount().compareTo(closed) > 0) {
            throw invalid("The agreed amount is at most the value of the goods closed, %s"
                    .formatted(closed.toPlainString()));
        }
        BigDecimal collected = collected(ofOrder);
        List<Reduction> reductions = new ArrayList<>();
        List<Installment> changed = new ArrayList<>();
        BigDecimal left = takeOff(ofOrder, closed, at, reductions, changed);
        return settle(first, ofOrder, Kind.REMAINDER_CLOSED, closureId, collected, closed, reductions, left,
                terms.on(collected), terms, by, at, day, "Fee for the rest closed", changed);
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
        Installment first = contractRow(ofOrder);
        Objects.requireNonNull(returnRequestId, "returnRequestId");
        Objects.requireNonNull(returned, "returned");
        if (returned.signum() <= 0 || returned.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A return refunds goods worth a positive whole amount, not " + returned);
        }
        BigDecimal collected = collected(ofOrder);
        List<Reduction> reductions = new ArrayList<>();
        List<Installment> changed = new ArrayList<>();
        BigDecimal left = takeOff(ofOrder, returned, at, reductions, changed);
        return settle(first, ofOrder, Kind.GOODS_RETURNED, returnRequestId, collected, returned, reductions, left,
                BigDecimal.ZERO, Fee.none(), by, at, day, "Fee", changed);
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
     * <p>Known limitation, made common by WF-05 (review F11): when batch 1 fails to deliver and its rest is cancelled
     * while batch 2 was delivered, the cut lands on batch 2's installment and batch 1's stays owed - the total is right,
     * the installment named is wrong. Fixing it is not local to this class: the order closes its rest per line, not per
     * batch, and {@code OrderRemainderClosed} names no batch, so the closure would first have to say which batches it
     * closed. Open question in the WF05-dfail report.</p>
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
        BigDecimal covered = fee.min(surplus);
        BigDecimal refundable = surplus.subtract(covered);
        BigDecimal owed = fee.subtract(covered);
        Installment feeItem = null;
        if (owed.signum() > 0) {
            int seq = ofOrder.stream().filter(Installment::isFeeItem).mapToInt(Installment::seq)
                    .max().orElse(Installment.FIRST_FEE_SEQ - 1) + 1;
            if (seq > Installment.LAST_FEE_SEQ) {
                throw new IllegalStateException("Contract %s has no room for another fee item"
                        .formatted(first.contractNumber()));
            }
            feeItem = Installment.openFee(first, seq, feeItemName, owed, at, day);
        }
        BigDecimal reduced = reductions.stream().map(Reduction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        OrderSettlement settlement = new OrderSettlement(Identifiers.newId(), first.orderId(), first.contractId(),
                first.customerId(), first.orderNumber(), first.contractNumber(), kind, sourceId, first.currency(),
                collected, closed, reduced, surplus, fee, terms.any() ? terms : Fee.none(), covered, refundable, owed,
                feeItem == null ? null : feeItem.id(), by, at, reductions);
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

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
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
    public BigDecimal feeCovered() { return feeCovered; }
    public BigDecimal refundable() { return refundable; }
    public BigDecimal feeOwed() { return feeOwed; }
    public UUID feeItemId() { return feeItemId; }
    public UUID settledBy() { return settledBy; }
    public Instant settledAt() { return settledAt; }
    public List<Reduction> reductions() { return reductions; }
}
