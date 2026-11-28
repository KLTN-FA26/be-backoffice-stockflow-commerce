package com.stockflow.order.internal.controller;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.CancellationFeeBearer;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.controller.dto.CancellationFeeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * D46, D50: Sales choose who bears every fee and nothing is preselected - a fee without {@code feeBearer} is a 400, so is
 * a bearer with no fee - and the customer never reads a fee the company bears.
 */
class OrderWebMapperFeeBearerTest {

    private static final CancellationFeeRequest FEE = new CancellationFeeRequest(new BigDecimal("500000"),
            "Cốc đã in theo thiết kế", "In 1.000 cốc trước khi huỷ");

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("an agreed fee carries the bearer Sales chose")
    void feeCarriesItsBearer() {
        assertThat(OrderWebMapper.toFee(FEE, CancellationFeeBearer.COMPANY, false).bearer())
                .isEqualTo(CancellationFeeBearer.COMPANY);
        assertThat(OrderWebMapper.toFee(FEE, CancellationFeeBearer.CUSTOMER, false).bearer())
                .isEqualTo(CancellationFeeBearer.CUSTOMER);
        assertThat(OrderWebMapper.toFee(null, null, false)).as("no fee, no bearer").isNull();
    }

    @Test
    @DisplayName("a fee without its bearer is refused (400, no default); so is a bearer with no fee")
    void bearerIsRequiredWithAFee() {
        assertThat(code(catchThrowable(() -> OrderWebMapper.toFee(FEE, null, false))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> OrderWebMapper.toFee(null, CancellationFeeBearer.COMPANY, false))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        // a share has no object of its own: its bearer may stand alone when a share is kept
        assertThat(OrderWebMapper.toFee(null, CancellationFeeBearer.COMPANY, true)).isNull();
        assertThat(OrderWebMapper.keepsShare(BigDecimal.ZERO)).isFalse();
        assertThat(OrderWebMapper.keepsShare(new BigDecimal("20"))).isTrue();
    }

    private static OrderSummary cancelledWithFeeBorneBy(CancellationFeeBearer bearer) {
        return new OrderSummary(UUID.randomUUID(), "SO-1", UUID.randomUUID(), OrderStatus.CANCELLED, Money.vnd(1_000),
                List.of(), Instant.now(), null, null, null, null, null, null, null, null, null, null,
                new OrderSummary.Cancellation(CancellationReasonCode.OUT_OF_STOCK, "OUT_OF_STOCK",
                        BigDecimal.ZERO, "Cốc đã in", "Chi tiết thoả thuận", new BigDecimal("500000"), bearer));
    }

    @Test
    @DisplayName("staff read the fee with its bearer; a customer reads no fee the company bears, and no bearer at all")
    void customerNeverReadsAFeeTheCompanyBears() {
        var staff = OrderWebMapper.toResponse(cancelledWithFeeBorneBy(CancellationFeeBearer.COMPANY), true)
                .cancellation();
        assertThat(staff.feeBearer()).isEqualTo("COMPANY");
        assertThat(staff.feeAmount()).isEqualByComparingTo("500000");
        assertThat(staff.feeReason()).isEqualTo("Cốc đã in");

        var customer = OrderWebMapper.toResponse(cancelledWithFeeBorneBy(CancellationFeeBearer.COMPANY), false)
                .cancellation();
        assertThat(customer.feeBearer()).isNull();
        assertThat(customer.feeAmount()).isNull();
        assertThat(customer.feeReason()).isNull();
        assertThat(customer.feeDetail()).isNull();

        // a fee the customer bears is shown to them as before
        var charged = OrderWebMapper.toResponse(cancelledWithFeeBorneBy(CancellationFeeBearer.CUSTOMER), false)
                .cancellation();
        assertThat(charged.feeAmount()).isEqualByComparingTo("500000");
        assertThat(charged.feeBearer()).isNull();
    }
}
