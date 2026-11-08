package com.stockflow.design.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * {@code design.design_project_quote_request_line}: one approved design of a project quote request and the cartons
 * asked of it - a suggestion (kltn-docs 12 BR-21, 14 BR-22). Insert-only.
 *
 * <p>{@link Persistable} with an explicit new flag: with an assigned key Spring Data would otherwise merge.</p>
 */
@Entity
@Table(name = "design_project_quote_request_line", schema = "design")
@IdClass(DesignProjectQuoteRequestLineJpaEntity.Key.class)
public class DesignProjectQuoteRequestLineJpaEntity implements Persistable<DesignProjectQuoteRequestLineJpaEntity.Key> {

    /** The table's key: the request and the design. */
    public static class Key implements Serializable {
        private UUID requestId;
        private UUID draftId;

        protected Key() {
        }

        public Key(UUID requestId, UUID draftId) {
            this.requestId = requestId;
            this.draftId = draftId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(requestId, key.requestId)
                    && Objects.equals(draftId, key.draftId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(requestId, draftId);
        }
    }

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Id
    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;

    @Column(name = "cartons", nullable = false, updatable = false)
    private int cartons;

    @Transient
    private boolean fresh;

    protected DesignProjectQuoteRequestLineJpaEntity() {
    }

    public DesignProjectQuoteRequestLineJpaEntity(UUID requestId, UUID draftId, int cartons) {
        this.requestId = requestId;
        this.draftId = draftId;
        this.cartons = cartons;
        this.fresh = true;
    }

    @PostLoad
    @PostPersist
    void stored() {
        fresh = false;
    }

    @Override
    public Key getId() { return new Key(requestId, draftId); }

    @Override
    public boolean isNew() { return fresh; }

    public UUID getRequestId() { return requestId; }
    public UUID getDraftId() { return draftId; }
    public int getCartons() { return cartons; }
}
