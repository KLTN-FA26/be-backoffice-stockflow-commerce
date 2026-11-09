package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One installment of a signed contract (4B-6, kltn-docs 15 §5.1); {@code receipts} only on the detail - for a customer,
 * the live ones only.
 */
@Schema(name = "Installment")
public record InstallmentResponse(UUID installmentId, UUID contractId,
                                  @Schema(example = "HD-20261108-000007-1") String contractNumber,
                                  UUID orderId, @Schema(example = "SO-20261108-000007") String orderNumber,
                                  UUID customerId, int seq, @Schema(example = "Đặt cọc") String name,
                                  @Schema(allowableValues = {"ON_SIGNING", "BEFORE_DELIVERY", "AFTER_DELIVERY"})
                                  String milestone,
                                  @Schema(description = "The delivery batch it is tied to (17 §4.5); null when none")
                                  Integer deliveryBatchSeq,
                                  BigDecimal amount, BigDecimal paidAmount, BigDecimal outstanding, String currency,
                                  @Schema(description = "Days after the milestone it falls due; null before delivery")
                                  Integer dueDays,
                                  @Schema(description = "When its milestone came; null while NOT_DUE")
                                  Instant reachedAt,
                                  @Schema(description = "Past it and not paid in full, it becomes OVERDUE; null before "
                                          + "its milestone and for an installment paid before delivery")
                                  LocalDate dueDate,
                                  @Schema(allowableValues = {"NOT_DUE", "DUE", "PARTIALLY_PAID", "PAID", "OVERDUE",
                                          "CANCELLED"}) String status,
                                  Instant paidAt, Instant cancelledAt,
                                  @Schema(description = "What the customer writes in the transfer: contract number + "
                                          + "installment (15 §4.2 step 5)", example = "HD-20261108-000007-1 DOT1")
                                  String transferReference,
                                  List<Receipt> receipts) {

    /** A receipt with a line paying it. */
    public record Receipt(UUID receiptId, String reference, LocalDate receivedOn, BigDecimal amount,
                          @Schema(allowableValues = {"RECORDED", "VOIDED"}) String status, Instant recordedAt) {
    }
}
