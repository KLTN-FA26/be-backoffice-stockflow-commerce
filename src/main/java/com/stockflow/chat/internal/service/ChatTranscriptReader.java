package com.stockflow.chat.internal.service;

import com.stockflow.chat.api.ChatCardKind;
import com.stockflow.chat.api.ChatContextKind;
import com.stockflow.chat.api.ChatContextRef;
import com.stockflow.chat.api.ChatTranscript;
import com.stockflow.chat.api.ChatTranscripts;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.Visibility;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The chat's answer to {@link ChatTranscripts}: the staff screen's read of a conversation
 * ({@link StaffChatService#messages}), for the assistant.
 *
 * <p><b>Who may read.</b> {@link ChatCallers#staff} first, which refuses a portal account before anything is read (the
 * CUSTOMER role holds {@code chat-conversations} for its own side), then the permission itself: this is not behind a
 * controller's guard, so the screen's {@code chat-conversations:READ} is asked here. Any staff member with it may read
 * any conversation, as on the screen, where the others read without answering ({@link StaffChatService}).</p>
 *
 * <p><b>Which side.</b> The repository filters by {@link Audience} in the query, the one place internal notes are kept
 * from the customer (16 BR-07): asked for the customer's side, a note never leaves this class.</p>
 */
@Service
@Transactional(readOnly = true)
class ChatTranscriptReader implements ChatTranscripts {

    private final ChatCallers callers;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;

    ChatTranscriptReader(ChatCallers callers, ConversationRepository conversations, ChatMessageRepository messages) {
        this.callers = callers;
        this.conversations = conversations;
        this.messages = messages;
    }

    @Override
    public ChatTranscript recent(UUID conversationId, int limit, boolean withInternalNotes, CurrentUser caller) {
        Objects.requireNonNull(caller, "caller");
        Actor staff = callers.staff(caller);
        if (!caller.can(ChatCallers.CONVERSATIONS, Action.READ)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Reading a conversation needs "
                    + ChatCallers.CONVERSATIONS + ":READ");
        }
        Conversation c = conversations.findById(conversationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_CONVERSATION_NOT_FOUND));
        int size = Math.max(1, Math.min(limit, MAX_MESSAGES));
        List<ChatMessage> newestFirst = messages.before(c.id(),
                withInternalNotes ? Audience.STAFF : Audience.CUSTOMER, null, size + 1);
        boolean truncated = newestFirst.size() > size;
        List<ChatMessage> kept = new ArrayList<>(truncated ? newestFirst.subList(0, size) : newestFirst);
        kept.sort(Comparator.comparingLong(ChatMessage::seq));
        List<ChatContextRef> contexts = conversations.contextsOf(c.id()).stream()
                .sorted(Comparator.comparing((ConversationContext context) -> !context.primary())
                        .thenComparing(ConversationContext::attachedAt))
                .map(context -> new ChatContextRef(ChatContextKind.valueOf(context.ref().kind().name()),
                        context.ref().id()))
                .distinct()
                .toList();
        return new ChatTranscript(c.id(), c.customerId(), c.subject(), c.status().name(), c.orderId(), contexts,
                kept.stream().map(message -> message(message, staff.userId())).toList(), truncated);
    }

    private static ChatTranscript.Message message(ChatMessage message, UUID reader) {
        boolean deleted = message.deletedAt() != null;
        return new ChatTranscript.Message(message.seq(), message.senderType().name(), message.kind().name(),
                message.visibility() == Visibility.STAFF, reader.equals(message.senderId()),
                deleted ? null : message.body(),
                message.card() == null ? null : ChatCardKind.valueOf(message.card().kind().name()),
                deleted ? 0 : message.attachments().size(), message.sentAt());
    }
}
