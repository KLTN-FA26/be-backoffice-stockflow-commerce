package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A bell opens the conversation in the app that rang it (WF-02 re-check round 3, A3): the storefront's page is
 * {@code /chat/{id}}, the back office's {@code /admin/chat/{id}}. The API path both used to carry,
 * {@code /chat/conversations/{id}}, is a page in neither: the customer's "Sale đã trả lời" opened a 404, the
 * supervisors' SLA bells did nothing.
 */
class ChatNotifierLinksTest {

    private final Inbox inbox = mock(Inbox.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final ChatPresence presence = mock(ChatPresence.class);
    private final ChatProperties properties = mock(ChatProperties.class);
    private final ChatNotifier notifier = new ChatNotifier(inbox, identity, presence,
            mock(ApplicationEventPublisher.class), properties);
    private final Conversation conversation = mock(Conversation.class);
    private final UUID id = UUID.randomUUID();

    private String postedLink() {
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(anyList(), message.capture());
        return message.getValue().link();
    }

    @Test
    @DisplayName("the customer's 'Sale đã trả lời' opens /chat/{id}, the storefront's conversation page")
    void customerLink() {
        when(conversation.id()).thenReturn(id);
        when(properties.customerNoticePeriod()).thenReturn(Duration.ofHours(1));
        when(inbox.post(anyList(), any())).thenReturn(1);

        notifier.salesReplied(conversation, List.of(UUID.randomUUID()), Instant.now());

        assertThat(postedLink()).isEqualTo("/chat/" + id);
    }

    @Test
    @DisplayName("a supervisor's late-first-answer bell opens /admin/chat/{id}, the back office's conversation page")
    void staffLink() {
        when(conversation.id()).thenReturn(id);
        when(properties.businessZone()).thenReturn("Asia/Ho_Chi_Minh");
        when(identity.notificationRecipientsHolding(any(), any()))
                .thenReturn(List.of(new StaffContact(UUID.randomUUID(), "lead@example.com")));

        notifier.firstResponseOverdue(conversation);

        assertThat(postedLink()).isEqualTo("/admin/chat/" + id);
    }
}
