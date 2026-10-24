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
 * How a return's accepted units are made good (SCRUM-260): replaced by a free replacement order, or credited - refunded
 * or set against what the customer owes. {@code sales-rma-resolutions}: UPDATE proposes (Sales, the coordinator),
 * APPROVE approves one that moves money (the Accountant), never the person who proposed it (four eyes).
 */
@RestController
@RequestMapping("/api/v1/returns")
@Tag(name = "Returns")
@PermissionResource(
        code = OrderResources.RMA_RESOLUTIONS,
        group = "Sales",
        label = "Return resolutions",
        route = "/sales/returns",
        apiPath = "/api/v1/returns/{returnId}/resolution",
        actions = {Action.UPDATE, Action.APPROVE})
class ReturnResolutionController {

    private final ReturnRequestService returns;

    ReturnResolutionController(ReturnRequestService returns) {
        this.returns = returns;
    }

    @PostMapping("/{returnId}/resolution")
    @Operation(summary = "Per line, accepted units replaced or credited at the price paid; moneyMode REFUND or "
            + "CREDIT_NOTE for the credited value. Always waits for another person's approval: free replacement goods "
            + "are worth what money is")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> propose(@PathVariable UUID returnId,
                                                      @Valid @RequestBody ReturnRequests.Resolve request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.proposeResolution(returnId,
                ReturnWebMapper.toCommand(request), user.userId()), List.of()));
    }

    @PostMapping("/{returnId}/settlement-approval")
    @Operation(summary = "Approve the resolution (not the proposer): the replacement order is placed, payment sets the "
            + "value against the order's receivables and refunds or keeps the rest as credit, the return is RESOLVED. "
            + "409 RETURN_REFUND_EXCEEDS_PAID when the order received less than the refund")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> approve(@PathVariable UUID returnId,
                                                      @Valid @RequestBody(required = false) ReturnRequests.Version request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.approveSettlement(returnId,
                request == null ? null : request.version(), user.userId()), List.of()));
    }

    @PostMapping("/{returnId}/settlement-rejection")
    @Operation(summary = "Send the settlement back to Sales, saying why; the return is INSPECTED again")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> reject(@PathVariable UUID returnId,
                                                     @Valid @RequestBody ReturnRequests.Reason request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.rejectSettlement(returnId, request.reason(),
                request.version(), user.userId()), List.of()));
    }

    @PostMapping("/{returnId}/replacement-withdrawal")
    @Operation(summary = "Propose to withdraw a resolved return's replacement order before it ships (the customer would "
            + "rather have the money, or the goods cannot be made again): its units are credited instead once another "
            + "person approves. 409 RETURN_REPLACEMENT_NOT_WITHDRAWABLE once the replacement is packed or shipped")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.UPDATE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> proposeWithdrawal(@PathVariable UUID returnId,
                                                                @Valid @RequestBody ReturnRequests.WithdrawReplacement request,
                                                                @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.proposeReplacementWithdrawal(returnId,
                request.reason(), request.version(), user.userId()), List.of()));
    }

    @PostMapping("/{returnId}/replacement-withdrawal/approval")
    @Operation(summary = "Approve the withdrawal (not the proposer): the replacement order is cancelled and its units' "
            + "value set against the original order's receivables, the rest kept as the customer's credit")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> approveWithdrawal(@PathVariable UUID returnId,
                                                                @Valid @RequestBody(required = false) ReturnRequests.Version request,
                                                                @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.approveReplacementWithdrawal(returnId,
                request == null ? null : request.version(), user.userId()), List.of()));
    }

    @PostMapping("/{returnId}/replacement-withdrawal/rejection")
    @Operation(summary = "Send the withdrawal back, saying why; the replacement ships as decided")
    @RequiresPermission(resource = OrderResources.RMA_RESOLUTIONS, action = Action.APPROVE, scope = DataScope.ALL)
    public ApiResponse<ReturnRequestResponse> rejectWithdrawal(@PathVariable UUID returnId,
                                                               @Valid @RequestBody ReturnRequests.Reason request,
                                                               @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.rejectReplacementWithdrawal(returnId,
                request.reason(), request.version(), user.userId()), List.of()));
    }
}
