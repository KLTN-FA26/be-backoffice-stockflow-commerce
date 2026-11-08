package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.DesignDraftResponse;
import com.stockflow.design.internal.service.DesignReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/review-queue")
    @Operation(summary = "Customers' designs waiting for review, the longest waiting first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.APPROVE)
    public ApiResponse<PageResponse<DesignDraftResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(reviews.queue(Pages.of(page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size)).map(mapper::toResponse)));
    }
}
