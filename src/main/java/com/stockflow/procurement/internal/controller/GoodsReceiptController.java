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
import com.stockflow.procurement.internal.controller.dto.ReceiptDecisionRequest;
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
 * ({@code procurement-qc-tasks:APPROVE}). A count beyond a PO line's over-receipt tolerance waits for the warehouse
 * manager, who approves it or rejects its excess ({@code procurement-goods-receipts:APPROVE}, BR-RCP-001).
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
    @Operation(summary = "Replace the count of a DRAFT receipt (tolerance BR-02, lot/expiry BR-03, RECEIVING areas)",
            description = """
                    The whole count each time. `quantity` is a line's total in base units. A line may carry \
                    `packages`, its labelled cartons (SCRUM-473): an entry with an `lpn` recounts that carton, one \
                    without labels a new one, a carton of the line left out is voided; the cartons hold at most \
                    `quantity` together and the rest is loose. With `packages`, `unitOfMeasure` is the active \
                    packing unit they are in; without, it is absent or the item's base unit. The response gives \
                    each line's `packages` with their LPNs, to print the labels from, and its `looseQuantity`.

                    A line is its PO line and lot. A line that has ever had labelled cartons keeps both: sent under \
                    another lot or PO line it would be a new line with the old one left out, which is refused. To \
                    correct the lot or PO line of such a line, cancel the receipt and count it again.

                    Every line is checked before anything is refused; `fieldErrors` names each field at fault \
                    (`lines[i].unitOfMeasure`, `lines[i].packages`, ...). Codes: `RECEIPT_LINE_UNIT_INVALID` (422: \
                    cartons in the base unit or a retired one, a packing unit without cartons), \
                    `UOM_CONVERSION_NOT_FOUND` (404: not a unit of the item), `RECEIPT_PACKAGES_EXCEED_LINE` (422), \
                    `RECEIPT_PACKAGES_NOT_SUPPORTED` (409: a subcontract receipt), `RECEIPT_LINE_HAS_PACKAGES` (409: \
                    the unit changed while a carton of the old one is kept, or a line that has ever had cartons - \
                    voided ones too - left out), \
                    `STOCK_PACKAGE_NOT_FOUND` (404: an LPN not of this line), `INVALID_STOCK_PACKAGE_TRANSITION` \
                    (409: a voided carton named again), `VALIDATION_FAILED` (cartons without a unit, an LPN listed \
                    twice, a carton under 1 unit).""")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> replaceLines(@PathVariable UUID receiptId,
                                                          @Valid @RequestBody ReceiptLinesRequest request) {
        return ApiResponse.ok(mapper.toResponse(receipts.replaceLines(receiptId, request.lines().stream()
                .map(l -> new GoodsReceipts.NewLine(l.purchaseOrderLineId(), l.quantity(), l.lotNumber(),
                        l.expiryDate(), l.locationCode(), l.note(), l.unitOfMeasure(),
                        l.packages() == null ? List.of() : l.packages().stream()
                                .map(p -> new GoodsReceipts.NewPackage(p.lpn(), p.quantity())).toList()))
                .toList())));
    }

    @PostMapping("/{receiptId}/confirmation")
    @Operation(summary = "Confirm the count: goods become INBOUND stock, the PO progresses, receipt goes to QC or putaway",
            description = """
                    A count beyond a PO line's over-receipt tolerance (BR-RCP-001) is not refused and not posted: the \
                    receipt becomes `PENDING_APPROVAL`, nothing is counted in, and the warehouse manager decides \
                    (`/approval`, `/excess-rejection`). The draft's `warnings` say which lines (`OVER_TOLERANCE`) \
                    before it is confirmed.""")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> confirm(@PathVariable UUID receiptId,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.confirm(receiptId, user.userId())));
    }

    @PostMapping("/{receiptId}/approval")
    @Operation(summary = "Approve a receipt waiting in PENDING_APPROVAL: its whole count, beyond the tolerance, is posted",
            description = """
                    BR-RCP-001. The warehouse manager accepts the count as it stands; the goods are counted in exactly \
                    as on a normal confirmation, by and in the name of the approver. `reason` is required. Four \
                    eyes: the approver is neither whoever started the receipt nor whoever sent it for approval \
                    (`RECEIPT_SELF_APPROVAL`, 409). Other refusals: `INVALID_RECEIPT_TRANSITION` (409, not waiting), \
                    `PURCHASE_ORDER_NOT_RECEIVABLE` (409).""")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.APPROVE)
    public ApiResponse<GoodsReceiptResponse> approve(@PathVariable UUID receiptId,
                                                     @Valid @RequestBody ReceiptDecisionRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.approve(receiptId, user.userId(), request.reason())));
    }

    @PostMapping("/{receiptId}/excess-rejection")
    @Operation(summary = "Reject the excess of a receipt waiting in PENDING_APPROVAL: only up to the tolerance is posted",
            description = """
                    BR-RCP-001. Each PO line keeps only what its limit still allows (ordered x (1 + tolerance), less \
                    what posted receipts hold); the excess comes off that PO line's receipt lines from the last one \
                    counted backwards, and the receipt is posted with the rest. A receipt with nothing left is \
                    cancelled. `reason` is required; same four-eyes rule as the approval. `RECEIPT_PACKAGES_EXCEED_LINE` \
                    / `RECEIPT_LINE_HAS_PACKAGES` (labelled cartons in the way of the cut): approve instead, or cancel \
                    the receipt and count it again.""")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.APPROVE)
    public ApiResponse<GoodsReceiptResponse> rejectExcess(@PathVariable UUID receiptId,
                                                          @Valid @RequestBody ReceiptDecisionRequest request,
                                                          @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.rejectExcess(receiptId, user.userId(), request.reason())));
    }

    @PostMapping("/{receiptId}/cancellation")
    @Operation(summary = "Cancel a DRAFT or PENDING_APPROVAL receipt; a confirmed one is corrected by a stock adjustment (BR-05)")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> cancel(@PathVariable UUID receiptId) {
        return ApiResponse.ok(mapper.toResponse(receipts.cancel(receiptId)));
    }

    @PostMapping("/{receiptId}/lines/{lineId}/qc-transfer")
    @Operation(summary = "The goods of a QC-required line were moved to a QUALITY_CONTROL area: its cartons whole,"
            + " its loose units")
    @RequiresPermission(resource = PurchaseOrderResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<GoodsReceiptResponse> moveToQc(@PathVariable UUID receiptId, @PathVariable UUID lineId,
                                                      @Valid @RequestBody MoveToQcRequest request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.moveToQc(receiptId, lineId, request.qcLocationCode(),
                user.userId())));
    }

    @PostMapping("/{receiptId}/lines/{lineId}/inspection")
    @Operation(summary = "Record the QC decision on a line: accepted, quarantined, rejected (must add up, BR-08)",
            description = "A quarantined or rejected part is its loose `quantity` plus the line's cartons it names in"
                    + " `packages` (LPNs), each going whole at what was counted into it (SCRUM-473). A named carton is"
                    + " one of this line's (STOCK_PACKAGE_NOT_FOUND), named once (VALIDATION_FAILED), in stock"
                    + " (INVALID_STOCK_PACKAGE_TRANSITION) at the line's QC area (STOCK_PACKAGE_WRONG_LOCATION)."
                    + " `accepted` is every unit no part takes; the loose parts may not exceed the line's"
                    + " `looseQuantity` (QC_QUANTITY_MISMATCH).")
    @RequiresPermission(resource = PurchaseOrderResources.QC_TASKS, action = Action.APPROVE)
    public ApiResponse<GoodsReceiptResponse> inspect(@PathVariable UUID receiptId, @PathVariable UUID lineId,
                                                     @Valid @RequestBody QcDecisionRequest request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(receipts.inspect(receiptId, lineId, new GoodsReceipts.Decision(
                request.accepted(), part(request.quarantined()), part(request.rejected())), user.userId())));
    }

    private static GoodsReceipts.Part part(QcDecisionRequest.Part part) {
        return part == null ? null
                : new GoodsReceipts.Part(part.quantity(), part.locationCode(), part.reason(), part.packages());
    }
}
