package com.stockflow.reporting.internal.controller.dto;

import com.stockflow.common.api.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The receivables report (kltn-docs 15 §4.3; 2026-11-14 plan, BE2-B): a page of customers and the totals of every
 * customer the filter keeps. Every amount is what is still to be paid, as of {@code asOf} - today; the system keeps no
 * ledger of payments, so there is no earlier day to ask for.
 */
@Schema(name = "ReceivablesReport", description = "Receivables per customer aged by due date, as of today")
public record ReceivablesReportResponse(
        @Schema(description = "The business day (Asia/Ho_Chi_Minh) the figures are of: always today") LocalDate asOf,
        PageResponse<Row> rows,
        @Schema(description = "Column sums over every row the filter keeps, not only this page; one per currency")
        List<Totals> totals) {

    @Schema(name = "ReceivableRow", description = "One customer that owes something after delivery")
    public record Row(
            UUID customerId,
            @Schema(description = "The tax code the customer is known by, or KH and part of the id") String customerCode,
            String customerName,
            @Schema(example = "VND") String currency,
            @Schema(description = "Installments after delivery whose goods were delivered, and fee items, still owed: "
                    + "notYetDue + the four overdue buckets") BigDecimal receivable,
            @Schema(description = "Receivable due today or later") BigDecimal notYetDue,
            @Schema(description = "Receivable 1 to 30 days past its due day") BigDecimal overdue1To30,
            BigDecimal overdue31To60,
            BigDecimal overdue61To90,
            @Schema(description = "Receivable more than 90 days past its due day") BigDecimal overdueOver90,
            @Schema(description = "The four overdue buckets together") BigDecimal overdue,
            @Schema(description = "Installments after delivery of signed contracts whose goods are not delivered yet: "
                    + "owed, not yet receivable") BigDecimal committedNotReached,
            @Schema(description = "receivable + committedNotReached: what the credit check of a new contract counts "
                    + "(15 BR-10)") BigDecimal exposure,
            @Schema(description = "Installments due on signing past their due day, still owed. Not receivable "
                    + "(15 §4.3) and in no other figure here - not in overdue, not in exposure - but the credit check of "
                    + "a new contract counts them as overdue, so they are shown apart: a customer the contract check "
                    + "calls overdue never looks clean on this report") BigDecimal signingOverdue,
            @Schema(description = "Null for a customer without approved credit") BigDecimal creditLimit,
            Integer creditTermDays,
            @Schema(description = "max(creditLimit - exposure, 0); null without a limit") BigDecimal available,
            @Schema(description = "The earliest due day among the receivable items") LocalDate oldestDueDate,
            @Schema(description = "How many receivable items are still owing") int openItems) {
    }

    @Schema(name = "ReceivableTotals", description = "Column sums of one currency")
    public record Totals(
            String currency,
            @Schema(description = "How many customers the sums are over") int customers,
            BigDecimal receivable,
            BigDecimal notYetDue,
            BigDecimal overdue1To30,
            BigDecimal overdue31To60,
            BigDecimal overdue61To90,
            BigDecimal overdueOver90,
            BigDecimal overdue,
            BigDecimal committedNotReached,
            BigDecimal exposure,
            @Schema(description = "Installments due on signing past their due day: apart from the other sums")
            BigDecimal signingOverdue) {
    }
}
