package com.stockflow.production.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.production.internal.domain.SampleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code production.sample_request}: one row per sample round (V20261010000200). The identity of a
 * round — number, customer, quote, the round it follows — never changes; the specification changes
 * only while the round is a draft, which the aggregate enforces.
 */
@Entity
@Table(name = "sample_request", schema = "production")
public class SampleRequestJpaEntity extends BaseEntity {

    @Column(name = "request_number", nullable = false, length = 30, updatable = false)
    private String requestNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "quote_id", updatable = false)
    private UUID quoteId;

    @Column(name = "previous_round_id", updatable = false)
    private UUID previousRoundId;

    @Column(name = "round_no", nullable = false, updatable = false)
    private int roundNo;

    @Column(name = "blank_sku", nullable = false, length = 64)
    private String blankSku;

    @Column(name = "design_snapshot_id", nullable = false)
    private UUID designSnapshotId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SampleStatus status;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "responded_by")
    private UUID respondedBy;

    @Column(name = "customer_feedback", length = 2000)
    private String customerFeedback;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    protected SampleRequestJpaEntity() {
    }

    public SampleRequestJpaEntity(UUID id, String requestNumber, UUID customerId, UUID quoteId, UUID previousRoundId,
                                  int roundNo) {
        super(id);
        this.requestNumber = requestNumber;
        this.customerId = customerId;
        this.quoteId = quoteId;
        this.previousRoundId = previousRoundId;
        this.roundNo = roundNo;
    }

    public void apply(String blankSku, UUID designSnapshotId, int quantity, LocalDate dueDate, SampleStatus status,
                      Instant sentAt, Instant respondedAt, UUID respondedBy, String customerFeedback,
                      String cancellationReason) {
        this.blankSku = blankSku;
        this.designSnapshotId = designSnapshotId;
        this.quantity = quantity;
        this.dueDate = dueDate;
        this.status = status;
        this.sentAt = sentAt;
        this.respondedAt = respondedAt;
        this.respondedBy = respondedBy;
        this.customerFeedback = customerFeedback;
        this.cancellationReason = cancellationReason;
    }

    public String getRequestNumber() { return requestNumber; }
    public UUID getCustomerId() { return customerId; }
    public UUID getQuoteId() { return quoteId; }
    public UUID getPreviousRoundId() { return previousRoundId; }
    public int getRoundNo() { return roundNo; }
    public String getBlankSku() { return blankSku; }
    public UUID getDesignSnapshotId() { return designSnapshotId; }
    public int getQuantity() { return quantity; }
    public LocalDate getDueDate() { return dueDate; }
    public SampleStatus getStatus() { return status; }
    public Instant getSentAt() { return sentAt; }
    public Instant getRespondedAt() { return respondedAt; }
    public UUID getRespondedBy() { return respondedBy; }
    public String getCustomerFeedback() { return customerFeedback; }
    public String getCancellationReason() { return cancellationReason; }
}
