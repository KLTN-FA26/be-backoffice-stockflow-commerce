package com.stockflow.payment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.payment.internal.domain.CreditRequestSource;
import com.stockflow.payment.internal.domain.CreditRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.credit_request} (QĐ-A, 2026-11-26): the table mapping of
 * {@link com.stockflow.payment.internal.domain.CreditRequest} and nothing else. What was asked is written once; the
 * opinion and the decision are added as the request moves.
 */
@Entity
@Table(name = "credit_request", schema = "payment")
public class CreditRequestJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false) private UUID customerId;
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, updatable = false, length = 16) private CreditRequestSource source;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20) private CreditRequestStatus status;
    @Column(name = "requested_limit", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal requestedLimit;
    @Column(name = "requested_term_days", nullable = false, updatable = false) private int requestedTermDays;
    @Column(name = "currency", nullable = false, updatable = false, length = 3) private String currency;
    @Column(name = "reason", updatable = false, length = 1000) private String reason;
    @Column(name = "submitted_by", nullable = false, updatable = false) private UUID submittedBy;
    @Column(name = "submitted_at", nullable = false, updatable = false) private Instant submittedAt;
    @Column(name = "sales_note", length = 1000) private String salesNote;
    @Column(name = "sales_recommended_limit", precision = 19, scale = 4) private BigDecimal salesRecommendedLimit;
    @Column(name = "recommended_by") private UUID recommendedBy;
    @Column(name = "recommended_at") private Instant recommendedAt;
    @Column(name = "approved_limit", precision = 19, scale = 4) private BigDecimal approvedLimit;
    @Column(name = "approved_term_days") private Integer approvedTermDays;
    @Column(name = "decided_by") private UUID decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_note", length = 1000) private String decisionNote;

    protected CreditRequestJpaEntity() {
    }

    public CreditRequestJpaEntity(UUID id, UUID customerId, CreditRequestSource source, BigDecimal requestedLimit,
                                  int requestedTermDays, String currency, String reason, UUID submittedBy,
                                  Instant submittedAt) {
        super(id);
        this.customerId = customerId;
        this.source = source;
        this.requestedLimit = requestedLimit;
        this.requestedTermDays = requestedTermDays;
        this.currency = currency;
        this.reason = reason;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
    }

    /** What moves after filing: the status, Sales' opinion and the decision. */
    public void progress(CreditRequestStatus status, String salesNote, BigDecimal salesRecommendedLimit,
                         UUID recommendedBy, Instant recommendedAt, BigDecimal approvedLimit,
                         Integer approvedTermDays, UUID decidedBy, Instant decidedAt, String decisionNote) {
        this.status = status;
        this.salesNote = salesNote;
        this.salesRecommendedLimit = salesRecommendedLimit;
        this.recommendedBy = recommendedBy;
        this.recommendedAt = recommendedAt;
        this.approvedLimit = approvedLimit;
        this.approvedTermDays = approvedTermDays;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
    }

    public UUID getCustomerId() { return customerId; }
    public CreditRequestSource getSource() { return source; }
    public CreditRequestStatus getStatus() { return status; }
    public BigDecimal getRequestedLimit() { return requestedLimit; }
    public int getRequestedTermDays() { return requestedTermDays; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getSalesNote() { return salesNote; }
    public BigDecimal getSalesRecommendedLimit() { return salesRecommendedLimit; }
    public UUID getRecommendedBy() { return recommendedBy; }
    public Instant getRecommendedAt() { return recommendedAt; }
    public BigDecimal getApprovedLimit() { return approvedLimit; }
    public Integer getApprovedTermDays() { return approvedTermDays; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
}
