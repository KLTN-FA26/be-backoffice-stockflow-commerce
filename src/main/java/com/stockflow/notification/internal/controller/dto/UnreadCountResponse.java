package com.stockflow.notification.internal.controller.dto;

/** How many messages of the caller's inbox are unread. */
public record UnreadCountResponse(long unreadCount) {
}
