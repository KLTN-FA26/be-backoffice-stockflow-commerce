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
 * QC inspects returned goods (SCRUM-260, 03 §12: RECEIVED → INSPECTED, or REJECTED when no unit holds the claim) and
 * decides where each unit goes. Its own resource, {@code sales-rma-inspections}: QC and the warehouse manager; scope
 * WAREHOUSE, as the stock moves it makes are.
 */
@RestController
@RequestMapping("/api/v1/returns")
@Tag(name = "Returns")
@PermissionResource(
        code = OrderResources.RMA_INSPECTIONS,
        group = "Sales",
        label = "Return inspections",
        route = "/warehouse/returns",
        apiPath = "/api/v1/returns/{returnId}/inspection",
        actions = {Action.UPDATE})
class ReturnInspectionController {

    private final ReturnRequestService returns;

    ReturnInspectionController(ReturnRequestService returns) {
        this.returns = returns;
    }

    @PostMapping("/{returnId}/inspection")
    @Operation(summary = "Per line: units accepted (defect confirmed) and rejected (no defect, misuse, more than "
            + "approved); and where they go - back on sale at a bin (stock items only, never custom-printed), scrap, "
            + "back to the customer (rejected units). Scrap and goods sent back are inventory write-offs a second "
            + "person approves. Nothing accepted: the return is rejected")
    @RequiresPermission(resource = OrderResources.RMA_INSPECTIONS, action = Action.UPDATE, scope = DataScope.WAREHOUSE)
    public ApiResponse<ReturnRequestResponse> inspect(@PathVariable UUID returnId,
                                                      @Valid @RequestBody ReturnRequests.Inspect request,
                                                      @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(ReturnWebMapper.toResponse(returns.inspect(returnId, ReturnWebMapper.toCommand(request),
                user.userId()), List.of()));
    }
}
