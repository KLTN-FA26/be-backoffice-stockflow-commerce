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
 * The warehouse counts a returned parcel in (SCRUM-260, 03 §12: AWAITING_RETURN → RECEIVED), into quarantine (OQ-12).
 * Its own resource, {@code sales-rma-receipts}, held by the warehouse; scope WAREHOUSE - inventory refuses a location
 * in a warehouse the caller is not assigned to (BR-SEC-002).
 */
@RestController
@RequestMapping("/api/v1/returns")
@Tag(name = "Returns")
@PermissionResource(
        code = OrderResources.RMA_RECEIPTS,
        group = "Sales",
        label = "Return receipts",
        route = "/warehouse/returns",
        apiPath = "/api/v1/returns/{returnId}/receipt",
        actions = {Action.UPDATE})
class ReturnReceiptController {

    private final ReturnRequestService returns;

    ReturnReceiptController(ReturnRequestService returns) {
        this.returns = returns;
    }

    @PostMapping("/{returnId}/receipt")
    @Operation(summary = "Count the parcel into a quarantine or QC area (default: the order's warehouse's): units per "
            + "line, the SKU when another arrived, the lot; anything belonging to no line. Fewer, more or other "
            + "goods than approved are recorded as discrepancies")
    @RequiresPermission(resource = OrderResources.RMA_RECEIPTS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<ReturnRequestResponse> receive(@PathVariable UUID returnId,
                                                      @Valid @RequestBody ReturnRequests.Receive request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.receive(returnId, ReturnWebMapper.toCommand(request),
                user.userId()), List.of()));
    }
}
