package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.internal.domain.InstallmentBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The port other modules read payment through: a customer's credit position (14 BR-06, 15 BR-10). Since 4B-10 it is the
 * installments of the customer's signed contracts alone - the money ledger's receivables went with the ledger.
 */
class PaymentServiceImplTest {

    private final InstallmentBook installments = mock(InstallmentBook.class);
    private final PaymentServiceImpl service = new PaymentServiceImpl(new CompanyBankProperties(null, null, null),
            installments, mock(InstallmentService.class), mock(OrderSettlementService.class));
    private final UUID id = UUID.randomUUID();

    @Test
    @DisplayName("4B-6 (15 BR-10): the installments paid after delivery count, not yet due too; one overdue counts")
    void creditPositionCountsInstallments() {
        when(installments.afterDeliveryUnpaid(id)).thenReturn(new BigDecimal("6000000"));
        when(installments.overdue(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any())).thenReturn(new InstallmentBook.Overdue(new BigDecimal("2000000"),
                LocalDate.of(2026, 10, 15)));

        CreditPosition position = service.creditPosition(id);

        assertThat(position.outstandingReceivables()).isEqualByComparingTo("6000000");
        assertThat(position.hasOverdue()).isTrue();
        assertThat(position.overdueAmount()).isEqualByComparingTo("2000000");
        assertThat(position.oldestOverdueDueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    @DisplayName("credit position of a customer with nothing owed: zero and not overdue")
    void creditPositionNoDebt() {
        when(installments.afterDeliveryUnpaid(id)).thenReturn(BigDecimal.ZERO);
        when(installments.overdue(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any())).thenReturn(new InstallmentBook.Overdue(BigDecimal.ZERO, null));

        CreditPosition position = service.creditPosition(id);

        assertThat(position.outstandingReceivables()).isEqualByComparingTo("0");
        assertThat(position.hasOverdue()).isFalse();
        assertThat(position.overdueAmount()).isEqualByComparingTo("0");
        assertThat(position.oldestOverdueDueDate()).isNull();
    }

    @Test
    @DisplayName("owed but nothing overdue: the amount counts, the flag stays off")
    void owedNotOverdue() {
        when(installments.afterDeliveryUnpaid(id)).thenReturn(new BigDecimal("3000000"));
        when(installments.overdue(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any())).thenReturn(new InstallmentBook.Overdue(BigDecimal.ZERO, null));

        CreditPosition position = service.creditPosition(id);

        assertThat(position.outstandingReceivables()).isEqualByComparingTo("3000000");
        assertThat(position.hasOverdue()).isFalse();
    }
}
