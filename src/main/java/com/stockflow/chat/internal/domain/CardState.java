package com.stockflow.chat.internal.domain;

/**
 * Where a card's action stands. The card shows the document's own state (16 BR-03: the chat changes no document); this
 * is only whether the card still invites an action. Mapped {@code EnumType.STRING}; {@code ck_message_card_state}.
 */
public enum CardState {
    /** Waiting for its action (accept, approve, pay...). */
    OPEN,
    /** Acted on, on the card or on the document's own page. */
    ACTED,
    /** A newer card of the same document replaced it. */
    SUPERSEDED,
    /** The document it shows lapsed (an expired quote). */
    EXPIRED,
    /** The document it shows was cancelled. */
    CANCELLED,
    /** Nothing to do on it any more, for any other reason. */
    CLOSED
}
