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
 * ({@link #unpay}); the unpaid installments of a cancelled order are {@link #cancel cancelled}.
 *
 * <p>The status always says what the money and the milestone say ({@code ck_installment_status_facts}); OVERDUE is the
 * one status a payment does not lift short of the full amount (§5.1: "giữ Overdue cho tới khi trả đủ"), because being
 * overdue is what the next credit check needs to know (BR-11).</p>
 */
public final class Installment {

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
    private final String currency;
    private final Integer dueDays;
    private Instant reachedAt;
    private LocalDate dueDate;
    private InstallmentStatus status;
    private Instant paidAt;
    private Instant cancelledAt;
    private final long version;

    /** Restores one as stored. */
    public Installment(UUID id, UUID contractId, UUID contractInstallmentId, UUID orderId, UUID customerId,
                       String contractNumber, String orderNumber, int seq, String name, InstallmentMilestone milestone,
                       UUID deliveryBatchId, Integer deliveryBatchSeq, BigDecimal amount, BigDecimal paidAmount,
                       String currency, Integer dueDays, Instant reachedAt, LocalDate dueDate, InstallmentStatus status,
                       Instant paidAt, Instant cancelledAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.contractId = Objects.requireNonNull(contractId, "contractId");
        this.contractInstallmentId = Objects.requireNonNull(contractInstallmentId, "contractInstallmentId");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.contractNumber = Objects.requireNonNull(contractNumber, "contractNumber");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        this.seq = seq;
        this.name = Objects.requireNonNull(name, "name");
        this.milestone = Objects.requireNonNull(milestone, "milestone");
        this.deliveryBatchId = deliveryBatchId;
        this.deliveryBatchSeq = deliveryBatchSeq;
        this.amount = Objects.requireNonNull(amount, "amount");
        this.paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.dueDays = dueDays;
        this.reachedAt = reachedAt;
        this.dueDate = dueDate;
        this.status = Objects.requireNonNull(status, "status");
        this.paidAt = paidAt;
        this.cancelledAt = cancelledAt;
        this.version = version;
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
        return new Installment(Identifiers.newId(), contractId, contractInstallmentId, orderId, customerId,
                contractNumber, orderNumber, seq, name, milestone, deliveryBatchId, deliveryBatchSeq, amount,
                BigDecimal.ZERO, currency, dueDays, null, null, InstallmentStatus.NOT_DUE, null, null, 0L);
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
     * over), never once it is paid in full or cancelled. Paid in full it is PAID; short of that it is PARTIALLY_PAID,
     * unless it is OVERDUE, which it stays until paid in full.
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
        if (paidAmount.compareTo(amount) == 0) {
            this.status = InstallmentStatus.PAID;
            this.paidAt = Objects.requireNonNull(at, "at");
        } else if (status != InstallmentStatus.OVERDUE) {
            this.status = InstallmentStatus.PARTIALLY_PAID;
        }
    }

    /**
     * A receipt that paid {@code money} of it was voided (15 §4.4 step 12): the money no longer counts, and the status
     * says again what is left - OVERDUE when its due day is past, else PARTIALLY_PAID, DUE or NOT_DUE.
     */
    public void unpay(BigDecimal money, LocalDate today) {
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
        if (dueDate != null && dueDate.isBefore(today)) {
            this.status = InstallmentStatus.OVERDUE;
        } else if (paidAmount.signum() > 0) {
            this.status = InstallmentStatus.PARTIALLY_PAID;
        } else {
            this.status = reachedAt != null ? InstallmentStatus.DUE : InstallmentStatus.NOT_DUE;
        }
    }

    /**
     * Its order was cancelled (15 §4.6 step 18, BR-15): not paid in full, it is no longer owed. What it was paid stays
     * on it, for the refund 4B-9 works out. A paid one stays PAID.
     *
     * @return whether it was cancelled now
     */
    public boolean cancel(Instant at) {
        if (status == InstallmentStatus.PAID || status == InstallmentStatus.CANCELLED) {
            return false;
        }
        this.status = InstallmentStatus.CANCELLED;
        this.cancelledAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /** What it still owes; zero once paid or cancelled. */
    public BigDecimal outstanding() {
        return status == InstallmentStatus.CANCELLED ? BigDecimal.ZERO : amount.subtract(paidAmount);
    }

    public boolean reached() { return reachedAt != null; }

    public UUID id() { return id; }
    public UUID contractId() { return contractId; }
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
     * accountant can match the statement line to it.
     */
    public String transferReference() {
        return contractNumber + " DOT" + seq;
    }
}
