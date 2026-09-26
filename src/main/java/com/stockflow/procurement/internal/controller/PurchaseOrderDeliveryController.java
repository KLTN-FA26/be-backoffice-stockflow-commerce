package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.internal.controller.dto.DeliveryAttemptResponse;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderDeliveryDecisionResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** Purchasing owns authorization; notification only supplies its public delivery-read API. */
@RestController
@RequestMapping("/api/v1/purchase-orders")
class PurchaseOrderDeliveryController {
    private final ProcurementService procurement;
    private final NotificationService notifications;
    PurchaseOrderDeliveryController(ProcurementService procurement, NotificationService notifications) {
        this.procurement = procurement; this.notifications = notifications;
    }
    @GetMapping("/{purchaseOrderId}/delivery-decisions")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    @Operation(summary = "Read who authorized initial delivery or recovery, date changes and reconciliation reasons")
    public ApiResponse<PageResponse<PurchaseOrderDeliveryDecisionResponse>> decisions(@PathVariable UUID purchaseOrderId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(procurement.deliveryDecisions(purchaseOrderId, page, size).map(s ->
                new PurchaseOrderDeliveryDecisionResponse(s.id(), s.generation(), s.previousExpectedAt(), s.expectedAt(),
                        s.reason(), s.reconciled(), s.acknowledgePastDue(), s.channel(), s.recipient(), s.actor(), s.requestedAt())));
    }
    @GetMapping("/{purchaseOrderId}/deliveries")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    @Operation(summary = "List delivery attempts; transport acceptance is not supplier confirmation")
    public ApiResponse<PageResponse<DeliveryAttemptResponse>> deliveries(@PathVariable UUID purchaseOrderId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        procurement.findById(purchaseOrderId).orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        return ApiResponse.ok(notifications.purchaseOrderDeliveries(purchaseOrderId, page, size)
                .map(s -> new DeliveryAttemptResponse(s.id(), s.channel(), s.status(), s.attemptedAt(), s.sentAt(), s.failure(), s.generation(), s.recipient())));
    }
}
