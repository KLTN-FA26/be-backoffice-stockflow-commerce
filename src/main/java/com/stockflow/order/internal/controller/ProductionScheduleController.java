package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.domain.DelayAlertType;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.ProductionScheduleQuery.ScheduledProduction;
import com.stockflow.order.internal.service.ProductionScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The production schedule (4A-7, kltn-docs 17 §4.12 step 47): the open production orders by the day their goods are
 * needed, with their delay flags, and the delivery batches still short that no production order covers. Read by the
 * Production Coordinator, Sales and the Sales Manager; the warehouse manager sees their warehouses only.
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/production-schedule")
@Tag(name = "Production schedule")
@PermissionResource(code = OrderResources.PRODUCTION_SCHEDULE, group = "Production", label = "Production schedule",
        route = "/production/schedule", apiPath = "/api/v1/production-schedule",
        actions = {Action.VIEW_PAGE, Action.READ})
class ProductionScheduleController {

    /** A delay flag. */
    @Schema(name = "DelayFlag")
    record FlagResponse(UUID alertId, DelayAlertType type, Instant openedAt) {

        static List<FlagResponse> of(List<ProductionScheduleService.Flag> flags) {
            return flags.stream().map(f -> new FlagResponse(f.alertId(), f.type(), f.openedAt())).toList();
        }
    }

    /** An open production order on the schedule. */
    @Schema(name = "ScheduledProductionOrder")
    record EntryResponse(UUID id, String code, ProductionOrderStatus status, UUID producerId, String producerCode,
                         String producerName, UUID salesOrderId, String orderNumber, UUID customerId,
                         UUID deliveryBatchId, int deliveryBatchSeq, LocalDate windowStart, LocalDate windowEnd,
                         UUID receivingWarehouseId, String receivingWarehouseCode, LocalDate needByDate,
                         LocalDate expectedCompletionDate, int cartons, int receivedCartons,
                         List<FlagResponse> alerts) {

        static EntryResponse of(ProductionScheduleService.Entry entry) {
            ScheduledProduction p = entry.production();
            return new EntryResponse(p.productionOrderId(), p.code(), p.status(), p.producerId(), p.producerCode(),
                    p.producerName(), p.salesOrderId(), p.orderNumber(), p.customerId(), p.deliveryBatchId(),
                    p.batchSeq(), p.windowStart(), p.windowEnd(), p.receivingWarehouseId(), entry.warehouseCode(),
                    p.needByDate(), p.expectedCompletionDate(), p.cartons(), p.receivedCartons(),
                    FlagResponse.of(entry.alerts()));
        }
    }

    /** What one line of a short batch lacks. */
    @Schema(name = "ShortBatchLine")
    record ShortLineResponse(UUID orderLineId, String sku, int cartons) {
    }

    /** A delivery batch still short that no open production order covers. */
    @Schema(name = "ShortDeliveryBatch")
    record ShortBatchResponse(UUID salesOrderId, String orderNumber, UUID customerId, UUID shippingWarehouseId,
                              String shippingWarehouseCode, UUID deliveryBatchId, int seq, LocalDate windowStart,
                              LocalDate windowEnd, int shortCartons, List<ShortLineResponse> lines,
                              List<FlagResponse> alerts) {

        static ShortBatchResponse of(ProductionScheduleService.ShortBatch b) {
            return new ShortBatchResponse(b.salesOrderId(), b.orderNumber(), b.customerId(), b.shippingWarehouseId(),
                    b.warehouseCode(), b.deliveryBatchId(), b.seq(), b.windowStart(), b.windowEnd(), b.shortCartons(),
                    b.lines().stream().map(l -> new ShortLineResponse(l.orderLineId(), l.sku(), l.cartons())).toList(),
                    FlagResponse.of(b.alerts()));
        }
    }

    @Schema(name = "ProductionSchedule")
    record ScheduleResponse(List<EntryResponse> productionOrders, List<ShortBatchResponse> shortBatches,
                            @Schema(description = "a list was cut at the limit: narrow the filters to see the rest")
                            boolean truncated) {
    }

    private final ProductionScheduleService schedule;

    ProductionScheduleController(ProductionScheduleService schedule) {
        this.schedule = schedule;
    }

    @GetMapping
    @Operation(summary = "Open production orders by need-by day, and delivery batches still short with no production "
            + "order covering them; filters: producer, warehouse, a range of days (need-by day / window start), only "
            + "what carries an open delay alert; each list holds at most limit rows (default 100, at most 200), truncated "
            + "says one was cut")
    @RequiresPermission(resource = OrderResources.PRODUCTION_SCHEDULE, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ScheduleResponse> schedule(
            @RequestParam(required = false) UUID producerId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean alertsOnly,
            @RequestParam(required = false) Integer limit,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        ProductionScheduleService.Schedule found = schedule.schedule(producerId, warehouseId, from, to, alertsOnly,
                limit == null ? ProductionScheduleService.DEFAULT_LIMIT : limit);
        return ApiResponse.ok(new ScheduleResponse(found.productionOrders().stream().map(EntryResponse::of).toList(),
                found.shortBatches().stream().map(ShortBatchResponse::of).toList(), found.truncated()));
    }
}
