package com.stockflow.chat.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Sales chat settings ({@code stockflow.chat}). Package 3 makes the clocks follow working hours (16 §4.1 step 3,
 * open-questions F3, F5); until then they run on the wall clock.
 *
 * @param firstResponseTarget how soon after a conversation is opened its first answer is due (BR-09)
 * @param reopenWindow        how long after it is resolved or auto-closed the customer's word reopens it (16 §5)
 * @param typingInterval      at most one typing signal per person per conversation in this time; the rest are dropped
 * @param presenceTimeout     a staff socket silent this long (no heartbeat, no frame) shows the agent as away
 * @param maxPageSize         the most messages or conversations one request returns
 */
@ConfigurationProperties(prefix = "stockflow.chat")
public record ChatProperties(
        @DefaultValue("PT30M") Duration firstResponseTarget,
        @DefaultValue("P7D") Duration reopenWindow,
        @DefaultValue("PT2S") Duration typingInterval,
        @DefaultValue("PT90S") Duration presenceTimeout,
        @DefaultValue("100") int maxPageSize) {

    public ChatProperties {
        requirePositive(firstResponseTarget, "first-response-target");
        requirePositive(reopenWindow, "reopen-window");
        requirePositive(typingInterval, "typing-interval");
        requirePositive(presenceTimeout, "presence-timeout");
        if (maxPageSize < 1) {
            throw new IllegalArgumentException("stockflow.chat.max-page-size must be at least 1");
        }
    }

    /** A page size the caller asked for, kept between 1 and {@link #maxPageSize}. */
    public int pageSize(Integer requested, int fallback) {
        int size = requested == null ? fallback : requested;
        return Math.max(1, Math.min(size, maxPageSize));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("stockflow.chat." + name + " must be positive");
        }
    }
}
