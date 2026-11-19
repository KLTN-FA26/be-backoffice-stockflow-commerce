package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The real reason a shipment's delivery failed, once the warehouse manager learnt it (kltn-docs 09 edge "hoàn hàng khi
 * còn Handed Over ... Quản lý kho sửa lý do thất bại nếu biết lý do thật").
 *
 * @param failureReason CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED or OTHER
 * @param note          required with OTHER
 * @param reason        why it is corrected; required, kept with the correction
 */
public record FailureReasonCorrectionRequest(
        @NotBlank @Pattern(regexp = "^(CUSTOMER_ABSENT|REFUSED|WRONG_ADDRESS|LOST|DAMAGED|OTHER)$") String failureReason,
        @Size(max = 500) String note,
        @NotBlank @Size(max = 500) String reason) {
}
