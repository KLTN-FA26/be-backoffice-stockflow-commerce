package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port: the back office's list of contracts and its tiles - one statement for a page, one for its total, one for the
 * summary, whatever the number of rows (the buyer's name, the order's number and total are read in the same join).
 */
public interface SalesContractList {

    /**
     * @param statuses      none = every status
     * @param salesOwnerId  the Sales owner the contract recorded when it was drawn up (14 BR-26); null = anyone's
     * @param signByBefore  contracts waiting for the customer whose deadline is before this day
     * @param termsReview   only those in this state; null = any
     * @param search        a part of the contract's or the order's number, or of the buyer's name
     * @param today         the business day "overdue to sign" is judged on
     */
    record Query(Set<SalesContractStatus> statuses, UUID salesOwnerId, LocalDate signByBefore, TermsReview termsReview,
                 String search, LocalDate today, int page, int size) {
    }

    record Row(UUID orderId, UUID contractId, String contractNumber, String orderNumber, UUID customerId,
               String customerName, SalesContractStatus status, int revision, BigDecimal totalAmount, Instant sentAt,
               LocalDate signBy, boolean overdueToSign, Instant customerSignedAt, Instant signedAt,
               TermsReview termsReview, String creditReview, UUID salesOwnerId) {
    }

    record Page(List<Row> rows, long total) {
    }

    /**
     * @param byStatus                 how many contracts in each status (statuses with none are absent)
     * @param awaitingCompanySignature CUSTOMER_SIGNED: the customer has signed and the company has not
     * @param overdueToSign            SENT past the signing deadline
     * @param termsReviewPending       drafts waiting for a Sales Manager to approve their terms
     */
    record Summary(Map<SalesContractStatus, Long> byStatus, long awaitingCompanySignature, long overdueToSign,
                   long termsReviewPending) {
    }

    Page page(Query query);

    Summary summary(LocalDate today);
}
