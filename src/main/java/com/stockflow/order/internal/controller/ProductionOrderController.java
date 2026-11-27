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
import com.stockflow.order.internal.domain.ProductionOrderKind;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.ProductionOrderLabels;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.WarehouseCapacityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Production orders (4A-2, kltn-docs 17 §4.3, §5.3): the Production Coordinator draws them up, edits, sends, cancels
 * and closes them (00 §2.1, 2026-11-07); since 2026-11-20 nobody approves them (BR-23 rewritten) - the Sales Manager,
 * Sales and the warehouse manager read them, the warehouse manager those coming to their warehouses. A response still
 * carries {@code submittedAt}, {@code approvedBy}, {@code approvedAt} and {@code returnedReason} for the orders of
 * before, and {@code sentBy} for who sent it. The coordinator also
 * records the producer's acceptance with its expected completion day, and the start of mass printing when no print
 * sample is to be approved (4A-4, 17 §4.3 steps 8 and 10); the print sample rounds are
 * {@link ProductionSampleRoundController}'s.
 *
 * <p>Handler methods are public, as on {@link OrderController}: the permission aspect needs a proxied public method.</p>
 */
@RestController
@RequestMapping("/api/v1/production-orders")
@Tag(name = "Production orders")
@PermissionResource(code = OrderResources.PRODUCTION_ORDERS, group = "Production", label = "Production orders",
        route = "/production/orders", apiPath = "/api/v1/production-orders",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class ProductionOrderController {

    /** What the coordinator draws up a production order from. */
    @Schema(name = "DrawUpProductionOrderRequest")
    record DrawUpRequest(@NotNull(message = "salesOrderId is required") UUID salesOrderId,
                         @Schema(description = "The delivery batch of the order's schedule, by its number")
                         @NotNull(message = "deliveryBatchSeq is required") @Min(value = 1, message = "deliveryBatchSeq is at least 1")
                         Integer deliveryBatchSeq,
                         @NotNull(message = "producerId is required") UUID producerId) {
    }

    /** What the coordinator draws up a replacement production order from (17 §4.13 step 52, BR-31). */
    @Schema(name = "DrawUpReplacementProductionOrderRequest")
    record DrawUpReplacementRequest(
            @Schema(description = "Posted DAMAGED write-offs of cartons of the production order replaced, one carton "
                    + "each, none already made up by a live replacement")
            @NotEmpty(message = "adjustmentIds is required") @Size(max = 500, message = "at most 500 write-offs")
            Set<@NotNull UUID> adjustmentIds,
            @Schema(description = "Null: the producer of the production order replaced") UUID producerId) {
    }

    /** A posted damaged write-off of a carton of a production order's goods, and what makes it up. */
    @Schema(name = "ProductionOrderWriteOff")
    record WriteOffResponse(UUID adjustmentId, String adjustmentNumber, String sku, UUID stockPackageId, String lpn,
                            Instant postedAt,
                            @Schema(description = "The live replacement production order making it up; null while "
                                    + "none does")
                            String madeUpBy) {
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

    /** A move of status that says why (cancel, close). */
    @Schema(name = "ProductionOrderReasonRequest")
    record ReasonRequest(@NotNull(message = "version is required") Long version,
                         @NotBlank(message = "reason is required")
                         @Size(max = 500, message = "reason is at most 500 characters") String reason) {
    }

    /** The producer accepted, and when it expects the goods done (17 §4.3 step 8). */
    @Schema(name = "AcceptProductionOrderRequest")
    record AcceptRequest(@NotNull(message = "version is required") Long version,
                         @NotNull(message = "expectedCompletionDate is required") LocalDate expectedCompletionDate,
                         @Schema(description = "Optional: who at the producer accepted, and how to reach them")
                         @Size(max = 500, message = "note is at most 500 characters") String note) {
    }

    /** The producer started mass printing; its new expected completion day, if it gave one (17 §4.3 step 10). */
    @Schema(name = "StartProductionRequest")
    record StartProductionRequest(@NotNull(message = "version is required") Long version,
                                  @Schema(description = "Null keeps the day given on acceptance")
                                  LocalDate expectedCompletionDate) {
    }

    /** The producer's new expected completion day. */
    @Schema(name = "ExpectedCompletionRequest")
    record ExpectedCompletionRequest(@NotNull(message = "version is required") Long version,
                                     @NotNull(message = "expectedCompletionDate is required")
                                     LocalDate expectedCompletionDate) {
    }

    /** One line of a production order. */
    @Schema(name = "ProductionOrderLine")
    record LineResponse(UUID id, UUID orderLineId, String sku, int cartons, int unitsPerCarton, int totalUnits,
                        UUID designSnapshotId, String printFileChecksum, String packingRequirements,
                        int receivedCartons) {
    }

    /** One change of status. */
    @Schema(name = "ProductionOrderHistoryEntry")
    record HistoryResponse(ProductionOrderStatus from, ProductionOrderStatus to, String reason, UUID by, Instant at,
                           @Schema(description = "Who made the change: full name, else username; null when by is null "
                                   + "(the system) or unknown")
                           String byName) {
    }

    /**
     * A production order; {@code history} on the detail only. Every id of another row carries its label beside it
     * ({@code salesOrderNumber}, {@code customerName}, {@code producerName}, {@code receivingWarehouseName},
     * {@code xxxByName}), read once for the whole answer by {@link ProductionOrderLabels}.
     */
    @Schema(name = "ProductionOrder")
    record ProductionOrderResponse(UUID id, String code, UUID salesOrderId, UUID deliveryBatchId, UUID customerId,
                                   UUID producerId, UUID receivingWarehouseId, LocalDate needByDate,
                                   ProductionOrderStatus status, boolean sampleApprovalRequired,
                                   boolean sampleRequiredByDefault, String sampleWaiverReason, String note,
                                   UUID drawnUpBy, Instant submittedAt, UUID approvedBy,
                                   Instant approvedAt, String returnedReason, Instant sentAt,
                                   @Schema(description = "Who sent it to its producer; the approver on orders sent "
                                           + "before 2026-11-20")
                                   UUID sentBy,
                                   Instant productionStartedAt,
                                   @Schema(description = "4A-4: the producer's expected completion day, from ACCEPTED on")
                                   LocalDate expectedCompletionDate,
                                   @Schema(description = "What the coordinator kept with the producer's acceptance "
                                           + "(who accepted, how to reach them); null when none")
                                   String acceptanceNote, Instant cancelledAt, String cancellationReason,
                                   Instant closedAt, String closingReason,
                                   @Schema(description = "STANDARD, or REPLACEMENT of damaged cartons (17 §4.13)")
                                   ProductionOrderKind kind,
                                   @Schema(description = "REPLACEMENT: the production order whose damaged cartons it "
                                           + "makes up; null otherwise")
                                   UUID replacesProductionOrderId,
                                   @Schema(description = "REPLACEMENT: the write-offs it makes up, one carton each; "
                                           + "empty otherwise")
                                   List<UUID> writeOffAdjustmentIds,
                                   @Schema(description = "BR-25: true since BE1 - capacity carries the receiving "
                                           + "warehouse's board")
                                   boolean capacityComputed,
                                   @Schema(description = "BR-25 (17 §4.3 steps 6-7; 06 §4.6): the receiving "
                                           + "warehouse's capacity board as it is now, and whether what this production "
                                           + "order still brings fits it - judged without its own cartons. A warning, "
                                           + "never a refusal; null when its warehouse is gone")
                                   WarehouseCapacityService.ProductionOrderCapacity capacity,
                                   List<LineResponse> lines, List<HistoryResponse> history, long version,
                                   @Schema(example = "SO-20261108-000007", description = "The sales order's number")
                                   String salesOrderNumber,
                                   @Schema(description = "The customer's registered name; null when the customer "
                                           + "record is gone")
                                   String customerName,
                                   @Schema(description = "The code the customer is known by; set where customerName is")
                                   String customerCode,
                                   @Schema(description = "The producer's name")
                                   String producerName,
                                   @Schema(example = "HCM", description = "The code (prefix) of the receiving "
                                           + "warehouse")
                                   String receivingWarehouseCode,
                                   @Schema(description = "The receiving warehouse's name")
                                   String receivingWarehouseName,
                                   @Schema(description = "Who drew it up; null when unknown")
                                   String drawnUpByName,
                                   @Schema(description = "Who approved it (orders of before 2026-11-20 only); null "
                                           + "when approvedBy is null or unknown")
                                   String approvedByName,
                                   @Schema(description = "Who sent it; null when sentBy is null or unknown")
                                   String sentByName) {

        static ProductionOrderResponse of(ProductionOrder p, List<ProductionOrderRepository.HistoryEntry> history,
                                          WarehouseCapacityService.ProductionOrderCapacity capacity) {
            return of(p, history, capacity, ProductionOrderLabels.Labels.NONE);
        }

        static ProductionOrderResponse of(ProductionOrder p, List<ProductionOrderRepository.HistoryEntry> history,
                                          WarehouseCapacityService.ProductionOrderCapacity capacity,
                                          ProductionOrderLabels.Labels labels) {
            com.stockflow.customer.api.CustomerLabel customer = labels.customer(p.customerId());
            com.stockflow.warehouse.api.WarehouseView warehouse = labels.warehouse(p.receivingWarehouseId());
            return new ProductionOrderResponse(p.id(), p.code(), p.salesOrderId(), p.deliveryBatchId(),
                    p.customerId(), p.producerId(), p.receivingWarehouseId(), p.needByDate(), p.status(),
                    p.sampleApprovalRequired(), p.sampleRequiredByDefault(), p.sampleWaiverReason(),
                    p.note(), p.drawnUpBy(), p.submittedAt(), p.approvedBy(), p.approvedAt(),
                    p.returnedReason(), p.sentAt(), p.sentBy(), p.productionStartedAt(), p.expectedCompletionDate(),
                    p.acceptanceNote(), p.cancelledAt(), p.cancellationReason(), p.closedAt(), p.closingReason(),
                    p.kind(), p.replacement().map(ProductionOrder.Replacement::replacesId).orElse(null),
                    p.replacement().map(r -> r.writeOffs().stream().map(ProductionOrder.WriteOff::adjustmentId)
                            .toList()).orElse(List.of()), true, capacity,
                    p.lines().stream().map(l -> new LineResponse(l.id(), l.orderLineId(), l.sku(), l.cartons(),
                            l.unitsPerCarton(), l.totalUnits(), l.designSnapshotId(), l.printFileChecksum(),
                            l.packingRequirements(), l.receivedCartons())).toList(),
                    history == null ? null : history.stream().map(h -> new HistoryResponse(h.from(), h.to(),
                            h.reason(), h.by(), h.at(), labels.person(h.by()))).toList(),
                    p.version(), labels.orderNumber(p.salesOrderId()), customer == null ? null : customer.name(),
                    customer == null ? null : customer.code(), labels.producer(p.producerId()),
                    warehouse == null ? null : warehouse.prefix(), warehouse == null ? null : warehouse.name(),
                    labels.person(p.drawnUpBy()), labels.person(p.approvedBy()), labels.person(p.sentBy()));
        }
    }

    private final ProductionOrderService productionOrders;
    private final WarehouseCapacityService capacity;
    private final ProductionOrderLabels labels;

    ProductionOrderController(ProductionOrderService productionOrders, WarehouseCapacityService capacity,
                              ProductionOrderLabels labels) {
        this.productionOrders = productionOrders;
        this.capacity = capacity;
        this.labels = labels;
    }

    /**
     * A production order as answered, with its warehouse's capacity read after the change committed (BR-25), and the
     * labels of what it refers to.
     */
    private ProductionOrderResponse respond(ProductionOrder p, List<ProductionOrderRepository.HistoryEntry> history) {
        return ProductionOrderResponse.of(p, history, capacity.ofProductionOrder(p), labels.of(List.of(p), history));
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
        PageResponse<ProductionOrder> found = productionOrders.list(warehouseId, status, producerId, salesOrderId, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort);
        // One board per warehouse on the page, not one per production order.
        Map<UUID, WarehouseCapacityService.ProductionOrderCapacity> capacities = capacity.ofProductionOrders(
                found.items());
        // And one read per kind of label for the page.
        ProductionOrderLabels.Labels names = labels.of(found.items(), null);
        return ApiResponse.ok(found.map(p -> ProductionOrderResponse.of(p, null, capacities.get(p.id()), names)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "One production order, with its lines and history")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> one(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        var detail = productionOrders.detail(id);
        return ApiResponse.ok(respond(detail.order(), detail.history()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Draw up a production order for what a delivery batch of a confirmed order of a contract "
            + "lacks at its shipping warehouse (Production Coordinator). 409 PRODUCTION_ORDER_NOTHING_SHORT")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> drawUp(@Valid @RequestBody DrawUpRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(respond(productionOrders.drawUp(request.salesOrderId(),
                request.deliveryBatchSeq(), request.producerId(), user.userId()), null));
    }

    @PostMapping("/{id}/replacements")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Draw up a replacement production order for cartons of this one written off as damaged after "
            + "they were received (Production Coordinator; 17 §4.13, BR-31): for the earliest delivery batch of its "
            + "sales order still short of them, never more cartons than were written off. 409 "
            + "PRODUCTION_ORDER_WRITE_OFF_INVALID, PRODUCTION_ORDER_WRITE_OFF_MADE_UP, PRODUCTION_ORDER_NOTHING_SHORT "
            + "(the cartons were surplus), PRODUCTION_ORDER_NOT_ALLOWED (the order cancelled, closed or on hold)")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.CREATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> drawUpReplacement(@PathVariable UUID id,
                                                                  @Valid @RequestBody DrawUpReplacementRequest request,
                                                                  @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(respond(productionOrders.drawUpReplacement(id, request.adjustmentIds(),
                request.producerId(), user.userId()), null));
    }

    @GetMapping("/{id}/write-offs")
    @Operation(summary = "The posted damaged write-offs of this production order's cartons, oldest first, each with "
            + "the live replacement production order making it up (17 §4.13)")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<List<WriteOffResponse>> writeOffs(@PathVariable UUID id, @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(productionOrders.writeOffs(id).stream().map(made -> new WriteOffResponse(
                made.writeOff().adjustmentId(), made.writeOff().adjustmentNumber(), made.writeOff().sku(),
                made.writeOff().stockPackageId(), made.writeOff().lpn(), made.writeOff().postedAt(), made.madeUpBy()))
                .toList());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a draft production order (producer, need-by date, note, sample flag, lines)")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> edit(@PathVariable UUID id, @Valid @RequestBody EditRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Map<UUID, ProductionOrder.LineEdit> lines = new LinkedHashMap<>();
        if (request.lines() != null) {
            request.lines().forEach(line -> lines.put(line.orderLineId(),
                    new ProductionOrder.LineEdit(line.cartons(), line.packingRequirements())));
        }
        return ApiResponse.ok(respond(productionOrders.edit(id, request.version(),
                new ProductionOrder.Draft(request.producerId(), request.needByDate(),
                        request.note(), request.sampleApprovalRequired(), request.sampleWaiverReason(), lines)), null));
    }

    @PostMapping("/{id}/sending")
    @Operation(summary = "Send a draft production order to its producer (Production Coordinator; no approval step - "
            + "the one who drew it up may send it). The print files are checked first: 409 PRINT_FILE_MISMATCH")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> send(@PathVariable UUID id, @Valid @RequestBody VersionRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(respond(productionOrders.send(id, request.version(), user.userId()), null));
    }

    @PostMapping("/{id}/acceptance")
    @Operation(summary = "Record that the producer accepted a SENT production order, and its expected completion day "
            + "(Production Coordinator)")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> accept(@PathVariable UUID id, @Valid @RequestBody AcceptRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(respond(productionOrders.accept(id, request.version(),
                request.expectedCompletionDate(), request.note(), user.userId()), null));
    }

    @PutMapping("/{id}/expected-completion-date")
    @Operation(summary = "Record the producer's new expected completion day, from acceptance until the goods are in")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> expectCompletion(@PathVariable UUID id,
                                                                 @Valid @RequestBody ExpectedCompletionRequest request,
                                                                 @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(respond(productionOrders.expectCompletion(id, request.version(),
                request.expectedCompletionDate()), null));
    }

    @PostMapping("/{id}/production-start")
    @Operation(summary = "Record that the producer started mass printing an ACCEPTED production order with no print "
            + "sample to approve. 409 PRODUCTION_ORDER_SAMPLE_REQUIRED: the customer's approval starts it")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> startProduction(@PathVariable UUID id,
                                                                @Valid @RequestBody StartProductionRequest request,
                                                                @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(respond(productionOrders.startProduction(id, request.version(),
                request.expectedCompletionDate(), user.userId()), null));
    }

    @PostMapping("/{id}/cancellation")
    @Operation(summary = "Cancel a production order before mass printing, with a reason")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(respond(productionOrders.cancel(id, request.version(),
                request.reason(), user.userId()), null));
    }

    @PostMapping("/{id}/closing")
    @Operation(summary = "Close a production order after mass printing started - short, or before anything came - "
            + "with a reason")
    @RequiresPermission(resource = OrderResources.PRODUCTION_ORDERS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ProductionOrderResponse> close(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(respond(productionOrders.close(id, request.version(),
                request.reason(), user.userId()), null));
    }
}
