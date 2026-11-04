package com.stockflow.inventory.internal.domain;

/**
 * {@code PENDING_APPROVAL → POSTED}, {@code → REJECTED} or {@code → WITHDRAWN}. Mapped
 * {@code EnumType.STRING}.
 *
 * <p>The table also allows {@code APPROVED}, a state between the decision and the posting. Here
 * they happen in one transaction — approving is what posts — so nothing ever rests in it.</p>
 *
 * <p>{@code WITHDRAWN} is the requester taking the request back before anyone decided it; it is
 * closed, but nobody decided it, so it carries no decider.</p>
 */
public enum AdjustmentStatus {
    PENDING_APPROVAL,
    REJECTED,
    POSTED,
    WITHDRAWN;

    /** No longer waiting for a decision. */
    public boolean isClosed() {
        return this != PENDING_APPROVAL;
    }
}
