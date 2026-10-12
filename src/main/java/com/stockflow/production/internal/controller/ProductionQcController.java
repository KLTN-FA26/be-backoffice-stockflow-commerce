package com.stockflow.production.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.production.internal.controller.dto.ProductionOrderResponse;
import com.stockflow.production.internal.controller.dto.QcRequest;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.production.internal.service.PrintRuns;
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
 * QC of printed goods (SCRUM-399, BR-PRD-05; 03 §16 actor: QC staff). A resource of its own: QC records the
 * round that completes an order or sends it back to print, and nothing else in the print shop.
 */
@RestController
@RequestMapping("/api/v1/production/orders/{productionOrderId}/qc-results")
@Tag(name = "Production QC", description = "QC rounds of printed goods")
@PermissionResource(
        code = ProductionResources.QC_RESULTS,
        group = "Production",
        label = "QC of printed goods",
        apiPath = "/api/v1/production/orders/{productionOrderId}/qc-results",
        actions = {Action.READ, Action.CREATE})
class ProductionQcController {

    private final PrintRuns runs;
    private final ProductionWebMapper mapper;

    ProductionQcController(PrintRuns runs, ProductionWebMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(summary = "A QC round over every good unit waiting: enough -> COMPLETED (goods to packing, or a sample READY), short -> READY for a reprint")
    @RequiresPermission(resource = ProductionResources.QC_RESULTS, action = Action.CREATE)
    public ApiResponse<ProductionOrderResponse> record(@PathVariable UUID productionOrderId,
                                                       @Valid @RequestBody QcRequest request,
                                                       @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(runs.recordQc(user, productionOrderId, request.passed(),
                request.failed(), request.note(), request.packingLocation())));
    }

    @GetMapping
    @Operation(summary = "The QC rounds of one production order, first round first")
    @RequiresPermission(resource = ProductionResources.QC_RESULTS, action = Action.READ)
    public ApiResponse<List<ProductionRecords.QcRound>> list(@PathVariable UUID productionOrderId,
                                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(runs.qcRoundsOf(user, productionOrderId));
    }
}
