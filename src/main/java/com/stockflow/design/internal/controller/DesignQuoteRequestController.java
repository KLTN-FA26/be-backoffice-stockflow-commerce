package com.stockflow.design.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.design.internal.controller.dto.DesignQuoteRequestResponse;
import com.stockflow.design.internal.domain.QuoteRequestRules;
import com.stockflow.design.internal.domain.QuoteRequestStatus;
import com.stockflow.design.internal.service.DesignQuoteRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quote requests on customers' designs (kltn-docs 12 §4.8; 16 §4.2 step 5): the customer asks on an approved design
 * (a draft asks with its submission, on {@code CustomerDesignController}); the sales staff list them and close the ones
 * they have answered. Handlers are public so the permission proxy advises them.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Design quote requests", description = "Customers asking the sales staff for a quote on their design")
@PermissionResource(code = DesignResources.QUOTE_REQUESTS, group = "Design", label = "Design quote requests",
        route = "/design-quote-requests", apiPath = "/api/v1/design-quote-requests",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE})
class DesignQuoteRequestController {

    private final DesignQuoteRequestService requests;

    DesignQuoteRequestController(DesignQuoteRequestService requests) {
        this.requests = requests;
    }

    /** What a customer asks: the cartons they would like and, if any, the delivery date they would like. */
    record QuoteRequestBody(@Min(1) @Max(QuoteRequestRules.MAX_CARTONS) int cartons, LocalDate desiredDeliveryDate) {

        DesignQuoteRequestService.QuoteRequestInput input() {
            return new DesignQuoteRequestService.QuoteRequestInput(cartons, desiredDeliveryDate);
        }
    }

    /** 201 with a new request; 200 when the design's open request now asks this instead. */
    @PostMapping("/designs/{id}/quote-requests")
    @Operation(summary = "Ask the sales staff for a quote on an approved design; asking again changes the open request")
    @RequiresPermission(resource = DesignResources.QUOTE_REQUESTS, action = Action.CREATE)
    public ResponseEntity<ApiResponse<DesignQuoteRequestResponse>> request(@PathVariable UUID id,
            @Valid @RequestBody QuoteRequestBody body, @AuthenticatedUser CurrentUser user) {
        var asked = requests.request(id, DesignWorkflowController.actor(user), body.input());
        return ResponseEntity.status(asked.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(toResponse(asked.request())));
    }

    /** A salesperson closes a request they have answered; the design asks again, for its next order, anew. */
    @PostMapping("/design-quote-requests/{requestId}/closure")
    @Operation(summary = "Close a quote request once answered")
    @RequiresPermission(resource = DesignResources.QUOTE_REQUESTS, action = Action.UPDATE)
    public ApiResponse<DesignQuoteRequestResponse> close(@PathVariable UUID requestId,
                                                         @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(toResponse(requests.close(requestId, DesignWorkflowController.actor(user))));
    }

    /** To whoever reads the design (plan D7): its owner, a reviewer while it waits, the sales staff answering it. */
    @GetMapping("/designs/{id}/quote-requests")
    @Operation(summary = "A design's quote requests, the newest first")
    @RequiresPermission(resource = DesignResources.DESIGNS, action = Action.READ)
    public ApiResponse<List<DesignQuoteRequestResponse>> ofDesign(@PathVariable UUID id,
                                                                  @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(requests.ofDesign(id, DesignWorkflowController.actor(user),
                DesignWorkflowController.readRights(user)).stream().map(DesignQuoteRequestController::toResponse)
                .toList());
    }

    @GetMapping("/design-quote-requests")
    @Operation(summary = "Customers' quote requests on their designs, the newest first")
    @RequiresPermission(resource = DesignResources.QUOTE_REQUESTS, action = Action.READ)
    public ApiResponse<PageResponse<DesignQuoteRequestResponse>> list(
            @RequestParam(required = false) UUID customerId, @RequestParam(required = false) QuoteRequestStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(Pages.toResponse(requests.list(customerId, status, Pages.of(page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size)).map(DesignQuoteRequestController::toResponse)));
    }

    static DesignQuoteRequestResponse toResponse(DesignQuoteRequestService.QuoteRequestView view) {
        return new DesignQuoteRequestResponse(view.id(), view.designId(), view.designName(), view.designStatus().name(),
                view.customerId(), view.customerName(), view.sku(), view.customerSku(), view.returnReason(),
                view.cartons(), view.desiredDeliveryDate(), view.status().name(), view.requestedBy(),
                view.requestedAt(), view.closedAt(), view.closedBy());
    }
}
