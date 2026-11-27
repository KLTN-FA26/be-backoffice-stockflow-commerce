package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * How many quotes {@code GET /api/v1/quotes} would list under each status, for the list's status tabs: counted under the
 * same filters and the same visibility, so a tab's number is the length of the list it opens.
 */
@Schema(name = "QuoteStatusCounts")
public record QuoteStatusCountsResponse(
        @Schema(description = "Every quote status (DRAFT, SENT, CHANGES_REQUESTED, ACCEPTED, EXPIRED, CANCELLED, "
                + "CONVERTED), zero included, counted by the status the list shows: a SENT quote past its validity is "
                + "EXPIRED, and to a customer a revision Sales are writing is CHANGES_REQUESTED (their DRAFT is always 0)",
                example = "{\"DRAFT\": 3, \"SENT\": 5, \"CHANGES_REQUESTED\": 1, \"ACCEPTED\": 2, \"EXPIRED\": 4, "
                        + "\"CANCELLED\": 0, \"CONVERTED\": 7}")
        Map<String, Long> byStatus,

        @Schema(description = "The sum of byStatus: the list's total under the same filters", example = "22")
        long total,

        @Schema(description = "14 BR-10: of those, the drafts waiting for the Sales Manager to approve their prices (the "
                + "list's priceReview=PENDING); always 0 for a portal customer", example = "1")
        long priceReviewPending
) {
}
