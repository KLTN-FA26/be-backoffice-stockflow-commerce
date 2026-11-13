package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>design</b> module when Sales close a customer's quote request over a design project (the request
 * was answered; the project asks again, for its next order, with a new one). The chat closes the request's card of
 * several lines (kltn-docs 16 §4.2), as it does a single design's on {@link DesignQuoteRequestClosed}.
 *
 * <p>Facts only. Consumers receive it in-process; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 *
 * @param closedBy the Sales account that closed it
 */
public record DesignProjectQuoteRequestClosed(UUID requestId, UUID projectId, UUID customerId, UUID closedBy,
                                              Instant closedAt) {
}
