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
 * Money a customer sent to pay what they owe, recorded by the accountant from the bank statement
 * (kltn-docs 15 §4.3 step 3, BR-04), once per statement reference (BR-07). What is not yet allocated
 * to a receivable is the customer's credit, used on their next receivable (15, "khách chuyển thừa").
 */
public final class CustomerTransfer extends AggregateRoot {

    private final UUID id;
    private final UUID customerId;
    private final String reference;
    private final BigDecimal amount;
    private BigDecimal unallocatedAmount;
    private final String currency;
    private final LocalDate receivedOn;
    private final UUID recordedBy;
    private final Instant recordedAt;
    private final String note;
    private final long version;

    public CustomerTransfer(UUID id, UUID customerId, String reference, BigDecimal amount, BigDecimal unallocatedAmount,
                            String currency, LocalDate receivedOn, UUID recordedBy, Instant recordedAt, String note,
                            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.reference = reference == null ? null : reference.trim();
        this.amount = Objects.requireNonNull(amount, "amount");
        this.unallocatedAmount = Objects.requireNonNull(unallocatedAmount, "unallocatedAmount");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.receivedOn = Objects.requireNonNull(receivedOn, "receivedOn");
        this.recordedBy = Objects.requireNonNull(recordedBy, "recordedBy");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.version = version;
        if (this.reference == null || this.reference.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A transfer names its bank statement reference");
        }
        if (amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A transfer brings money");
        }
    }

    public static CustomerTransfer record(UUID customerId, String reference, BigDecimal amount, String currency,
                                          LocalDate receivedOn, UUID recordedBy, Instant now, String note) {
        return new CustomerTransfer(Identifiers.newId(), customerId, reference, amount, amount, currency, receivedOn,
                recordedBy, now, note, 0L);
    }

    /** Takes up to {@code wanted} of the money not yet allocated; returns what was taken. */
    public BigDecimal take(BigDecimal wanted) {
        BigDecimal taken = wanted.min(unallocatedAmount).max(BigDecimal.ZERO);
        this.unallocatedAmount = unallocatedAmount.subtract(taken);
        return taken;
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public String reference() { return reference; }
    public BigDecimal amount() { return amount; }
    public BigDecimal unallocatedAmount() { return unallocatedAmount; }
    public String currency() { return currency; }
    public LocalDate receivedOn() { return receivedOn; }
    public UUID recordedBy() { return recordedBy; }
    public Instant recordedAt() { return recordedAt; }
    public String note() { return note; }
    public long version() { return version; }
}
