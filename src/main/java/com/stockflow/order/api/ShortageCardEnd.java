package com.stockflow.order.api;

/**
 * Why a shortage choice card ended without being executed (kltn-docs 17 §4.14 step 60). Stored as text in
 * {@code ordering.shortage_card.ended_reason}.
 */
public enum ShortageCardEnd {
    /** The warehouse manager replaced the missing cartons: nothing is short any more. */
    REALLOCATED,
    /** The hold was lifted by a path that is not the warehouse's reallocation. */
    HOLD_RESOLVED_ELSEWHERE,
    /** The order was cancelled by another route (a request, a credit refusal, staff outside the card). */
    ORDER_CANCELLED,
    /** The rest of the order was closed by another route than the card. */
    REMAINDER_CLOSED,
    /** Sales lifted the hold or otherwise decided without the card, with a note (D47). */
    DECIDED_WITHOUT_CARD,
    /** A newer card replaced this one ({@code SUPERSEDED}). */
    REPLACED
}
