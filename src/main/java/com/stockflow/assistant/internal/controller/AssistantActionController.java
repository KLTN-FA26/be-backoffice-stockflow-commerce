package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.ActionResponse;
import com.stockflow.assistant.internal.controller.dto.ConfirmGoodsReceiptRequest;
import com.stockflow.assistant.internal.controller.dto.ConfirmPurchaseOrderRequest;
import com.stockflow.assistant.internal.service.AssistantActions;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
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
 * The assistant's suggestions and their owner's decisions (ADR-0011, design §8.2).
 *
 * <p><b>Each confirmation carries the guard of the document's own screen.</b> A purchase order needs
 * {@code procurement-purchase-orders} CREATE and its warehouse in the caller's scope, exactly as
 * {@code POST /api/v1/purchase-orders}; a goods receipt {@code procurement-goods-receipts} CREATE and UPDATE, as the
 * receipt screen's create and count. Reading and dismissing a suggestion needs only the assistant: it is the caller's
 * own, and nothing else is shown. A suggestion of another user is not found, whatever the caller's scope.</p>
 *
 * <p>An {@code Idempotency-Key} makes a retried confirmation return the first one's answer; without one, a second
 * confirmation of the same suggestion still returns the document the first created.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant/actions")
@Tag(name = "Assistant")
public class AssistantActionController {

    private final AssistantActions actions;

    AssistantActionController(AssistantActions actions) {
        this.actions = actions;
    }

    @GetMapping
    @Operation(summary = "My suggestions, newest first; open=true for those still to decide")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<PageResponse<ActionResponse>> mine(@AuthenticatedUser CurrentUser user,
                                                          @RequestParam(defaultValue = "false") boolean open,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(required = false) Integer size) {
        ScopeGuards.requireBackOffice(user);
        int pageSize = Math.min(size == null ? Pages.DEFAULT_PAGE_SIZE : size, Pages.MAX_PAGE_SIZE);
        return ApiResponse.ok(actions.mine(user, open, Math.max(0, page), Math.max(1, pageSize))
                .map(AssistantActionController::toResponse));
    }

    @GetMapping("/{actionId}")
    @Operation(summary = "One suggestion of mine")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.READ, scope = DataScope.OWN)
    public ApiResponse<ActionResponse> get(@AuthenticatedUser CurrentUser user, @PathVariable UUID actionId) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(actions.get(actionId, user)));
    }

    @PostMapping("/{actionId}/purchase-order")
    @Operation(summary = "Create the DRAFT purchase order a suggestion proposed, edited",
            description = "Needs procurement-purchase-orders:CREATE and the warehouse in the caller's scope, as the "
                    + "purchase-order screen does. Lines: the suggested SKUs, quantities changed or lines removed; "
                    + "prices are the supplier's catalog prices. 404 when the suggestion is not the caller's; 409 "
                    + "ASSISTANT_ACTION_EXPIRED after a day, ASSISTANT_ACTION_CLOSED once dismissed or superseded; the "
                    + "purchase-order screen's own errors otherwise, the suggestion left open. Confirmed already: the same "
                    + "order. possibleDuplicate is the purchase-order screen's warning. No expectedAt: today plus the "
                    + "supplier's lead time, as on that screen.")
    @RequiresPermission(resource = "procurement-purchase-orders", action = Action.CREATE)
    public ApiResponse<ActionResponse> confirmPurchaseOrder(@AuthenticatedUser CurrentUser user,
                                                            @PathVariable UUID actionId,
                                                            @Valid @RequestBody ConfirmPurchaseOrderRequest request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(actions.confirmPurchaseOrder(actionId, user,
                new AssistantActions.PurchaseOrderChoice(request.warehouseId(), request.expectedAt(), request.note(),
                        request.lines().stream()
                                .map(line -> new AssistantActions.OrderLine(line.sku(), line.quantity())).toList()))));
    }

    @PostMapping("/{actionId}/goods-receipt")
    @Operation(summary = "Create the DRAFT goods receipt a delivery note was read into, edited",
            description = "Needs procurement-goods-receipts:CREATE and UPDATE and the order's warehouse in the caller's "
                    + "scope, as the goods-receipt screen does; the receipt is a DRAFT, confirmed on that screen. 404 "
                    + "when the suggestion is not the caller's; 409 ASSISTANT_ACTION_EXPIRED, ASSISTANT_ACTION_CLOSED; "
                    + "the receipt screen's own errors otherwise, the suggestion left open.")
    @RequiresPermission(resource = "procurement-goods-receipts", action = Action.CREATE)
    public ApiResponse<ActionResponse> confirmGoodsReceipt(@AuthenticatedUser CurrentUser user,
                                                           @PathVariable UUID actionId,
                                                           @Valid @RequestBody ConfirmGoodsReceiptRequest request) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(actions.confirmGoodsReceipt(actionId, user,
                new AssistantActions.GoodsReceiptChoice(request.deliveryNote(), request.note(), request.locationCode(),
                        request.lines().stream().map(line -> new AssistantActions.ReceiptLine(line.poLineNo(),
                                line.quantity(), line.lotNumber(), line.expiryDate(), line.locationCode(),
                                line.note())).toList()))));
    }

    @PostMapping("/{actionId}/dismissal")
    @Operation(summary = "Dismiss a suggestion of mine; 409 ASSISTANT_ACTION_CLOSED when it was confirmed")
    @RequiresPermission(resource = AssistantResources.CONVERSATIONS, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<ActionResponse> dismiss(@AuthenticatedUser CurrentUser user, @PathVariable UUID actionId) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(actions.dismiss(actionId, user)));
    }

    static ActionResponse toResponse(AssistantActions.ActionView action) {
        return new ActionResponse(action.id(), action.kind().name(), action.status().name(), action.createdAt(),
                action.expiresAt(), action.decidedAt(), action.resultId(), action.resultNumber(), action.suggested(),
                action.submitted(), action.possibleDuplicate());
    }
}
