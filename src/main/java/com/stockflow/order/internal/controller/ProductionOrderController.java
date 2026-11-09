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
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.ProductionOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Production orders (4A-2, kltn-docs 17 §4.3, §5.3): the Production Coordinator draws them up, edits, submits, cancels
 * and closes them (00 §2.1, 2026-11-07); the Sales Manager approves - never their own - or returns them (BR-23); Sales
 * and the warehouse manager read them, the warehouse manager those coming to their warehouses.
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/production-orders")
@Tag(name = "Production orders")
@PermissionResource(code = OrderResources.PRODUCTION_ORDERS, group = "Production", label = "Production orders",
        route = "/production/orders", apiPath = "/api/v1/production-orders",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class ProductionOrderController {

    /** What the coordinator draws up a production order from. */
    @Schema(name = "DrawUpProductionOrderRequest")
    record DrawUpRequest(@NotNull(message = "salesOrderId is required") UUID salesOrderId,
                         @Schema(description = "The delivery batch of the order's schedule, by its number")
                         @NotNull(message = "deliveryBatchSeq is required") @Min(value = 1, message = "deliveryBatchSeq is at least 1")
                         Integer deliveryBatchSeq,
                         @NotNull(message = "producerId is required") UUID producerId) {
    }

    /** A line's new cartons and packing requirements. */
    @Schema(name = "ProductionOrderLineEdit")
    record LineEditRequest(@NotNull(message = "orderLineId is required") UUID orderLineId,
                           @NotNull(message = "cartons is required") @Min(value = 1, message = "cartons is at least 1")
                           Integer cartons,
                           @Size(max = 500, message = "packingRequirements is at most 500 characters")
                           String packingRequirements) {
    }

    /** The coordinator's changes to a draft, read at {@code version} (B33). */
    @Schema(name = "EditProductionOrderRequest")
    record EditRequest(@NotNull(message = "version is required") Long version,
                       @NotNull(message = "producerId is required") UUID producerId,
                       @NotNull(message = "needByDate is required") LocalDate needByDate,
                       @DecimalMin(value = "0", message = "unitPrice is not negative") BigDecimal unitPrice,
                       @Size(max = 500, message = "note is at most 500 characters") String note,
                       @Schema(description = "BR-24: turned off while on by default, it needs sampleWaiverReason")
                       boolean sampleApprovalRequired,
                       @Size(max = 500, message = "sampleWaiverReason is at most 500 characters")
                       String sampleWaiverReason,
                       @Size(max = 200, message = "at most 200 lines") List<@Valid @NotNull LineEditRequest> lines) {
    }

    /** A move of status, at the version read. */
    @Schema(name = "ProductionOrderVersionRequest")
    record VersionRequest(@NotNull(message = "version is required") Long version) {
    }

    /** A move of status that says why (return, cancel, close). */
    @Schema(name = "ProductionOrderReasonRequest")
    record ReasonRequest(@NotNull(message = "version is required") Long version,
                         @NotBlank(message = "reason is required")
                         @Size(max = 500, message = "reason is at most 500 characters") String reason) {
    }

    /** One line of a production order. */
    @Schema(name = "ProductionOrderLine")
    record LineResponse(UUID id, UUID orderLineId, String sku, int cartons, int unitsPerCarton, int totalUnits,
                        UUID designSnapshotId, String printFileChecksum, String packingRequirements,
                        int receivedCartons) {
    }

    /** One change of status. */
    @Schema(name = "ProductionOrderHistoryEntry")
    record HistoryResponse(ProductionOrderStatus from, ProductionOrderStatus to, String reason, UUID by, Instant at) {
    }

    /** A production order; {@code history} on the detail only. */
    @Schema(name = "ProductionOrder")
    record ProductionOrderResponse(UUID id, String code, UUID salesOrderId, UUID deliveryBatchId, UUID customerId,
                                   UUID producerId, UUID receivingWarehouseId, LocalDate needByDate,
                                   ProductionOrderStatus status, boolean sampleApprovalRequired,
                                   boolean sampleRequiredByDefault, String sampleWaiverReason, BigDecimal unitPrice,
                                   String currency, String note, UUID drawnUpBy, Instant submittedAt, UUID approvedBy,
                                   Instant approvedAt, String returnedReason, Instant sentAt,
                                   Instant productionStartedAt, Instant cancelledAt, String cancellationReason,
                                   Instant closedAt, String closingReason,
                                   @Schema(description = "BR-25: the warehouse capacity check is not computed yet")
                                   boolean capacityComputed,
                                   List<LineResponse> lines, List<HistoryResponse> history, long version) {

        static ProductionOrderResponse of(ProductionOrder p, List<ProductionOrderRepository.HistoryEntry> history) {
            return new ProductionOrderResponse(p.id(), p.code(), p.salesOrderId(), p.deliveryBatchId(),
                    p.customerId(), p.producerId(), p.receivingWarehouseId(), p.needByDate(), p.status(),
                    p.sampleApprovalRequired(), p.sampleRequiredByDefault(), p.sampleWaiverReason(), p.unitPrice(),
                    p.currency(), p.note(), p.drawnUpBy(), p.submittedAt(), p.approvedBy(), p.approvedAt(),
                    p.returnedReason(), p.sentAt(), p.productionStartedAt(), p.cancelledAt(),
                    p.cancellationReason(), p.closedAt(), p.closingReason(), false,
                    p.lines().stream().map(l -> new LineResponse(l.id(), l.orderLineId(), l.sku(), l.cartons(),
                            l.unitsPerCarton(), l.totalUnits(), l.designSnapshotId(), l.printFileChecksum(),
                            l.packingRequirements(), l.receivedCartons())).toList(),
                    history == null ? null : history.stream().map(h -> new HistoryResponse(h.from(), h.to(),
                            h.reason(), h.by(), h.at())).toList(),
                    p.version());
        }
    }

    private final ProductionOrderService productionOrders;

    ProductionOrderController(ProductionOrderService productionOrders) {
        this.productionOrders = productionOrders;
    }

    @GetMapping
    @Operation(summary = "Production orders, latest first; filter by receiving warehouse, status, producer and sales "
            + "order. A warehouse-bound caller sees those received at their warehouses")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<ProductionOrderResponse>> list(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) ProductionOrderStatus status,
            @RequestParam(required = false) UUID producerId,
            @RequestParam(required = false) UUID salesOrderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(productionOrders.list(warehouseId, status, producerId, salesOrderId, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(p -> ProductionOrderResponse.of(p, null)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One production order, with its lines and history")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> one(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var detail = productionOrders.detail(id);
        return ApiResponse.ok(ProductionOrderResponse.of(detail.order(), detail.history()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Draw up a production order for what a delivery batch of a confirmed order of a contract "
            + "lacks at its shipping warehouse (Production Coordinator). 409 PRODUCTION_ORDER_NOTHING_SHORT")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> drawUp(@Valid @RequestBody DrawUpRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProductionOrderResponse.of(productionOrders.drawUp(request.salesOrderId(),
                request.deliveryBatchSeq(), request.producerId(), user.userId()), null));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a draft production order (producer, need-by date, price, note, sample flag, lines)")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> edit(@PathVariable UUID id, @Valid @RequestBody EditRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Map<UUID, ProductionOrder.LineEdit> lines = new LinkedHashMap<>();
        if (request.lines() != null) {
            request.lines().forEach(line -> lines.put(line.orderLineId(),
                    new ProductionOrder.LineEdit(line.cartons(), line.packingRequirements())));
        }
        return ApiResponse.ok(ProductionOrderResponse.of(productionOrders.edit(id, request.version(),
                new ProductionOrder.Draft(request.producerId(), request.needByDate(), request.unitPrice(),
                        request.note(), request.sampleApprovalRequired(), request.sampleWaiverReason(), lines)), null));
    }

    @PostMapping("/{id}/submission")
    @Operation(summary = "Submit a draft for approval")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> submit(@PathVariable UUID id, @Valid @RequestBody VersionRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProductionOrderResponse.of(productionOrders.submit(id, request.version(), user.userId()),
                null));
    }

    @PostMapping("/{id}/approval")
    @Operation(summary = "Approve a production order, which sends it (Sales Manager; not the one who drew it up - "
            + "409 PRODUCTION_ORDER_SELF_APPROVAL). The print files are checked first: 409 PRINT_FILE_MISMATCH")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> approve(@PathVariable UUID id,
                                                        @Valid @RequestBody VersionRequest request,
                                                        @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProductionOrderResponse.of(productionOrders.approve(id, request.version(),
                user.userId()), null));
    }

    @PostMapping("/{id}/return")
    @Operation(summary = "Return a production order to its coordinator, with a reason (Sales Manager)")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> returnToDraft(@PathVariable UUID id,
                                                              @Valid @RequestBody ReasonRequest request,
                                                              @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProductionOrderResponse.of(productionOrders.returnToDraft(id, request.version(),
                request.reason(), user.userId()), null));
    }

    @PostMapping("/{id}/cancellation")
    @Operation(summary = "Cancel a production order before mass printing, with a reason")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProductionOrderResponse.of(productionOrders.cancel(id, request.version(),
                request.reason(), user.userId()), null));
    }

    @PostMapping("/{id}/closing")
    @Operation(summary = "Close a production order after mass printing started - short, or before anything came - "
            + "with a reason")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> close(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(ProductionOrderResponse.of(productionOrders.close(id, request.version(),
                request.reason(), user.userId()), null));
    }
}
