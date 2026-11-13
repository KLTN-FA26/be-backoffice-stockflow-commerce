package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.controller.dto.ChatRequests;
import com.stockflow.chat.internal.service.ChatRealtimeService;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

/**
 * What a client sends over the chat's socket - a typing signal, the staff client's heartbeat - and the socket's
 * comings and goings, which make an agent online or away. Messages themselves go over REST, where they are idempotent
 * and answered; the socket only carries what may be lost.
 *
 * <p>Whether the sender may send to the destination at all was decided before this runs, by
 * {@code StompAuthenticationInterceptor} asking {@link ChatStompPolicy}.</p>
 */
@Controller
class ChatStompController {

    private final ChatRealtimeService realtime;
    private final CurrentUserProvider users;

    ChatStompController(ChatRealtimeService realtime, CurrentUserProvider users) {
        this.realtime = realtime;
        this.users = users;
    }

    /** {@code /app/chat.conversations.{id}.typing} with {@code {"typing": true|false}}. */
    @MessageMapping("chat.conversations.{conversationId}.typing")
    public void typing(@DestinationVariable UUID conversationId, @Payload ChatRequests.Typing frame,
                       Principal principal) {
        caller(principal).ifPresent(user -> realtime.typing(user, conversationId, frame != null && frame.typing()));
    }

    /** {@code /app/chat.presence.heartbeat}, every 30 s or so from a staff client: still here. */
    @MessageMapping("chat.presence.heartbeat")
    public void heartbeat(Principal principal) {
        caller(principal).ifPresent(realtime::heartbeat);
    }

    @EventListener
    public void connected(SessionConnectedEvent event) {
        String session = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        caller(event.getUser()).ifPresent(user -> realtime.connected(user, session));
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        realtime.disconnected(event.getSessionId());
    }

    private Optional<CurrentUser> caller(Principal principal) {
        return principal instanceof Authentication authentication ? users.of(authentication) : Optional.empty();
    }
}
