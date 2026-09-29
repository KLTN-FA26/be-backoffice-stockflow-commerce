package com.stockflow.notification.api;

import com.stockflow.common.api.PageResponse;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Delivery visibility and transactional dispatch control; only after-commit listeners perform transport.
 */
public interface NotificationService {
    void queueInventoryAlert(InventoryAlertMessage message);
    java.util.List<AlertDeliverySummary> inventoryAlertDeliveries(UUID alertId);
    void retryInventoryAlert(UUID alertId);
    int prepareSupplierDelivery(UUID purchaseOrderId, boolean recovery);
    void suppressSupplierDelivery(UUID purchaseOrderId);
    void validateSupplierDelivery(String channel, String recipient);
    Map<UUID, String> purchaseOrderDeliveryStatuses(Collection<UUID> ids);

    PageResponse<DeliveryAttemptSummary> purchaseOrderDeliveries(UUID purchaseOrderId, int page, int size);
}
