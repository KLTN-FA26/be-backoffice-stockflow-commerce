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
 * While it is open, asking again changes the cartons and the date; once closed, nothing changes.
 */
@Entity
@Table(name = "design_quote_request", schema = "design")
public class DesignQuoteRequestJpaEntity extends BaseEntity {

    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;
    @Column(name = "cartons", nullable = false)
    private int cartons;
    @Column(name = "desired_delivery_date")
    private LocalDate desiredDeliveryDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuoteRequestStatus status;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by")
    private UUID closedBy;

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

    /** Asked again while open: the customer's latest wish is what the sales staff answer. */
    public void askAgain(int nextCartons, LocalDate nextDesiredDeliveryDate) {
        cartons = nextCartons;
        desiredDeliveryDate = nextDesiredDeliveryDate;
    }

    /**
     * Closed: answered by {@code by}, a salesperson; or, with {@code by} null, the draft it was on was deleted (B40).
     */
    public void close(Instant at, UUID by) {
        status = QuoteRequestStatus.CLOSED;
        closedAt = at;
        closedBy = by;
    }

    public UUID getDraftId() { return draftId; }
    public UUID getRequestedBy() { return requestedBy; }
    public int getCartons() { return cartons; }
    public LocalDate getDesiredDeliveryDate() { return desiredDeliveryDate; }
    public QuoteRequestStatus getStatus() { return status; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getClosedBy() { return closedBy; }
}
