package com.stockflow.notification.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.notification.api.InboxSeverity;

import java.time.Instant;
import java.util.UUID;

/**
 * What a signed-in user does with their own inbox. Every method takes the user's id and touches only that
 * user's rows: there is no way to name somebody else's message, which is what lets a customer and a
 * staff member share the same endpoints.
 */
public interface UserInbox {

    record Item(UUID id, String category, InboxSeverity severity, String title, String body, String link,
                String sourceType, String sourceId, Instant createdAt, Instant readAt) {
    }

    /** A page of messages plus how many are unread in all, so the badge does not depend on the page asked for. */
    record InboxPage(PageResponse<Item> page, long unreadCount) {
    }

    /** The user's messages, newest first. A page number or size out of range is {@code UNSUPPORTED_PARAMETER}. */
    InboxPage list(UUID userId, boolean unreadOnly, int page, int size);

    long unreadCount(UUID userId);

    /**
     * Marks one message read. Idempotent: reading it again keeps the first read time.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOTIFICATION_NOT_FOUND} when the user has no
     *         such message, which is also the answer for a message that belongs to somebody else
     */
    void markRead(UUID userId, UUID notificationId);

    /** @return how many messages were unread and are now read */
    int markAllRead(UUID userId);

    /** Deletes messages read more than the retention ago, for every user. @return how many */
    int purgeRead();
}
