package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>product</b> module when a product master row is discontinued (WBS 3.1.8.1).
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made
 * durable by Spring Modulith's event publication registry. See {@code package-info.java} for the
 * rules on changing this contract.
 *
 * <p>{@code reason} (SCRUM-461) is why it was discontinued, mandatory when the product is discontinued
 * now but null on an event published before the field existed. The catalog listens to hide the
 * product; a purchase order or an order that still references the product is unaffected (kltn-docs 01
 * step 12, 02 edge cases).</p>
 */
public record ProductDiscontinued(UUID productId, Instant discontinuedAt, String reason) {
}
