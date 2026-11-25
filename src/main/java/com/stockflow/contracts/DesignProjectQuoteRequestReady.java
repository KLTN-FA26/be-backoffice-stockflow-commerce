package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>design</b> module when the last design of an open project quote request that was not approved
 * yet is approved - or leaves the request - so that every design on it is approved now (kltn-docs 12 BR-25): Sales can
 * draw its quote. A request asked over approved designs only is never "ready": {@link DesignProjectQuoteRequested}
 * already says it can be quoted. {@code notification} tells the sales staff; the chat refreshes the request's card.
 *
 * <p>Facts only. Consumers receive it in-process; see {@code package-info.java} for the rules on changing this
 * contract.</p>
 *
 * @param requestId  the request
 * @param projectId  the project it asks over
 * @param customerId whose project it is
 * @param lines      how many designs it asks, every one approved
 * @param readyAt    when the last one was approved, or left it
 */
public record DesignProjectQuoteRequestReady(UUID requestId, UUID projectId, UUID customerId, int lines,
                                             Instant readyAt) {
}
