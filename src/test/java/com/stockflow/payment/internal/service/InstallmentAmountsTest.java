package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.api.OrderPaymentAmounts;
import com.stockflow.payment.internal.domain.Installment;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.domain.InstallmentMilestone;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit bo-17: the money of a page of orders, as the order list shows it. Paid and outstanding must be what the order's
 * installments screen says ({@link InstallmentService#standingOf}); overdue is what the installments past their due day
 * still owe on the business day of today - worked out from the due day, as {@link Installment#overdue} does.
 */
class InstallmentAmountsTest {

    /** 2026-11-27 10:00 in Vietnam: the business day is the 27th. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T03:00:00Z"), ZoneOffset.UTC);
    private static final Instant AT = Instant.parse("2026-11-20T03:00:00Z");

    private final InstallmentBook book = mock(InstallmentBook.class);
    private final InstallmentService service = new InstallmentService(book, mock(InstallmentSearch.class),
            mock(InstallmentOrders.class), new CompanyBankProperties("Bank", "0001", "StockFlow"), CLOCK);

    private static Installment installment(UUID order, InstallmentMilestone milestone, long amount, int dueDays) {
        return Installment.open(UUID.randomUUID(), UUID.randomUUID(), order, UUID.randomUUID(), "HD-1", "SO-1", 1,
                milestone.name(), milestone, null, null, BigDecimal.valueOf(amount), "VND", dueDays);
    }

    @Test
    @DisplayName("a page of orders: paid, outstanding and overdue per order from one read; an order without "
            + "installments is no key")
    void pageOfOrders() {
        UUID paidInFull = UUID.randomUUID();
        UUID partlyPaidOverdue = UUID.randomUUID();
        UUID notDueYet = UUID.randomUUID();
        UUID noContract = UUID.randomUUID();

        Installment deposit = installment(paidInFull, InstallmentMilestone.ON_SIGNING, 30_000_000, 3);
        deposit.reach(AT, LocalDate.of(2026, 11, 20));
        deposit.pay(BigDecimal.valueOf(30_000_000), AT);
        Installment afterDelivery = installment(paidInFull, InstallmentMilestone.AFTER_DELIVERY, 70_000_000, 30);

        // Due on the 23rd, 4 million of 10 paid: 6 million overdue on the 27th.
        Installment late = installment(partlyPaidOverdue, InstallmentMilestone.ON_SIGNING, 10_000_000, 3);
        late.reach(AT, LocalDate.of(2026, 11, 20));
        late.pay(BigDecimal.valueOf(4_000_000), AT);
        // Due on the 27th itself: owed, not overdue yet (overdue from the day after the due day).
        Installment dueToday = installment(partlyPaidOverdue, InstallmentMilestone.AFTER_DELIVERY, 5_000_000, 7);
        dueToday.reach(AT, LocalDate.of(2026, 11, 20));

        Installment waiting = installment(notDueYet, InstallmentMilestone.ON_SIGNING, 8_000_000, 30);
        waiting.reach(AT, LocalDate.of(2026, 11, 20));

        when(book.ofOrders(any())).thenReturn(List.of(deposit, afterDelivery, late, dueToday, waiting));

        Map<UUID, OrderPaymentAmounts> amounts = service.amounts(
                List.of(paidInFull, partlyPaidOverdue, notDueYet, noContract));

        verify(book, times(1)).ofOrders(any());
        assertThat(amounts).containsOnlyKeys(paidInFull, partlyPaidOverdue, notDueYet);

        assertThat(amounts.get(paidInFull).paid()).isEqualByComparingTo("30000000");
        assertThat(amounts.get(paidInFull).outstanding()).isEqualByComparingTo("70000000");
        assertThat(amounts.get(paidInFull).overdue()).as("nothing past a due day").isEqualByComparingTo("0");

        assertThat(amounts.get(partlyPaidOverdue).paid()).isEqualByComparingTo("4000000");
        assertThat(amounts.get(partlyPaidOverdue).outstanding()).isEqualByComparingTo("11000000");
        assertThat(amounts.get(partlyPaidOverdue).overdue()).as("what the late one still owes, not its amount")
                .isEqualByComparingTo("6000000");

        assertThat(amounts.get(notDueYet).paid()).isEqualByComparingTo("0");
        assertThat(amounts.get(notDueYet).outstanding()).isEqualByComparingTo("8000000");
        assertThat(amounts.get(notDueYet).overdue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("paid and outstanding are the order's installments screen's: its standing, cancelled ones and all")
    void agreesWithTheStanding() {
        UUID order = UUID.randomUUID();
        Installment deposit = installment(order, InstallmentMilestone.ON_SIGNING, 22_680_000, 3);
        deposit.reach(AT, LocalDate.of(2026, 11, 20));
        deposit.pay(BigDecimal.valueOf(10_000_000), AT);
        Installment rest = installment(order, InstallmentMilestone.AFTER_DELIVERY, 52_920_000, 30);
        deposit.cancel(AT);
        rest.cancel(AT);
        when(book.ofOrders(any())).thenReturn(List.of(deposit, rest));

        OrderPaymentAmounts amounts = service.amounts(List.of(order)).get(order);
        var standing = InstallmentService.standingOf(order, List.of(deposit, rest));

        assertThat(amounts.paid()).isEqualByComparingTo(standing.paid()).isEqualByComparingTo("10000000");
        assertThat(amounts.outstanding()).isEqualByComparingTo(standing.outstanding()).isEqualByComparingTo("0");
        assertThat(amounts.overdue()).as("a cancelled installment owes nothing, past its due day or not")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("no order asked: nothing read")
    void nothingAsked() {
        assertThat(service.amounts(List.of())).isEmpty();
        verify(book, times(0)).ofOrders(any());
    }
}
