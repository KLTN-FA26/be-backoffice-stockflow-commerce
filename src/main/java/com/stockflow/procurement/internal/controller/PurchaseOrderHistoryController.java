package com.stockflow.procurement.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderEventResponse;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderRevisionDetailResponse;
import com.stockflow.procurement.internal.controller.dto.PurchaseOrderRevisionResponse;
import com.stockflow.procurement.internal.controller.dto.RevisionChangeResponse;
import com.stockflow.procurement.internal.service.PurchaseOrderHistory;
import com.stockflow.procurement.internal.service.PurchaseOrderHistory.Revision;

import io.swagger.v3.oas.annotations.Operation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * SCRUM-117, design section 9: what an order's revisions, decisions and timeline say. Reads only, under the same
 * permission and warehouse scope as {@code GET /purchase-orders/{id}}.
 */
@RestController
@RequestMapping("/api/v1/purchase-orders")
class PurchaseOrderHistoryController {

    private final PurchaseOrderHistory history;

    PurchaseOrderHistoryController(PurchaseOrderHistory history) {
        this.history = history;
    }

    @GetMapping("/{purchaseOrderId}/revisions")
    @Operation(summary = "List the revisions of a purchase order, newest first, with how each was decided")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<List<PurchaseOrderRevisionResponse>> revisions(@PathVariable UUID purchaseOrderId) {
        return ApiResponse.ok(
                history.revisions(purchaseOrderId).stream()
                        .map(PurchaseOrderHistoryController::toResponse)
                        .toList());
    }

    @GetMapping("/{purchaseOrderId}/revisions/{revisionNo}")
    @Operation(
            summary = "One revision with its lines and what it changes",
            description =
                    "Changes are against the closest earlier approved revision, or the one just before it when"
                        + " none was approved.")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<PurchaseOrderRevisionDetailResponse> revision(
            @PathVariable UUID purchaseOrderId, @PathVariable long revisionNo) {
        var detail = history.revision(purchaseOrderId, revisionNo);
        return ApiResponse.ok(
                new PurchaseOrderRevisionDetailResponse(
                        toResponse(detail.revision()),
                        detail.currency(),
                        detail.expectedDate(),
                        detail.note(),
                        detail.subtotal(),
                        detail.taxTotal(),
                        detail.lines().stream()
                                .map(
                                        l ->
                                                new PurchaseOrderRevisionDetailResponse.Line(
                                                        l.lineNo(),
                                                        l.sku(),
                                                        l.description(),
                                                        l.unit(),
                                                        l.quantity(),
                                                        l.unitPrice(),
                                                        l.taxRate(),
                                                        l.lineSubtotal(),
                                                        l.lineTax(),
                                                        l.lineTotal()))
                                .toList(),
                        detail.comparedWithRevisionNo(),
                        detail.changes().stream()
                                .map(
                                        c ->
                                                new RevisionChangeResponse(
                                                        c.scope(),
                                                        c.lineNo(),
                                                        c.sku(),
                                                        c.type(),
                                                        c.field(),
                                                        c.before(),
                                                        c.after()))
                                .toList()));
    }

    @GetMapping("/{purchaseOrderId}/timeline")
    @Operation(summary = "A purchase order's timeline, newest first; size up to 100")
    @RequiresPermission(resource = PurchaseOrderResources.PURCHASE_ORDERS, action = Action.READ)
    public ApiResponse<PageResponse<PurchaseOrderEventResponse>> timeline(
            @PathVariable UUID purchaseOrderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(
                history.timeline(purchaseOrderId, page, size)
                        .map(
                                e ->
                                        new PurchaseOrderEventResponse(
                                                e.id(),
                                                e.action(),
                                                e.fromStatus(),
                                                e.toStatus(),
                                                e.actorId(),
                                                e.actorName(),
                                                e.reason(),
                                                e.revisionNo(),
                                                e.payload(),
                                                e.occurredAt())));
    }

    private static PurchaseOrderRevisionResponse toResponse(Revision r) {
        return new PurchaseOrderRevisionResponse(
                r.revisionNo(),
                r.purpose(),
                r.state(),
                r.changedBy(),
                r.changedByName(),
                r.changedAt(),
                r.reason(),
                r.totalAmount(),
                r.decision(),
                r.decidedBy(),
                r.decidedByName(),
                r.decidedAt(),
                r.decisionReason(),
                r.autoApprovalLimit());
    }
}
