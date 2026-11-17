package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationEventType;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * A message's author edits or deletes it, within {@link ChatProperties#editWindow} of sending it (decided 2026-11-13:
 * 5 minutes) - a typo fixed, a message sent to the wrong conversation taken back. Never a card or a system line, never
 * someone else's message ({@link ChatMessage#edit}, {@link ChatMessage#delete}). A deletion is soft: the row and its
 * place stay, its text and files are no longer shown to anyone. Both are logged (BR-01: MESSAGE_EDITED,
 * MESSAGE_DELETED) and pushed as {@code message.updated} to whoever may see the message.
 */
@Service
@Transactional
public class ChatMessageEdits {

    private final ChatCallers callers;
    private final ConversationWriter writer;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatViews views;
    private final ChatBroadcaster broadcaster;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatMessageEdits(ChatCallers callers, ConversationWriter writer, ConversationRepository conversations,
                            ChatMessageRepository messages, ChatViews views, ChatBroadcaster broadcaster,
                            ChatProperties properties, Clock clock) {
        this.callers = callers;
        this.writer = writer;
        this.conversations = conversations;
        this.messages = messages;
        this.views = views;
        this.broadcaster = broadcaster;
        this.properties = properties;
        this.clock = clock;
    }

    public ChatMessageView customerEdit(CurrentUser user, UUID conversationId, UUID messageId, String text) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        Conversation c = writer.lockOwn(conversationId, caller.customerId());
        return change(c, caller.actor(), messageId, Audience.CUSTOMER, text, false);
    }

    public ChatMessageView customerDelete(CurrentUser user, UUID conversationId, UUID messageId) {
        ChatCallers.CustomerCaller caller = callers.customer(user);
        Conversation c = writer.lockOwn(conversationId, caller.customerId());
        return change(c, caller.actor(), messageId, Audience.CUSTOMER, null, true);
    }

    public ChatMessageView staffEdit(CurrentUser user, UUID conversationId, UUID messageId, String text) {
        Actor staff = callers.staff(user);
        return change(writer.lock(conversationId), staff, messageId, Audience.STAFF, text, false);
    }

    public ChatMessageView staffDelete(CurrentUser user, UUID conversationId, UUID messageId) {
        Actor staff = callers.staff(user);
        return change(writer.lock(conversationId), staff, messageId, Audience.STAFF, null, true);
    }

    private ChatMessageView change(Conversation c, Actor by, UUID messageId, Audience audience, String text,
                                   boolean delete) {
        ChatMessage message = messages.findById(messageId, audience).filter(m -> m.conversationId().equals(c.id()))
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
        if (c.status() == com.stockflow.chat.internal.domain.ConversationStatus.CLOSED) {
            throw new BusinessException(ErrorCode.CHAT_CONVERSATION_ENDED);
        }
        Instant now = clock.instant();
        ChatMessage changed = delete ? message.delete(by, now, properties.editWindow())
                : message.edit(by, text, now, properties.editWindow());
        if (changed == message) {
            return views.message(message);
        }
        ChatMessage stored = messages.save(changed);
        c.logMessageAction(delete ? ConversationEventType.MESSAGE_DELETED : ConversationEventType.MESSAGE_EDITED, by,
                stored.id(), stored.contextId(), null, null, now);
        conversations.append(c.pullLog());
        ChatMessageView view = views.message(stored);
        broadcaster.message(ChatEvent.MESSAGE_UPDATED, view, writer.customerAccounts(c));
        return view;
    }
}
