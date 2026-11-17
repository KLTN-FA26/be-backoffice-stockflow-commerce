package com.stockflow.warehouse.internal.domain;

/**
 * The life of a putaway trip (kltn-docs 05 §5): {@code LOADING} while cartons are scanned onto the cart,
 * {@code IN_PROGRESS} once the clerk set off (bins assigned, space held, stops ordered), then {@code COMPLETED} when
 * every carton on the cart was put away or {@code CLOSED} when it was ended with cartons still on it.
 */
public enum PutawayTripStatus {
    LOADING, IN_PROGRESS, COMPLETED, CLOSED;

    /** Whether the trip still has a cart out: the two statuses a clerk may have only one trip in (05 BR-15). */
    public boolean open() {
        return this == LOADING || this == IN_PROGRESS;
    }
}
