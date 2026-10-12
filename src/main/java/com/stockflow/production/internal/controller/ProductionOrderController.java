package com.stockflow.production.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.production.internal.controller.dto.CancelProductionRequest;
import com.stockflow.production.internal.controller.dto.HoldRequest;
import com.stockflow.production.internal.controller.dto.OutputRequest;
import com.stockflow.production.internal.controller.dto.ScrapLineRequest;
import com.stockflow.production.internal.controller.dto.StopProductionRequest;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.production.internal.service.PrintRuns;
import com.stockflow.production.internal.controller.dto.PrepressRequest;
import com.stockflow.production.internal.controller.dto.ProductionOrderResponse;
import com.stockflow.production.internal.controller.dto.ProductionOrderRowResponse;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.ProductionWorkflow;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Production orders (LSX): the list and detail (SCRUM-395/406) and the print shop's verbs (SCRUM-396..401) —
 * prepress, hold and resume, printing and its output, cancellation and stop. There is no create endpoint: a
 * SAMPLE order is born of a submitted sample round and an ORDER order of a release. Issuing blanks and
 * recording QC are resources of their own ({@link ProductionMaterialIssueController},
 * {@link ProductionQcController}), so the warehouse and QC hold exactly their step.
 */
@RestController
@RequestMapping("/api/v1/production/orders")
@Tag(name = "Production orders", description = "LSX: sample and order production in the print shop")
@PermissionResource(
        code = ProductionResources.PRODUCTION_ORDERS,
        group = "Production",
        label = "Production orders",
        route = "/production/orders",
        apiPath = "/api/v1/production/orders",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE, Action.EXPORT})
class ProductionOrderController {

    private final ProductionOrders orders;
    private final ProductionWorkflow workflow;
    private final PrintRuns runs;
    private final ProductionWebMapper mapper;

    ProductionOrderController(ProductionOrders orders, ProductionWorkflow workflow, PrintRuns runs,
                              ProductionWebMapper mapper) {
        this.orders = orders;
        this.workflow = workflow;
        this.runs = runs;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Production orders, filtered by type, status, warehouse and due date; newest first")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<ProductionOrderRowResponse>> list(
            @RequestParam(name = "type", required = false) List<ProductionOrder.Type> types,
            @RequestParam(name = "status", required = false) List<ProductionOrder.Status> statuses,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
            @RequestParam(required = false) UUID sampleRequestId,
            @RequestParam(required = false) UUID salesOrderId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(orders.list(user, new ProductionOrders.Criteria(types, statuses, warehouseId, dueFrom,
                        dueTo, sampleRequestId, salesOrderId, search, page,
                        size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort))
                .map(mapper::toResponse));
    }

    @GetMapping("/{productionOrderId}")
    @Operation(summary = "One production order with its status history")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.READ)
    public ApiResponse<ProductionOrderResponse> get(@PathVariable UUID productionOrderId,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(orders.get(user, productionOrderId)));
    }

    @GetMapping("/{productionOrderId}/print-file")
    @Operation(summary = "A short-lived link to the PRINT_READY file of the locked design, verified on the way")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.READ)
    public ApiResponse<ProductionWorkflow.PrintFile> printFile(@PathVariable UUID productionOrderId,
                                                               @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(workflow.printFile(user, productionOrderId));
    }

    @PostMapping("/{productionOrderId}/prepress")
    @Operation(summary = "Prepress verdict: PASS -> READY (checksum re-verified, BR-PRD-02); FAIL -> ON_HOLD FILE_REJECTED, Sales notified")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.UPDATE)
    public ApiResponse<ProductionOrderResponse> prepress(@PathVariable UUID productionOrderId,
                                                         @Valid @RequestBody PrepressRequest request,
                                                         @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(workflow.prepress(user, productionOrderId,
                request.decision() == PrepressRequest.Decision.PASS, request.note())));
    }

    @PostMapping("/{productionOrderId}/hold")
    @Operation(summary = "Stop work with a reason (bad file, blanks short, machine down, waiting for the customer)")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.UPDATE)
    public ApiResponse<ProductionOrderResponse> hold(@PathVariable UUID productionOrderId,
                                                     @Valid @RequestBody HoldRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(workflow.hold(user, productionOrderId, request.reason(), request.note())));
    }

    @PostMapping("/{productionOrderId}/printing")
    @Operation(summary = "Start printing: MATERIAL_ISSUED -> PRINTING (a sample's round becomes IN_PRODUCTION)")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.UPDATE)
    public ApiResponse<ProductionOrderResponse> startPrinting(@PathVariable UUID productionOrderId,
                                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(runs.startPrinting(user, productionOrderId)));
    }

    @PostMapping("/{productionOrderId}/output")
    @Operation(summary = "Record a run: printed and spoilt with reasons (BR-PRD-04); PRINTING -> QC; spoilt units out of stock use at once, write-off asked for")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.UPDATE)
    public ApiResponse<ProductionOrderResponse> recordOutput(@PathVariable UUID productionOrderId,
                                                             @Valid @RequestBody OutputRequest request,
                                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(runs.recordOutput(user, productionOrderId, request.printed(),
                scrapLines(request.scrap()))));
    }

    @GetMapping("/{productionOrderId}/scrap")
    @Operation(summary = "What was spoilt, with the write-off asked for each line")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.READ)
    public ApiResponse<List<ProductionRecords.Scrap>> scrap(@PathVariable UUID productionOrderId,
                                                            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(runs.scrapOf(user, productionOrderId));
    }

    /** Cancelling is the Warehouse Manager's (03 §16): APPROVE. */
    @PostMapping("/{productionOrderId}/cancellation")
    @Operation(summary = "Cancel before printing; issued blanks go back to stock (SCRUM-401)")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.APPROVE)
    public ApiResponse<ProductionOrderResponse> cancel(@PathVariable UUID productionOrderId,
                                                       @Valid @RequestBody CancelProductionRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(runs.cancel(user, productionOrderId, request.reason())));
    }

    @PostMapping("/{productionOrderId}/stop")
    @Operation(summary = "Stop a job on the press (Q2): output so far kept and written off, unprinted blanks back")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.APPROVE)
    public ApiResponse<ProductionOrderResponse> stop(@PathVariable UUID productionOrderId,
                                                     @Valid @RequestBody StopProductionRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(runs.stop(user, productionOrderId, request.printed(),
                scrapLines(request.scrap()), request.reason())));
    }

    static List<PrintRuns.ScrapLine> scrapLines(List<ScrapLineRequest> lines) {
        return lines == null ? List.of() : lines.stream()
                .map(line -> new PrintRuns.ScrapLine(line.reason(), line.quantity(), line.note())).toList();
    }

    /** The Warehouse Manager resolves a hold (Jira 401): APPROVE, which the print shop does not hold. */
    @PostMapping("/{productionOrderId}/resume")
    @Operation(summary = "Resume a held order to the state it was in")
    @RequiresPermission(resource = ProductionResources.PRODUCTION_ORDERS, action = Action.APPROVE)
    public ApiResponse<ProductionOrderResponse> resume(@PathVariable UUID productionOrderId,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(workflow.resume(user, productionOrderId)));
    }
}
