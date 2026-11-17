package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
import com.stockflow.payment.internal.domain.ReceiptFilter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The receipts of a filter as the accountant's export lays them out (2026-11-14 plan, BE2-A; kltn-docs 15 BR-06): the
 * receipts, and apart from them every line with the installment it paid. Read as flat rows, two statements, rather than
 * as pages of aggregates: an export is every row at once, in the order of the bank statement.
 */
public interface ReceiptExportSearch {

    /** A receipt as recorded, and its void when voided. */
    record ExportedReceipt(UUID id, UUID customerId, String reference, LocalDate receivedOn, BigDecimal amount,
                           String currency, String note, PaymentReceiptStatus status, UUID recordedBy,
                           Instant recordedAt, UUID voidedBy, Instant voidedAt, String voidReason) {
    }

    /** One line of a receipt, with the contract, the order and the installment it paid. */
    record ExportedLine(String reference, LocalDate receivedOn, PaymentReceiptStatus receiptStatus,
                        String contractNumber, String orderNumber, int seq, String installmentName, String milestone,
                        BigDecimal amount, String currency) {
    }

    /** The receipts {@code filter} matches, by day received, then recorded; at most {@code limit}. */
    List<ExportedReceipt> receipts(ReceiptFilter filter, int limit);

    /** The lines of those receipts, in the same order, each receipt's in the order recorded; at most {@code limit}. */
    List<ExportedLine> lines(ReceiptFilter filter, int limit);
}
