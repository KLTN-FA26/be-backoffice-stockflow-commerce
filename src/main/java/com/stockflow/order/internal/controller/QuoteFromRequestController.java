package com.stockflow.order.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.order.api.QuotesFromRequests;
import com.stockflow.order.internal.controller.dto.QuoteResponse;
import com.stockflow.order.internal.service.Quotes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The back office's request inbox answers a customer's quote request with a DRAFT quote (kltn-docs 14 BR-22), without
 * going through the chat: the same operation as the chat card's CREATE_QUOTE ({@link QuotesFromRequests}), so the two
 * draw the same quote. The paths are the design module's requests, the endpoints this module's, because what they
 * make and answer is a quote - in the shape {@code GET /quotes/{id}} answers, which only this module can draw.
 *
 * <p>{@code sales-quotes:CREATE}, as drafting any quote. 201 with the quote drawn up; 200 with the same quote when the
 * request was answered already, from here or from the chat.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Quotes")
class QuoteFromRequestController {

    private final QuotesFromRequests drafts;
    private final Quotes quotes;

    QuoteFromRequestController(QuotesFromRequests drafts, Quotes quotes) {
        this.drafts = drafts;
        this.quotes = quotes;
    }

    @PostMapping("/design-quote-requests/{requestId}/quote")
    @Operation(summary = "Draw up a DRAFT quote from a quote request on one design and close the request: the design's "
            + "customer SKU at the cartons asked, the delivery date asked. 201 when drawn up, 200 with the same quote "
            + "when the request was answered already (here or on the chat card); 409 DESIGN_NOT_APPROVED while the "
            + "design is not approved, 409 DESIGN_QUOTE_REQUEST_CLOSED for a request closed without a quote")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.CREATE)
    public ResponseEntity<ApiResponse<QuoteResponse>> fromDesignRequest(@PathVariable UUID requestId,
                                                                        @AuthenticatedUser CurrentUser user) {
        return answer(user, drafts.draw(user, QuotesFromRequests.Scope.DESIGN, requestId));
    }

    @PostMapping("/design-project-quote-requests/{requestId}/quote")
    @Operation(summary = "Draw up one DRAFT quote of every design of a project's quote request and close the request. "
            + "201 when drawn up, 200 with the same quote when the request was answered already; 409 "
            + "DESIGN_NOT_APPROVED while a design of it is not approved, 409 DESIGN_QUOTE_REQUEST_CLOSED for a request "
            + "closed without a quote")
    @RequiresPermission(resource = OrderResources.QUOTES, action = Action.CREATE)
    public ResponseEntity<ApiResponse<QuoteResponse>> fromProjectRequest(@PathVariable UUID requestId,
                                                                         @AuthenticatedUser CurrentUser user) {
        return answer(user, drafts.draw(user, QuotesFromRequests.Scope.PROJECT, requestId));
    }

    private ResponseEntity<ApiResponse<QuoteResponse>> answer(CurrentUser user, QuotesFromRequests.Drawn drawn) {
        return ResponseEntity.status(drawn.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(QuoteWebMapper.toResponse(quotes.get(user, drawn.quoteId()))));
    }
}
