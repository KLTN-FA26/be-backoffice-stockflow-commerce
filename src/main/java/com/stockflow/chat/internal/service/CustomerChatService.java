package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.Slice;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The customer's side of the chat on the portal (kltn-docs 16 §4): open a conversation from the page they are on, see
 * their conversations - the closed ones too - and the ones about a document, read and write, reopen.
 *
 * <p>The customer is always the one the sign-in belongs to ({@link ChatCallers#customer}); a conversation id of another
 * customer answers {@code CHAT_CONVERSATION_NOT_FOUND}, as one that does not exist. Every message read here is read for
 * {@link Audience#CUSTOMER}, so an internal note cannot reach this side (16 BR-07).</p>
 */
@Service
@Transactional
public class CustomerChatService {

    private final ChatCallers callers;
    private final ConversationRepository conversations;
    private final ConversationWriter writer;
    private final ChatReading reading;
    private final ContextResolver contexts;
    private final ChatViews views;
    private final ChatProperties properties;
    private final ChatMessageRepository messages;
    private final ChatCards cards;
    private final ChatCardActions actions;

    public CustomerChatService(ChatCallers callers, ConversationRepository conversations,
                               ConversationWriter writer, ChatReading reading,
                               ContextResolver contexts, ChatViews views, ChatProperties properties,
                               ChatMessageRepository messages, ChatCards cards, ChatCardActions actions) {
        this.messages = messages;
        this.cards = cards;
        this.actions = actions;
        this.callers = callers;
        this.conversations = conversations;
        this.writer = writer;
        this.reading = reading;
        this.contexts = contexts;
        this.views = views;
        this.properties = properties;
    }

    /** What the customer opens a conversation with. */
    public record Open(String subject, ContextRef about, String text, String clientMessageId) {
    }

    /**
     * Opens a conversation from the page the customer is on (16 §4.1 step 1). An open conversation of the customer
     * about the same document is reused rather than duplicated (16 edge case "khách mở nhiều hội thoại cho cùng một
     * việc"); without a document, the newest open conversation about nothing in particular is. The first message, when
     * given, is posted into it.
     *
     * @throws BusinessException {@code CHAT_CONTEXT_NOT_FOUND} for a document that is not the customer's (BR-02)
     */
    public ChatConversationView open(CurrentUser user, Open command) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        List<ContextResolver.Resolved> about = command.about() == null ? List.of()
                : List.of(contexts.requireOwnedBy(command.about(), caller.customerId(), true));
        Conversation c = conversations.findOpenAbout(caller.customerId(), command.about())
                .flatMap(found -> Optional.of(writer.lock(found.id())))
                .orElseGet(() -> writer.open(caller.customerId(), command.subject(), caller.actor(), about, null,
                        null));
        if (command.text() != null && !command.text().isBlank()) {
            postText(c, caller, command.text(), command.clientMessageId());
        }
        return views.detail(c, user.userId(), null);
    }

    /** The customer's conversations, any status, the most recently active first; only those about a document. */
    @Transactional(readOnly = true)
    public PageResponse<ChatConversationView> mine(CurrentUser user, ContextRef about, int page, Integer size) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        int pageSize = properties.pageSize(size, 20);
        Slice<Conversation> slice = conversations.ofCustomer(caller.customerId(), about, Math.max(0, page), pageSize);
        return PageResponse.of(views.rows(slice.items(), user.userId(), null), Math.max(0, page), pageSize,
                slice.total());
    }

    @Transactional(readOnly = true)
    public ChatConversationView get(CurrentUser user, UUID conversationId) {
        return views.detail(own(user, conversationId), user.userId(), null);
    }

    /** Messages for the customer only (16 BR-07); see {@link ChatReading#page}. */
    @Transactional(readOnly = true)
    public ChatMessagePage messages(CurrentUser user, UUID conversationId, Long before, Long since, Integer limit) {
        return reading.page(own(user, conversationId), Audience.CUSTOMER, before, since, limit);
    }

    /**
     * A text from the customer. A conversation waiting on them is active again, a resolved or auto-closed one within its
     * window reopens (16 §5).
     *
     * @throws BusinessException {@code CHAT_CONVERSATION_ENDED} past the window: {@link #reopen} starts its sequel
     */
    public ChatMessageView send(CurrentUser user, UUID conversationId, String text, String clientMessageId) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        Conversation c = writer.lockOwn(conversationId, caller.customerId());
        return views.message(postText(c, caller, text, clientMessageId).message());
    }

    public ChatReadMarkerView markRead(CurrentUser user, UUID conversationId, Long seq) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        Conversation c = writer.lockOwn(conversationId, caller.customerId());
        return reading.markRead(c, caller.actor(), seq);
    }

    /**
     * The customer comes back to a conversation (16 §5). One still open is simply returned; a resolved or auto-closed
     * one within its window is REOPENED; a closed one, or one past its window, gets a new conversation that continues
     * it - about the same documents (those still the customer's) - and that new one is returned.
     */
    public ChatConversationView reopen(CurrentUser user, UUID conversationId) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        Conversation c = writer.lockOwn(conversationId, caller.customerId());
        if (c.continuesAsNewAt(writer.now())) {
            List<ContextResolver.Resolved> about = new ArrayList<>();
            List<ConversationContext> earlier = new ArrayList<>(conversations.contextsOf(c.id()));
            earlier.sort((a, b) -> Boolean.compare(b.primary(), a.primary()));
            for (ConversationContext context : earlier) {
                contexts.find(context.ref()).filter(found -> found.customerId() == null
                        || found.customerId().equals(caller.customerId())).ifPresent(about::add);
            }
            Conversation next = writer.open(caller.customerId(), c.subject(), caller.actor(), about, null, c.id());
            return views.detail(next, user.userId(), null);
        }
        if (c.reopensAt(writer.now())) {
            UUID agent = c.assignedAgentId();
            c.reopen(caller.actor(), writer.now());
            writer.save(c, agent);
        }
        return views.detail(c, user.userId(), null);
    }

    /**
     * The customer rates a resolved conversation (CSAT, 16 §4.6 step 18): 1 to 5 stars and an optional comment, once.
     * The survey card, if one is open, shows the rating.
     *
     * @throws BusinessException {@code CHAT_CSAT_NOT_OPEN}; {@code CHAT_CSAT_ALREADY_GIVEN}
     */
    public ChatConversationView rate(CurrentUser user, UUID conversationId, Integer score, String comment) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        Conversation c = writer.lockOwn(conversationId, caller.customerId());
        int stars = score == null ? 0 : score;
        actions.rate(c, caller.actor(), stars, comment);
        for (ChatMessage survey : messages.openCardsIn(c.id(), CardKind.CSAT_SURVEY)) {
            cards.update(survey.id(), CardState.ACTED, ((com.fasterxml.jackson.databind.node.ObjectNode) cards
                    .readPayload(survey.card().payloadJson())).put("score", stars), caller.actor(),
                    CardActions.RATE, "{\"score\":" + stars + "}");
        }
        return views.detail(writer.lockOwn(conversationId, caller.customerId()), user.userId(), null);
    }

    /** The customer's badge: conversations with something unread, and the messages unread in them. */
    @Transactional(readOnly = true)
    public ChatMessageRepository.UnreadTotal unread(CurrentUser user) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        return messages.unreadTotal(user.userId(), Audience.CUSTOMER, caller.customerId(), null);
    }

    private ConversationWriter.Posted postText(Conversation c, ChatCallers.CustomerCaller caller, String text,
                                               String clientMessageId) {
        return writer.post(c, caller.actor(), clientMessageId, text,
                conversation -> conversation.acceptCustomerMessage(caller.actor(), writer.now(),
                        writer.firstResponseDue(writer.now())),
                seq -> ChatMessage.text(Identifiers.newId(), c.id(), seq, caller.actor(), text,
                        blankToNull(clientMessageId), null, writer.now()));
    }

    private Conversation own(CurrentUser user, UUID conversationId) {
        UUID customerId = callers.customer(user).customerId();
        return conversations.findById(conversationId).filter(c -> c.customerId().equals(customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** A context named by its kind and id from a request; null when neither is given. */
    public static ContextRef ref(ContextKind kind, UUID id) {
        if (kind == null && id == null) {
            return null;
        }
        if (kind == null || id == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A context is a kind and an id");
        }
        return new ContextRef(kind, id);
    }
}
