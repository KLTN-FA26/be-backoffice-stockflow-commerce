package com.stockflow.chat.api;

import java.util.List;

/**
 * What other modules - and package 3's card listeners - ask of the sales chat (kltn-docs 16).
 *
 * <p>The chat changes no document (16 BR-03): a card shows a document of another module and its action is that
 * module's own operation. What crosses this boundary is therefore only "put this card in the customer's conversation",
 * "this card's document moved", and "which conversations are about this document".</p>
 */
public interface ChatService {

    /**
     * Puts a card into the customer's open conversation about {@code command.about()} - opened, as the system, when
     * there is none (16 §4.2 step 5: a quote request opens one). The card is a CARD message seen by both sides, its
     * state OPEN, logged as CARD_SENT (16 BR-01).
     *
     * @throws com.stockflow.common.error.BusinessException {@code CHAT_CONTEXT_NOT_FOUND} when the document is not the
     *         customer's (16 BR-02); {@code VALIDATION_FAILED} for a payload that is not a JSON object
     */
    PostedCard postCard(PostCardCommand command);

    /**
     * A card's document moved, or its action was taken (on the card or on the document's own page): its new state
     * and, when given, its new payload. Logged as CARD_ACTION when someone acted, CARD_STATE_CHANGED otherwise.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CHAT_MESSAGE_NOT_FOUND} for no such card
     */
    void updateCard(UpdateCardCommand command);

    /** The conversations about a document, any customer's and status, the newest first (for the document's page). */
    List<ChatConversationRef> conversationsAbout(ChatContextRef about);
}
