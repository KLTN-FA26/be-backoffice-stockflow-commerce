package com.stockflow.notification.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A page of the caller's inbox: the fields of {@code PageResponse} plus {@code unreadCount}, so the bell
 * badge needs no second request.
 */
public record UserNotificationPageResponse(
        List<UserNotificationResponse> items,
        @Schema(description = "Zero-based page number", example = "0")
        int page,
        int size,
        @Schema(description = "Messages matching the filter (all of them, or only the unread ones with unreadOnly=true)")
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious,
        @Schema(description = "Unread messages of the caller in all, whatever the filter and the page")
        long unreadCount) {
}
