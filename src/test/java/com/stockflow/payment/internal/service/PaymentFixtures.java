package com.stockflow.payment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.payment.api.PayableOrder;
import com.stockflow.payment.internal.domain.CustomerTransfer;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Builders shared by the payment service unit tests. */
final class PaymentFixtures {

    static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private PaymentFixtures() {
    }

    static PayableOrder order(UUID id, UUID customerId, String status, String term, String billable) {
        return new PayableOrder(id, "SO-" + id.toString().substring(0, 4), customerId, status, term, null, "VND",
                new BigDecimal(billable), null, BigDecimal.ZERO, null, true);
    }

    static CustomerTransfer transfer(UUID customerId, String amount) {
        return CustomerTransfer.record(customerId, "FT-" + UUID.randomUUID(), new BigDecimal(amount), "VND", TODAY,
                UUID.randomUUID(), NOW, null);
    }

    static Receivable receivable(UUID customerId, String amount, LocalDate due) {
        return Receivable.open(UUID.randomUUID(), customerId, new BigDecimal(amount), "VND", NOW, due);
    }

    static ReceivableBook.Allocation allocation(UUID transferId, UUID orderId, UUID receivableId, String amount) {
        return new ReceivableBook.Allocation(UUID.randomUUID(), transferId, orderId, receivableId,
                new BigDecimal(amount), NOW, UUID.randomUUID(), null, null);
    }

    static ErrorCode codeOf(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).as("a BusinessException").isNotNull();
        return thrown.errorCode();
    }
}
