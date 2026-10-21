package com.stockflow.payment.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Money that reached the business, recorded by the accountant from the bank statement (kltn-docs 15 BR-04), once per
 * statement reference (BR-07): a customer's transfer, or a carrier remitting what it collected on delivery (09 §4.3
 * step 4). The one ledger of incoming money (G3-Q3): it pays orders (prepaid, deposit, the balance before delivery) and
 * receivables. What is not allocated is the customer's credit, used on a later order or receivable or refunded on
 * request (15 edge "khách chuyển thừa").
 *
 * <p>The amount never changes. {@code amount = Σ net allocations + refundedAmount + unallocatedAmount} is what the
 * ledger keeps true: an allocation takes from the unallocated money, its reversal gives it back, a credit refund
 * takes it out for good.</p>
 */
public final class CustomerTransfer extends AggregateRoot {

    private final UUID id;
    private final UUID customerId;
    private final String reference;
    private final BigDecimal amount;
    private BigDecimal unallocatedAmount;
    private BigDecimal refundedAmount;
    private final String currency;
    private final LocalDate receivedOn;
    private final UUID recordedBy;
    private final Instant recordedAt;
    private final String note;
    private final TransferChannel channel;
    private final String carrierName;
    private final long version;

    public CustomerTransfer(UUID id, UUID customerId, String reference, BigDecimal amount, BigDecimal unallocatedAmount,
                            String currency, LocalDate receivedOn, UUID recordedBy, Instant recordedAt, String note,
                            long version) {
        this(id, customerId, reference, amount, unallocatedAmount, BigDecimal.ZERO, currency, receivedOn, recordedBy,
                recordedAt, note, TransferChannel.BANK_TRANSFER, null, version);
    }

    public CustomerTransfer(UUID id, UUID customerId, String reference, BigDecimal amount, BigDecimal unallocatedAmount,
                            BigDecimal refundedAmount, String currency, LocalDate receivedOn, UUID recordedBy,
                            Instant recordedAt, String note, TransferChannel channel, String carrierName,
                            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.reference = reference == null ? null : reference.trim();
        this.amount = Objects.requireNonNull(amount, "amount");
        this.unallocatedAmount = Objects.requireNonNull(unallocatedAmount, "unallocatedAmount");
        this.refundedAmount = refundedAmount == null ? BigDecimal.ZERO : refundedAmount;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.receivedOn = Objects.requireNonNull(receivedOn, "receivedOn");
        this.recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.channel = channel == null ? TransferChannel.BANK_TRANSFER : channel;
        this.carrierName = carrierName == null || carrierName.isBlank() ? null : carrierName.trim();
        this.version = version;
        if (this.reference == null || this.reference.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A transfer names its bank statement reference");
        }
        if (amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A transfer brings money");
        }
        if ((this.channel == TransferChannel.CARRIER_REMITTANCE) != (this.carrierName != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A carrier remittance names its carrier");
        }
    }

    public static CustomerTransfer record(UUID customerId, String reference, BigDecimal amount, String currency,
                                          LocalDate receivedOn, UUID recordedBy, Instant now, String note) {
        return new CustomerTransfer(Identifiers.newId(), customerId, reference, amount, amount, currency, receivedOn,
                recordedBy, now, note, 0L);
    }

    /**
     * What a carrier remitted for one order's balance (09 §4.3 step 4): the customer paid the carrier on delivery, so
     * the money is the customer's, and its reference names the carrier's statement line and the order, since one
     * remittance carries many orders (15 BR-07 still holds per order).
     */
    public static CustomerTransfer carrierRemittance(UUID customerId, String carrier, String reference,
                                                     BigDecimal amount, String currency, LocalDate receivedOn,
                                                     UUID recordedBy, Instant now, String note) {
        return new CustomerTransfer(Identifiers.newId(), customerId, reference, amount, amount, BigDecimal.ZERO,
                currency, receivedOn, recordedBy, now, note, TransferChannel.CARRIER_REMITTANCE, carrier, 0L);
    }

    /** Takes up to {@code wanted} of the money not yet allocated; returns what was taken. */
    public BigDecimal take(BigDecimal wanted) {
        BigDecimal taken = wanted.min(unallocatedAmount).max(BigDecimal.ZERO);
        this.unallocatedAmount = unallocatedAmount.subtract(taken);
        return taken;
    }

    /**
     * Takes exactly {@code wanted} or nothing: an allocation the accountant asked for by amount.
     *
     * @throws BusinessException {@code ALLOCATION_EXCEEDS_TRANSFER} when the transfer has less left
     */
    public void takeExactly(BigDecimal wanted) {
        if (wanted.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An allocation brings money");
        }
        if (wanted.compareTo(unallocatedAmount) > 0) {
            throw new BusinessException(ErrorCode.ALLOCATION_EXCEEDS_TRANSFER,
                    "Transfer %s has %s %s left to allocate, not %s".formatted(reference,
                            plain(unallocatedAmount), currency, plain(wanted)));
        }
        this.unallocatedAmount = unallocatedAmount.subtract(wanted);
    }

    /** A reversed allocation's money is the customer's credit again (15 BR-06: the reversal is its own row). */
    public void giveBack(BigDecimal reversed) {
        if (reversed.signum() <= 0 || unallocatedAmount.add(reversed).add(refundedAmount).compareTo(amount) > 0) {
            throw new IllegalStateException("Transfer %s cannot get back %s".formatted(id, reversed));
        }
        this.unallocatedAmount = unallocatedAmount.add(reversed);
    }

    /**
     * Refund part of the customer's credit (15 edge "hoàn lại theo yêu cầu", "tiền về sau khi đơn đã huỷ"): it leaves
     * the ledger as a PENDING refund the accountant pays out. Never more than the transfer still holds (BR-05).
     *
     * @throws BusinessException {@code ALLOCATION_EXCEEDS_TRANSFER}
     */
    public void refundCredit(BigDecimal refunded) {
        takeExactly(refunded);
        this.refundedAmount = refundedAmount.add(refunded);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public String reference() { return reference; }
    public BigDecimal amount() { return amount; }
    public BigDecimal unallocatedAmount() { return unallocatedAmount; }
    public BigDecimal refundedAmount() { return refundedAmount; }
    public String currency() { return currency; }
    public LocalDate receivedOn() { return receivedOn; }
    public UUID recordedBy() { return recordedBy; }
    public Instant recordedAt() { return recordedAt; }
    public String note() { return note; }
    public TransferChannel channel() { return channel; }
    public String carrierName() { return carrierName; }
    public long version() { return version; }
}
