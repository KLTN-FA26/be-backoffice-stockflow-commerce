package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A payment receipt (4B-6, kltn-docs 15 §5.3): as recorded, and its void when voided. */
@Schema(name = "PaymentReceipt")
public record PaymentReceiptResponse(UUID receiptId, UUID customerId,
                                     @Schema(description = "The customer's registered name; null when the customer "
                                             + "record is gone")
                                     String customerName,
                                     @Schema(description = "The code the customer is known by (tax code, or KH + 8 "
                                             + "characters of the id); null when the customer record is gone")
                                     String customerCode,
                                     String reference, LocalDate receivedOn,
                                     BigDecimal amount, String currency, String note,
                                     @Schema(allowableValues = {"RECORDED", "VOIDED"}) String status,
                                     UUID recordedBy, Instant recordedAt, UUID voidedBy, Instant voidedAt,
                                     String voidReason, List<Line> lines,
                                     @Schema(description = "R5-8: the display name of recordedBy; null when the "
                                             + "account is gone") String recordedByName,
                                     @Schema(description = "R5-8: the display name of voidedBy; null while not "
                                             + "voided") String voidedByName,
                                     @Schema(description = "WF-05 Q-20: whether a void of the receipt would be taken "
                                             + "now - the same rule POST /{receiptId}/void enforces, read without its "
                                             + "locks. On the receipt's detail, and on what recording or voiding it "
                                             + "returns; null on the list, which does not work it out per row. A void "
                                             + "that would leave a confirmed order short of its deposit is voidable: it "
                                             + "asks for holdOrders (409 PAYMENT_RECEIPT_VOID_NEEDS_HOLD)")
                                     Boolean voidable,
                                     @Schema(description = "WF-05 Q-20: why it is not voidable - the error code the "
                                             + "void would answer with (409): PAYMENT_RECEIPT_ALREADY_VOIDED, "
                                             + "PAYMENT_RECEIPT_VOID_NOT_ALLOWED (its order's money is settled), "
                                             + "PAYMENT_RECEIPT_VOID_AFTER_HANDOVER (it paid an installment on signing "
                                             + "or before delivery, and the goods it let go were handed over); null when "
                                             + "voidable, and on the list",
                                             allowableValues = {"PAYMENT_RECEIPT_ALREADY_VOIDED",
                                                     "PAYMENT_RECEIPT_VOID_NOT_ALLOWED",
                                                     "PAYMENT_RECEIPT_VOID_AFTER_HANDOVER"})
                                     String voidBlockedBy) {

    /** One installment the receipt pays; the installment's facts are null when it cannot be read. */
    public record Line(UUID installmentId, BigDecimal amount, String contractNumber, UUID orderId, String orderNumber,
                       Integer seq, String name, String milestone) {
    }
}
