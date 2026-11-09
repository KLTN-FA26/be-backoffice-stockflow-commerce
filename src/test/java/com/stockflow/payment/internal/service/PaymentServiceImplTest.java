package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.OrderLedger;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.ReceivableBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The port other modules read payment through: a customer's credit position (14 BR-06) and an order's ledger. */
class PaymentServiceImplTest {

    private final ReceivableBook book = mock(ReceivableBook.class);
    private final InstallmentBook installments = mock(InstallmentBook.class);
    private final PaymentServiceImpl service = new PaymentServiceImpl(book, new CompanyBankProperties(null, null, null),
            installments, mock(InstallmentService.class));
    private final UUID id = UUID.randomUUID();

    @org.junit.jupiter.api.BeforeEach
    void noInstallments() {
        when(installments.afterDeliveryUnpaid(id)).thenReturn(BigDecimal.ZERO);
        when(installments.overdue(id)).thenReturn(new InstallmentBook.Overdue(BigDecimal.ZERO, null));
    }

    @Test
    @DisplayName("4B-6 (15 BR-10): the installments paid after delivery count, not yet due too; one overdue counts")
    void creditPositionCountsInstallments() {
        when(book.outstanding(id)).thenReturn(new BigDecimal("1000000"));
        when(book.overdue(id)).thenReturn(new ReceivableBook.Overdue(BigDecimal.ZERO, null));
        when(installments.afterDeliveryUnpaid(id)).thenReturn(new BigDecimal("6000000"));
        when(installments.overdue(id)).thenReturn(new InstallmentBook.Overdue(new BigDecimal("2000000"),
                LocalDate.of(2026, 10, 15)));

        CreditPosition position = service.creditPosition(id);

        assertThat(position.outstandingReceivables()).isEqualByComparingTo("7000000");
        assertThat(position.hasOverdue()).as("an overdue installment, though no receivable is").isTrue();
        assertThat(position.overdueAmount()).isEqualByComparingTo("2000000");
        assertThat(position.oldestOverdueDueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    @DisplayName("4B-6: the oldest overdue day is the earlier of a receivable's and an installment's")
    void oldestOverdueOfBoth() {
        when(book.outstanding(id)).thenReturn(BigDecimal.ZERO);
        when(book.hasOverdue(id)).thenReturn(true);
        when(book.overdue(id)).thenReturn(new ReceivableBook.Overdue(new BigDecimal("500"), LocalDate.of(2026, 10, 20)));
        when(installments.overdue(id)).thenReturn(new InstallmentBook.Overdue(new BigDecimal("700"),
                LocalDate.of(2026, 10, 1)));

        CreditPosition position = service.creditPosition(id);

        assertThat(position.overdueAmount()).isEqualByComparingTo("1200");
        assertThat(position.oldestOverdueDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("credit position: what the customer owes on their own receivables, and whether any is overdue")
    void creditPosition() {
        when(book.outstanding(id)).thenReturn(new BigDecimal("4500000"));
        when(book.hasOverdue(id)).thenReturn(true);
        when(book.overdue(id)).thenReturn(new ReceivableBook.Overdue(new BigDecimal("1500000"), LocalDate.of(2026, 9, 30)));

        CreditPosition position = service.creditPosition(id);

        assertThat(position.outstandingReceivables()).isEqualByComparingTo("4500000");
        assertThat(position.hasOverdue()).isTrue();
        assertThat(position.overdueAmount()).isEqualByComparingTo("1500000");
        assertThat(position.oldestOverdueDueDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("credit position of a customer with no debt: zero and not overdue")
    void creditPositionNoDebt() {
        when(book.outstanding(id)).thenReturn(BigDecimal.ZERO);
        when(book.overdue(id)).thenReturn(new ReceivableBook.Overdue(BigDecimal.ZERO, null));
        CreditPosition position = service.creditPosition(id);
        assertThat(position.outstandingReceivables()).isEqualByComparingTo("0");
        assertThat(position.hasOverdue()).isFalse();
    }

    @Test
    @DisplayName("order ledger: collected, and paidAhead = the part not paid on receivables (money paid to the order itself)")
    void ledgerSplitsReceivableMoney() {
        when(book.collectedFor(id)).thenReturn(new BigDecimal("1000"));
        when(book.collectedOnReceivablesOf(id)).thenReturn(new BigDecimal("400"));

        OrderLedger ledger = service.ledgerOf(id);

        assertThat(ledger.collected()).isEqualByComparingTo("1000");
        assertThat(ledger.paidAhead()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("order ledger: never negative when receivables took more than the net collected (a reversal lag)")
    void ledgerNeverNegative() {
        when(book.collectedFor(id)).thenReturn(new BigDecimal("100"));
        when(book.collectedOnReceivablesOf(id)).thenReturn(new BigDecimal("150"));
        assertThat(service.ledgerOf(id).paidAhead()).isEqualByComparingTo("0");
    }
}
