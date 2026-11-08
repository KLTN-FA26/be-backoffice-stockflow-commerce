package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>design</b> module when the Sales Manager decides a customer's design (kltn-docs 12 §4.4, §4.5):
 * {@code notification} tells the customer, and the sales staff when a quote request on the design is open (12 §4.5
 * step 17; 16 §4.2: the request's card follows the design). Facts only - the reason a design was returned stays in
 * the design's own history, where the customer reads it.
 *
 * @param designId           the design decided
 * @param ownerUserId        the customer's own login that owns it
 * @param outcome            {@code APPROVED} or {@code RETURNED}
 * @param sku                the customer's SKU issued for it when approved; the variant it is drawn on when returned
 * @param decidedAt          when it was decided
 * @param openQuoteRequestId the quote request open on the design, or null when there is none
 */
public record DesignReviewed(UUID designId, UUID ownerUserId, String outcome, String sku, Instant decidedAt,
                             UUID openQuoteRequestId) {
}
