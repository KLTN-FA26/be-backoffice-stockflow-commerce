package com.stockflow.customer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The payment schedule a customer's new sales contract starts from (kltn-docs 18 §3 "lịch thanh toán mặc định", 14 §4.3
 * step 12). Its instalments' shares of the contract's total with tax add up to 100. A signed contract keeps its own
 * schedule whatever this one becomes (15 BR-12).
 *
 * @param configured  false for a customer nobody set one for: then a single ON_SIGNING instalment of 100%
 * @param approvedBy  the accountant who approved an AFTER_DELIVERY instalment (18 BR-03); null otherwise
 * @param version     the version to send back when changing it; -1 when not configured
 */
public record DefaultPaymentSchedule(UUID customerId, boolean configured, List<Installment> installments, UUID setBy,
                                     Instant setAt, UUID approvedBy, Instant approvedAt, String note, long version) {

    /**
     * One instalment.
     *
     * @param percent share of the total with tax, two decimals at most
     * @param dueDays days after the milestone it falls due
     */
    public record Installment(int seq, String name, BigDecimal percent, PaymentMilestone milestone, int dueDays) { }

    public DefaultPaymentSchedule {
        installments = List.copyOf(installments);
    }

    /** Nothing set: everything at signing. */
    public static DefaultPaymentSchedule notConfigured(UUID customerId) {
        return new DefaultPaymentSchedule(customerId, false, List.of(new Installment(1, "Thanh toán khi ký hợp đồng",
                new BigDecimal("100.00"), PaymentMilestone.ON_SIGNING, 0)), null, null, null, null, null, -1L);
    }

    /** Whether any instalment falls due after delivery (credit). */
    public boolean paysAfterDelivery() {
        return installments.stream().anyMatch(i -> i.milestone() == PaymentMilestone.AFTER_DELIVERY);
    }
}
