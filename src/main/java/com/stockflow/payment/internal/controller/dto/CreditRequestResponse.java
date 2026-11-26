package com.stockflow.payment.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A credit request as a screen shows it (QĐ-A, 2026-11-26). For a portal customer Sales' opinion, who in the back office
 * acted and the review are left out (null): they are the business's own deliberation. The accountant's note - the
 * reason of a rejection - is shown to the customer.
 *
 * @param files  the documents kept with it; null on a list row
 * @param review what the accountant weighs it on (plan §4.4), read now; null on a list row and for a portal customer
 */
@Schema(description = "A credit request")
public record CreditRequestResponse(
        UUID id,
        UUID customerId,
        String customerName,
        String customerCode,
        @Schema(description = "PORTAL (the customer asked) or SALES (Sales asked for them)") String source,
        @Schema(description = "SUBMITTED, PENDING_APPROVAL, APPROVED, REJECTED or WITHDRAWN") String status,
        BigDecimal requestedLimit,
        int requestedTermDays,
        String currency,
        String reason,
        UUID submittedBy,
        String submittedByName,
        Instant submittedAt,
        String salesNote,
        BigDecimal salesRecommendedLimit,
        UUID recommendedBy,
        String recommendedByName,
        Instant recommendedAt,
        BigDecimal approvedLimit,
        Integer approvedTermDays,
        UUID decidedBy,
        String decidedByName,
        @Schema(description = "When it was decided, or withdrawn") Instant decidedAt,
        @Schema(description = "The accountant's note; the reason of a rejection") String decisionNote,
        long version,
        List<File> files,
        Review review
) {

    /** A document kept with the request; the bytes from {@code GET .../files/{fileId}/download-url}. */
    public record File(UUID id, String fileName, String contentType, long sizeBytes, UUID uploadedBy,
                       Instant uploadedAt) {
    }

    /**
     * How the customer has paid and what they owe now (plan §4.4), with the warnings the review raises - warnings
     * only, the accountant decides.
     *
     * @param onTimeRatio        on-time installments over paid ones, 0-1 to four places; null when none was paid
     * @param averageDaysLate    over the installments paid late; null when none was
     * @param creditTermsVersion the version of the customer's credit, -1 while they have none: send it back as
     *                           {@code creditTermsVersion} to approve
     * @param warnings           SHORT_HISTORY, ABOVE_SUGGESTED, OVERDUE
     */
    public record Review(int settledContracts, int paidInstallments, int onTimeInstallments, BigDecimal onTimeRatio,
                         BigDecimal averageDaysLate, BigDecimal paidLast12Months, BigDecimal outstanding,
                         boolean hasOverdue, BigDecimal overdueAmount, LocalDate oldestOverdueDueDate,
                         BigDecimal currentCreditLimit, Integer currentCreditTermDays, long creditTermsVersion,
                         BigDecimal suggestedLimit, List<String> warnings, int minSettledContracts,
                         int suggestedMonths) {
    }
}
