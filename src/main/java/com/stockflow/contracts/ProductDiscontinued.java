package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>product</b> module when a product master row is discontinued (WBS 3.1.8.1).
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made
 * durable by Spring Modulith's event publication registry. See {@code package-info.java} for the
 * rules on changing this contract.
 *
 * <p>The catalog listens to hide the product; a purchase order or an order that still references the
 * product is unaffected (kltn-docs 01 step 12, 02 edge cases). The reason stays with the product module
 * ({@code products.discontinue_reason} and its change log): a contract carries facts and codes, never
 * text a user typed.</p>
 */
public record ProductDiscontinued(UUID productId, Instant discontinuedAt) {
}
