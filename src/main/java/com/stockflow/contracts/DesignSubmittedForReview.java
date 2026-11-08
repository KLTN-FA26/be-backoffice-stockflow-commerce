package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>design</b> module when a customer's design passes the server's preflight and waits for the Sales
 * Manager's review (kltn-docs 12 §4.3 step 12). {@code notification} tells whoever holds the right to review designs;
 * the design module never calls it directly. Facts only: the design is looked up by its id, the customer by theirs.
 *
 * @param designId    the design now pending review
 * @param customerId  whose design it is
 * @param sku         the variant it is drawn on
 * @param submittedAt when it was submitted; a design returned and submitted again is a new submission
 */
public record DesignSubmittedForReview(UUID designId, UUID customerId, String sku, Instant submittedAt) {
}
