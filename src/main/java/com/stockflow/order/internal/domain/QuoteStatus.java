package com.stockflow.order.internal.domain;

/**
 * The life of a quote (03-state-machines §18, {@code ck_quote_status}).
 *
 * <p>{@code EXPIRED}, {@code CANCELLED} and {@code CONVERTED} are terminal. A customer who wants a quote
 * that expired gets a new one: the old offer and its validity stay as they were.</p>
 */
public enum QuoteStatus {
    DRAFT, SENT, CHANGES_REQUESTED, ACCEPTED, EXPIRED, CANCELLED, CONVERTED;

    public boolean canTransitionTo(QuoteStatus target) {
        return switch (this) {
            case DRAFT -> target == SENT || target == CANCELLED;
            case SENT -> target == ACCEPTED || target == CHANGES_REQUESTED || target == EXPIRED;
            case CHANGES_REQUESTED -> target == DRAFT;
            case ACCEPTED -> target == CONVERTED;
            case EXPIRED, CANCELLED, CONVERTED -> false;
        };
    }

    /** Whether the customer has ever been shown this quote: the portal sees only these. */
    public boolean seenByCustomer() {
        return this != DRAFT && this != CANCELLED;
    }
}
