package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when Sales answer a customer's quote request with a DRAFT quote (kltn-docs 14
 * BR-22, 16 §4.2 BR-11), from the chat's card or from the back office's request inbox. The chat follows it: the
 * request's card shows the quote and is marked acted on, and the quote is attached to the open conversation the card
 * is in, so the quote's own card lands there when it is sent - wherever Sales pressed the button. Published once per
 * quote, with the request's closing ({@link DesignQuoteRequestClosed}, {@link DesignProjectQuoteRequestClosed}) in the
 * same transaction.
 *
 * <p>Facts only. Consumers receive it in-process; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 *
 * @param scope     {@code DESIGN} for a request on one design, {@code PROJECT} for one over a design project
 * @param requestId the design module's request of that scope
 * @param drawnBy   the Sales account that drew the quote up
 */
public record QuoteDrawnFromRequest(UUID quoteId, String quoteNumber, UUID customerId, String scope, UUID requestId,
                                    UUID drawnBy, Instant drawnAt) {
}
