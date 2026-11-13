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
 * {@code design.design_project_quote_request}: one quote a customer asks over approved designs of a project, with one
 * delivery date (kltn-docs 12 §4.11, BR-21); its lines are {@link DesignProjectQuoteRequestLineJpaEntity}.
 *
 * <p>One is open per project at a time ({@code uk_design_project_quote_request_open}), as one is per design: while it
 * is open, asking again changes what it asks - the customer's latest wish is what the sales staff answer - and asking
 * the same again changes nothing. The sales staff close it once answered; then nothing changes and the project asks
 * anew. {@code ck_design_project_quote_request_closed} states the closing in the database too.</p>
 */
@Entity
@Table(name = "design_project_quote_request", schema = "design")
public class DesignProjectQuoteRequestJpaEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;
    @Column(name = "desired_delivery_date")
    private LocalDate desiredDeliveryDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuoteRequestStatus status;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by")
    private UUID closedBy;

    protected DesignProjectQuoteRequestJpaEntity() {
    }

    public DesignProjectQuoteRequestJpaEntity(UUID projectId, UUID requestedBy, LocalDate desiredDeliveryDate) {
        super(Identifiers.newId());
        this.projectId = projectId;
        this.requestedBy = requestedBy;
        this.desiredDeliveryDate = desiredDeliveryDate;
        this.status = QuoteRequestStatus.OPEN;
    }

    /**
     * Asked again, differently, while open: the date is the latest one asked (the lines are replaced beside it).
     *
     * @throws IllegalStateException once closed - the caller opens a new request instead
     */
    public void askAgain(LocalDate nextDesiredDeliveryDate) {
        if (status != QuoteRequestStatus.OPEN) {
            throw new IllegalStateException("Project quote request " + getId() + " is closed");
        }
        desiredDeliveryDate = nextDesiredDeliveryDate;
    }

    /** Closed by {@code by}, the salesperson who answered it. Closing a closed request changes nothing. */
    public void close(Instant at, UUID by) {
        if (status == QuoteRequestStatus.OPEN) {
            status = QuoteRequestStatus.CLOSED;
            closedAt = at;
            closedBy = by;
        }
    }

    public UUID getProjectId() { return projectId; }
    public UUID getRequestedBy() { return requestedBy; }
    public LocalDate getDesiredDeliveryDate() { return desiredDeliveryDate; }
    public QuoteRequestStatus getStatus() { return status; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getClosedBy() { return closedBy; }
}
