package com.stockflow.notification.internal.controller.dto;

/** How many messages a mark-all-read turned from unread to read; 0 when there was nothing unread. */
public record MarkedReadResponse(int updated) {
}
