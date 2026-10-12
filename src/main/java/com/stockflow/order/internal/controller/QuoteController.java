package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.internal.controller.dto.QuoteRequests;
import com.stockflow.order.internal.controller.dto.QuoteResponse;
import com.stockflow.order.internal.controller.dto.QuoteRowResponse;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.service.Quotes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * B2B quotes (SCRUM-448, 03-state-machines §18): Sales drafts, sends, revises and converts; the customer
 * accepts or asks for changes on the portal.
 *
 * <p>Actions follow V20261014000300: CREATE drafts, UPDATE is Sales handling the quote (edit, send,
 * revise, cancel, convert), APPROVE is the customer's answer and is granted to CUSTOMER only. The service
 * adds what a permission cannot say: a portal user only sees their own quotes once sent and is refused
 * every back-office verb, and only a portal customer answers. Every endpoint keeps scope ALL, the
 * ownership being enforced in the service (the CUSTOMER role's scope is ALL, see {@code ScopeGuards}).</p>
 */
@RestController
@RequestMapping("/api/v1/quotes")
@Tag(name = "Quotes", description = "B2B quotes: tiers, MOQ, payment term, acceptance and conversion to an order")
@PermissionResource(
        code = OrderResources.QUOTES,
        group = "Sales",
        label = "Quotes",
        route = "/sales/quotes",
        apiPath = "/api/v1/quotes",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.APPROVE})
class QuoteController {

    private final Quotes quotes;

    QuoteController(Quotes quotes) {
        this.quotes = quotes;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Draft a quote; idempotent on requestId")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.CREATE)
    public ApiResponse<QuoteResponse> create(@Valid @RequestBody QuoteRequests.Create request,
                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.create(user, new Quotes.Create(request.requestId(),
                request.customerId(), new Quotes.Draft(request.paymentTerm(), request.depositPercent(),
                request.validUntil(), request.terms(), QuoteWebMapper.lines(request.lines()))))));
    }

    @GetMapping
    @Operation(summary = "Quotes, newest first; a portal customer sees only their own, once sent")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.READ)
    public ApiResponse<PageResponse<QuoteRowResponse>> list(
            @RequestParam(name = "status", required = false) List<QuoteStatus> statuses,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(quotes.list(user, new Quotes.Criteria(customerId, statuses, search, page,
                size == null ? Pages.DEFAULT_PAGE_SIZE : size, sort)).map(QuoteWebMapper::toResponse));
    }

    @GetMapping("/{quoteId}")
    @Operation(summary = "One quote with its lines, tiers, sample status per line and history")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.READ)
    public ApiResponse<QuoteResponse> get(@PathVariable UUID quoteId, @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.get(user, quoteId)));
    }

    @PutMapping("/{quoteId}")
    @Operation(summary = "Replace the offer of a DRAFT quote")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.UPDATE)
    public ApiResponse<QuoteResponse> edit(@PathVariable UUID quoteId, @Valid @RequestBody QuoteRequests.Edit request,
                                           @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.edit(user, quoteId, request.version(),
                new Quotes.Draft(request.paymentTerm(), request.depositPercent(), request.validUntil(),
                        request.terms(), QuoteWebMapper.lines(request.lines())))));
    }

    @PostMapping("/{quoteId}/sending")
    @Operation(summary = "DRAFT -> SENT: the customer sees it on the portal")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.UPDATE)
    public ApiResponse<QuoteResponse> send(@PathVariable UUID quoteId, @Valid @RequestBody QuoteRequests.Version request,
                                           @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.send(user, quoteId, request.version())));
    }

    @PostMapping("/{quoteId}/acceptance")
    @Operation(summary = "The customer accepts within the validity; every new design needs a sample they approved")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.APPROVE)
    public ApiResponse<QuoteResponse> accept(@PathVariable UUID quoteId,
                                             @Valid @RequestBody QuoteRequests.Acceptance request,
                                             @AuthenticatedUser CurrentUser user) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        if (request.lines() != null) {
            request.lines().forEach(line -> quantities.merge(line.lineId(), line.quantity(), (a, b) -> b));
        }
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.accept(user, quoteId,
                new Quotes.Acceptance(request.version(), quantities))));
    }

    @PostMapping("/{quoteId}/change-request")
    @Operation(summary = "The customer asks for changes, saying what")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.APPROVE)
    public ApiResponse<QuoteResponse> requestChanges(@PathVariable UUID quoteId,
                                                     @Valid @RequestBody QuoteRequests.Feedback request,
                                                     @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.requestChanges(user, quoteId, request.version(),
                request.feedback())));
    }

    @PostMapping("/{quoteId}/revision")
    @Operation(summary = "CHANGES_REQUESTED -> DRAFT, the next revision")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.UPDATE)
    public ApiResponse<QuoteResponse> reopen(@PathVariable UUID quoteId, @Valid @RequestBody QuoteRequests.Version request,
                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.reopen(user, quoteId, request.version())));
    }

    @PostMapping("/{quoteId}/cancellation")
    @Operation(summary = "Cancel a DRAFT quote, with a reason")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.UPDATE)
    public ApiResponse<QuoteResponse> cancel(@PathVariable UUID quoteId,
                                             @Valid @RequestBody QuoteRequests.Cancellation request,
                                             @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.cancel(user, quoteId, request.version(),
                request.reason())));
    }

    @PostMapping("/{quoteId}/conversion")
    @Operation(summary = "ACCEPTED -> CONVERTED: place the one sales order at the accepted quantities and prices; "
            + "a repeat returns the same order")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.UPDATE)
    public ApiResponse<QuoteResponse> convert(@PathVariable UUID quoteId,
                                              @Valid @RequestBody QuoteRequests.Conversion request,
                                              @AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(QuoteWebMapper.toResponse(quotes.convert(user, quoteId,
                new Quotes.Conversion(request.version(), request.shippingAddressId(), request.billingAddressId()))));
    }
}
