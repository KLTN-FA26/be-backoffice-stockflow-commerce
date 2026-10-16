package com.stockflow.order.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.api.DesignService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.PriceTier;
import com.stockflow.order.internal.domain.ProductionRecords;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteLine;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.repository.QuoteSearch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quote use cases' decisions with collaborators mocked: the sample rule's reorder branch (which real
 * data can only reach for designs printed before samples existed), what conversion hands the order core,
 * and that a converted quote is answered without placing again. The flows themselves run against the
 * database in {@code QuoteIntegrationTest}.
 */
class QuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");

    private final QuoteRepository quotes = mock(QuoteRepository.class);
    private final QuoteSearch search = mock(QuoteSearch.class);
    private final ProductionRecords production = mock(ProductionRecords.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final DesignService designs = mock(DesignService.class);
    private final QuotedOrders quotedOrders = mock(QuotedOrders.class);
    private final OrderService orders = mock(OrderService.class);
    private final com.stockflow.catalog.api.CatalogService catalog = mock(com.stockflow.catalog.api.CatalogService.class);
    private final org.springframework.context.ApplicationEventPublisher events =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final QuoteService service = new QuoteService(quotes, search, production, customers, designs, quotedOrders,
            orders, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), catalog, events);

    private final UUID customer = UUID.randomUUID();
    private final UUID portalUser = UUID.randomUUID();
    private final UUID design = UUID.randomUUID();
    private final CurrentUser sales = new CurrentUser(UUID.randomUUID(), "sales", Set.of(Role.SALES_STAFF), Set.of(),
            DataScope.ALL, Set.of(), Set.of());
    private final CurrentUser portal = new CurrentUser(portalUser, "buyer", Set.of(Role.CUSTOMER), Set.of(),
            DataScope.ALL, Set.of(), Set.of());

    @BeforeEach
    void stubs() {
        when(customers.findById(customer)).thenReturn(Optional.of(new CustomerSummary(customer, portalUser, "A", "a@x.vn",
                null, null, null, "ACTIVE", 0, NOW)));
        when(customers.findByUserId(portalUser)).thenReturn(Optional.of(new CustomerSummary(customer, portalUser, "A",
                "a@x.vn", null, null, null, "ACTIVE", 0, NOW)));
        when(customers.creditTerms(customer)).thenReturn(new CreditTerms(customer, true, true, true, false,
                com.stockflow.customer.api.CommercialTerm.DEPOSIT, new BigDecimal("30"), null, null, "VND", null, null,
                null, 1));
        when(search.findRow(any())).thenAnswer(call -> Optional.of(new QuoteSearch.Row(call.getArgument(0), "QT-1",
                customer, QuoteStatus.SENT, 1, PaymentTerm.DEPOSIT, NOW.plusSeconds(3600), NOW, null, NOW, "sales", 7)));
        when(search.history(any())).thenReturn(List.of());
    }

    private Quote stored(QuoteStatus status) {
        QuoteLine line = new QuoteLine(UUID.randomUUID(), 1, new Sku("CUP-PP-500"), design, null, 1000,
                List.of(PriceTier.of(1000, new BigDecimal("2000")), PriceTier.of(5000, new BigDecimal("1800"))),
                status == QuoteStatus.ACCEPTED || status == QuoteStatus.CONVERTED ? 5000 : null,
                status == QuoteStatus.ACCEPTED || status == QuoteStatus.CONVERTED ? Money.vnd(1800) : null);
        Quote quote = Quote.restore(UUID.randomUUID(), "QT-1", UUID.randomUUID(), customer, status, 1,
                new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("40"), NOW.plusSeconds(3600), null, List.of(line)),
                NOW, sales.userId(), status == QuoteStatus.SENT ? null : NOW, status == QuoteStatus.SENT ? null : portalUser,
                null, null, status == QuoteStatus.CONVERTED ? UUID.randomUUID() : null,
                status == QuoteStatus.CONVERTED ? NOW : null);
        when(quotes.findByIdForUpdate(quote.id())).thenReturn(Optional.of(quote));
        when(quotes.findById(quote.id())).thenReturn(Optional.of(quote));
        return quote;
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void aReorderOfWhatWasPrintedForThisCustomerIsAcceptedWithoutASample() {
        Quote quote = stored(QuoteStatus.SENT);
        when(production.approvedSampleOf(customer, design, "CUP-PP-500")).thenReturn(Optional.empty());
        when(production.producedBeforeFor(customer, design, "CUP-PP-500")).thenReturn(true);

        Quotes.QuoteView accepted = service.accept(portal, quote.id(), new Quotes.Acceptance(7, Map.of()));

        assertThat(accepted.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(accepted.lines().getFirst().sample()).isEqualTo(new Quotes.SampleGate(true, null, true));
    }

    @Test
    void neitherASampleNorAReorderRefusesAcceptance() {
        Quote quote = stored(QuoteStatus.SENT);
        when(production.approvedSampleOf(any(), any(), any())).thenReturn(Optional.empty());
        when(production.producedBeforeFor(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.accept(portal, quote.id(), new Quotes.Acceptance(7, Map.of())))
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
        verify(quotes, never()).save(any());
    }

    @Test
    void conversionHandsTheOrderCoreTheAcceptedQuantitiesPricesAndTermsUnderADerivedRequestId() {
        Quote quote = stored(QuoteStatus.ACCEPTED);
        UUID orderId = UUID.randomUUID();
        when(quotedOrders.placeQuotedOrder(any())).thenReturn(new OrderSummary(orderId, "SO-1", customer,
                OrderStatus.PENDING_PAYMENT, Money.vnd(9_000_000), List.of(), NOW));

        service.convert(sales, quote.id(), new Quotes.Conversion(7, null, null));

        ArgumentCaptor<PlaceOrderCommand> placed = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(quotedOrders).placeQuotedOrder(placed.capture());
        PlaceOrderCommand command = placed.getValue();
        assertThat(command.requestId()).isEqualTo(QuoteService.conversionRequestId(quote.id()));
        assertThat(command.customerId()).isEqualTo(customer);
        assertThat(command.snapshotCustomer()).isTrue();
        assertThat(command.paymentTerm()).isEqualTo(PaymentTerm.DEPOSIT);
        assertThat(command.depositPercent()).isEqualByComparingTo("40");
        assertThat(command.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku().code()).isEqualTo("CUP-PP-500");
            assertThat(line.quantity()).isEqualTo(5000);
            assertThat(line.unitPrice()).isEqualTo(Money.vnd(1800));
            assertThat(line.designSnapshotId()).isEqualTo(design);
        });
        assertThat(quote.status()).isEqualTo(QuoteStatus.CONVERTED);
        assertThat(quote.convertedOrderId()).isEqualTo(orderId);
    }

    @Test
    void aConvertedQuoteIsAnsweredWithoutPlacingAgainAndOnlyStaffConvert() {
        Quote converted = stored(QuoteStatus.CONVERTED);
        service.convert(sales, converted.id(), new Quotes.Conversion(1, null, null));
        verify(quotedOrders, never()).placeQuotedOrder(any());

        Quote accepted = stored(QuoteStatus.ACCEPTED);
        assertThatThrownBy(() -> service.convert(portal, accepted.id(), new Quotes.Conversion(7, null, null)))
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.convert(sales, accepted.id(), new Quotes.Conversion(6, null, null)))
                .as("a version not seen").extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        verify(quotedOrders, never()).placeQuotedOrder(any());
    }

    @Test
    void aReorderOfABlankWithoutAListPriceReachesSalesUnpricedNeverAtTheOldDeal() {
        Quote source = stored(QuoteStatus.CONVERTED);
        UUID orderId = source.convertedOrderId();
        when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-1", customer,
                OrderStatus.CONFIRMED, Money.vnd(9_000_000), List.of(), NOW)));
        when(quotes.findByConvertedOrderId(orderId)).thenReturn(Optional.of(source));
        when(quotes.findByRequestId(any())).thenReturn(Optional.empty());
        when(quotes.nextNumber(any())).thenReturn("QT-2");
        when(catalog.priceSchedule(customer, "CUP-PP-500"))
                .thenThrow(new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));

        Quotes.QuoteView draft = service.reorder(portal, new Quotes.Reorder(UUID.randomUUID(), orderId));

        assertThat(draft.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(draft.priced()).isFalse();
        assertThat(draft.proposedTotal()).isNull();
        assertThat(draft.lines()).singleElement().satisfies(line -> {
            assertThat(line.priced()).isFalse();
            assertThat(line.tiers()).as("not the 1,800 deal, not a zero").isEmpty();
            assertThat(line.moq()).isNull();
            assertThat(line.proposedUnitPrice()).isNull();
            assertThat(line.quantity()).as("what the order bought").isEqualTo(5000);
            assertThat(line.designSnapshotId()).isEqualTo(design);
        });
        verify(quotedOrders, never()).placeQuotedOrder(any());
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(com.stockflow.contracts.CustomerActedOnQuote.class,
                e -> {
                    assertThat(e.action()).isEqualTo(com.stockflow.contracts.CustomerActedOnQuote.Action.REORDER_REQUESTED);
                    assertThat(e.sourceOrderNumber()).isEqualTo("SO-1");
                    assertThat(e.salesUserId()).as("who sent the order's quote; the address is looked up on delivery")
                            .isEqualTo(sales.userId());
                });
    }

    @Test
    void aReorderWhoseCreditWasWithdrawnReachesSalesOnTodaysTermsNeverTheOldCredit() {
        // R1b: the deal was on credit; today the customer may only prepay.
        QuoteLine line = new QuoteLine(UUID.randomUUID(), 1, new Sku("CUP-PP-500"), design, null, 1000,
                List.of(PriceTier.of(1000, new BigDecimal("2000"))), 1000, Money.vnd(2000));
        Quote source = Quote.restore(UUID.randomUUID(), "QT-1", UUID.randomUUID(), customer, QuoteStatus.CONVERTED, 1,
                new Quote.Offer(PaymentTerm.CREDIT, null, NOW.plusSeconds(3600), null, List.of(line)), NOW,
                sales.userId(), NOW, portalUser, null, null, UUID.randomUUID(), NOW);
        UUID orderId = source.convertedOrderId();
        when(customers.creditTerms(customer)).thenReturn(CreditTerms.prepaidOnly(customer));
        when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-1", customer,
                OrderStatus.CONFIRMED, Money.vnd(2_000_000), List.of(), NOW)));
        when(quotes.findByConvertedOrderId(orderId)).thenReturn(Optional.of(source));
        when(quotes.findByRequestId(any())).thenReturn(Optional.empty());
        when(quotes.nextNumber(any())).thenReturn("QT-2");
        when(catalog.priceSchedule(customer, "CUP-PP-500"))
                .thenThrow(new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));

        Quotes.QuoteView draft = service.reorder(portal, new Quotes.Reorder(UUID.randomUUID(), orderId));

        assertThat(draft.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(draft.paymentTerm()).as("only a term allowed today").isEqualTo(PaymentTerm.PREPAID);
        assertThat(draft.depositPercent()).isNull();
        verify(quotedOrders, never()).placeQuotedOrder(any());
        verify(quotes).recordTransition(any(), org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(QuoteStatus.DRAFT),
                org.mockito.ArgumentMatchers.contains("CREDIT is no longer allowed, drafted as PREPAID"),
                org.mockito.ArgumentMatchers.isNull(), any(), any());
    }

    @Test
    void theConversionRequestIdIsStablePerQuoteAndDistinctAcrossQuotes() {
        UUID quote = UUID.randomUUID();
        assertThat(QuoteService.conversionRequestId(quote)).isEqualTo(QuoteService.conversionRequestId(quote));
        assertThat(QuoteService.conversionRequestId(quote)).isNotEqualTo(QuoteService.conversionRequestId(UUID.randomUUID()));
    }
}
