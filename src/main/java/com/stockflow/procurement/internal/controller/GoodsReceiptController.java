package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.internal.controller.dto.CreateProductionGoodsReceiptRequest;
import com.stockflow.procurement.internal.controller.dto.GoodsReceiptResponse;
import com.stockflow.procurement.internal.controller.dto.GoodsReceiptRowResponse;
import com.stockflow.procurement.internal.controller.dto.ReceiptLinesRequest;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.GoodsReceiptType;
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
 * Goods receipts against production orders (4A-5, kltn-docs 03 §4.1): counting and confirming are warehouse work
 * ({@code procurement-goods-receipts}); there is no quality check at the warehouse (kltn-docs 03 BR-07), so a confirmed
 * receipt goes straight to putaway. Nothing over what a production order orders is received (BR-02), so nothing waits
 * for approval.
 *
 * <p>Purchasing is gone (4A-8, kltn-docs 00 of 04/11): the receipts against a purchase order or none
 * ({@code POST /goods-receipts}, {@code /without-po}), the manager's over-tolerance decisions ({@code /approval},
 * {@code /rejection}, {@code /excess-rejection}) and the PO reference of a blind receipt went with it, and so did the
 * {@code APPROVE} action of this resource. A historical purchase receipt ({@code receiptType = PURCHASE}) is still
 * listed, read and - while it has counted nothing in - cancelled.</p>
 */
@RestController
@RequestMapping("/api/v1/goods-receipts")
@Tag(name = "Goods receipts", description = "Receiving against production orders, then putaway")
@PermissionResource(
        code = GoodsReceiptResources.GOODS_RECEIPTS,
        group = "Procurement",
        label = "Goods receipts",
        route = "/procurement/goods-receipts",
        apiPath = "/api/v1/goods-receipts",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class GoodsReceiptController {

    private final GoodsReceipts receipts;
    private final GoodsReceiptWebMapper mapper;

    GoodsReceiptController(GoodsReceipts receipts, GoodsReceiptWebMapper mapper) {
        this.receipts = receipts;
        this.mapper = mapper;
    }

    @PostMapping("/production")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a receipt (DRAFT) against a production order (kltn-docs 03 §4.1, BR-01, BR-15)",
            description = """
                    The production order is IN_PRODUCTION or PARTIALLY_RECEIVED, or SENT / ACCEPTED when no print \
                    sample is to be approved first (`PRODUCTION_ORDER_NOT_RECEIVABLE`, 409 otherwise); the receipt is \
                    at the warehouse it is received at, which the caller is assigned to. While the draft is open the \
                    production order is neither cancelled nor closed. Lines: `PUT /{id}/lines`, each naming a \
                    `productionOrderLineId` and listing its cartons; the lot is the production order's code.""")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.CREATE)
    public ApiResponse<GoodsReceiptResponse> createForProductionOrder(
            @Valid @RequestBody CreateProductionGoodsReceiptRequest request, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.createForProductionOrder(
                new GoodsReceipts.CreateForProductionOrder(request.productionOrderId(), request.deliveryNote(),
                        request.note()), user.userId())));
    }

    @GetMapping
    @Operation(summary = "Goods receipts, newest first",
            description = "`receiptType`: PRODUCTION, or PURCHASE for the historical receipts of the purchasing there was.")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.READ)
    public ApiResponse<PageResponse<GoodsReceiptRowResponse>> list(
            @RequestParam(name = "status", required = false) List<GoodsReceiptStatus> statuses,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant receivedTo,
            @RequestParam(required = false) UUID productionOrderId,
            @RequestParam(required = false) GoodsReceiptType receiptType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(receipts.list(statuses, warehouseId, search, receivedFrom, receivedTo, productionOrderId,
                receiptType, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort).map(mapper::toResponse));
    }

    @GetMapping("/{receiptId}")
    @Operation(summary = "One receipt with its lines and cartons")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.READ)
    public ApiResponse<GoodsReceiptResponse> get(@PathVariable UUID receiptId) {
        return ApiResponse.ok(mapper.toResponse(receipts.get(receiptId)));
    }

    @PutMapping("/{receiptId}/lines")
    @Operation(summary = "Replace the count of a DRAFT receipt against a production order (BR-02, BR-12, BR-13)",
            description = """
                    The whole count each time, one line per production order line: its `productionOrderLineId`, its \
                    RECEIVING `locationCode` and at least one carton in `packages`. An entry with an `lpn` recounts \
                    that carton, one without labels a new one, a carton of the line left out is voided. Every carton \
                    holds the line's standard count, so `quantity` (the line's and a carton's) may be left out. No \
                    `lotNumber` or `expiryDate`: the lot is the production order's code. `unitOfMeasure` names the \
                    SKU's carton of that count when it has several. The response gives each line's `packages` with \
                    their LPNs, to print the labels from.

                    Every line is checked before anything is refused; `fieldErrors` names each field at fault. Codes: \
                    `RECEIPT_LINE_NOT_ON_PRODUCTION_ORDER` (409), `RECEIPT_CARTON_NOT_STANDARD` (422: a carton of \
                    another count, BR-13), `RECEIPT_EXCEEDS_PRODUCTION_ORDER` (409: more cartons than the line orders \
                    on all its receipts not cancelled, drafts included - BR-02), `PRODUCTION_ORDER_NOT_RECEIVABLE` \
                    (409), `RECEIPT_LINE_UNIT_INVALID` (422), `UOM_CONVERSION_NOT_FOUND` (404), \
                    `RECEIPT_LINE_HAS_PACKAGES` (409: a line that has had cartons left out, or a carton kept in \
                    another unit), `STOCK_PACKAGE_NOT_FOUND` (404), `VALIDATION_FAILED`. A historical purchase \
                    receipt answers `INVALID_RECEIPT_TRANSITION` (409): it is only cancelled.""")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> replaceLines(@PathVariable UUID receiptId,
                                                          @Valid @RequestBody ReceiptLinesRequest request) {
        // A quantity left out is 0: the count is taken from the cartons.
        return ApiResponse.ok(mapper.toResponse(receipts.replaceLines(receiptId, request.lines().stream()
                .map(l -> new GoodsReceipts.NewLine(l.productionOrderLineId(), l.quantity() == null ? 0 : l.quantity(),
                        l.lotNumber(), l.expiryDate(), l.locationCode(), l.note(), l.unitOfMeasure(),
                        l.packages() == null ? List.of() : l.packages().stream()
                                .map(p -> new GoodsReceipts.NewPackage(p.lpn(), p.quantity() == null ? 0 : p.quantity()))
                                .toList(), l.sku()))
                .toList())));
    }

    @PostMapping("/{receiptId}/confirmation")
    @Operation(summary = "Confirm the count: the cartons become INBOUND stock, the production order moves on, putaway follows",
            description = """
                    The cartons become INBOUND stock in the production order's lot and the production order moves \
                    on - to IN_PRODUCTION first when it was still SENT / ACCEPTED (BR-15), then PARTIALLY_RECEIVED, \
                    or RECEIVED once every line is in. BR-02 is checked again; there is no approval step. A \
                    historical purchase receipt answers `INVALID_RECEIPT_TRANSITION` (409).""")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> confirm(@PathVariable UUID receiptId,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.confirm(receiptId, user.userId())));
    }

    @PostMapping("/{receiptId}/cancellation")
    @Operation(summary = "Cancel a receipt that has counted nothing in; a confirmed one is corrected by a stock adjustment (BR-05)",
            description = """
                    A DRAFT. The cartons labelled while counting are voided; a production order lets its draft go.""")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> cancel(@PathVariable UUID receiptId) {
        return ApiResponse.ok(mapper.toResponse(receipts.cancel(receiptId)));
    }
}
