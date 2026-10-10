package com.stockflow.payment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** {@code payment.customer_transfer} (SCRUM-431). Only the money not yet allocated changes. */
@Entity
@Table(name = "customer_transfer", schema = "payment")
public class CustomerTransferJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "reference", nullable = false, length = 100, updatable = false)
    private String reference;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "unallocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unallocatedAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    protected CustomerTransferJpaEntity() {
    }

    public CustomerTransferJpaEntity(UUID id, UUID customerId, String reference, BigDecimal amount, String currency,
                                     LocalDate receivedOn, UUID recordedBy, Instant recordedAt, String note) {
        super(id);
        this.customerId = customerId;
        this.reference = reference;
        this.amount = amount;
        this.unallocatedAmount = amount;
        this.currency = currency;
        this.receivedOn = receivedOn;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.note = note;
    }

    public void apply(BigDecimal unallocatedAmount) {
        this.unallocatedAmount = unallocatedAmount;
    }

    public UUID getCustomerId() { return customerId; }
    public String getReference() { return reference; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getUnallocatedAmount() { return unallocatedAmount; }
    public String getCurrency() { return currency; }
    public LocalDate getReceivedOn() { return receivedOn; }
    public UUID getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
    public String getNote() { return note; }
}
