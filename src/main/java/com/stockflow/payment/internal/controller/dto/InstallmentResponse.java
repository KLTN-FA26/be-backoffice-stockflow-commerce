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
                                  @Schema(description = "CANCELLATION_FEE: the part of a cancellation fee the "
                                          + "money collected did not cover (15 §4.6 step 18), paid like an installment. "
                                          + "FAILED_TRIP_FEE: the trip of a delivery the customer refused or "
                                          + "misaddressed, charged when it is delivered again",
                                          allowableValues = {"ON_SIGNING", "BEFORE_DELIVERY", "AFTER_DELIVERY",
                                                  "CANCELLATION_FEE", "FAILED_TRIP_FEE"})
                                  String milestone,
                                  @Schema(description = "The delivery batch it is tied to (17 §4.5); null when none")
                                  Integer deliveryBatchSeq,
                                  BigDecimal amount, BigDecimal paidAmount,
                                  @Schema(description = "What closures of the rest took off it (15 §4.6 step 19)")
                                  BigDecimal reducedAmount, BigDecimal outstanding, String currency,
                                  @Schema(description = "Days after the milestone it falls due; null before delivery")
                                  Integer dueDays,
                                  @Schema(description = "When its milestone came; null while NOT_DUE")
                                  Instant reachedAt,
                                  @Schema(description = "Past it and not paid in full, it is overdue; null before "
                                          + "its milestone and for an installment paid before delivery")
                                  LocalDate dueDate,
                                  @Schema(description = "What the money and the milestone say. Overdue is not a "
                                          + "status: read `overdue`", allowableValues = {"NOT_DUE", "DUE",
                                          "PARTIALLY_PAID", "PAID", "CANCELLED"}) String status,
                                  @Schema(description = "Past its due day and still owing, today in the business "
                                          + "calendar. Worked out when read; an overdue installment paid in part is "
                                          + "PARTIALLY_PAID and overdue")
                                  boolean overdue,
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
