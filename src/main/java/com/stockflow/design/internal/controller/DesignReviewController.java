package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.DesignDraftResponse;
import com.stockflow.design.internal.service.DesignReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Sales Manager's review of customers' designs (kltn-docs 12 §4.4). Handlers are public so the permission proxy
 * advises them; the resource is declared once, on {@code DesignController}.
 */
@RestController
@RequestMapping("/api/v1/designs")
@Tag(name = "Design review", description = "Customers' designs waiting for the Sales Manager")
class DesignReviewController {

    private final DesignReviewService reviews;
    private final DesignWorkflowWebMapper mapper;

    DesignReviewController(DesignReviewService reviews, DesignWorkflowWebMapper mapper) {
        this.reviews = reviews;
        this.mapper = mapper;
    }

    /** {@code notes} is required when returning (the reason, BR-06), optional when approving. */
    record DecisionRequest(@PositiveOrZero long version, @NotNull Boolean approved, @Size(max = 2000) String notes) {
    }

    /**
     * Approves the design - its files stamped and frozen with the layout in a snapshot and the customer's SKU issued, in
     * one step (BR-09) - or returns it with a reason. Either way at the version reviewed (BR-11).
     */
    @PostMapping("/{id}/reviews")
    @Operation(summary = "Approve or return a design waiting for review")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.APPROVE)
    public ApiResponse<DesignDraftResponse> decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request,
                                                   @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(mapper.toResponse(reviews.decide(id, DesignWorkflowController.actor(user),
                request.version(), request.approved(), request.notes())));
    }

    @GetMapping("/review-queue")
    @Operation(summary = "Customers' designs waiting for review, the longest waiting first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.APPROVE)
    public ApiResponse<PageResponse<DesignDraftResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(reviews.queue(Pages.of(page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size)).map(mapper::toResponse)));
    }
}
