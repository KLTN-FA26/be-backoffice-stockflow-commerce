package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.internal.controller.dto.CreateReturnReceiptRequest;
import com.stockflow.procurement.internal.controller.dto.ScanReturnCartonRequest;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.service.ReturnReceiptService;
import com.stockflow.procurement.internal.service.ReturnReceiptService.ReturnReceiptView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Return receipts ("phiếu nhập Hàng hoàn"; team plan 4D-2; kltn-docs 03 §4.2, BR-14; 17 BR-18; 09 BR-06): goods that
 * came back, taken in by LPN straight into quarantine. Warehouse work, under the goods-receipt permissions
 * ({@code procurement-goods-receipts}) - a return receipt is a goods receipt of type RETURN (03 §1, §2: the warehouse
 * staff open, scan and confirm both kinds). Photos of the cartons' condition are the receipt's photos,
 * {@code /api/v1/goods-receipts/{receiptId}/photos}, as for any receipt.
 */
@RestController
@RequestMapping("/api/v1/goods-receipts/returns")
@Tag(name = "Return receipts", description = "Returned goods received by LPN into quarantine")
class ReturnReceiptController {

    private final ReturnReceiptService receipts;

    ReturnReceiptController(ReturnReceiptService receipts) {
        this.receipts = receipts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a return receipt (DRAFT) for a returned shipment or an approved return request",
            description = """
                    Exactly one of `shipmentId` (a shipment the carrier brought back: `RETURNED`) and \
                    `returnRequestId` (a return request approved and awaiting its goods: `AWAITING_RETURN`). The \
                    receipt is at the warehouse the goods left from, into `quarantineLocationCode`, a usable \
                    QUARANTINE area of it. Refusals: `VALIDATION_FAILED` (neither or both), `SHIPMENT_NOT_FOUND`, \
                    `RETURN_REQUEST_NOT_FOUND` (404), `SHIPMENT_NOT_RETURNED`, `RETURN_NOT_AWAITING_GOODS`, \
                    `RETURN_RECEIPT_ALREADY_OPEN` (409, one receipt not cancelled per shipment and per return \
                    request; `details.receiptId` names it), `LOCATION_NOT_FOUND`, `LOCATION_AREA_MISMATCH`, \
                    `LOCATION_NOT_USABLE`.""")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.CREATE)
    public ApiResponse<ReturnReceiptView> create(@Valid @RequestBody CreateReturnReceiptRequest request,
                                                 @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(receipts.create(new ReturnReceiptService.Create(request.shipmentId(),
                request.returnRequestId(), request.quarantineLocationCode(), request.note()), user.userId()));
    }

    @GetMapping
    @Operation(summary = "Return receipts, newest first")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.READ)
    public ApiResponse<PageResponse<ReturnReceiptService.ReturnReceiptRow>> list(
            @RequestParam(name = "status", required = false) List<GoodsReceiptStatus> statuses,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) UUID shipmentId,
            @RequestParam(required = false) UUID returnRequestId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(receipts.list(statuses, warehouseId, orderId, shipmentId, returnRequestId, search, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort));
    }

    @GetMapping("/{receiptId}")
    @Operation(summary = "One return receipt: its cartons, and the cartons its shipment or order carried",
            description = """
                    `expectedCartons` is what the source carried - the shipment's cartons, or those delivered on the \
                    return request's order - each `scanned` when it is on the receipt (03 §4.2 step 11). Fewer \
                    scanned than expected does not stop the confirmation.""")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.READ)
    public ApiResponse<ReturnReceiptView> get(@PathVariable UUID receiptId) {
        return ApiResponse.ok(receipts.get(receiptId));
    }

    @PostMapping("/{receiptId}/cartons")
    @Operation(summary = "Scan a carton by LPN onto a DRAFT return receipt",
            description = """
                    Only a carton that left on the receipt's shipment, or was delivered on the return request's \
                    order, and is not in the warehouse: `RETURN_CARTON_NOT_OF_SOURCE`, `RETURN_CARTON_IN_WAREHOUSE` \
                    (409). It keeps its LPN, SKU, count and lot. The same LPN again is the same carton: a \
                    `condition` sent replaces the one noted before. `INVALID_RECEIPT_TRANSITION` once the receipt \
                    is not a draft.""")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<ReturnReceiptView> scan(@PathVariable UUID receiptId,
                                               @Valid @RequestBody ScanReturnCartonRequest request,
                                               @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(receipts.scan(receiptId, request.lpn(), request.condition(), user.userId()));
    }

    @DeleteMapping("/{receiptId}/cartons/{lpn}")
    @Operation(summary = "Take a carton scanned by mistake off a DRAFT return receipt")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<ReturnReceiptView> removeCarton(@PathVariable UUID receiptId, @PathVariable String lpn) {
        return ApiResponse.ok(receipts.removeCarton(receiptId, lpn));
    }

    @PostMapping("/{receiptId}/confirmation")
    @Operation(summary = "Confirm: the cartons go back into stock in quarantine, and the receipt is CLOSED",
            description = """
                    Each carton comes back whole - CONSUMED to IN_STOCK at the receipt's quarantine area, its stock \
                    QUARANTINE (not available to promise), its lot kept - with one RECEIPT ledger line naming the \
                    receipt. No putaway task: a warehouse manager releases or writes off each carton afterwards. \
                    `ReturnedGoodsReceived` is published for the order and payment. Confirmed again, it answers as \
                    it is. `INVALID_RECEIPT_TRANSITION` (cancelled, or no carton), \
                    `INVALID_STOCK_PACKAGE_TRANSITION` (a carton came back on another receipt meanwhile).""")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<ReturnReceiptView> confirm(@PathVariable UUID receiptId, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(receipts.confirm(receiptId, user.userId()));
    }

    @PostMapping("/{receiptId}/cancellation")
    @Operation(summary = "Cancel a DRAFT return receipt; nothing was received (03 BR-05)")
    @RequiresPermission(resource = GoodsReceiptResources.GOODS_RECEIPTS, action = Action.UPDATE)
    public ApiResponse<ReturnReceiptView> cancel(@PathVariable UUID receiptId) {
        return ApiResponse.ok(receipts.cancel(receiptId));
    }
}
