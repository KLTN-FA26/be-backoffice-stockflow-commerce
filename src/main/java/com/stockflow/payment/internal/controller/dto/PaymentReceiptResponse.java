package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A payment receipt (4B-6, kltn-docs 15 §5.3): as recorded, and its void when voided. */
@Schema(name = "PaymentReceipt")
public record PaymentReceiptResponse(UUID receiptId, UUID customerId, String reference, LocalDate receivedOn,
                                     BigDecimal amount, String currency, String note,
                                     @Schema(allowableValues = {"RECORDED", "VOIDED"}) String status,
                                     UUID recordedBy, Instant recordedAt, UUID voidedBy, Instant voidedAt,
                                     String voidReason, List<Line> lines) {

    /** One installment the receipt pays; the installment's facts are null when it cannot be read. */
    public record Line(UUID installmentId, BigDecimal amount, String contractNumber, UUID orderId, String orderNumber,
                       Integer seq, String name, String milestone) {
    }
}
