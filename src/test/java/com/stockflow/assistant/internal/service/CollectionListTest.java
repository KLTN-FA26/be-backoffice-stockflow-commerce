package com.stockflow.assistant.internal.service;

import com.stockflow.common.i18n.Messages;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerCredits;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.CustomerDocuments;
import com.stockflow.payment.api.CustomerReceivable;
import com.stockflow.payment.api.InstallmentsToCollect;
import com.stockflow.payment.api.PayableInstallment;
import com.stockflow.payment.api.Receivables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The collections list (work features 2.2): the priority's points, and the list's one read per kind of fact, its
 * ranking, its salesperson and its bound.
 */
class CollectionListTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final UUID ABC = UUID.randomUUID();
    private static final UUID XYZ = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();

    private final InstallmentsToCollect toCollect = mock(InstallmentsToCollect.class);
    private final Receivables receivables = mock(Receivables.class);
    private final CustomerCredits credits = mock(CustomerCredits.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final CustomerDocuments documents = mock(CustomerDocuments.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final CollectionList list = new CollectionList(toCollect, receivables, credits, customers, documents,
            identity, new Messages(new StaticMessageSource()));

    private static PayableInstallment installment(UUID customer, UUID contract, String number, long outstanding,
                                                  LocalDate due, int daysOverdue) {
        return new PayableInstallment(UUID.randomUUID(), customer, contract, number, UUID.randomUUID(),
                "SO-" + number.substring(3, 18), 1, "Đợt 1", "AFTER_DELIVERY", false, BigDecimal.valueOf(outstanding),
                BigDecimal.valueOf(outstanding), "VND", due, "DUE", daysOverdue > 0, daysOverdue,
                number + " DOT1");
    }

    private static CustomerReceivable owed(UUID customer, long receivable, long overdue, long signingOverdue,
                                           long committed) {
        return new CustomerReceivable(customer, "VND", BigDecimal.valueOf(receivable),
                BigDecimal.valueOf(receivable - overdue), BigDecimal.valueOf(overdue), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(committed), BigDecimal.valueOf(signingOverdue),
                null, 1);
    }

    @BeforeEach
    void names() {
        when(customers.labels(any())).thenReturn(Map.of(ABC, new CustomerLabel(ABC, "Công ty ABC", "0312345678"),
                XYZ, new CustomerLabel(XYZ, "Công ty XYZ", "0109876543")));
        when(credits.creditTermsOf(any())).thenReturn(Map.of());
        when(receivables.receivables(any(), eq(null))).thenReturn(List.of());
        when(documents.contractSalespeople(any())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("points: days overdue in steps, a large amount, other overdue money, a spent credit limit")
    void priority() {
        UUID contract = UUID.randomUUID();
        PayableInstallment late45 = installment(ABC, contract, "HD-20261001-000001-1", 60_000_000,
                TODAY.minusDays(45), 45);
        PayableInstallment soon = installment(ABC, contract, "HD-20261001-000001-1", 300_000_000, TODAY.plusDays(5), 0);
        PayableInstallment late3 = installment(ABC, contract, "HD-20261001-000001-1", 10_000_000, TODAY.minusDays(3),
                3);

        CollectionPriority.Rank high = CollectionPriority.of(late45, CollectionPriority.Customer.UNKNOWN);
        CollectionPriority.Rank low = CollectionPriority.of(soon, CollectionPriority.Customer.UNKNOWN);
        CollectionPriority.Rank crowded = CollectionPriority.of(late3, new CollectionPriority.Customer(
                BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(900_000_000), BigDecimal.valueOf(500_000_000)));

        assertThat(high.points()).isEqualTo(40 + 10);
        assertThat(high.level()).isEqualTo(CollectionPriority.Level.HIGH);
        assertThat(high.why()).containsExactly(CollectionPriority.Why.OVERDUE, CollectionPriority.Why.LARGE_AMOUNT);
        assertThat(low.points()).isEqualTo(20);
        assertThat(low.level()).isEqualTo(CollectionPriority.Level.LOW);
        assertThat(low.why()).containsExactly(CollectionPriority.Why.DUE_SOON, CollectionPriority.Why.LARGE_AMOUNT);
        assertThat(crowded.points()).isEqualTo(20 + 15 + 15);
        assertThat(crowded.level()).isEqualTo(CollectionPriority.Level.HIGH);
        assertThat(crowded.why()).containsExactly(CollectionPriority.Why.OVERDUE, CollectionPriority.Why.OTHER_OVERDUE,
                CollectionPriority.Why.OVER_CREDIT_LIMIT);
        assertThat(CollectionPriority.of(late3, CollectionPriority.Customer.UNKNOWN).level())
                .isEqualTo(CollectionPriority.Level.LOW);
    }

    @Test
    @DisplayName("ranked by points: a customer over its limit with other overdue money comes before a larger, "
            + "fresher debt; the salesperson is the contract's, by name")
    void ranked() {
        UUID abcContract = UUID.randomUUID();
        UUID xyzContract = UUID.randomUUID();
        PayableInstallment abcLate = installment(ABC, abcContract, "HD-20261001-000001-1", 20_000_000,
                TODAY.minusDays(10), 10);
        PayableInstallment xyzLate = installment(XYZ, xyzContract, "HD-20261002-000002-1", 40_000_000,
                TODAY.minusDays(12), 12);
        PayableInstallment xyzSoon = installment(XYZ, xyzContract, "HD-20261002-000002-1", 45_000_000,
                TODAY.plusDays(2), 0);
        when(toCollect.dueWithin(CollectionList.DUE_WITHIN_DAYS, CollectionList.READ))
                .thenReturn(new InstallmentsToCollect.Page(TODAY, List.of(xyzLate, abcLate, xyzSoon), 3));
        // ABC: 20M overdue here and 15M more on another installment; owes 700M against a 500M limit.
        when(receivables.receivables(TODAY, null)).thenReturn(List.of(owed(ABC, 600_000_000, 35_000_000, 0,
                100_000_000), owed(XYZ, 40_000_000, 40_000_000, 0, 0)));
        when(credits.creditTermsOf(any())).thenReturn(Map.of(ABC, new CreditTerms(ABC, true,
                BigDecimal.valueOf(500_000_000), 30, "VND", null, Instant.now(), null, 1)));
        when(documents.contractSalespeople(any())).thenReturn(Map.of(abcContract, SALES));
        when(identity.displayNames(any())).thenReturn(Map.of(SALES, "Nguyễn Văn Sale"));

        CollectionList.Result result = list.list();

        assertThat(result.items()).extracting(item -> item.installment().id())
                .containsExactly(abcLate.id(), xyzLate.id(), xyzSoon.id());
        CollectionList.Item first = result.items().getFirst();
        assertThat(first.points()).isEqualTo(30 + 15 + 15);
        assertThat(first.priority()).isEqualTo("HIGH");
        assertThat(first.customerName()).isEqualTo("Công ty ABC");
        assertThat(first.salespersonId()).isEqualTo(SALES);
        assertThat(first.salespersonName()).isEqualTo("Nguyễn Văn Sale");
        assertThat(first.why()).containsExactly("Overdue by 10 days (due 17/11/2026)",
                "The customer has 15.000.000 VND overdue on other installments",
                "The customer owes 700.000.000 VND, over its credit limit of 500.000.000 VND");
        // XYZ's 40M is its only overdue money: nothing "other" about it.
        assertThat(result.items().get(1).why()).containsExactly("Overdue by 12 days (due 15/11/2026)");
        assertThat(result.items().get(1).salespersonId()).isNull();
        // Not yet due, but its customer owes XYZ's overdue 40M elsewhere: worth the same call.
        assertThat(result.items().get(2).why()).containsExactly("Due in 2 days (29/11/2026)",
                "The customer has 40.000.000 VND overdue on other installments");
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("at most fifty shown, with how many there are")
    void bounded() {
        List<PayableInstallment> many = java.util.stream.IntStream.range(0, 60).mapToObj(n -> installment(ABC,
                UUID.randomUUID(), "HD-20261001-%06d-1".formatted(n), 1_000_000 + n, TODAY.minusDays(1), 1)).toList();
        when(toCollect.dueWithin(anyInt(), anyInt())).thenReturn(new InstallmentsToCollect.Page(TODAY, many, 75));

        CollectionList.Result result = list.list();

        assertThat(result.items()).hasSize(CollectionList.MAX_ITEMS);
        assertThat(result.total()).isEqualTo(75);
        assertThat(result.truncated()).isTrue();
        // The largest of equally late first.
        assertThat(result.items().getFirst().installment().outstanding()).isEqualByComparingTo("1000059");
        verify(customers).labels(any());
        verify(documents).contractSalespeople(any());
    }

    @Test
    @DisplayName("nothing to chase reads nothing else")
    void empty() {
        when(toCollect.dueWithin(anyInt(), anyInt())).thenReturn(new InstallmentsToCollect.Page(TODAY, List.of(), 0));

        CollectionList.Result result = list.list();

        assertThat(result.items()).isEmpty();
        assertThat(result.truncated()).isFalse();
        verifyNoInteractions(receivables, credits, documents, identity);
    }
}
