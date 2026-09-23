package com.stockflow.notification.internal.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery history uses purchasing permissions because it exposes purchasing operations.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final com.stockflow.notification.api.NotificationService service;
    NotificationController(com.stockflow.notification.api.NotificationService service) { this.service = service; }

    @org.springframework.web.bind.annotation.GetMapping("/purchase-orders/{purchaseOrderId}/deliveries")
    @com.stockflow.common.security.RequiresPermission(resource = "procurement-purchase-orders", action = com.stockflow.common.security.Action.READ)
    @io.swagger.v3.oas.annotations.Operation(summary = "List PO delivery attempts; SENT is transport acceptance, not supplier confirmation")
    public com.stockflow.common.api.ApiResponse<com.stockflow.common.api.PageResponse<com.stockflow.notification.internal.controller.dto.DeliveryAttemptResponse>> deliveries(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID purchaseOrderId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return com.stockflow.common.api.ApiResponse.ok(service.purchaseOrderDeliveries(purchaseOrderId, page, size)
                .map(s -> new com.stockflow.notification.internal.controller.dto.DeliveryAttemptResponse(s.id(), s.channel(),
                        s.status(), s.attemptedAt(), s.sentAt(), s.failure())));
    }
}
