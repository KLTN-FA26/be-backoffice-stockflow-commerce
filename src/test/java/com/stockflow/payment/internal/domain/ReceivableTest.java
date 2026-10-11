package com.stockflow.payment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-431, kltn-docs 15 §5.3: a receivable's money and status, no Spring. */
class ReceivableTest {

    private static final Instant NOW = Instant.parse("2026-10-10T03:00:00Z");
    private static final LocalDate DUE = LocalDate.of(2026, 11, 9);

    private static Receivable owed(String amount) {
        return Receivable.open(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount), "VND", NOW, DUE);
    }

    @Test
    @DisplayName("a part payment leaves it PARTIALLY_PAID; the rest settles it PAID; more is never taken")
    void allocation() {
        Receivable receivable = owed("12000000");

        assertThat(receivable.allocate(new BigDecimal("5000000"), NOW)).isEqualByComparingTo("5000000");
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PARTIALLY_PAID);
        assertThat(receivable.outstanding()).isEqualByComparingTo("7000000");

        assertThat(receivable.allocate(new BigDecimal("10000000"), NOW)).isEqualByComparingTo("7000000");
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PAID);
        assertThat(receivable.settledAt()).isEqualTo(NOW);
        assertThat(receivable.allocate(new BigDecimal("1"), NOW)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("OVERDUE only after the due date, and it stays OVERDUE until paid in full")
    void overdue() {
        Receivable receivable = owed("12000000");
        assertThat(receivable.markOverdue(DUE)).isFalse();
        assertThat(receivable.markOverdue(DUE.plusDays(1))).isTrue();
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.OVERDUE);

        receivable.allocate(new BigDecimal("2000000"), NOW);
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.OVERDUE);
        receivable.allocate(new BigDecimal("10000000"), NOW);
        assertThat(receivable.status()).isEqualTo(ReceivableStatus.PAID);
        assertThat(receivable.markOverdue(DUE.plusDays(30))).isFalse();
    }

    @Test
    @DisplayName("a transfer gives what it still holds; it needs a reference and money")
    void transfer() {
        CustomerTransfer transfer = CustomerTransfer.record(UUID.randomUUID(), " FT123 ", new BigDecimal("10000000"),
                "VND", LocalDate.of(2026, 10, 10), UUID.randomUUID(), NOW, null);
        assertThat(transfer.reference()).isEqualTo("FT123");
        assertThat(transfer.take(new BigDecimal("7000000"))).isEqualByComparingTo("7000000");
        assertThat(transfer.take(new BigDecimal("7000000"))).isEqualByComparingTo("3000000");
        assertThat(transfer.unallocatedAmount()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> CustomerTransfer.record(UUID.randomUUID(), " ", BigDecimal.TEN, "VND",
                LocalDate.of(2026, 10, 10), UUID.randomUUID(), NOW, null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> CustomerTransfer.record(UUID.randomUUID(), "FT9", BigDecimal.ZERO, "VND",
                LocalDate.of(2026, 10, 10), UUID.randomUUID(), NOW, null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
