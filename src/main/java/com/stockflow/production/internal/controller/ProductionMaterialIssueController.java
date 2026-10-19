package com.stockflow.production.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.production.internal.controller.dto.MaterialIssueRequest;
import com.stockflow.production.internal.controller.dto.MaterialIssueResponse;
import com.stockflow.production.internal.service.MaterialIssues;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Blanks issued to the print shop (SCRUM-397). A resource of its own, so the warehouse can scan blanks out
 * without {@code production-orders:UPDATE}, which would also let it pass prepress or record output.
 */
@RestController
@RequestMapping("/api/v1/production/orders/{productionOrderId}/material-issues")
@Tag(name = "Production material issues", description = "Blanks scanned out to the PRODUCTION area")
@PermissionResource(
        code = ProductionResources.MATERIAL_ISSUES,
        group = "Production",
        label = "Blanks issued to production",
        apiPath = "/api/v1/production/orders/{productionOrderId}/material-issues",
        actions = {Action.READ, Action.CREATE})
class ProductionMaterialIssueController {

    private final MaterialIssues issues;
    private final ProductionWebMapper mapper;

    ProductionMaterialIssueController(MaterialIssues issues, ProductionWebMapper mapper) {
        this.issues = issues;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(summary = "Issue one held lot of blanks to a PRODUCTION area; READY -> MATERIAL_ISSUED once enough (BR-PRD-03)")
    @RequiresPermission(resource = ProductionResources.MATERIAL_ISSUES, action = Action.CREATE)
    public ApiResponse<MaterialIssueResponse> issue(@PathVariable UUID productionOrderId,
                                                    @Valid @RequestBody MaterialIssueRequest request,
                                                    @AuthenticatedUser CurrentUser user) {
        MaterialIssues.Result result = issues.issue(user, productionOrderId,
                new MaterialIssues.Issue(request.requestId(), request.toLocation(), request.reservationId(),
                        request.scannedLocation(), request.scannedCode()));
        return ApiResponse.ok(new MaterialIssueResponse(result.issue(), mapper.toResponse(result.order())));
    }

    @GetMapping("/pending")
    @Operation(summary = "What is still to scan: blanks required, issued and outstanding, and each hold waiting with "
            + "its location, lot, quantity and barcode (SCRUM-410)")
    @RequiresPermission(resource = ProductionResources.MATERIAL_ISSUES, action = Action.READ)
    public ApiResponse<MaterialIssues.Pending> pending(@PathVariable UUID productionOrderId,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(issues.pending(user, productionOrderId));
    }

    @GetMapping
    @Operation(summary = "The blanks issued to one production order, oldest first")
    @RequiresPermission(resource = ProductionResources.MATERIAL_ISSUES, action = Action.READ)
    public ApiResponse<List<MaterialIssues.Issued>> list(@PathVariable UUID productionOrderId,
                                                         @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(issues.list(user, productionOrderId));
    }
}
