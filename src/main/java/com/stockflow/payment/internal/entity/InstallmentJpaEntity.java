package com.stockflow.payment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.InstallmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** {@code payment.installment} (4B-6): what it is is written once; the money, the milestone and the status move. */
@Entity
@Table(name = "installment", schema = "payment")
public class InstallmentJpaEntity extends BaseEntity {

    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    /** The signed plan row it was made from; null for a cancellation fee item (4B-9), which has none. */
    @Column(name = "contract_installment_id", updatable = false)
    private UUID contractInstallmentId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "contract_number", nullable = false, updatable = false, length = 40)
    private String contractNumber;

    @Column(name = "order_number", nullable = false, updatable = false, length = 32)
    private String orderNumber;

    @Column(name = "seq", nullable = false, updatable = false)
    private short seq;

    @Column(name = "name", nullable = false, updatable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "milestone", nullable = false, updatable = false, length = 16)
    private InstallmentMilestone milestone;

    @Column(name = "delivery_batch_id", updatable = false)
    private UUID deliveryBatchId;

    @Column(name = "delivery_batch_seq", updatable = false)
    private Short deliveryBatchSeq;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount;

    /** 4B-9: what closures of the rest took off it (payment.installment_reduction). */
    @Column(name = "reduced_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal reducedAmount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "due_days", updatable = false)
    private Integer dueDays;

    @Column(name = "reached_at")
    private Instant reachedAt;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InstallmentStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected InstallmentJpaEntity() {
    }

    public InstallmentJpaEntity(UUID id, UUID contractId, UUID contractInstallmentId, UUID orderId, UUID customerId,
                                String contractNumber, String orderNumber, int seq, String name,
                                InstallmentMilestone milestone, UUID deliveryBatchId, Integer deliveryBatchSeq,
                                BigDecimal amount, String currency, Integer dueDays) {
        super(id);
        this.contractId = contractId;
        this.contractInstallmentId = contractInstallmentId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.contractNumber = contractNumber;
        this.orderNumber = orderNumber;
        this.seq = (short) seq;
        this.name = name;
        this.milestone = milestone;
        this.deliveryBatchId = deliveryBatchId;
        this.deliveryBatchSeq = deliveryBatchSeq == null ? null : deliveryBatchSeq.shortValue();
        this.amount = amount;
        this.paidAmount = BigDecimal.ZERO;
        this.reducedAmount = BigDecimal.ZERO;
        this.currency = currency;
        this.dueDays = dueDays;
        this.status = InstallmentStatus.NOT_DUE;
    }

    /** What moves: the money, the milestone and the status (the rest is {@code updatable = false}). */
    public void apply(BigDecimal paidAmount, BigDecimal reducedAmount, Instant reachedAt, LocalDate dueDate,
                      InstallmentStatus status, Instant paidAt, Instant cancelledAt) {
        this.paidAmount = paidAmount;
        this.reducedAmount = reducedAmount;
        this.reachedAt = reachedAt;
        this.dueDate = dueDate;
        this.status = status;
        this.paidAt = paidAt;
        this.cancelledAt = cancelledAt;
    }

    public UUID getContractId() { return contractId; }
    public UUID getContractInstallmentId() { return contractInstallmentId; }
    public UUID getOrderId() { return orderId; }
    public UUID getCustomerId() { return customerId; }
    public String getContractNumber() { return contractNumber; }
    public String getOrderNumber() { return orderNumber; }
    public int getSeq() { return seq; }
    public String getName() { return name; }
    public InstallmentMilestone getMilestone() { return milestone; }
    public UUID getDeliveryBatchId() { return deliveryBatchId; }
    public Integer getDeliveryBatchSeq() { return deliveryBatchSeq == null ? null : deliveryBatchSeq.intValue(); }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public BigDecimal getReducedAmount() { return reducedAmount; }
    public String getCurrency() { return currency; }
    public Integer getDueDays() { return dueDays; }
    public Instant getReachedAt() { return reachedAt; }
    public LocalDate getDueDate() { return dueDate; }
    public InstallmentStatus getStatus() { return status; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}
