package com.stockflow.common.websocket;

import com.stockflow.common.security.CurrentUser;

/**
 * Who may subscribe to, or send to, a module's STOMP destinations. A module that publishes over the socket declares one
 * for the destinations it owns (the chat: {@code /topic/chat.*} and {@code /app/chat.*}).
 *
 * <p>Closed by default: {@link StompAuthenticationInterceptor} refuses a destination that no policy claims. A topic
 * nobody thought about is a topic nobody may read - the opposite would let any signed-in account subscribe to
 * another customer's conversation by guessing its name.</p>
 */
public interface StompDestinationPolicy {

    /** Whether the destination is this policy's to decide. */
    boolean claims(String destination);

    /** Whether {@code user} may receive what is published to {@code destination}. Asked on every SUBSCRIBE. */
    boolean maySubscribe(CurrentUser user, String destination);

    /** Whether {@code user} may send to {@code destination} (an {@code /app/...} one). Asked on every SEND. */
    boolean maySend(CurrentUser user, String destination);
}
