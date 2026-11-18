package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.internal.controller.dto.ReturnRequestResponse;
import com.stockflow.order.internal.controller.dto.ReturnRequests;
import com.stockflow.order.internal.service.ReturnRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The refund of a return's goods once they came back (team plan 4D-3; kltn-docs 17 §4.10 step 40, 15 §4.5):
 * {@code sales-rma-resolutions}: UPDATE proposes (Sales, the coordinator), APPROVE approves it (the Accountant), never
 * the person who proposed it (four eyes). Approving settles the refund against the order's installments; the
 * accountant then records the transfer under {@code /api/v1/refunds} (4B-9).
 */
@RestController
@RequestMapping("/api/v1/returns")
@Tag(name = "Returns")
@PermissionResource(
        code = OrderResources.RMA_RESOLUTIONS,
        group = "Sales",
        label = "Return refunds",
        route = "/sales/returns",
        apiPath = "/api/v1/returns/{returnId}/resolution",
        actions = {Action.UPDATE, Action.APPROVE})
class ReturnResolutionController {

    private final ReturnRequestService returns;

    ReturnResolutionController(ReturnRequestService returns) {
        this.returns = returns;
    }

    @PostMapping("/{returnId}/resolution")
    @Operation(summary = "Propose the refund of a RECEIVED return: units per line, at most those approved and back "
            + "(none listed: every one of them), worth what the order line charged with its VAT. Waits for another "
            + "person's approval")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> propose(@PathVariable UUID returnId,
                                                      @Valid @RequestBody(required = false) ReturnRequests.Refund request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.proposeRefund(returnId,
                ReturnWebMapper.toCommand(request), user.userId()), List.of()));
    }

    @PostMapping("/{returnId}/settlement-approval")
    @Operation(summary = "Approve the refund (not the proposer): payment takes the goods' value off what the order's "
            + "installments still owe and makes what was paid refundable; the return is RESOLVED. A refund of an order "
            + "with no installments (nothing was paid in the system, WF-05 TH-2) is worth 0 and moves no money")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> approve(@PathVariable UUID returnId,
                                                      @Valid @RequestBody(required = false) ReturnRequests.Version request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.approveSettlement(returnId,
                request == null ? null : request.version(), user.userId()), List.of()));
    }

    @PostMapping("/{returnId}/goods-complete")
    @Operation(summary = "Say the goods of an AWAITING_RETURN return are all here although fewer than approved came "
            + "back - the customer sent what they had, or another SKU (WF-05 TH-3): RECEIVED with what its return "
            + "receipts brought, the units short recorded; the refund is then proposed on those, or the return closed "
            + "without one. 409 RETURN_INVALID_TRANSITION when not AWAITING_RETURN or when no receipt brought anything "
            + "yet")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> goodsComplete(@PathVariable UUID returnId,
            @Valid @RequestBody(required = false) ReturnRequests.GoodsComplete request,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.markGoodsComplete(returnId,
                request == null ? null : request.note(), request == null ? null : request.version(), user.userId()),
                List.of()));
    }

    @PostMapping("/{returnId}/closure-without-refund")
    @Operation(summary = "Close a RECEIVED return with no refund, when nothing approved came back usable - every "
            + "carton counted empty, or only goods the return did not claim (WF-05 TH-4): RESOLVED, no money, no "
            + "adjustment invoice, one person. 409 RETURN_INVALID_TRANSITION when not RECEIVED; 400 "
            + "RETURN_QUANTITIES_INVALID while something approved came back and is to be refunded")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> closeWithoutRefund(@PathVariable UUID returnId,
            @Valid @RequestBody(required = false) ReturnRequests.CloseWithoutRefund request,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.closeWithoutRefund(returnId,
                request == null ? null : request.note(), request == null ? null : request.version(), user.userId()),
                List.of()));
    }

    @PostMapping("/{returnId}/settlement-rejection")
    @Operation(summary = "Send the refund back to Sales, saying why; the return is RECEIVED again")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> reject(@PathVariable UUID returnId,
                                                     @Valid @RequestBody ReturnRequests.Reason request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.rejectSettlement(returnId, request.reason(),
                request.version(), user.userId()), List.of()));
    }
}
