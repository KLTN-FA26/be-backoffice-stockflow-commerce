package com.stockflow.chat.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A card to put into a customer's conversation.
 *
 * @param about        the document the card shows; the conversation is the customer's open one about it
 * @param payloadJson  the card's data, a JSON object shaped by {@code kind}
 * @param fallbackText what a client that cannot draw the card shows instead; may be null
 * @param sentBy       the staff account whose action produced the card (an agent sending a quote); null when the
 *                     system sent it on its own (a quote request, a due instalment)
 */
public record PostCardCommand(UUID customerId, ChatContextRef about, ChatCardKind kind, String payloadJson,
                              String fallbackText, UUID sentBy) {

    public PostCardCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(about, "about");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payloadJson, "payloadJson");
    }
}
