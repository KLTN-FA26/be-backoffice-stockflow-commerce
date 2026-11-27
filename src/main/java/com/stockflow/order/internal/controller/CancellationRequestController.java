package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
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
 * coordinator. The queue is read with {@code sales-order-cancellations:READ} (V20261022000200), not with
 * {@code sales-orders:READ}, which also lets the Accountant read orders.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
class CancellationRequestController {

    private final CancellationRequestService requests;
    private final com.stockflow.order.internal.service.CancellationQueue queue;
    private final com.stockflow.identity.api.IdentityService identities;

    CancellationRequestController(CancellationRequestService requests,
                                  com.stockflow.order.internal.service.CancellationQueue queue,
                                  com.stockflow.identity.api.IdentityService identities) {
        this.requests = requests;
        this.queue = queue;
        this.identities = identities;
    }

    @GetMapping("/cancellation-requests")
    @Operation(summary = "Cancellation requests, newest first; status=PENDING is the queue to decide")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<PageResponse<CancellationRequestResponse>> list(
            @RequestParam(required = false) CancellationRequestStatus status,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @AuthenticatedUser CurrentUser user) {
        // Every customer's requests: the back office's, whatever a portal account was granted.
        ScopeGuards.requireBackOffice(user);
        var found = requests.list(status, orderId, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort);
        var facts = queue.of(found.items());
        var names = namesOf(found.items());
        return ApiResponse.ok(found.map(r -> toResponse(r, facts.get(r.orderId()), names)));
    }

    @PostMapping("/{orderId}/cancellation-requests/{requestId}/approval")
    @Operation(summary = "Approve: the order is cancelled, keeping retainedPercent of what was paid")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<OrderResponse> approve(@PathVariable UUID orderId, @PathVariable UUID requestId,
                                              @Valid @RequestBody(required = false) ApproveCancellationRequest request,
                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(OrderWebMapper.toResponse(requests.approve(orderId, requestId,
                request == null ? null : request.retainedPercent(),
                request == null ? null : OrderWebMapper.toFee(request.agreedFee()),
                request == null ? null : request.note(), user.userId())));
    }

    @PostMapping("/{orderId}/cancellation-requests/{requestId}/rejection")
    @Operation(summary = "Reject, saying why; the order goes on")
    @RequiresPermission(resource = OrderResources.ORDER_CANCELLATIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<CancellationRequestResponse> reject(@PathVariable UUID orderId, @PathVariable UUID requestId,
                                                           @Valid @RequestBody RejectCancellationRequest request,
                                                           @AuthenticatedUser CurrentUser user) {
        CancellationRequest rejected = requests.reject(orderId, requestId, request.note(), user.userId());
        return ApiResponse.ok(toResponse(rejected, null, namesOf(java.util.List.of(rejected))));
    }

    /** The display names of everyone who asked or decided among {@code found}, in one lookup. */
    private java.util.Map<UUID, String> namesOf(java.util.Collection<CancellationRequest> found) {
        return identities.displayNames(found.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.requestedBy(), r.decidedBy()))
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
    }

    static CancellationRequestResponse toResponse(CancellationRequest r) {
        return toResponse(r, null, java.util.Map.of());
    }

    /**
     * With what Sales decide it on (R5-6); {@code facts} null leaves those fields out. {@code names}: display names by
     * user id, for who asked and who decided.
     */
    static CancellationRequestResponse toResponse(CancellationRequest r,
                                                  com.stockflow.order.internal.service.CancellationQueue.Facts facts,
                                                  java.util.Map<UUID, String> names) {
        return new CancellationRequestResponse(r.id(), r.orderId(), r.requestedBy(), r.reasonCode().name(), r.note(),
                r.requestedAt(), r.status().name(), r.decidedBy(), r.decidedAt(), r.decisionNote(),
                r.retainedPercent(), r.version(), facts == null ? null : facts.orderNumber(),
                facts == null ? null : facts.customerName(), facts == null ? null : facts.orderTotal(),
                facts == null ? null : facts.paidTotal(), facts == null ? null : facts.orderStatus(),
                facts == null ? null : facts.printingStarted(),
                r.requestedBy() == null ? null : names.get(r.requestedBy()),
                r.decidedBy() == null ? null : names.get(r.decidedBy()));
    }
}
