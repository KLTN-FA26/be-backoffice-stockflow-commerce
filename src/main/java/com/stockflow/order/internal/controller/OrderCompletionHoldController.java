package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.order.internal.controller.dto.CompletionHoldRequest;
import com.stockflow.order.internal.controller.dto.CompletionHoldResponse;
import com.stockflow.order.internal.controller.dto.ReleaseCompletionHoldRequest;
import com.stockflow.order.internal.service.OrderCompletionHolds;
import com.stockflow.order.internal.service.OrderWarehouseVisibility;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * WF-05 GH-2: Sales pause an order's automatic completion while a delivery is verified with the carrier, and lift the
 * pause ({@link OrderCompletionHolds}). Guarded by {@code sales-order-holds:UPDATE}, the permission Sales hold and
 * resolve an order with (17 §4.9): it is the same decision - "keep this order where it is until we know" - taken
 * without stopping its fulfilment. Scope ALL, as the other hold endpoints: no customer holds the permission, and
 * {@code ScopeGuards.requireBackOffice} refuses one again.
 */
@RestController
@RequestMapping("/api/v1/orders/{orderId}/completion-hold")
@Tag(name = "Orders")
class OrderCompletionHoldController {

    private final OrderCompletionHolds holds;
    private final OrderWarehouseVisibility visibility;
    private final com.stockflow.identity.api.IdentityService identities;
    /** 17 BR-32: holding and releasing completion, recorded as done on the owner's behalf when someone else does it. */
    private final com.stockflow.order.internal.service.OrderOperations operations;

    OrderCompletionHoldController(OrderCompletionHolds holds, OrderWarehouseVisibility visibility,
                                  com.stockflow.identity.api.IdentityService identities,
                                  com.stockflow.order.internal.service.OrderOperations operations) {
        this.operations = operations;
        this.holds = holds;
        this.visibility = visibility;
        this.identities = identities;
    }

    @GetMapping
    @Operation(summary = "The order's open completion hold; data null when its completion is not paused")
    @RequiresPermission(resource = OrderResources.ORDERS, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<CompletionHoldResponse> current(@PathVariable UUID orderId,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        // BR-SEC-002: a warehouse manager reads the holds of orders of their warehouses only.
        visibility.requireVisible(orderId, user);
        return ApiResponse.ok(holds.current(orderId).map(this::toResponse).orElse(null));
    }

    @PostMapping
    @Operation(summary = "Pause the order's automatic completion while a delivery is verified (DELIVERY_DISPUTED, or "
            + "OTHER with a note). The order keeps its status and its other batches; the nightly run does not complete "
            + "it until the hold is released. 409 ORDER_COMPLETION_ALREADY_HELD, 409 "
            + "ORDER_COMPLETION_HOLD_NOT_APPLICABLE for an order completed, closed, cancelled, returned or refunded")
    @RequiresPermission(resource = OrderResources.HOLDS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<CompletionHoldResponse> hold(@PathVariable UUID orderId,
                                                    @Valid @RequestBody CompletionHoldRequest request,
                                                    @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(operations.holdCompletion(orderId, request.reasonCode(), request.note(), user.userId())));
    }

    @PostMapping("/release")
    @Operation(summary = "Release the order's completion hold with a note of what the verification found; the next "
            + "nightly run completes the order if everything else holds. 409 ORDER_COMPLETION_NOT_HELD")
    @RequiresPermission(resource = OrderResources.HOLDS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<CompletionHoldResponse> release(@PathVariable UUID orderId,
                                                       @Valid @RequestBody ReleaseCompletionHoldRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(operations.releaseCompletionHold(orderId, request.note(), user.userId())));
    }

    /** With who held and who released it, named in one lookup. */
    private CompletionHoldResponse toResponse(OrderCompletionHolds.CompletionHold hold) {
        java.util.Map<UUID, String> names = identities.displayNames(java.util.stream.Stream.of(hold.heldBy(),
                hold.releasedBy()).filter(java.util.Objects::nonNull).toList());
        return new CompletionHoldResponse(hold.id(), hold.orderId(), hold.reason().name(), hold.note(), hold.heldAt(),
                hold.heldBy(), hold.releasedAt(), hold.releasedBy(), hold.releaseNote(),
                hold.heldBy() == null ? null : names.get(hold.heldBy()),
                hold.releasedBy() == null ? null : names.get(hold.releasedBy()));
    }
}
