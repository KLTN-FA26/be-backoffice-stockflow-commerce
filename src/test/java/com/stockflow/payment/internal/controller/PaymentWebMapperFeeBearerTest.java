package com.stockflow.payment.internal.controller;

import com.stockflow.payment.api.FeeBearer;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.domain.OrderSettlement;
import com.stockflow.payment.internal.domain.RefundStanding;
import com.stockflow.payment.internal.service.RefundService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D50: the accountant reads the fee the company bears ("Phí do công ty chịu") with its bearer and its terms; the
 * customer, who was charged nothing for it, reads none of it.
 */
class PaymentWebMapperFeeBearerTest {

    private static final Instant AT = Instant.parse("2026-11-28T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 28);

    private static OrderSettlement companyBearsShare() {
        UUID orderId = UUID.randomUUID();
        Installment onSigning = Installment.open(UUID.randomUUID(), UUID.randomUUID(), orderId, UUID.randomUUID(),
                "HD-1", "SO-1", 1, "On signing", InstallmentMilestone.ON_SIGNING, null, null, new BigDecimal("1000000"),
                "VND", 0);
        onSigning.reach(AT, DAY);
        onSigning.pay(new BigDecimal("1000000"), AT);
        return OrderSettlement.cancel(List.of(onSigning),
                new OrderSettlement.Fee(new BigDecimal("10"), null, null, null, FeeBearer.COMPANY), UUID.randomUUID(), AT,
                DAY).settlement();
    }

    private static RefundService.OrderRefundDetail detailOf(OrderSettlement settlement) {
        return new RefundService.OrderRefundDetail(RefundStanding.of(settlement.orderId(), List.of(settlement), List.of()),
                "SO-1", "VND", List.of(settlement), List.of(), BigDecimal.ZERO);
    }

    @Test
    @DisplayName("staff read the bearer, the company's cost and the total of it; the customer reads none of them, nor "
            + "the share kept")
    void customerNeverReadsAFeeTheCompanyBears() {
        OrderSettlement settlement = companyBearsShare();

        var staff = PaymentWebMapper.toResponse(detailOf(settlement), false, java.util.Map.of(), null);
        assertThat(staff.feeBorneByCompany()).isEqualByComparingTo("100000");
        assertThat(staff.settlements()).singleElement().satisfies(s -> {
            assertThat(s.feeBearer()).isEqualTo("COMPANY");
            assertThat(s.feeBorneByCompany()).isEqualByComparingTo("100000");
            assertThat(s.fee()).as("what the customer is charged").isEqualByComparingTo("0");
            assertThat(s.feePercent()).isEqualByComparingTo("10");
            assertThat(s.refundable()).isEqualByComparingTo("1000000");
        });

        var customer = PaymentWebMapper.toResponse(detailOf(settlement), true);
        assertThat(customer.feeBorneByCompany()).isNull();
        assertThat(customer.settlements()).singleElement().satisfies(s -> {
            assertThat(s.feeBearer()).isNull();
            assertThat(s.feeBorneByCompany()).isNull();
            assertThat(s.feePercent()).isNull();
            assertThat(s.fee()).isEqualByComparingTo("0");
            assertThat(s.refundable()).isEqualByComparingTo("1000000");
        });
    }
}
