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
        CancellationFeeRequest agreedFee,

        @Schema(description = "Who bears the fee (D46, D50): the customer is charged it, or the company bears it - then the customer is "
                + "charged nothing and the amount is the company's cost. Required whenever a fee is given "
                + "(retainedPercent above 0, or agreedFee), no default; refused without a fee")
        com.stockflow.order.api.CancellationFeeBearer feeBearer
) {

    /** Without a fee (callers written before the review of #150). */
    public CloseRemainderRequest(CancellationReasonCode reasonCode, String note) {
        this(reasonCode, note, null, null);
    }
}
