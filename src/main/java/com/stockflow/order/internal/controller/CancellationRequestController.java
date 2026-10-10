package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.internal.controller.dto.ApproveCancellationRequest;
import com.stockflow.order.internal.controller.dto.CancellationRequestResponse;
import com.stockflow.order.internal.controller.dto.OrderResponse;
import com.stockflow.order.internal.controller.dto.RejectCancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import com.stockflow.order.internal.service.CancellationRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Customers' requests to cancel orders already released to production or the warehouse (SCRUM-460).
 * kltn-docs 17 §2: Sales handle them, so deciding is {@code sales-order-cancellations:UPDATE} — the
 * permission that already cancels an order from the back office (SCRUM-459), held by Sales and the
 * coordinator. The queue is read with {@code sales-orders:READ}.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class CancellationRequestController {

    private final CancellationRequestService requests;

    CancellationRequestController(CancellationRequestService requests) {
        this.requests = requests;
    }

    @GetMapping("/cancellation-requests")
    @Operation(summary = "Cancellation requests, newest first; status=PENDING is the queue to decide")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<CancellationRequestResponse>> list(
            @RequestParam(required = false) CancellationRequestStatus status,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(requests.list(status, orderId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)
                .map(CancellationRequestController::toResponse));
    }

    @PostMapping("/{orderId}/cancellation-requests/{requestId}/approval")
    @Operation(summary = "Approve: the order is cancelled, keeping retainedPercent of what was paid")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> approve(@PathVariable UUID orderId, @PathVariable UUID requestId,
                                              @Valid @RequestBody(required = false) ApproveCancellationRequest request,
                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(requests.approve(orderId, requestId,
                request == null ? null : request.retainedPercent(), request == null ? null : request.note(),
                user.userId())));
    }

    @PostMapping("/{orderId}/cancellation-requests/{requestId}/rejection")
    @Operation(summary = "Reject, saying why; the order goes on")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<CancellationRequestResponse> reject(@PathVariable UUID orderId, @PathVariable UUID requestId,
                                                           @Valid @RequestBody RejectCancellationRequest request,
                                                           @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(requests.reject(orderId, requestId, request.note(), user.userId())));
    }

    static CancellationRequestResponse toResponse(CancellationRequest r) {
        return new CancellationRequestResponse(r.id(), r.orderId(), r.requestedBy(), r.reasonCode().name(), r.note(),
                r.requestedAt(), r.status().name(), r.decidedBy(), r.decidedAt(), r.decisionNote(),
                r.retainedPercent(), r.version());
    }
}
