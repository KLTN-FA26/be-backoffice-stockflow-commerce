package com.stockflow.chat.internal.controller;

import com.stockflow.chat.internal.service.ChatRealtimeService;
import com.stockflow.chat.internal.service.ChatTopics;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.websocket.StompDestinationPolicy;
import org.springframework.stereotype.Component;

/**
 * Who may listen to and send to the chat's STOMP destinations ({@link ChatTopics}). A conversation's topic: its
 * customer or the staff; its staff topic and the queue: the staff only; presence: anyone signed in. Anything else under
 * the chat's prefixes is refused.
 */
@Component
class ChatStompPolicy implements StompDestinationPolicy {

    private final ChatRealtimeService realtime;

    ChatStompPolicy(ChatRealtimeService realtime) {
        this.realtime = realtime;
    }

    @Override
    public boolean claims(String destination) {
        return destination.startsWith("/topic/chat.") || destination.startsWith(ChatTopics.APP_PREFIX);
    }

    @Override
    public boolean maySubscribe(CurrentUser user, String destination) {
        if (ChatTopics.INBOX.equals(destination)) {
            return realtime.mayFollowInbox(user);
        }
        if (ChatTopics.PRESENCE.equals(destination)) {
            return true;
        }
        return ChatTopics.parse(destination).filter(d -> !d.application() && !d.typing())
                .map(d -> realtime.mayFollow(user, d.conversationId(), d.staffOnly())).orElse(false);
    }

    @Override
    public boolean maySend(CurrentUser user, String destination) {
        if (ChatTopics.HEARTBEAT.equals(destination)) {
            return realtime.mayFollowInbox(user);
        }
        return ChatTopics.parse(destination).filter(d -> d.application() && d.typing())
                .map(d -> realtime.mayFollow(user, d.conversationId(), false)).orElse(false);
    }
}
