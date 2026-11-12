package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One installment of a signed contract's payment plan (kltn-docs 15 §4.1, §5.1): made when the contract is signed, as
 * the plan row says - amount, milestone, days to pay, the delivery batch it is tied to - and never changed after. Its
 * milestone comes once ({@link #reach}); receipts pay it ({@link #pay}), a voided receipt takes that back
 * ({@link #unpay}); the unpaid installments of a cancelled order are {@link #cancel cancelled}, and a closure of the
 * rest of the order takes off what the goods closed were worth ({@link #reduce}, 4B-9).
 *
 * <p>A cancellation fee the money collected did not cover is an installment too ({@link #openFee}, milestone
 * {@link InstallmentMilestone#CANCELLATION_FEE}): 15 §5.1 gives it the same lifecycle starting from DUE, so receipts,
 * the nightly overdue sweep and the credit check (BR-10) take it as they take any installment. It names no plan row.</p>
 *
 * <p>The status always says what the money and the milestone say ({@code ck_installment_status_facts}). Being overdue
 * is not a status but a flag worked out from the due day ({@link #overdue}; WF-05 fix plan, section 8): what the next
 * credit check needs to know (BR-11) is read the same way, so an overdue installment paid in part reads PARTIALLY_PAID
 * and is still overdue for what is left. What a closure took off counts as settled: paid in part and the rest taken
 * off, it is PAID; taken off whole with nothing paid, it is no longer owed - CANCELLED.</p>
 */
public final class Installment {

    /** A contract's plan has at most 20 rows (14 BR-13); its fee items come after them. */
    public static final int FIRST_FEE_SEQ = 21;
    public static final int LAST_FEE_SEQ = 40;

    private final UUID id;
    private final UUID contractId;
    private final UUID contractInstallmentId;
    private final UUID orderId;
    private final UUID customerId;
    private final String contractNumber;
    private final String orderNumber;
    private final int seq;
    private final String name;
    private final InstallmentMilestone milestone;
    private final UUID deliveryBatchId;
    private final Integer deliveryBatchSeq;
    private final BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal reducedAmount;
    private final String currency;
    private final Integer dueDays;
    private Instant reachedAt;
    private LocalDate dueDate;
    private InstallmentStatus status;
    private Instant paidAt;
    private Instant cancelledAt;
    private final long version;

    /** Restores one as stored, nothing taken off it by a closure. */
    public Installment(UUID id, UUID contractId, UUID contractInstallmentId, UUID orderId, UUID customerId,
                       String contractNumber, String orderNumber, int seq, String name, InstallmentMilestone milestone,
                       UUID deliveryBatchId, Integer deliveryBatchSeq, BigDecimal amount, BigDecimal paidAmount,
                       String currency, Integer dueDays, Instant reachedAt, LocalDate dueDate, InstallmentStatus status,
                       Instant paidAt, Instant cancelledAt, long version) {
        this(id, contractId, contractInstallmentId, orderId, customerId, contractNumber, orderNumber, seq, name,
                milestone, deliveryBatchId, deliveryBatchSeq, amount, paidAmount, BigDecimal.ZERO, currency, dueDays,
                reachedAt, dueDate, status, paidAt, cancelledAt, version);
    }

    /** Restores one as stored. */
    public Installment(UUID id, UUID contractId, UUID contractInstallmentId, UUID orderId, UUID customerId,
                       String contractNumber, String orderNumber, int seq, String name, InstallmentMilestone milestone,
                       UUID deliveryBatchId, Integer deliveryBatchSeq, BigDecimal amount, BigDecimal paidAmount,
                       BigDecimal reducedAmount, String currency, Integer dueDays, Instant reachedAt, LocalDate dueDate,
                       InstallmentStatus status, Instant paidAt, Instant cancelledAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.contractId = Objects.requireNonNull(contractId, "contractId");
        this.milestone = Objects.requireNonNull(milestone, "milestone");
        // ck_installment_fee_item: a fee item names no plan row, every other installment names its own.
        if ((milestone == InstallmentMilestone.CANCELLATION_FEE) != (contractInstallmentId == null)) {
            throw new IllegalArgumentException("Only a cancellation fee item has no plan row");
        }
        this.contractInstallmentId = contractInstallmentId;
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.contractNumber = Objects.requireNonNull(contractNumber, "contractNumber");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        this.seq = seq;
        this.name = Objects.requireNonNull(name, "name");
        this.deliveryBatchId = deliveryBatchId;
        this.deliveryBatchSeq = deliveryBatchSeq;
        this.amount = Objects.requireNonNull(amount, "amount");
        this.paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        this.reducedAmount = reducedAmount == null ? BigDecimal.ZERO : reducedAmount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.dueDays = dueDays;
        this.reachedAt = reachedAt;
        this.dueDate = dueDate;
        this.status = Objects.requireNonNull(status, "status");
        this.paidAt = paidAt;
        this.cancelledAt = cancelledAt;
        this.version = version;
        // ck_installment_status: overdue is a flag, never a stored status (V20261121000100).
        if (status == InstallmentStatus.OVERDUE) {
            throw new IllegalArgumentException("OVERDUE is historical: an installment is overdue by its due day");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("An installment is owed money");
        }
        if ((milestone == InstallmentMilestone.BEFORE_DELIVERY) != (dueDays == null)) {
            throw new IllegalArgumentException("Only an installment paid before delivery has no days to pay");
        }
    }

    /**
     * The installment the signed plan's row {@code contractInstallmentId} makes (15 §4.1 step 1): NOT_DUE. One due on
     * signing is {@link #reach reached} at once by whoever opens the plan.
     */
    public static Installment open(UUID contractId, UUID contractInstallmentId, UUID orderId, UUID customerId,
                                   String contractNumber, String orderNumber, int seq, String name,
                                   InstallmentMilestone milestone, UUID deliveryBatchId, Integer deliveryBatchSeq,
                                   BigDecimal amount, String currency, Integer dueDays) {
        return new Installment(Identifiers.newId(), contractId, Objects.requireNonNull(contractInstallmentId,
                "contractInstallmentId"), orderId, customerId, contractNumber, orderNumber, seq, name, milestone,
                deliveryBatchId, deliveryBatchSeq, amount, BigDecimal.ZERO, currency, dueDays, null, null,
                InstallmentStatus.NOT_DUE, null, null, 0L);
    }

    /**
     * The cancellation fee item (15 §4.6 step 18, §5.1): the part of a fee the money collected did not cover, which the
     * customer pays by receipt like an installment. Due at once, by the day it is opened (§5.1: "đến hạn ngay ngày huỷ /
     * ngày đóng phần còn lại"), so it is {@link #overdue} from the next day it is still unpaid.
     *
     * @param seq {@link #FIRST_FEE_SEQ} for the contract's first fee item, then on
     */
    public static Installment openFee(Installment ofContract, int seq, String name, BigDecimal owed, Instant at,
                                      LocalDate day) {
        Objects.requireNonNull(ofContract, "ofContract");
        if (seq < FIRST_FEE_SEQ || seq > LAST_FEE_SEQ) {
            throw new IllegalArgumentException("A fee item is numbered %d to %d, not %d"
                    .formatted(FIRST_FEE_SEQ, LAST_FEE_SEQ, seq));
        }
        Installment fee = new Installment(Identifiers.newId(), ofContract.contractId, null, ofContract.orderId,
                ofContract.customerId, ofContract.contractNumber, ofContract.orderNumber, seq, name,
                InstallmentMilestone.CANCELLATION_FEE, null, null, owed, BigDecimal.ZERO, ofContract.currency, 0, null,
                null, InstallmentStatus.NOT_DUE, null, null, 0L);
        fee.reach(at, day);
        return fee;
    }

    /**
     * Its milestone came on {@code day} (15 §4.1 step 2): due from then on, and by {@code day} plus its days to pay
     * unless it is paid before delivery, which has no due day. Paid early, it keeps PARTIALLY_PAID or PAID and from now
     * on has a due day (step 3). A milestone comes once; a cancelled installment stays cancelled.
     *
     * @return whether anything changed
     */
    public boolean reach(Instant at, LocalDate day) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(day, "day");
        if (reachedAt != null || status == InstallmentStatus.CANCELLED) {
            return false;
        }
        this.reachedAt = at;
        this.dueDate = milestone == InstallmentMilestone.BEFORE_DELIVERY ? null : day.plusDays(dueDays);
        if (status == InstallmentStatus.NOT_DUE) {
            this.status = InstallmentStatus.DUE;
        }
        return true;
    }

    /**
     * A receipt line pays {@code money} of it (15 §4.2 step 6-7): never more than it still owes (BR-05: nothing is left
     * over), never once it is paid in full or cancelled. Paid in full it is PAID; short of that it is PARTIALLY_PAID -
     * past its due day too: it is then {@link #overdue} for what is left, which the status no longer hides.
     *
     * @throws BusinessException {@code INSTALLMENT_NOT_PAYABLE}; {@code PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING}
     */
    public void pay(BigDecimal money, Instant at) {
        Objects.requireNonNull(money, "money");
        if (money.signum() <= 0) {
            throw new IllegalArgumentException("A receipt line pays money");
        }
        if (!status.takesMoney()) {
            throw new BusinessException(ErrorCode.INSTALLMENT_NOT_PAYABLE, "Installment %d of contract %s is %s"
                    .formatted(seq, contractNumber, status));
        }
        if (money.compareTo(outstanding()) > 0) {
            throw new BusinessException(ErrorCode.PAYMENT_RECEIPT_EXCEEDS_OUTSTANDING,
                    "Installment %d of contract %s still owes %s %s, not %s".formatted(seq, contractNumber,
                            outstanding().toPlainString(), currency, money.toPlainString()));
        }
        this.paidAmount = paidAmount.add(money);
        if (paidAmount.add(reducedAmount).compareTo(amount) == 0) {
            this.status = InstallmentStatus.PAID;
            this.paidAt = Objects.requireNonNull(at, "at");
        } else {
            this.status = InstallmentStatus.PARTIALLY_PAID;
        }
    }

    /**
     * A receipt that paid {@code money} of it was voided (15 §4.4 step 12): the money no longer counts, and the status
     * says again what is left - PARTIALLY_PAID, DUE or NOT_DUE. Whether it is overdue again is the due day's to say
     * ({@link #overdue}), not this method's.
     */
    public void unpay(BigDecimal money) {
        Objects.requireNonNull(money, "money");
        if (status == InstallmentStatus.CANCELLED) {
            throw new IllegalStateException("Installment %d of contract %s is cancelled; its receipts stay"
                    .formatted(seq, contractNumber));
        }
        if (money.signum() <= 0 || money.compareTo(paidAmount) > 0) {
            throw new IllegalStateException("Installment %d of contract %s was paid %s, not %s".formatted(seq,
                    contractNumber, paidAmount.toPlainString(), money.toPlainString()));
        }
        this.paidAmount = paidAmount.subtract(money);
        this.paidAt = null;
        if (paidAmount.signum() > 0) {
            this.status = InstallmentStatus.PARTIALLY_PAID;
        } else {
            this.status = reachedAt != null ? InstallmentStatus.DUE : InstallmentStatus.NOT_DUE;
        }
    }

    /**
     * Its order was cancelled (15 §4.6 step 18, BR-15): not paid in full, it is no longer owed. What it was paid stays
     * on it: the cancellation's settlement counts it as collected ({@link OrderSettlement#cancel}). A paid one stays
     * PAID. A fee item is never cancelled: its order is cancelled or closed already, and the fee is what is still owed.
     *
     * @return whether it was cancelled now
     */
    public boolean cancel(Instant at) {
        if (status == InstallmentStatus.PAID || status == InstallmentStatus.CANCELLED || isFeeItem()) {
            return false;
        }
        this.status = InstallmentStatus.CANCELLED;
        this.cancelledAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /**
     * A closure of the rest of the order takes {@code money} off what it still owes (15 §4.6 step 19, BR-16): the
     * customer never pays more than the goods they received and the fees. Never more than it still owes, never a fee
     * item. Settled by it - nothing left to pay - it is PAID when it was paid something, else no longer owed: CANCELLED.
     */
    public void reduce(BigDecimal money, Instant at) {
        Objects.requireNonNull(money, "money");
        Objects.requireNonNull(at, "at");
        if (isFeeItem() || !status.takesMoney()) {
            throw new IllegalStateException("Installment %d of contract %s is %s; nothing is taken off it"
                    .formatted(seq, contractNumber, isFeeItem() ? "a fee item" : status));
        }
        if (money.signum() <= 0 || money.compareTo(outstanding()) > 0) {
            throw new IllegalStateException("Installment %d of contract %s owes %s, %s cannot be taken off"
                    .formatted(seq, contractNumber, outstanding().toPlainString(), money.toPlainString()));
        }
        this.reducedAmount = reducedAmount.add(money);
        if (paidAmount.add(reducedAmount).compareTo(amount) == 0) {
            if (paidAmount.signum() > 0) {
                this.status = InstallmentStatus.PAID;
                this.paidAt = at;
            } else {
                this.status = InstallmentStatus.CANCELLED;
                this.cancelledAt = at;
            }
        }
    }

    /**
     * Whether it is overdue on {@code today} (15 §4.1 step 4, §4.3 step 9; WF-05 fix plan, section 8): its due day is
     * past and it still owes. Worked out, never stored: an installment paid before delivery has no due day and is never
     * overdue, one paid in full or cancelled owes nothing. The credit check ({@code InstallmentBook.overdue}) and
     * {@code payment.installment_is_overdue} in the database say the same.
     */
    public boolean overdue(LocalDate today) {
        Objects.requireNonNull(today, "today");
        return dueDate != null && dueDate.isBefore(today) && status.takesMoney();
    }

    /** What it still owes: its amount less what was paid and taken off; zero once paid or cancelled. */
    public BigDecimal outstanding() {
        return status == InstallmentStatus.CANCELLED ? BigDecimal.ZERO
                : amount.subtract(paidAmount).subtract(reducedAmount);
    }

    /** What it is owed now: its amount less what closures took off (15 §4.6 step 19). */
    public BigDecimal owedAmount() {
        return amount.subtract(reducedAmount);
    }

    /** A cancellation fee item rather than a row of the contract's plan (15 §4.6 step 18). */
    public boolean isFeeItem() {
        return milestone == InstallmentMilestone.CANCELLATION_FEE;
    }

    public boolean reached() { return reachedAt != null; }

    public UUID id() { return id; }
    public UUID contractId() { return contractId; }
    /** The signed plan row it was made from; null for a fee item. */
    public UUID contractInstallmentId() { return contractInstallmentId; }
    public UUID orderId() { return orderId; }
    public UUID customerId() { return customerId; }
    public String contractNumber() { return contractNumber; }
    public String orderNumber() { return orderNumber; }
    public int seq() { return seq; }
    public String name() { return name; }
    public InstallmentMilestone milestone() { return milestone; }
    public UUID deliveryBatchId() { return deliveryBatchId; }
    public Integer deliveryBatchSeq() { return deliveryBatchSeq; }
    public BigDecimal amount() { return amount; }
    public BigDecimal paidAmount() { return paidAmount; }
    public BigDecimal reducedAmount() { return reducedAmount; }
    public String currency() { return currency; }
    public Integer dueDays() { return dueDays; }
    public Instant reachedAt() { return reachedAt; }
    public LocalDate dueDate() { return dueDate; }
    public InstallmentStatus status() { return status; }
    public Instant paidAt() { return paidAt; }
    public Instant cancelledAt() { return cancelledAt; }
    public long version() { return version; }

    /**
     * What the customer writes in the transfer (15 §4.2 step 5: "mã hợp đồng + mã đợt"), e.g.
     * {@code HD-20261108-000007-1 DOT2}: the contract's number and the installment's place in its plan, so the
     * accountant can match the statement line to it. A fee item is {@code ... FEE1}, {@code FEE2}: it has no place in
     * the plan, and must not read as one of its installments.
     */
    public String transferReference() {
        return isFeeItem() ? contractNumber + " FEE" + (seq - FIRST_FEE_SEQ + 1) : contractNumber + " DOT" + seq;
    }
}
