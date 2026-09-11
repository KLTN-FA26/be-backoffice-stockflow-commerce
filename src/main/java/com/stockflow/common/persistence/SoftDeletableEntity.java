package com.stockflow.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;
import java.util.UUID;

/**
 * Base class for entities that are hidden rather than deleted.
 *
 * <h2>When to extend this, and when not to</h2>
 *
 * <p>Soft delete is not a default to apply everywhere — it is a decision with a cost. Extend this
 * when the row is <b>referenced by history</b>: a product that appears on last year's invoices, a
 * customer with closed orders, a warehouse location a stock movement points at. Deleting those for
 * real either breaks the history or, worse, silently succeeds and leaves a report unable to
 * resolve the name.</p>
 *
 * <p>Do <b>not</b> extend it for rows with no history behind them — a draft, a cart line, a
 * notification preference. A soft-deleted row still occupies the table, still has to be filtered
 * out of every query, and still collides with unique constraints (see below). Where a real
 * {@code DELETE} is correct, use one.</p>
 *
 * <h2>Two things that must be done on the concrete entity</h2>
 *
 * <p><b>1. {@code @SQLRestriction("deleted_at is null")} on the {@code @Entity} class.</b> It is
 * not inherited from a mapped superclass, so without it every finder returns deleted rows and the
 * soft delete does nothing at all. This is the single most common way to get soft delete wrong,
 * so {@code ArchitectureTest} fails the build when an entity extends this class without it.</p>
 *
 * <p><b>2. Unique constraints must include the deleted flag.</b> A plain
 * {@code UNIQUE (code)} makes it impossible to re-create a record whose predecessor was soft
 * deleted — the old row is still there and still holds the code. Write it as a partial index
 * instead:</p>
 * <pre>
 * CREATE UNIQUE INDEX uk_product_code
 *     ON product.product (code) WHERE deleted_at IS NULL;
 * </pre>
 *
 * <p>Both points are the reason this is a base class with documentation rather than an annotation:
 * the mechanics are easy, the two traps are not.</p>
 */
@MappedSuperclass
public abstract class SoftDeletableEntity extends BaseEntity {

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    protected SoftDeletableEntity() {
    }

    protected SoftDeletableEntity(UUID id) {
        super(id);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Hide the row.
     *
     * <p>Idempotent: deleting an already-deleted row keeps the original timestamp and actor. Two
     * concurrent delete requests, or a retried one, must not rewrite who deleted it and when — the
     * first answer is the true one.</p>
     */
    public void softDelete(Instant now, String actor) {
        if (deletedAt == null) {
            this.deletedAt = now;
            this.deletedBy = actor;
        }
    }

    /**
     * Bring the row back.
     *
     * <p>Deliberately present, and deliberately not something to expose casually: restoring a row
     * whose code was taken over by a replacement will fail on the partial unique index described in
     * the class javadoc. Check for a conflict before calling this.</p>
     */
    public void restore() {
        this.deletedAt = null;
        this.deletedBy = null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public String getDeletedBy() {
        return deletedBy;
    }
}
