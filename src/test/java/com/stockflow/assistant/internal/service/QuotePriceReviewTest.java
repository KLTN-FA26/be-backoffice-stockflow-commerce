package com.stockflow.assistant.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.CustomerSalesHistory;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.product.api.CustomerSkus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quote price review (contract §1.4): each line against the customer's price, the standard price and the last
 * price sold, at the proposed cartons; the credit only for a manager who may read it.
 */
class QuotePriceReviewTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID QUOTE = UUID.randomUUID();

    private final QuoteCards quotes = mock(QuoteCards.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private final CustomerSkus customerSkus = mock(CustomerSkus.class);
    private final CustomerSalesHistory history = mock(CustomerSalesHistory.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final QuotePriceReview review = new QuotePriceReview(quotes, customers, catalog, customerSkus, history,
            payments);

    @Test
    @DisplayName("the customer's own price is the reference where it has one, read at the proposed cartons; a price "
            + "below it and below the last sale is flagged, with the delta negative")
    void customerPriceFirst() {
        quote(line(1, "CUP-A", 12, 50, "2700"));
        when(catalog.customerPrice(CUSTOMER, "CUP-A")).thenReturn(Optional.of(cartons("CUP-A",
                tier(1, "3200"), tier(10, "3000"), tier(50, "2800"))));
        when(customerSkus.sourceSkusOf(any())).thenReturn(Map.of("CUP-A", "VAR-12OZ"));
        when(catalog.standardPrice("VAR-12OZ")).thenReturn(Optional.of(cartons("VAR-12OZ", tier(1, "3500"))));
        when(history.lastSold(any(), any())).thenReturn(Map.of("CUP-A", new CustomerSalesHistory.LastSold("CUP-A",
                new BigDecimal("2900"), "VND", LocalDate.parse("2026-10-01"), "SO-20261001-000007",
                UUID.randomUUID(), 10, 40)));

        QuotePriceReview.Review found = review.of(QUOTE, manager());

        QuotePriceReview.Line line = found.lines().getFirst();
        assertThat(line.customerPrice()).as("the break from 10 cartons, not the one from 50").isEqualByComparingTo("3000");
        assertThat(line.standardPrice()).isEqualByComparingTo("3500");
        assertThat(line.lastSoldUnitPrice()).isEqualByComparingTo("2900");
        assertThat(line.reference()).isEqualTo("CUSTOMER_PRICE");
        assertThat(line.deltaVsReference()).isEqualByComparingTo("-10.00");
        assertThat(line.flags()).containsExactly(QuotePriceReview.BELOW_REFERENCE, QuotePriceReview.BELOW_LAST_SOLD,
                QuotePriceReview.LAST_SOLD_OTHER_PACK);
        assertThat(found.flags()).contains("LINES_BELOW_REFERENCE", "LINES_BELOW_LAST_SOLD");
        assertThat(found.customerName()).isEqualTo("Công ty Ly Giấy");
    }

    @Test
    @DisplayName("without a price of its own the standard price is the reference; without either, the last sale; "
            + "without any, the line has no reference")
    void referenceFallsBack() {
        quote(line(1, "CUP-A", 5, 50, "3400"), line(2, "CUP-B", 5, 50, "3100"), line(3, "CUP-C", 5, 50, "3000"));
        when(catalog.customerPrice(any(), any())).thenReturn(Optional.empty());
        when(customerSkus.sourceSkusOf(any())).thenReturn(Map.of("CUP-A", "VAR-12OZ"));
        when(catalog.standardPrice("VAR-12OZ")).thenReturn(Optional.of(cartons("VAR-12OZ", tier(1, "3200"))));
        when(history.lastSold(any(), any())).thenReturn(Map.of("CUP-B", new CustomerSalesHistory.LastSold("CUP-B",
                new BigDecimal("3000"), "VND", LocalDate.parse("2026-10-01"), "SO-20261001-000007",
                UUID.randomUUID(), 5, 50)));

        List<QuotePriceReview.Line> lines = review.of(QUOTE, manager()).lines();

        assertThat(lines.get(0).reference()).isEqualTo("STANDARD_PRICE");
        assertThat(lines.get(0).deltaVsReference()).isEqualByComparingTo("6.25");
        assertThat(lines.get(0).flags()).isEmpty();
        assertThat(lines.get(1).reference()).isEqualTo("LAST_SOLD");
        assertThat(lines.get(1).deltaVsReference()).isEqualByComparingTo("3.33");
        assertThat(lines.get(2).reference()).isNull();
        assertThat(lines.get(2).deltaVsReference()).isNull();
        assertThat(lines.get(2).flags()).containsExactly(QuotePriceReview.NO_REFERENCE);
    }

    @Test
    @DisplayName("a schedule kept in pieces is read at the line's pieces, not its cartons")
    void unitSchedule() {
        QuoteCards.Line line = line(1, "CUP-A", 3, 50, "3000");
        PriceSchedule pieces = new PriceSchedule("CUP-A", 1, List.of(tier(1, "3500"), tier(100, "3100")),
                PriceSchedule.Measure.UNIT);

        assertThat(QuotePriceReview.priceAt(pieces, line)).contains(new BigDecimal("3100"));
        assertThat(QuotePriceReview.priceAt(cartons("CUP-A", tier(5, "2000")), line)).isEmpty();
    }

    @Test
    @DisplayName("credit only for a caller who may read the customer's credit, computed as its screen does")
    void creditForWhoMaySeeIt() {
        quote(line(1, "CUP-A", 5, 50, null));
        when(catalog.customerPrice(any(), any())).thenReturn(Optional.empty());
        when(customerSkus.sourceSkusOf(any())).thenReturn(Map.of());
        when(history.lastSold(any(), any())).thenReturn(Map.of());

        QuotePriceReview.Review hidden = review.of(QUOTE, manager());
        assertThat(hidden.credit()).isNull();
        verify(payments, never()).creditPosition(any());
        assertThat(hidden.lines().getFirst().flags()).contains(QuotePriceReview.NOT_PRICED,
                QuotePriceReview.NO_REFERENCE);

        when(customers.creditTerms(CUSTOMER)).thenReturn(new CreditTerms(CUSTOMER, true,
                new BigDecimal("100000000"), 30, "VND", null, null, null, 1));
        when(payments.creditPosition(CUSTOMER)).thenReturn(new CreditPosition(new BigDecimal("120000000"), true,
                new BigDecimal("5000000"), LocalDate.parse("2026-10-15")));

        QuotePriceReview.Review shown = review.of(QUOTE, manager("customer-credit-profiles:READ"));
        assertThat(shown.credit().limit()).isEqualByComparingTo("100000000");
        assertThat(shown.credit().outstanding()).isEqualByComparingTo("120000000");
        assertThat(shown.credit().available()).as("never below zero").isEqualByComparingTo("0");
        assertThat(shown.credit().overdue()).isEqualByComparingTo("5000000");
        assertThat(shown.flags()).contains("CREDIT_OVERDUE");
    }

    @Test
    @DisplayName("an unknown quote is 404")
    void unknownQuote() {
        when(quotes.quote(QUOTE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> review.of(QUOTE, manager())).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------------------------------------------------

    private void quote(QuoteCards.Line... lines) {
        when(quotes.quote(QUOTE)).thenReturn(Optional.of(new QuoteCards.Card(QUOTE, "QT-20261127-0001", CUSTOMER,
                "DRAFT", 1, Instant.now(), null, List.of(lines), null, null, null, null, null, null, 3)));
        when(customers.labels(any())).thenReturn(Map.of(CUSTOMER, new CustomerLabel(CUSTOMER, "Công ty Ly Giấy",
                "KH-001")));
    }

    private static QuoteCards.Line line(int no, String sku, int cartons, int perCarton, String price) {
        return new QuoteCards.Line(UUID.randomUUID(), no, sku, cartons, perCarton, cartons * perCarton,
                price == null ? null : new BigDecimal(price), null, null);
    }

    private static PriceSchedule.Tier tier(int from, String price) {
        return new PriceSchedule.Tier(from, Money.vnd(Long.parseLong(price)));
    }

    private static PriceSchedule cartons(String sku, PriceSchedule.Tier... tiers) {
        return new PriceSchedule(sku, tiers[0].minQuantity(), List.of(tiers), PriceSchedule.Measure.CARTON);
    }

    private static CurrentUser manager(String... more) {
        Set<PermissionCode> permissions = Arrays.stream(more).map(PermissionCode::parse).collect(Collectors.toSet());
        permissions.add(PermissionCode.parse("sales-quote-price-approvals:APPROVE"));
        return new CurrentUser(UUID.randomUUID(), "manager", Set.of(Role.SALES_MANAGER), permissions, DataScope.ALL,
                Set.of());
    }
}
