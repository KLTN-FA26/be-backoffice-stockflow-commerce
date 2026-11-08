package com.stockflow.design.internal.entity;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.design.internal.domain.QuoteRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code design.design_quote_request}: a customer's quote request on their design (kltn-docs 12 §4.8, 16 §4.2 step 5).
 * What was asked never changes; only whether it is still open does (B40).
 */
@Entity
@Table(name = "design_quote_request", schema = "design")
public class DesignQuoteRequestJpaEntity extends BaseEntity {

    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;
    @Column(name = "cartons", nullable = false, updatable = false)
    private int cartons;
    @Column(name = "desired_delivery_date", updatable = false)
    private LocalDate desiredDeliveryDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuoteRequestStatus status;
    @Column(name = "closed_at")
    private Instant closedAt;

    protected DesignQuoteRequestJpaEntity() {
    }

    public DesignQuoteRequestJpaEntity(UUID draftId, UUID requestedBy, int cartons, LocalDate desiredDeliveryDate) {
        super(Identifiers.newId());
        this.draftId = draftId;
        this.requestedBy = requestedBy;
        this.cartons = cartons;
        this.desiredDeliveryDate = desiredDeliveryDate;
        this.status = QuoteRequestStatus.OPEN;
    }

    /** The draft it was on was deleted (B40): nothing is left to quote. */
    public void close(Instant at) {
        status = QuoteRequestStatus.CLOSED;
        closedAt = at;
    }

    public UUID getDraftId() { return draftId; }
    public UUID getRequestedBy() { return requestedBy; }
    public int getCartons() { return cartons; }
    public LocalDate getDesiredDeliveryDate() { return desiredDeliveryDate; }
    public QuoteRequestStatus getStatus() { return status; }
    public Instant getClosedAt() { return closedAt; }
}
