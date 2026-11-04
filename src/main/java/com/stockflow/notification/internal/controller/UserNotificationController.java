package com.stockflow.notification.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.notification.internal.controller.dto.MarkedReadResponse;
import com.stockflow.notification.internal.controller.dto.UnreadCountResponse;
import com.stockflow.notification.internal.controller.dto.UserNotificationPageResponse;
import com.stockflow.notification.internal.service.UserInbox;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The signed-in user's own inbox (the bell), for staff and for customers.
 *
 * <p><b>Why a role that holds {@code notification-notifications}, CUSTOMER included, may call this</b>: every
 * operation is keyed by the caller's own user id, taken from the token and never from the request, and every
 * query behind it filters {@code recipient_id = that id} (see {@code UserNotificationRepository}). There is
 * no data scope to apply and nothing a caller can name that belongs to somebody else: a message id that
 * is not the caller's is answered {@code 404 NOTIFICATION_NOT_FOUND}, the same as one that does not exist.
 * So this endpoint needs no {@code ScopeGuards.requireBackOffice}; adding an endpoint here that takes a
 * user id or lists more than the caller's rows would break that and must add one.</p>
 *
 * <p>Writes are not {@code @Auditable}: marking one's own message read is not a business action, and an
 * audit row per click would bury the entries that matter.</p>
 */
@RestController
@RequestMapping("/api/v1/notifications/me")
@Tag(name = "Notifications", description = "The caller's own in-app inbox")
@PermissionResource(
        code = NotificationResources.NOTIFICATIONS,
        group = "Notification",
        label = "My notifications",
        route = "/notifications",
        apiPath = "/api/v1/notifications/me",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
class UserNotificationController {

    private final UserInbox inbox;
    private final UserInboxWebMapper mapper;

    UserNotificationController(UserInbox inbox, UserInboxWebMapper mapper) {
        this.inbox = inbox;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "My messages, newest first, with how many are unread")
    @RequiresPermission(resource = NotificationResources.NOTIFICATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<UserNotificationPageResponse> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(inbox.list(user.userId(), unreadOnly, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size)));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many of my messages are unread (for polling the bell badge)")
    @RequiresPermission(resource = NotificationResources.NOTIFICATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<UnreadCountResponse> unreadCount(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(new UnreadCountResponse(inbox.unreadCount(user.userId())));
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Mark one of my messages read; idempotent. 404 for any id that is not mine")
    @RequiresPermission(resource = NotificationResources.NOTIFICATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    public void markRead(@PathVariable UUID notificationId, @AuthenticatedUser CurrentUser user) {
        inbox.markRead(user.userId(), notificationId);
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my unread messages read")
    @RequiresPermission(resource = NotificationResources.NOTIFICATIONS, action = Action.UPDATE, scope = DataScope.OWN)
    public ApiResponse<MarkedReadResponse> markAllRead(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(new MarkedReadResponse(inbox.markAllRead(user.userId())));
    }
}
