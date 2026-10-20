package com.stockflow.notification.internal.controller;

/** Permission resource codes owned by the notification module. */
final class NotificationResources {

    /**
     * A user's own in-app inbox. Seeded in V20260903000100, granted to CUSTOMER there and to the staff roles
     * in V20261014000400; guards {@code /api/v1/notifications/me}.
     */
    static final String NOTIFICATIONS = "notification-notifications";

    private NotificationResources() {
    }
}
