package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.domain.DelayAlertType;
import com.stockflow.order.internal.domain.ProductionScheduleQuery.AlertView;
import com.stockflow.order.internal.service.DelayAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The delay alerts (4A-7, kltn-docs 17 §4.12 step 49): the "late" flags on the order page, open ones by default. The
 * system alone opens and closes them, so there is nothing to write here. A warehouse-bound caller sees the alerts of
 * their warehouses.
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/delay-alerts")
@Tag(name = "Delay alerts")
@PermissionResource(code = OrderResources.DELAY_ALERTS, group = "Production", label = "Delay alerts",
        route = "/sales/orders", apiPath = "/api/v1/delay-alerts", actions = {Action.READ})
class DelayAlertController {

    /** An alert and what names its subject. */
    @Schema(name = "DelayAlert")
    record AlertResponse(UUID id, DelayAlertType type, boolean open, UUID salesOrderId, String orderNumber,
                         UUID productionOrderId, String productionOrderCode, UUID deliveryBatchId,
                         Integer deliveryBatchSeq, UUID warehouseId, Instant openedAt, Instant closedAt) {

        static AlertResponse of(AlertView a) {
            return new AlertResponse(a.id(), a.type(), a.closedAt() == null, a.salesOrderId(), a.orderNumber(),
                    a.productionOrderId(), a.productionOrderCode(), a.deliveryBatchId(), a.batchSeq(), a.warehouseId(),
                    a.openedAt(), a.closedAt());
        }
    }

    private final DelayAlertService alerts;

    DelayAlertController(DelayAlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    @Operation(summary = "Delay alerts, newest first: of one order and type when given; open ones unless open=false")
    @RequiresPermission(resource = OrderResources.DELAY_ALERTS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<AlertResponse>> list(
            @RequestParam(required = false) UUID salesOrderId,
            @RequestParam(required = false) DelayAlertType type,
            @RequestParam(defaultValue = "true") boolean open,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(alerts.list(salesOrderId, type, open, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size).map(AlertResponse::of));
    }
}
