package com.stockflow.design.internal.entity;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code design.design_project_quote_request}: one quote a customer asks over approved designs of a project, with one
 * delivery date (kltn-docs 12 §4.11, BR-21); its lines are {@link DesignProjectQuoteRequestLineJpaEntity}. What was
 * asked never changes.
 */
@Entity
@Table(name = "design_project_quote_request", schema = "design")
public class DesignProjectQuoteRequestJpaEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;
    @Column(name = "desired_delivery_date", updatable = false)
    private LocalDate desiredDeliveryDate;

    protected DesignProjectQuoteRequestJpaEntity() {
    }

    public DesignProjectQuoteRequestJpaEntity(UUID projectId, UUID requestedBy, LocalDate desiredDeliveryDate) {
        super(Identifiers.newId());
        this.projectId = projectId;
        this.requestedBy = requestedBy;
        this.desiredDeliveryDate = desiredDeliveryDate;
    }

    public UUID getProjectId() { return projectId; }
    public UUID getRequestedBy() { return requestedBy; }
    public LocalDate getDesiredDeliveryDate() { return desiredDeliveryDate; }
}
