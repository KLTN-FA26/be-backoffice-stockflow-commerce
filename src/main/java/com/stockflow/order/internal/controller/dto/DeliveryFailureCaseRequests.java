package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** What Sales send to decide a delivery failure case (WF-05 fix plan, section 2). */
public final class DeliveryFailureCaseRequests {

    private DeliveryFailureCaseRequests() {
    }

    /** (a) Deliver the goods again. */
    @Schema(name = "DeliveryFailureRedeliveryRequest", description = "Deliver again the goods of a shipment that came "
            + "back")
    public record Redelivery(

            @Schema(example = "Khách xác nhận nhận hàng vào thứ Sáu, địa chỉ đã sửa")
            @NotBlank(message = "note is required")
            @Size(max = 1000, message = "note is at most 1000 characters")
            String note,

            @Schema(description = "What the failed trip is charged at, whole dong. Required when the delivery failed "
                    + "for REFUSED or WRONG_ADDRESS - the customer made it fail and pays for it, on a fee item of the "
                    + "contract; must be left out for any other reason, which is the carrier's to answer for",
                    example = "850000", nullable = true)
            @DecimalMin(value = "0", inclusive = false, message = "failedTripFee is more than zero")
            @Digits(integer = 15, fraction = 0, message = "failedTripFee is in whole units of the currency")
            BigDecimal failedTripFee
    ) {
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
