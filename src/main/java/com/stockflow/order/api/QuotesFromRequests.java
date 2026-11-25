package com.stockflow.order.api;

import com.stockflow.common.security.CurrentUser;

import java.util.UUID;

/**
 * Sales answer a customer's quote request - on one design, or over the designs of a project - with a DRAFT quote
 * (kltn-docs 14 BR-22, 16 §4.2 BR-11): the customer's own SKUs at the cartons asked, as a suggestion, and the delivery
 * date asked. One operation for every place it is pressed from - the chat's quote request card and the back office's
 * request inbox - so the two cannot drift apart: the same checks, the same lines, the same quote.
 *
 * <p><b>Once per request.</b> The quote's request id is derived from the quote request's, so drawing it again - a
 * retry, the other screen, a second salesperson - answers the quote the first call drew up, whatever became of the
 * request since; but a draft cancelled before it was ever sent answers nothing (LC-3). Each caller adds its own checks before (the chat: the conversation and who is in charge of it).</p>
 */
public interface QuotesFromRequests {

    /** What the quote request is about; its id names a row of that kind. */
    enum Scope { DESIGN, PROJECT }

    /**
     * The quote drawn up.
     *
     * @param created false when the request had been answered already and this is that quote
     */
    record Drawn(UUID quoteId, String quoteNumber, UUID customerId, boolean created) {
    }

    /**
     * Draws up the DRAFT quote of the request and closes the request.
     *
     * @throws com.stockflow.common.error.BusinessException 404 {@code NOT_FOUND} for no such request; 409
     *         {@code DESIGN_NOT_APPROVED} while a design of it is not approved or has no customer SKU; 409
     *         {@code DESIGN_QUOTE_REQUEST_CLOSED} for a request closed without a quote drawn from it, or whose draft
     *         was cancelled before it was sent (Sales draw a quote by hand, or the customer asks again); the quote's own
     *         refusals ({@code CUSTOMER_NOT_ACTIVE}, an inactive SKU...)
     */
    Drawn draw(CurrentUser staff, Scope scope, UUID requestId);
}
