package com.stockflow.chat.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.api.ChatContextRef;
import com.stockflow.chat.api.ChatConversationRef;
import com.stockflow.chat.api.ChatService;
import com.stockflow.chat.api.PostCardCommand;
import com.stockflow.chat.api.PostedCard;
import com.stockflow.chat.api.UpdateCardCommand;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextKind;
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
import java.util.UUID;

/**
 * The only implementation of {@link ChatService}: the cards other modules (package 3) put into conversations, and the
 * conversations about a document. A card is drawn by the system from a document (16 BR-06, BR-10, BR-11), never typed;
 * its action is the owning module's own operation (BR-03), which reports back here through {@link #updateCard}.
 */
@Service
@Transactional
class ChatServiceImpl implements ChatService {

    private static final int ABOUT_LIMIT = 50;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ConversationWriter writer;
    private final ContextResolver contexts;
    private final ChatViews views;
    private final ChatBroadcaster broadcaster;
    private final CustomerService customers;
    private final ObjectMapper json;
    private final Clock clock;

    ChatServiceImpl(ConversationRepository conversations, ChatMessageRepository messages, ConversationWriter writer,
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

    @Override
    public PostedCard postCard(PostCardCommand command) {
        ContextRef ref = ref(command.about());
        ContextResolver.Resolved about = contexts.requireOwnedBy(ref, command.customerId(), false);
        String payload = requireObject(command.payloadJson());
        Actor sender = command.sentBy() == null ? Actor.system() : Actor.agent(command.sentBy(), false);
        Conversation c = conversations.findOpenAbout(command.customerId(), ref).map(found -> writer.lock(found.id()))
                .orElseGet(() -> writer.open(command.customerId(), null, Actor.system(), List.of(about), null, null));
        UUID contextId = conversations.findContext(c.id(), ref).map(ConversationContext::id).orElse(null);
        Instant now = clock.instant();
        CardKind kind = CardKind.valueOf(command.kind().name());
        ConversationWriter.Posted posted = writer.post(c, sender, null, command.fallbackText(),
                conversation -> conversation.acceptSystemMessage(now),
                seq -> ChatMessage.card(Identifiers.newId(), c.id(), seq, sender, kind, payload, contextId,
                        command.fallbackText(), now));
        c.logMessageAction(ConversationEventType.CARD_SENT, sender, posted.message().id(), contextId, null,
                "{\"card\":\"" + kind.name() + "\"}", now);
        conversations.append(c.pullLog());
        return new PostedCard(c.id(), posted.message().id(), posted.message().seq());
    }

    @Override
    public void updateCard(UpdateCardCommand command) {
        ChatMessage card = messages.findById(command.messageId(), Audience.STAFF)
                .filter(m -> m.kind() == MessageKind.CARD)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
        Conversation c = writer.lock(card.conversationId());
        Instant now = clock.instant();
        String payload = command.payloadJson() == null ? null : requireObject(command.payloadJson());
        ChatMessage updated = messages.save(card.withCard(CardState.valueOf(command.state().name()), payload, now));
        Actor actor = command.actedBy() == null ? Actor.system()
                : customers.findByUserId(command.actedBy()).isPresent() ? Actor.customer(command.actedBy())
                : Actor.agent(command.actedBy(), false);
        String action = command.action() == null || command.action().isBlank() ? null : command.action().strip();
        c.logMessageAction(command.actedBy() == null ? ConversationEventType.CARD_STATE_CHANGED
                        : ConversationEventType.CARD_ACTION, actor, card.id(), card.contextId(), action,
                "{\"state\":\"" + command.state().name() + "\"}", now);
        conversations.append(c.pullLog());
        broadcaster.message(ChatEvent.MESSAGE_UPDATED, views.message(updated), writer.customerAccounts(c));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatConversationRef> conversationsAbout(ChatContextRef about) {
        return conversations.about(ref(about), ABOUT_LIMIT).stream().map(c -> new ChatConversationRef(c.id(),
                c.customerId(), c.subject(), c.status().name(), c.assignedAgentId(), c.createdAt(),
                c.lastMessageAt())).toList();
    }

    private static ContextRef ref(ChatContextRef about) {
        return new ContextRef(ContextKind.valueOf(about.kind().name()), about.id());
    }

    private String requireObject(String payloadJson) {
        try {
            JsonNode node = json.readTree(payloadJson);
            if (node == null || !node.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A card's payload is a JSON object");
            }
            return json.writeValueAsString(node);
        } catch (JsonProcessingException malformed) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A card's payload is a JSON object");
        }
    }
}
