package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** The refund asked for, PENDING until the accountant pays it out, and the transfer after it. */
@Schema(name = "CreditRefund")
public record CreditRefundResponse(UUID refundId, CustomerTransferResponse transfer) {
}
