package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.api.PostedCard;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationEventType;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.MessageKind;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Puts cards into conversations and keeps them in step with their documents (kltn-docs 16 §3 Output, BR-01, BR-03):
 * the one place a card is written, so every card is logged (CARD_SENT, CARD_ACTION, CARD_STATE_CHANGED) and pushed the
 * same way, whichever module's event or which side's action caused it.
 *
 * <p><b>Which conversation a card goes to.</b> The customer's open conversation about the card's document; else one
 * about a related document (an invoice's order, an installment's contract); else the customer's most recently active
 * open conversation, whatever it is about - a customer talks to Sales in one thread, and a card should land where they
 * already are; only when there is none does the system open a conversation for it, routed like any other (16 §4.1).
 * The card's document is attached to the conversation it lands in, so the card names it and the document's page lists
 * the conversation.</p>
 */
@Service
@Transactional
public class ChatCards {

    /**
     * A card to put into a customer's conversation.
     *
     * @param related   documents whose open conversation is the next best place, in order
     * @param sentBy    the staff account whose action produced it (a quote sent); null when the system did
     * @param asksSales whether the card asks Sales to do something (a quote request, 16 BR-11): a conversation opened for
     *                  it starts the first-response clock; one opened for an invoice or a parcel does not
     */
    public record NewCard(UUID customerId, ContextRef about, List<ContextRef> related, CardKind kind,
                          JsonNode payload, String fallbackText, UUID sentBy, boolean asksSales) {

        public NewCard {
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(about, "about");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payload, "payload");
            related = related == null ? List.of() : List.copyOf(related);
        }
    }

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ConversationWriter writer;
    private final ContextResolver contexts;
    private final ChatViews views;
    private final ChatBroadcaster broadcaster;
    private final CustomerService customers;
    private final ObjectMapper json;
    private final Clock clock;

    public ChatCards(ConversationRepository conversations, ChatMessageRepository messages, ConversationWriter writer,
                     ContextResolver contexts, ChatViews views, ChatBroadcaster broadcaster,
                     CustomerService customers, ObjectMapper json, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.writer = writer;
        this.contexts = contexts;
        this.views = views;
        this.broadcaster = broadcaster;
        this.customers = customers;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Posts {@code card} into the customer's conversation as described on the class.
     *
     * @throws BusinessException {@code CHAT_CONTEXT_NOT_FOUND} when the document is not the customer's (BR-02)
     */
    public PostedCard post(NewCard card) {
        ContextResolver.Resolved about = contexts.requireOwnedBy(card.about(), card.customerId(), false);
        Actor sender = card.sentBy() == null ? Actor.system() : Actor.agent(card.sentBy(), false);
        Conversation c = conversationFor(card, about, sender);
        UUID contextId = attach(c, about.ref(), sender);
        return postInto(c, contextId, card.kind(), card.payload(), card.fallbackText(), sender);
    }

    /**
     * Posts a card into {@code c}, which the caller locked: the conversation's own cards (a CSAT survey) and those Sales
     * send from inside it (transfer details).
     *
     * @param contextId the conversation's context the card is about; null for none
     */
    public PostedCard postInto(Conversation c, UUID contextId, CardKind kind, JsonNode payload, String fallbackText,
                               Actor sender) {
        Instant now = clock.instant();
        String body = write(payload);
        ConversationWriter.Posted posted = writer.post(c, sender, null, fallbackText,
                conversation -> conversation.acceptSystemMessage(now),
                seq -> ChatMessage.card(Identifiers.newId(), c.id(), seq, sender, kind, body, contextId, fallbackText,
                        now));
        c.logMessageAction(ConversationEventType.CARD_SENT, sender, posted.message().id(), contextId, null,
                "{\"card\":\"" + kind.name() + "\"}", now);
        conversations.append(c.pullLog());
        return new PostedCard(c.id(), posted.message().id(), posted.message().seq());
    }

    /**
     * A card's new state and payload, from an action on it ({@code actor} a person, {@code action} named: logged as
     * CARD_ACTION) or from its document moving (logged as CARD_STATE_CHANGED when the state changed; a payload that
     * only shows fresher figures is not a consequential action and is not logged). Nothing changes when both are what
     * the card has already - which is what a listener redelivered, or following an action taken on the card, finds.
     *
     * @param payload the new payload; null to keep it
     * @return whether the card changed
     */
    public boolean update(UUID messageId, CardState state, JsonNode payload, Actor actor, String action,
                          String detailJson) {
        ChatMessage found = messages.findById(messageId, Audience.STAFF).filter(m -> m.kind() == MessageKind.CARD)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
        Conversation c = writer.lock(found.conversationId());
        ChatMessage card = messages.findById(messageId, Audience.STAFF).orElseThrow();
        String next = payload == null ? null : write(payload);
        boolean samePayload = next == null || sameJson(card.card().payloadJson(), next);
        boolean sameState = state == null || state == card.card().state();
        boolean acted = action != null && actor.type() != com.stockflow.chat.internal.domain.SenderType.SYSTEM;
        if (samePayload && sameState && !acted) {
            return false;
        }
        Instant now = clock.instant();
        ChatMessage updated = messages.save(card.withCard(state, next, now));
        if (acted || !sameState) {
            String detail = detailJson != null ? detailJson
                    : "{\"state\":\"" + updated.card().state().name() + "\"}";
            c.logMessageAction(acted ? ConversationEventType.CARD_ACTION : ConversationEventType.CARD_STATE_CHANGED,
                    actor, card.id(), card.contextId(), action, detail, now);
            conversations.append(c.pullLog());
        }
        broadcaster.message(ChatEvent.MESSAGE_UPDATED, views.message(updated), writer.customerAccounts(c));
        return true;
    }

    /** Every card of {@code kind} about {@code ref} still OPEN, but {@code keep}, becomes SUPERSEDED: a newer one came. */
    public void supersede(ContextRef ref, CardKind kind, UUID keep) {
        for (ChatMessage card : messages.cardsAbout(ref, kind)) {
            if (card.card().state() == CardState.OPEN && !card.id().equals(keep)) {
                update(card.id(), CardState.SUPERSEDED, null, Actor.system(), null, null);
            }
        }
    }

    /** Who acted, as the chat names them: the customer's portal account, a staff member, or the system for null. */
    public Actor actorOf(UUID userId) {
        if (userId == null) {
            return Actor.system();
        }
        return customers.findByUserId(userId).isPresent() ? Actor.customer(userId) : Actor.agent(userId, false);
    }

    /** {@code payload} as a JSON object; the card refuses anything else ({@code ck_message_card}). */
    public JsonNode readPayload(String payloadJson) {
        try {
            JsonNode node = json.readTree(payloadJson);
            if (node == null || !node.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A card's payload is a JSON object");
            }
            return node;
        } catch (JsonProcessingException malformed) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A card's payload is a JSON object");
        }
    }

    /**
     * Attaches {@code ref} to {@code c} unless it is there already, as the system or the staff member who sent the
     * card. The first context of a conversation is its primary one.
     *
     * @return the context's id
     */
    public UUID attach(Conversation c, ContextRef ref, Actor by) {
        Optional<ConversationContext> existing = conversations.findContext(c.id(), ref);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        Instant now = clock.instant();
        boolean first = conversations.contextsOf(c.id()).isEmpty();
        ConversationContext attached = conversations.attach(new ConversationContext(Identifiers.newId(), c.id(), ref,
                first, by.userId(), now));
        c.contextAttached(by, attached.id(), now);
        conversations.append(c.pullLog());
        return attached.id();
    }

    private Conversation conversationFor(NewCard card, ContextResolver.Resolved about, Actor sender) {
        Optional<Conversation> found = conversations.findOpenAbout(card.customerId(), card.about());
        for (ContextRef related : card.related()) {
            if (found.isPresent()) {
                break;
            }
            found = conversations.findOpenAbout(card.customerId(), related);
        }
        if (found.isEmpty()) {
            found = conversations.newestOpenOf(card.customerId());
        }
        return found.map(c -> writer.lock(c.id())).orElseGet(() -> writer.open(card.customerId(), null,
                sender.isStaff() ? sender : Actor.system(), List.of(about), null, null, card.asksSales()));
    }

    private String write(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A card's payload is a JSON object");
        }
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("A card's payload could not be written", unwritable);
        }
    }

    private boolean sameJson(String a, String b) {
        try {
            return json.readTree(a).equals(json.readTree(b));
        } catch (JsonProcessingException malformed) {
            return false;
        }
    }
}
