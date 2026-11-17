package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>design</b> module when a customer's quote request on a design closes: Sales answered it (a quote
 * was drawn up from it), or the customer deleted the draft it was on (kltn-docs 16 §4.2 B40: the request's card closes).
 * The chat closes the request's card.
 *
 * <p>Facts only. Consumers receive it in-process; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 *
 * @param outcome  {@code ANSWERED} (closed by Sales) or {@code DESIGN_ARCHIVED}
 * @param closedBy the Sales account that closed it; null when the design's deletion did
 */
public record DesignQuoteRequestClosed(UUID requestId, UUID designId, UUID customerId, String outcome, UUID closedBy,
                                       Instant closedAt) {
}
