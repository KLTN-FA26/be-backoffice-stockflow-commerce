package com.stockflow.chat.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A card's new state.
 *
 * @param payloadJson the card's new data; null to keep it
 * @param actedBy     who took the card's action (the customer's portal account accepting a quote); null when the
 *                    document moved on its own
 * @param action      what was done (e.g. {@code ACCEPT}, {@code REQUEST_REPRINT}); logged with the card (16 BR-01)
 */
public record UpdateCardCommand(UUID messageId, ChatCardState state, String payloadJson, UUID actedBy,
                                String action) {

    public UpdateCardCommand {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(state, "state");
    }
}
