package com.stockflow.order.api;

/**
 * Where a shortage choice card stands (kltn-docs 17 §5.6). Stored as text in {@code ordering.shortage_card.status}.
 *
 * <pre>
 *   SENT ──customer chooses──▶ ANSWERED ──Sales execute──▶ EXECUTED
 *    └────────────┴──a newer card──▶ SUPERSEDED     └──nothing left to decide──▶ EXPIRED
 * </pre>
 */
public enum ShortageCardStatus {
    SENT, ANSWERED, EXECUTED, SUPERSEDED, EXPIRED;

    /** A card the customer may still choose on, or Sales may still execute: at most one per batch of an order. */
    public boolean live() {
        return this == SENT || this == ANSWERED;
    }
}
