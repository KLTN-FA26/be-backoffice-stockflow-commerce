package com.stockflow.inventory.internal.domain;

/**
 * {@code PENDING_APPROVAL → POSTED} or {@code → REJECTED}. Mapped {@code EnumType.STRING}.
 *
 * <p>The table also allows {@code APPROVED}, a state between the decision and the posting. Here
 * they happen in one transaction — approving is what posts — so nothing ever rests in it.</p>
 */
public enum AdjustmentStatus {
    PENDING_APPROVAL,
    REJECTED,
    POSTED;

    public boolean isDecided() {
        return this != PENDING_APPROVAL;
    }
}
