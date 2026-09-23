package com.stockflow.notification.api;

/**
 * Read-only delivery visibility; outbound work remains owned by after-commit event listeners.
 */
public interface NotificationService {

    com.stockflow.common.api.PageResponse<DeliveryAttemptSummary> purchaseOrderDeliveries(
            java.util.UUID purchaseOrderId, int page, int size);
}
