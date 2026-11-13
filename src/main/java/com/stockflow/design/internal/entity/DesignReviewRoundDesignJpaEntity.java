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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * {@code design.design_review_round_design}: one design of a review round and the decision on it, null while it waits
 * (kltn-docs 12 BR-20). Changed only under the round's row lock, which decides when the round closes.
 *
 * <p>{@link Persistable} with an explicit new flag: with an assigned key Spring Data would otherwise merge.</p>
 */
@Entity
@Table(name = "design_review_round_design", schema = "design")
@IdClass(DesignReviewRoundDesignJpaEntity.Key.class)
public class DesignReviewRoundDesignJpaEntity implements Persistable<DesignReviewRoundDesignJpaEntity.Key> {

    /** The table's key: the round and the design. */
    public static class Key implements Serializable {
        private UUID roundId;
        private UUID draftId;

        protected Key() {
        }

        public Key(UUID roundId, UUID draftId) {
            this.roundId = roundId;
            this.draftId = draftId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(roundId, key.roundId)
                    && Objects.equals(draftId, key.draftId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roundId, draftId);
        }
    }

    @Id
    @Column(name = "round_id", nullable = false, updatable = false)
    private UUID roundId;

    @Id
    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;

    @Column(name = "outcome", length = 20)
    private String outcome;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Transient
    private boolean fresh;

    protected DesignReviewRoundDesignJpaEntity() {
    }

    public DesignReviewRoundDesignJpaEntity(UUID roundId, UUID draftId) {
        this.roundId = roundId;
        this.draftId = draftId;
        this.fresh = true;
    }

    @PostLoad
    @PostPersist
    void stored() {
        fresh = false;
    }

    /** {@code APPROVED} or {@code RETURNED} ({@code ck_design_review_round_design_outcome}). */
    public void decide(String decided, Instant at) {
        outcome = decided;
        decidedAt = at;
    }

    @Override
    public Key getId() { return new Key(roundId, draftId); }

    @Override
    public boolean isNew() { return fresh; }

    public UUID getRoundId() { return roundId; }
    public UUID getDraftId() { return draftId; }
    public String getOutcome() { return outcome; }
    public Instant getDecidedAt() { return decidedAt; }
}
