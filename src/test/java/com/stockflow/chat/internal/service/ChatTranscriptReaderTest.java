package com.stockflow.chat.internal.service;

import com.stockflow.chat.api.ChatContextKind;
import com.stockflow.chat.api.ChatContextRef;
import com.stockflow.chat.api.ChatTranscript;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ConversationContext;
import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The assistant's read of a conversation ({@code chat :: api} {@code ChatTranscripts}): the staff screen's access, the
 * side asked for filtered in the query, the newest messages oldest first, and no staff names.
 */
class ChatTranscriptReaderTest {

    private static final Instant NOW = Instant.parse("2026-11-27T03:00:00Z");
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID CUSTOMER_USER = UUID.randomUUID();

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ChatTranscriptReader reader = new ChatTranscriptReader(new ChatCallers(mock(CustomerService.class)),
            conversations, messages);
    private final Conversation conversation = Conversation.open(UUID.randomUUID(), CUSTOMER, "Hỏi lịch giao", null,
            Actor.customer(CUSTOMER_USER), null, NOW, Duration.ofHours(1));

    @Test
    @DisplayName("the customer's side is read with the customer's audience, the newest messages kept, oldest first")
    void customerSide() {
        CurrentUser sales = staff("chat-conversations:READ");
        when(conversations.findById(conversation.id())).thenReturn(Optional.of(conversation));
        when(messages.before(eq(conversation.id()), eq(Audience.CUSTOMER), isNull(), eq(3))).thenReturn(List.of(
                text(5, Actor.agent(sales.userId(), false), "Dạ em kiểm tra"),
                text(4, Actor.customer(CUSTOMER_USER), "Đơn của tôi đâu?"),
                text(3, Actor.customer(CUSTOMER_USER), "Chào em")));
        UUID quote = UUID.randomUUID();
        when(conversations.contextsOf(conversation.id())).thenReturn(List.of(
                new ConversationContext(UUID.randomUUID(), conversation.id(), new ContextRef(ContextKind.QUOTE, quote),
                        true, null, NOW)));

        ChatTranscript transcript = reader.recent(conversation.id(), 2, false, sales);

        assertThat(transcript.truncated()).isTrue();
        assertThat(transcript.messages()).extracting(ChatTranscript.Message::seq).containsExactly(4L, 5L);
        assertThat(transcript.messages().get(1).byCaller()).isTrue();
        assertThat(transcript.messages().get(1).sender()).isEqualTo("AGENT");
        assertThat(transcript.contexts()).containsExactly(new ChatContextRef(ChatContextKind.QUOTE, quote));
        assertThat(transcript.customerId()).isEqualTo(CUSTOMER);
    }

    @Test
    @DisplayName("the staff's side is read with the staff's audience")
    void staffSide() {
        when(conversations.findById(conversation.id())).thenReturn(Optional.of(conversation));

        reader.recent(conversation.id(), 10, true, staff("chat-conversations:READ"));

        verify(messages).before(conversation.id(), Audience.STAFF, null, 11);
    }

    @Test
    @DisplayName("a portal account, and staff without the screen's permission, are refused before anything is read")
    void refusals() {
        CurrentUser portal = new CurrentUser(CUSTOMER_USER, "portal", Set.of(Role.CUSTOMER),
                Set.of(PermissionCode.parse("chat-conversations:READ")), DataScope.ALL, Set.of());
        assertThatThrownBy(() -> reader.recent(conversation.id(), 10, false, portal))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reader.recent(conversation.id(), 10, false, staff()))
                .extracting(failure -> ((BusinessException) failure).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verifyNoInteractions(conversations, messages);
    }

    @Test
    @DisplayName("an unknown conversation is CHAT_CONVERSATION_NOT_FOUND")
    void unknown() {
        when(conversations.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reader.recent(UUID.randomUUID(), 10, false, staff("chat-conversations:READ")))
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ErrorCode.CHAT_CONVERSATION_NOT_FOUND);
        verify(messages, org.mockito.Mockito.never()).before(any(), any(), any(), anyInt());
    }

    private ChatMessage text(long seq, Actor sender, String body) {
        return ChatMessage.text(UUID.randomUUID(), conversation.id(), seq, sender, body, null, null, NOW);
    }

    private static CurrentUser staff(String... permissions) {
        return new CurrentUser(UUID.randomUUID(), "sales", Set.of(Role.SALES_STAFF),
                java.util.Arrays.stream(permissions).map(PermissionCode::parse)
                        .collect(java.util.stream.Collectors.toSet()), DataScope.ALL, Set.of());
    }
}
