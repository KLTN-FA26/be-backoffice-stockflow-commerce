package com.stockflow.production.internal.domain;

/**
 * The life of one sample round (03-state-machines §17, {@code ck_sample_request_status}).
 *
 * <p>{@code CHANGES_REQUESTED} is terminal for its row: the next round is a new request pointing back
 * at it ({@code previous_round_id}), so what the customer was shown and what they said about it is
 * never overwritten by the round that follows.</p>
 */
public enum SampleStatus {
    DRAFT, SUBMITTED, IN_PRODUCTION, READY, SENT_TO_CUSTOMER, CHANGES_REQUESTED, APPROVED, CANCELLED;

    public boolean canTransitionTo(SampleStatus target) {
        return switch (this) {
            case DRAFT -> target == SUBMITTED || target == CANCELLED;
            case SUBMITTED -> target == IN_PRODUCTION;
            case IN_PRODUCTION -> target == READY;
            case READY -> target == SENT_TO_CUSTOMER;
            case SENT_TO_CUSTOMER -> target == APPROVED || target == CHANGES_REQUESTED;
            case CHANGES_REQUESTED, APPROVED, CANCELLED -> false;
        };
    }
}
