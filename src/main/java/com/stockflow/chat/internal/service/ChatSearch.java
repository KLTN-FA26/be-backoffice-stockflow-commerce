package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.ConversationStatus;
import com.stockflow.chat.internal.domain.Slice;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The staff's search over what was said (kltn-docs 16 §4.6 step 19: the history is the record - "đã hứa giảm giá qua
 * chat" is settled by reading it): every message the staff may see, internal notes included, deleted ones never.
 * Full-text with accents ignored ({@code unaccent}), so "bao gia" finds "báo giá", plus a substring match for a word
 * typed in part. A customer has no search: they scroll their own conversations.
 */
@Service
@Transactional(readOnly = true)
public class ChatSearch {

    /** A word shorter than this matches too much to be a search. */
    private static final int MIN_QUERY = 2;

    /** A message found, with the conversation it is in. */
    public record Hit(ChatMessageView message, UUID conversationId, UUID customerId, String customerName,
                      String subject, ConversationStatus conversationStatus) {
    }

    private final ChatCallers callers;
    private final ChatMessageRepository messages;
    private final ConversationRepository conversations;
    private final ChatViews views;
    private final CustomerService customers;
    private final ChatProperties properties;

    public ChatSearch(ChatCallers callers, ChatMessageRepository messages, ConversationRepository conversations,
                      ChatViews views, CustomerService customers, ChatProperties properties) {
        this.callers = callers;
        this.messages = messages;
        this.conversations = conversations;
        this.views = views;
        this.customers = customers;
        this.properties = properties;
    }

    public PageResponse<Hit> search(CurrentUser user, String query, UUID customerId, UUID conversationId, int page,
                                    Integer size) {
        callers.staff(user);
        String q = query == null ? "" : query.strip();
        if (q.length() < MIN_QUERY || q.length() > 200) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Search for 2 to 200 characters");
        }
        int pageSize = properties.pageSize(size, 20);
        int index = Math.max(0, page);
        Slice<ChatMessage> found = messages.search(q, Audience.STAFF, customerId, conversationId, index, pageSize);
        List<UUID> ids = found.items().stream().map(ChatMessage::conversationId).distinct().toList();
        Map<UUID, Conversation> byId = ids.stream().map(conversations::findById).flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(Conversation::id, Function.identity()));
        Map<UUID, String> names = customers.fullNames(byId.values().stream().map(Conversation::customerId)
                .collect(Collectors.toSet()));
        List<ChatMessageView> shown = views.messages(found.items());
        List<Hit> hits = shown.stream().map(m -> {
            Conversation c = byId.get(m.conversationId());
            return new Hit(m, m.conversationId(), c == null ? null : c.customerId(),
                    c == null ? null : names.get(c.customerId()), c == null ? null : c.subject(),
                    c == null ? null : c.status());
        }).toList();
        return PageResponse.of(hits, index, pageSize, found.total());
    }
}
