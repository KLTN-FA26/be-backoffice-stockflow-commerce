package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One pause of an order's automatic completion (WF-05 GH-2, {@code ordering.order_completion_hold}): held with a
 * reason, released with a note, and kept afterwards - {@code tg_order_completion_hold_kept} refuses any other change.
 * The reason is stored by name ({@code ck_order_completion_hold_reason}), as {@code order_hold.reason} is.
 */
@Entity
@Table(name = "order_completion_hold", schema = "ordering")
public class OrderCompletionHoldJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "reason", nullable = false, updatable = false, length = 32) private String reason;
    @Column(name = "note", updatable = false, length = 1000) private String note;
    @Column(name = "held_at", nullable = false, updatable = false) private Instant heldAt;
    @Column(name = "held_by", nullable = false, updatable = false) private UUID heldBy;
    @Column(name = "released_at") private Instant releasedAt;
    @Column(name = "released_by") private UUID releasedBy;
    @Column(name = "release_note", length = 1000) private String releaseNote;

    protected OrderCompletionHoldJpaEntity() {
    }

    public OrderCompletionHoldJpaEntity(UUID id, UUID orderId, String reason, String note, Instant heldAt,
                                        UUID heldBy) {
        super(id);
        this.orderId = orderId;
        this.reason = reason;
        this.note = note;
        this.heldAt = heldAt;
        this.heldBy = heldBy;
    }

    /** Released once; a second release changes nothing (the caller refuses it first). */
    public void release(UUID by, String note, Instant at) {
        if (releasedAt != null) {
            return;
        }
        this.releasedBy = by;
        this.releaseNote = note;
        this.releasedAt = at;
    }

    public UUID getOrderId() { return orderId; }
    public String getReason() { return reason; }
    public String getNote() { return note; }
    public Instant getHeldAt() { return heldAt; }
    public UUID getHeldBy() { return heldBy; }
    public Instant getReleasedAt() { return releasedAt; }
    public UUID getReleasedBy() { return releasedBy; }
    public String getReleaseNote() { return releaseNote; }
}
