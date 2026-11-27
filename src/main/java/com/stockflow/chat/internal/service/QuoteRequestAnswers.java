package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.contracts.QuoteDrawnFromRequest;
import com.stockflow.design.api.QuoteRequests;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a quote request's card shows once Sales answered the request with a quote (kltn-docs 16 §4.2, BR-11), wherever
 * they pressed the button: the card ACTED with the quote on it, and the quote attached to the card's conversation, so
 * the quote's own card lands there when it is sent. The card's CREATE_QUOTE ({@link ChatCardActions}) does it in its
 * transaction; a quote drawn up from the back office's request inbox reaches the card through
 * {@link QuoteDrawnFromRequest}, after commit - both through {@link #answer}, so the card reads the same either way.
 *
 * <p>Redelivered events and the event of a quote drawn on the card itself find the card already carrying its quote
 * and change nothing. The request's own closing event ({@code DocumentCards}) is delivered alongside: read after the
 * answer, it keeps the quote on the card and leaves an ACTED card ACTED; read before it, its write is refused on the
 * card's version (see {@link #answerCard}).</p>
 */
@Service
@Transactional
public class QuoteRequestAnswers {

    private final ChatCards cards;
    private final ChatMessageRepository messages;
    private final ConversationWriter writer;
    private final DocumentCards documents;
    private final QuoteRequests quoteRequests;

    public QuoteRequestAnswers(ChatCards cards, ChatMessageRepository messages, ConversationWriter writer,
                               DocumentCards documents, QuoteRequests quoteRequests) {
        this.cards = cards;
        this.messages = messages;
        this.writer = writer;
        this.documents = documents;
        this.quoteRequests = quoteRequests;
    }

    /**
     * The card {@code card} of {@code c} (locked by the caller) answered by the quote: the quote attached to the
     * conversation, the card ACTED, logged as {@code by}'s CREATE_QUOTE.
     */
    public void answer(Conversation c, ChatMessage card, UUID quoteId, String quoteNumber, Actor by) {
        JsonNode old = cards.readPayload(card.card().payloadJson());
        cards.attach(c, new ContextRef(ContextKind.QUOTE, quoteId), by);
        cards.update(card.id(), CardState.ACTED, answered(old, quoteId, quoteNumber), by, CardActions.CREATE_QUOTE,
                "{\"quoteId\":\"" + quoteId + "\"}");
    }

    /** A quote request's card and the conversation it is in. */
    public record RequestCard(UUID cardId, UUID conversationId) {
    }

    /** The cards of the request a quote was drawn up from, in any conversation; each is answered on its own. */
    @Transactional(readOnly = true)
    public List<RequestCard> cardsOf(QuoteDrawnFromRequest event) {
        Optional<ContextRef> about = "PROJECT".equals(event.scope())
                ? quoteRequests.projectRequest(event.requestId())
                        .map(r -> new ContextRef(ContextKind.DESIGN_PROJECT, r.projectId()))
                : quoteRequests.designRequest(event.requestId())
                        .map(r -> new ContextRef(ContextKind.DESIGN, r.designId()));
        return about.map(ref -> messages.cardsAbout(ref, CardKind.QUOTE_REQUEST).stream()
                .filter(card -> event.requestId().toString()
                        .equals(cards.readPayload(card.card().payloadJson()).path("requestId").asText()))
                .map(card -> new RequestCard(card.id(), card.conversationId())).toList()).orElse(List.of());
    }

    /**
     * A quote was drawn up from a request, perhaps outside the chat: its card {@code cardId}, unless it shows a quote
     * already or was superseded, is answered. The quote is attached only to a conversation still open - an ended one
     * is not reopened for it; its card still shows the answer.
     *
     * <p>In a transaction of its own, the conversation locked before the card is read: the request's closing event is
     * delivered at the same time and refreshes the same card, and a card read before that commit would be written back
     * stale (and refused on its version). Read under the lock, this answer always sees, and keeps, what the other
     * wrote.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void answerCard(QuoteDrawnFromRequest event, RequestCard located) {
        Conversation c = writer.lock(located.conversationId());
        ChatMessage card = messages.findById(located.cardId(), Audience.STAFF).orElseThrow();
        JsonNode payload = cards.readPayload(card.card().payloadJson());
        if (card.card().state() == CardState.SUPERSEDED || payload.hasNonNull("quoteId")) {
            return;
        }
        Actor by = cards.actorOf(event.drawnBy());
        if (c.status().isEnded()) {
            cards.update(card.id(), CardState.ACTED, answered(payload, event.quoteId(), event.quoteNumber()), by,
                    CardActions.CREATE_QUOTE, "{\"quoteId\":\"" + event.quoteId() + "\"}");
        } else {
            answer(c, card, event.quoteId(), event.quoteNumber(), by);
        }
    }

    /**
     * The card's payload once answered: a design's request read again (closed now), a project's as it was with its
     * status CLOSED - then the quote on it.
     */
    private ObjectNode answered(JsonNode old, UUID quoteId, String quoteNumber) {
        ObjectNode next;
        if ("PROJECT".equals(old.path("scope").asText())) {
            next = (ObjectNode) old.deepCopy();
            next.put("status", "CLOSED");
        } else {
            UUID requestId = UUID.fromString(old.path("requestId").asText());
            next = quoteRequests.designRequest(requestId).map(r -> documents.designRequestPayload(r, null))
                    .orElseGet(() -> (ObjectNode) old.deepCopy());
            next.put("requestStatus", "CLOSED");
        }
        next.put("quoteId", quoteId.toString());
        next.put("quoteNumber", quoteNumber);
        return next;
    }
}
