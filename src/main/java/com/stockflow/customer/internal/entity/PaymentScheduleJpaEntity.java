package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.customer.api.PaymentMilestone;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A customer's default payment schedule (tables {@code customer.default_payment_schedule} and
 * {@code customer.default_payment_installment}); the instalments are replaced whole with it.
 */
@Entity
@Table(name = "default_payment_schedule", schema = "customer")
public class PaymentScheduleJpaEntity extends BaseEntity {

    /** One instalment row. */
    @Embeddable
    public static class InstallmentRow {
        @Column(name = "seq", nullable = false) private int seq;
        @Column(name = "name", nullable = false, length = 100) private String name;
        @Column(name = "percent", nullable = false, precision = 5, scale = 2) private BigDecimal percent;
        @Enumerated(EnumType.STRING)
        @Column(name = "milestone", nullable = false, length = 16) private PaymentMilestone milestone;
        @Column(name = "due_days", nullable = false) private int dueDays;

        protected InstallmentRow() {
        }

        public InstallmentRow(int seq, String name, BigDecimal percent, PaymentMilestone milestone, int dueDays) {
            this.seq = seq;
            this.name = name;
            this.percent = percent;
            this.milestone = milestone;
            this.dueDays = dueDays;
        }

        public int getSeq() { return seq; }
        public String getName() { return name; }
        public BigDecimal getPercent() { return percent; }
        public PaymentMilestone getMilestone() { return milestone; }
        public int getDueDays() { return dueDays; }
    }

    @Column(name = "customer_id", nullable = false, updatable = false) private UUID customerId;
    @Column(name = "set_by", nullable = false) private UUID setBy;
    @Column(name = "set_at", nullable = false) private Instant setAt;
    @Column(name = "approved_by") private UUID approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "note", length = 1000) private String note;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "default_payment_installment", schema = "customer",
            joinColumns = @JoinColumn(name = "schedule_id"))
    @OrderBy("seq")
    private List<InstallmentRow> installments = new ArrayList<>();

    protected PaymentScheduleJpaEntity() {
    }

    public PaymentScheduleJpaEntity(UUID id, UUID customerId) {
        super(id);
        this.customerId = customerId;
    }

    public void apply(List<InstallmentRow> rows, UUID setBy, Instant setAt, UUID approvedBy, Instant approvedAt,
                      String note) {
        this.installments.clear();
        this.installments.addAll(rows);
        this.setBy = setBy;
        this.setAt = setAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.note = note;
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getSetBy() { return setBy; }
    public Instant getSetAt() { return setAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getNote() { return note; }
    public List<InstallmentRow> getInstallments() { return installments; }
}
