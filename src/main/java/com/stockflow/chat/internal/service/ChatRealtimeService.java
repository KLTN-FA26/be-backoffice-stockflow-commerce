package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.ConversationRepository;
import com.stockflow.chat.internal.domain.SenderType;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The socket's side of the chat: who may listen to what, typing and presence.
 *
 * <p>The rule for a conversation's topics is the REST rule: the customer of the conversation (its portal account,
 * holding {@code chat-conversations:READ}) or a staff member holding it; the staff topic, where internal notes go, is
 * the staff's alone (16 BR-07). It is asked again on every SUBSCRIBE, with permissions resolved afresh (ADR-0008), so
 * a revoked grant or a conversation that is not yours is refused even on a socket that was opened long ago.</p>
 */
@Service
public class ChatRealtimeService {

    private final ConversationRepository conversations;
    private final CustomerService customers;
    private final ChatPresence presence;
    private final TypingThrottle typing;
    private final ChatBroadcaster broadcaster;
    private final Clock clock;

    public ChatRealtimeService(ConversationRepository conversations, CustomerService customers,
                               ChatPresence presence, TypingThrottle typing, ChatBroadcaster broadcaster,
                               Clock clock) {
        this.conversations = conversations;
        this.customers = customers;
        this.presence = presence;
        this.typing = typing;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    /**
     * Whether {@code user} may follow the conversation: its customer, or the staff.
     *
     * @param staffOnly the conversation's staff topic (internal notes): never a customer
     */
    @Transactional(readOnly = true)
    public boolean mayFollow(CurrentUser user, UUID conversationId, boolean staffOnly) {
        if (!user.can(ChatCallers.CONVERSATIONS, Action.READ)) {
            return false;
        }
        if (ScopeGuards.isBackOffice(user)) {
            return conversations.existsById(conversationId);
        }
        if (staffOnly) {
            return false;
        }
        return customers.findByUserId(user.userId())
                .flatMap(customer -> conversations.findById(conversationId)
                        .filter(c -> c.customerId().equals(customer.customerId())))
                .isPresent();
    }

    /** Whether {@code user} may follow the staff queue. */
    public boolean mayFollowInbox(CurrentUser user) {
        return ScopeGuards.isBackOffice(user) && user.can(ChatCallers.CONVERSATIONS, Action.READ);
    }

    /** Someone signals typing in a conversation they may follow; dropped when over the rate. */
    public void typing(CurrentUser user, UUID conversationId, boolean isTyping) {
        if (!typing.allow(user.userId(), conversationId)) {
            return;
        }
        String side = ScopeGuards.isBackOffice(user) ? SenderType.AGENT.name() : SenderType.CUSTOMER.name();
        broadcaster.conversation(conversationId, new ChatEvent(ChatEvent.TYPING, conversationId,
                new ChatEvent.Typing(user.userId(), side, isTyping), clock.instant()));
        if (ScopeGuards.isBackOffice(user)) {
            presence.seen(user.userId());
        }
    }

    /** A staff client's heartbeat: the agent is still there. */
    public void heartbeat(CurrentUser user) {
        if (ScopeGuards.isBackOffice(user)) {
            presence.seen(user.userId());
        }
    }

    /** A socket opened: an agent's first one puts them online. */
    public void connected(CurrentUser user, String sessionId) {
        if (mayFollowInbox(user) && presence.connected(user.userId(), sessionId)) {
            broadcaster.presence(new ChatEvent(ChatEvent.PRESENCE, null,
                    new ChatEvent.Presence(user.userId(), true), clock.instant()));
        }
    }

    /** A socket closed: an agent's last one takes them offline. */
    public void disconnected(String sessionId) {
        UUID gone = presence.disconnected(sessionId);
        if (gone != null) {
            broadcaster.presence(new ChatEvent(ChatEvent.PRESENCE, null, new ChatEvent.Presence(gone, false),
                    clock.instant()));
        }
    }
}
