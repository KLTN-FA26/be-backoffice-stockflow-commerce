package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.QuoteStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@code ordering.quote} (V20261015000300) with its lines. */
@Entity
@Table(name = "quote", schema = "ordering")
public class QuoteJpaEntity extends BaseEntity {

    @Column(name = "quote_number", nullable = false, length = 30, updatable = false)
    private String quoteNumber;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuoteStatus status;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_term", nullable = false, length = 16)
    private PaymentTerm paymentTerm;

    @Column(name = "deposit_percent", precision = 5, scale = 2)
    private BigDecimal depositPercent;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "terms", length = 2000)
    private String terms;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "sent_by")
    private UUID sentBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @Column(name = "customer_feedback", length = 2000)
    private String customerFeedback;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "converted_order_id")
    private UUID convertedOrderId;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo")
    private List<QuoteLineJpaEntity> lines = new ArrayList<>();

    protected QuoteJpaEntity() {
    }

    public QuoteJpaEntity(UUID id, String quoteNumber, UUID requestId, UUID customerId) {
        super(id);
        this.quoteNumber = quoteNumber;
        this.requestId = requestId;
        this.customerId = customerId;
    }

    public void apply(QuoteStatus status, int revision, PaymentTerm paymentTerm, BigDecimal depositPercent,
                      Instant validUntil, String terms, Instant sentAt, UUID sentBy, Instant respondedAt,
                      UUID respondedBy, String customerFeedback, String cancellationReason, UUID convertedOrderId,
                      Instant convertedAt) {
        this.status = status;
        this.revision = revision;
        this.paymentTerm = paymentTerm;
        this.depositPercent = depositPercent;
        this.validUntil = validUntil;
        this.terms = terms;
        this.sentAt = sentAt;
        this.sentBy = sentBy;
        this.respondedAt = respondedAt;
        this.respondedBy = respondedBy;
        this.customerFeedback = customerFeedback;
        this.cancellationReason = cancellationReason;
        this.convertedOrderId = convertedOrderId;
        this.convertedAt = convertedAt;
    }

    public void addLine(QuoteLineJpaEntity line) {
        line.attachTo(this);
        lines.add(line);
    }

    public String getQuoteNumber() { return quoteNumber; }
    public UUID getRequestId() { return requestId; }
    public UUID getCustomerId() { return customerId; }
    public QuoteStatus getStatus() { return status; }
    public int getRevision() { return revision; }
    public PaymentTerm getPaymentTerm() { return paymentTerm; }
    public BigDecimal getDepositPercent() { return depositPercent; }
    public Instant getValidUntil() { return validUntil; }
    public String getTerms() { return terms; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
    public Instant getRespondedAt() { return respondedAt; }
    public UUID getRespondedBy() { return respondedBy; }
    public String getCustomerFeedback() { return customerFeedback; }
    public String getCancellationReason() { return cancellationReason; }
    public UUID getConvertedOrderId() { return convertedOrderId; }
    public Instant getConvertedAt() { return convertedAt; }
    public List<QuoteLineJpaEntity> getLines() { return lines; }
}
