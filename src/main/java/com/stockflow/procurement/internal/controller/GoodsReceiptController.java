package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.internal.controller.dto.CreateGoodsReceiptRequest;
import com.stockflow.procurement.internal.controller.dto.GoodsReceiptResponse;
import com.stockflow.procurement.internal.controller.dto.GoodsReceiptRowResponse;
import com.stockflow.procurement.internal.controller.dto.MoveToQcRequest;
import com.stockflow.procurement.internal.controller.dto.QcDecisionRequest;
import com.stockflow.procurement.internal.controller.dto.ReceiptLinesRequest;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.util.List;
import java.util.UUID;

/**
 * Goods receipts against purchase orders (SCRUM-435, docs 03). Counting, confirming and moving goods
 * to QC are warehouse work ({@code procurement-goods-receipts}); deciding QC is the QC staff's
 * ({@code procurement-qc-tasks:APPROVE}).
 *
 * <p>The only way goods are received: the old {@code POST /purchase-orders/{id}/receipts}, which only
 * advanced PO-line totals, went with the old purchasing tables (contract C4).</p>
 */
@RestController
@RequestMapping("/api/v1/goods-receipts")
@Tag(name = "Goods receipts", description = "Receiving against purchase orders, with inbound QC")
@PermissionResource(
        code = PurchaseOrderResources.GOODS_RECEIPTS,
        group = "Procurement",
        label = "Goods receipts",
        route = "/procurement/goods-receipts",
        apiPath = "/api/v1/goods-receipts",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class GoodsReceiptController {

    private final GoodsReceipts receipts;
    private final GoodsReceiptWebMapper mapper;

    GoodsReceiptController(GoodsReceipts receipts, GoodsReceiptWebMapper mapper) {
        this.receipts = receipts;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a receipt (DRAFT) against a CONFIRMED or PARTIALLY_RECEIVED purchase order")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.CREATE)
    public ApiResponse<GoodsReceiptResponse> create(@Valid @RequestBody CreateGoodsReceiptRequest request,
                                                    @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.create(
                new GoodsReceipts.Create(request.purchaseOrderId(), request.deliveryNote(), request.note()),
                user.userId())));
    }

    @GetMapping
    @Operation(summary = "Goods receipts, newest first")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.READ)
    public ApiResponse<PageResponse<GoodsReceiptRowResponse>> list(
            @RequestParam(name = "status", required = false) List<GoodsReceiptStatus> statuses,
            @RequestParam(required = false) UUID purchaseOrderId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(receipts.list(statuses, purchaseOrderId, warehouseId, search, receivedFrom, receivedTo,
                page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(mapper::toResponse));
    }

    @GetMapping("/{receiptId}")
    @Operation(summary = "One receipt with its lines and QC results")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.READ)
    public ApiResponse<GoodsReceiptResponse> get(@PathVariable UUID receiptId) {
        return ApiResponse.ok(mapper.toResponse(receipts.get(receiptId)));
    }

    @PutMapping("/{receiptId}/lines")
    @Operation(summary = "Replace the count of a DRAFT receipt (tolerance BR-02, lot/expiry BR-03, RECEIVING areas)")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> replaceLines(@PathVariable UUID receiptId,
                                                          @Valid @RequestBody ReceiptLinesRequest request) {
        return ApiResponse.ok(mapper.toResponse(receipts.replaceLines(receiptId, request.lines().stream()
                .map(l -> new GoodsReceipts.NewLine(l.purchaseOrderLineId(), l.quantity(), l.lotNumber(),
                        l.expiryDate(), l.locationCode(), l.note()))
                .toList())));
    }

    @PostMapping("/{receiptId}/confirmation")
    @Operation(summary = "Confirm the count: goods become INBOUND stock, the PO progresses, receipt goes to QC or putaway")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> confirm(@PathVariable UUID receiptId,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.confirm(receiptId, user.userId())));
    }

    @PostMapping("/{receiptId}/cancellation")
    @Operation(summary = "Cancel a DRAFT receipt; a confirmed one is corrected by a stock adjustment (BR-05)")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> cancel(@PathVariable UUID receiptId) {
        return ApiResponse.ok(mapper.toResponse(receipts.cancel(receiptId)));
    }

    @PostMapping("/{receiptId}/lines/{lineId}/qc-transfer")
    @Operation(summary = "The goods of a QC-required line were moved to a QUALITY_CONTROL area")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> moveToQc(@PathVariable UUID receiptId, @PathVariable UUID lineId,
                                                      @Valid @RequestBody MoveToQcRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.moveToQc(receiptId, lineId, request.qcLocationCode(),
                user.userId())));
    }

    @PostMapping("/{receiptId}/lines/{lineId}/inspection")
    @Operation(summary = "Record the QC decision on a line: accepted, quarantined, rejected (must add up, BR-08)")
    @RequiresPermission(resource = PurchaseOrderResources.QC_TASKS, action = Action.APPROVE)
    public ApiResponse<GoodsReceiptResponse> inspect(@PathVariable UUID receiptId, @PathVariable UUID lineId,
                                                     @Valid @RequestBody QcDecisionRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.inspect(receiptId, lineId, new GoodsReceipts.Decision(
                request.accepted(), part(request.quarantined()), part(request.rejected())), user.userId())));
    }

    private static GoodsReceipts.Part part(QcDecisionRequest.Part part) {
        return part == null ? null : new GoodsReceipts.Part(part.quantity(), part.locationCode(), part.reason());
    }
}
