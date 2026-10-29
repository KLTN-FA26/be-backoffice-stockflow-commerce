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
            // CANCELLED from SENT: Sales withdraws an offer before the customer answers - a price typed wrong, a
            // design the customer dropped by phone. An offer that cannot be taken back can be accepted as it stands.
            case SENT -> target == ACCEPTED || target == CHANGES_REQUESTED || target == EXPIRED || target == CANCELLED;
            // CANCELLED from CHANGES_REQUESTED: the changes asked for are not offered; nothing is revised.
            case CHANGES_REQUESTED -> target == DRAFT || target == CANCELLED;
            // CANCELLED: decision 2026-10-17 - Sales, with a reason, when a SKU on it stopped (QuoteService).
            case ACCEPTED -> target == CONVERTED || target == CANCELLED;
            case EXPIRED, CANCELLED, CONVERTED -> false;
        };
    }

    /** Whether the customer has ever been shown this quote: the portal sees only these. */
    public boolean seenByCustomer() {
        return this != DRAFT && this != CANCELLED;
    }
}
