package com.stockflow.customer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The payment schedule a customer's new sales contract starts from (kltn-docs 18 §3 "lịch thanh toán mặc định", 14 §4.3
 * step 12). Its installments' shares of the contract's total with tax add up to 100. A signed contract keeps its own
 * schedule whatever this one becomes (15 BR-12).
 *
 * @param configured  false for a customer nobody set one for: then it follows their commercial terms - a deposit
 *                    customer's deposit share on signing and the rest before delivery, anyone else everything on
 *                    signing (never credit nobody approved)
 * @param approvedBy  the accountant who approved its AFTER_DELIVERY installments (18 BR-03, 15 BR-12); null when it
 *                    has none, or while Sales' proposal of one awaits approval ({@link #awaitingApproval()})
 * @param version     the version to send back when changing it; -1 when not configured
 */
public record DefaultPaymentSchedule(UUID customerId, boolean configured, List<Installment> installments, UUID setBy,
                                     Instant setAt, UUID approvedBy, Instant approvedAt, String note, long version) {

    /**
     * One installment.
     *
     * @param percent share of the total with tax, two decimals at most
     * @param dueDays days after the milestone it falls due
     */
    public record Installment(int seq, String name, BigDecimal percent, PaymentMilestone milestone, int dueDays) { }

    public DefaultPaymentSchedule {
        installments = List.copyOf(installments);
    }

    /** Whether any installment falls due after delivery (credit). */
    public boolean paysAfterDelivery() {
        return installments.stream().anyMatch(i -> i.milestone() == PaymentMilestone.AFTER_DELIVERY);
    }

    /** It pays after delivery and accounting has not approved that yet: a contract is not drafted on it (15 BR-12). */
    public boolean awaitingApproval() {
        return paysAfterDelivery() && approvedBy == null;
    }
}
