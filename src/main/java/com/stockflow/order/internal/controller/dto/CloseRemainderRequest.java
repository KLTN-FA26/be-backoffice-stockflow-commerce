package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.CancellationReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Close what is left of a partly fulfilled order (G3-2, split shipments): the units not shipped will not be delivered.
 *
 * @param note required for OTHER
 */
@Schema(description = "Close the rest of a partly fulfilled order")
public record CloseRemainderRequest(

        @Schema(example = "OUT_OF_STOCK")
        @NotNull(message = "reasonCode is required")
        CancellationReasonCode reasonCode,

        @Size(max = 1000, message = "note is at most 1000 characters")
        String note,

        @io.swagger.v3.oas.annotations.media.Schema(description = "Optional amount agreed with the customer for print "
                + "lines closed after printing started, at most their value (review of #150): the money received pays "
                + "it first, the rest is refunded, what it does not cover is owed as a receivable. Before printing: "
                + "409 CANCELLATION_FEE_BEFORE_PRINTING")
        @jakarta.validation.Valid
        CancellationFeeRequest agreedFee
) {

    /** Without a fee (callers written before the review of #150). */
    public CloseRemainderRequest(CancellationReasonCode reasonCode, String note) {
        this(reasonCode, note, null);
    }
}
