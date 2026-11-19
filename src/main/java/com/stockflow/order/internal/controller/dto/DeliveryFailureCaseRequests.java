package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What Sales send to decide a delivery failure case (WF-05 fix plan, section 2). */
public final class DeliveryFailureCaseRequests {

    private DeliveryFailureCaseRequests() {
    }

    /** (b) Cancel what was not delivered. */
    @Schema(name = "DeliveryFailureCancellationRequest", description = "Cancel what a failed delivery left "
            + "undelivered: the rest of the order is closed")
    public record Cancellation(

            @Schema(example = "Khách không nhận lại hàng; hai bên thống nhất huỷ phần chưa giao")
            @NotBlank(message = "note is required")
            @Size(max = 1000, message = "note is at most 1000 characters")
            String note,

            @Schema(description = "The amount agreed with the customer for the goods already made, at most their "
                    + "value: goods printed to the customer's design are not refunded by default. The money collected "
                    + "pays it first, the rest above the goods delivered is refundable, what it does not cover is "
                    + "owed on a fee item", nullable = true)
            @Valid
            CancellationFeeRequest agreedFee
    ) {
    }
}
