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
            + "installments still owe and makes what was paid refundable; the return is RESOLVED. 409 "
            + "RETURN_REFUND_NO_INSTALLMENTS for an order not paid through a contract's installments")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> approve(@PathVariable UUID returnId,
                                                      @Valid @RequestBody(required = false) ReturnRequests.Version request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.approveSettlement(returnId,
                request == null ? null : request.version(), user.userId()), List.of()));
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
