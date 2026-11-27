package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The salesperson the back office customer list shows for a customer (owner decision 2026-11-27): who sent the
 * customer's most recent quote or contract (kltn-docs 00 §2: a document's salesperson is whoever sent it). Derived on
 * read; the customer has no salesperson of its own.
 */
@Schema(name = "SalesContact")
public record SalesContactResponse(
        UUID customerId,
        @Schema(description = "Who sent the customer's most recent document; for a contract with no sender recorded, "
                + "who recorded it signed") UUID salesUserId,
        @Schema(description = "That account's display name: full name, or the username when none was given")
        String salesName,
        @Schema(description = "False when the account is locked or disabled: the salesperson is no longer there, show "
                + "them as such") boolean salesActive,
        @Schema(description = "The kind of document the salesperson is named from", allowableValues = {"QUOTE",
                "CONTRACT"}) String source,
        @Schema(description = "The quote number or the contract number") String documentNumber,
        @Schema(description = "When that document was sent (or recorded signed, in the fallback above)") Instant at
) {
}
