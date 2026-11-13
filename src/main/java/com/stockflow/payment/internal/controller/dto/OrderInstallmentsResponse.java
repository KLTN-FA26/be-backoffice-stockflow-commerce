package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An order's installments and where they stand (4B-6, kltn-docs 15 §5.2): the payment status is worked out from the
 * installments not cancelled, never stored.
 */
@Schema(name = "OrderInstallments")
public record OrderInstallmentsResponse(UUID orderId, String orderNumber, String contractNumber,
                                        @Schema(allowableValues = {"UNPAID", "PARTIALLY_PAID", "PAID"},
                                                description = "Null when every installment is cancelled")
                                        String paymentStatus,
                                        BigDecimal total, BigDecimal paid, BigDecimal outstanding,
                                        @Schema(description = "Every installment due on signing is paid (15 BR-07)")
                                        boolean onSigningPaid,
                                        @Schema(description = "What the installments paid before delivery still owe "
                                                + "(BR-08)")
                                        BigDecimal beforeDeliveryUnpaid,
                                        @Schema(description = "What must be paid before goods may leave: before "
                                                + "delivery and on signing (BR-07, BR-08)")
                                        BigDecimal dueBeforeHandover,
                                        String currency,
                                        List<InstallmentResponse> installments) {
}
