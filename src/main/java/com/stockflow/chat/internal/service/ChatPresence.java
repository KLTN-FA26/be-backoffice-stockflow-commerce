package com.stockflow.chat.internal.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which agents are online: those with an open staff socket that has spoken (a frame, or the client's heartbeat to
 * {@code /app/chat.presence.heartbeat}) within {@link ChatProperties#presenceTimeout}.
 *
 * <p>In memory, on purpose: the application runs as one instance on the VPS, and presence is a hint ("Sale đang trực
 * tuyến", 16 §4.1 step 3), not a record - losing it on a restart costs nothing, since every client reconnects. A second
 * instance would keep it in Redis (a key per agent with a TTL, refreshed by the heartbeat) and spread the presence
 * events over Redis pub/sub, as the broker would be (see {@code WebSocketConfig}).</p>
 */
@Component
public class ChatPresence {

    private record Agent(Set<String> sessions, Instant lastSeen) {
    }

    private final Map<UUID, Agent> online = new ConcurrentHashMap<>();
    private final Map<String, UUID> sessions = new ConcurrentHashMap<>();
    /** Customers' portal accounts and how many chat sockets each has open. */
    private final Map<UUID, Integer> customers = new ConcurrentHashMap<>();
    private final ChatProperties properties;
    private final Clock clock;

    public ChatPresence(ChatProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A staff socket opened.
     *
     * @return whether the agent just came online (their first socket)
     */
    public boolean connected(UUID userId, String sessionId) {
        sessions.put(sessionId, userId);
        boolean[] first = {false};
        online.compute(userId, (id, agent) -> {
            Set<String> open = new HashSet<>(agent == null ? Set.of() : agent.sessions());
            first[0] = open.isEmpty();
            open.add(sessionId);
            return new Agent(Set.copyOf(open), clock.instant());
        });
        return first[0];
    }

    /**
     * A socket closed.
     *
     * @return the agent who just went offline (their last socket closed); null otherwise
     */
    public UUID disconnected(String sessionId) {
        UUID userId = sessions.remove(sessionId);
        if (userId == null) {
            return null;
        }
        if (customers.containsKey(userId)) {
            customers.computeIfPresent(userId, (id, open) -> open > 1 ? open - 1 : null);
            return null;
        }
        boolean[] last = {false};
        online.computeIfPresent(userId, (id, agent) -> {
            Set<String> open = new HashSet<>(agent.sessions());
            open.remove(sessionId);
            last[0] = open.isEmpty();
            return open.isEmpty() ? null : new Agent(Set.copyOf(open), agent.lastSeen());
        });
        return last[0] ? userId : null;
    }

    /** The agent's socket spoke: they are still there. */
    public void seen(UUID userId) {
        online.computeIfPresent(userId, (id, agent) -> new Agent(agent.sessions(), clock.instant()));
    }

    /**
     * A customer's socket opened. A customer has no heartbeat: an open socket is a portal page with the chat on it,
     * which is all "the customer is here" means for telling them of a reply (16 §4.1 step 3) - the socket's own
     * heartbeats close it when the page goes away.
     */
    public void customerConnected(UUID userId, String sessionId) {
        sessions.put(sessionId, userId);
        customers.merge(userId, 1, Integer::sum);
    }

    /** Whether the customer's portal account has a chat socket open now. */
    public boolean isCustomerOnline(UUID userId) {
        return userId != null && customers.getOrDefault(userId, 0) > 0;
    }

    public boolean isOnline(UUID userId) {
        if (userId == null) {
            return false;
        }
        Agent agent = online.get(userId);
        return agent != null && !agent.sessions().isEmpty()
                && agent.lastSeen().plus(properties.presenceTimeout()).isAfter(clock.instant());
    }
}
