package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.contracts.ShortageCardChanged;
import com.stockflow.order.api.ShortageCardStatus;
import com.stockflow.order.api.ShortageCards;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The shortage choice card in the chat (kltn-docs 16 §4.7, BR-15; 17 §4.14): what its message says, and keeping it in step
 * with the document ({@link ShortageCards}) wherever the customer chose or the order moved on - the chat decides nothing
 * about what the card offers.
 *
 * <p>The card's state in the chat follows the document's status: SENT and ANSWERED are still OPEN (what the card offers
 * is decided by {@code payload.status}, as for a quote - {@link CardActions}), EXECUTED is CLOSED, SUPERSEDED and
 * EXPIRED are themselves.</p>
 */
@Component
@Transactional
public class ShortageCardMessages {

    private final ShortageCards shortage;
    private final ChatCards cards;
    private final ChatMessageRepository messages;
    private final ObjectMapper json;

    public ShortageCardMessages(ShortageCards shortage, ChatCards cards, ChatMessageRepository messages,
                                ObjectMapper json) {
        this.shortage = shortage;
        this.cards = cards;
        this.messages = messages;
        this.json = json;
    }

    /** The chat state of a card with the document status {@code status}. */
    static CardState stateOf(ShortageCardStatus status) {
        return switch (status) {
            case SENT, ANSWERED -> CardState.OPEN;
            case EXECUTED -> CardState.CLOSED;
            case SUPERSEDED -> CardState.SUPERSEDED;
            case EXPIRED -> CardState.EXPIRED;
        };
    }

    /**
     * The card's payload (shared by the back office and the storefront): the batch, the cartons short and the options with
     * their figures, what the customer chose. Never an LPN, bin, hold reason or internal note.
     */
    public ObjectNode payload(ShortageCards.Card card) {
        ObjectNode payload = json.createObjectNode();
        payload.put("cardId", card.cardId().toString());
        payload.put("code", card.code());
        payload.put("orderId", card.orderId().toString());
        payload.put("orderNumber", card.orderNumber());
        payload.put("status", card.status().name());
        payload.put("version", card.version());
        ObjectNode batch = payload.putObject("batch");
        batch.put("seq", card.batch().seq());
        batch.put("windowFrom", card.batch().windowFrom() == null ? null : card.batch().windowFrom().toString());
        batch.put("windowTo", card.batch().windowTo() == null ? null : card.batch().windowTo().toString());
        batch.put("moved", card.batch().moved());
        payload.put("missingCartons", card.missingCartons());
        ArrayNode lines = payload.putArray("lines");
        for (ShortageCards.Line line : card.lines()) {
            ObjectNode node = lines.addObject();
            node.put("sku", line.sku());
            node.put("skuName", line.skuName());
            node.put("batchCartons", line.batchCartons());
            node.put("availableCartons", line.availableCartons());
            node.put("missingCartons", line.missingCartons());
            node.put("unitsPerCarton", line.unitsPerCarton());
        }
        ArrayNode options = payload.putArray("options");
        for (ShortageCards.Option option : card.options()) {
            ObjectNode node = options.addObject();
            node.put("option", option.option().name());
            if (option.expectedDeliveryDate() != null) {
                node.put("expectedDeliveryDate", option.expectedDeliveryDate().toString());
            }
            if (option.afterWindow() != null) {
                node.put("afterWindow", option.afterWindow());
            }
            if (option.amountReduced() != null) {
                node.put("amountReduced", option.amountReduced());
            }
            if (option.laterBatchesClosed() != null) {
                ArrayNode later = node.putArray("laterBatchesClosed");
                option.laterBatchesClosed().forEach(later::add);
            }
            if (option.refundUpTo() != null) {
                node.put("refundUpTo", option.refundUpTo());
            }
            if (option.feePossible() != null) {
                node.put("feePossible", option.feePossible());
            }
        }
        payload.put("salesNote", card.salesNote());
        if (card.chosen() == null) {
            payload.putNull("chosen");
        } else {
            ObjectNode chosen = payload.putObject("chosen");
            chosen.put("option", card.chosen().option().name());
            chosen.put("at", card.chosen().at().toString());
            chosen.put("note", card.chosen().note());
        }
        payload.put("executedAt", card.executedAt() == null ? null : card.executedAt().toString());
        payload.put("executionStarted", card.executionStarted());
        payload.put("endedReason", card.endedReason() == null ? null : card.endedReason().name());
        return payload;
    }

    /** What a client that cannot draw the card shows. */
    public static String fallbackText(ShortageCards.Card card) {
        return "Đơn thiếu %d thùng - %s".formatted(card.missingCartons(), card.orderNumber());
    }

    /**
     * The document changed (sent, chosen, replaced, ended): its message shows it. Nothing changes when the message
     * already says so - which is what a redelivered event, or one following the customer's own action on the card, finds.
     * A card not posted yet has no message and nothing to update.
     */
    public void changed(ShortageCardChanged event) {
        Optional<ShortageCards.Card> found = shortage.card(event.cardId());
        if (found.isEmpty()) {
            return;
        }
        ShortageCards.Card card = found.get();
        Optional<UUID> message = messageOf(card);
        if (message.isEmpty()) {
            return;
        }
        cards.update(message.get(), stateOf(card.status()), payload(card), Actor.system(), null,
                "{\"change\":\"" + event.change() + "\"}");
    }

    private Optional<UUID> messageOf(ShortageCards.Card card) {
        if (card.chatMessageId() != null) {
            return Optional.of(card.chatMessageId());
        }
        ContextRef ref = new ContextRef(ContextKind.ORDER, card.orderId());
        return messages.cardsAbout(ref, CardKind.SHORTAGE_CHOICE).stream()
                .filter(m -> card.cardId().toString().equals(cardIdOf(m)))
                .map(ChatMessage::id).findFirst();
    }

    private String cardIdOf(ChatMessage message) {
        JsonNode payload = cards.readPayload(message.card().payloadJson());
        return payload.path("cardId").asText(null);
    }
}
