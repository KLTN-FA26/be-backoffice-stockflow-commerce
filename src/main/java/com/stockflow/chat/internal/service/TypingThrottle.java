package com.stockflow.chat.internal.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most one typing signal per person per conversation per {@link ChatProperties#typingInterval}: a client that sends
 * one per keystroke would otherwise fan out to every subscriber for every key. A signal over the rate is dropped, not
 * refused - typing is a hint, and the next one a moment later says the same. In memory, like presence: one instance.
 */
@Component
public class TypingThrottle {

    /** Past this many remembered pairs, the stale ones are forgotten before the next is added. */
    private static final int SWEEP_AT = 10_000;

    private final Map<String, Instant> last = new ConcurrentHashMap<>();
    private final ChatProperties properties;
    private final Clock clock;

    public TypingThrottle(ChatProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether this signal may go out. */
    public boolean allow(UUID userId, UUID conversationId) {
        Instant now = clock.instant();
        if (last.size() > SWEEP_AT) {
            Instant stale = now.minus(properties.typingInterval());
            last.values().removeIf(at -> at.isBefore(stale));
        }
        boolean[] allowed = {false};
        last.compute(userId + ":" + conversationId, (key, at) -> {
            if (at == null || !at.plus(properties.typingInterval()).isAfter(now)) {
                allowed[0] = true;
                return now;
            }
            return at;
        });
        return allowed[0];
    }
}
