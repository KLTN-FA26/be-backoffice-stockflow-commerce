package com.stockflow.order.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.catalog.api.PriceSchedule;
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
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.PriceTier;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteLine;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.repository.QuoteSearch;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quote use cases' decisions with collaborators mocked (4B-2: cartons of the customer's own SKUs): what drafting
 * checks and fills in, acceptance without any sample rule, what conversion hands the order core, that a converted quote
 * is answered without placing again, and that a legacy quote is never placed. The flows themselves run against the
 * database in {@code QuoteIntegrationTest}.
 */
class QuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final String SKU = "LY12-ACME-01";
    private static final UUID CARTON = UUID.randomUUID();

    private final QuoteRepository quotes = mock(QuoteRepository.class);
    private final QuoteSearch search = mock(QuoteSearch.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final UomConversionService units = mock(UomConversionService.class);
    private final QuotedOrders quotedOrders = mock(QuotedOrders.class);
    private final OrderService orders = mock(OrderService.class);
    private final com.stockflow.catalog.api.CatalogService catalog = mock(com.stockflow.catalog.api.CatalogService.class);
    private final org.springframework.context.ApplicationEventPublisher events =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final CustomerSkus customerSkus = mock(CustomerSkus.class);
    private final com.stockflow.product.api.ProductService products = mock(com.stockflow.product.api.ProductService.class);
    private final com.stockflow.order.internal.domain.TaxRates taxRates =
            mock(com.stockflow.order.internal.domain.TaxRates.class);
    private final com.stockflow.order.internal.domain.SkuProductionHistory production =
            mock(com.stockflow.order.internal.domain.SkuProductionHistory.class);
    private final QuoteService service = new QuoteService(quotes, search, customers, units, quotedOrders,
            orders, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), catalog, events, products, customerSkus,
            taxRates, production);

    private final UUID customer = UUID.randomUUID();
    private final UUID portalUser = UUID.randomUUID();
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
        ownedByTheCustomer(SKU);
        // 4B-3: the products are STANDARD (10%) unless a test says otherwise; every SKU was produced before.
        when(taxRates.all()).thenReturn(Map.of("STANDARD", new BigDecimal("10.00"), "REDUCED", new BigDecimal("5.00"),
                "EXEMPT", new BigDecimal("0.00")));
        when(products.taxClassesOf(anyCollection())).thenAnswer(call -> {
            Map<String, com.stockflow.product.api.TaxClass> classes = new java.util.HashMap<>();
            for (Object code : (java.util.Collection<?>) call.getArgument(0)) {
                classes.put((String) code, com.stockflow.product.api.TaxClass.STANDARD);
            }
            return classes;
        });
        when(production.producedBefore(anyCollection())).thenAnswer(call ->
                new java.util.HashSet<>((java.util.Collection<String>) call.getArgument(0)));
        when(units.conversionsOf(anyCollection())).thenAnswer(call -> {
            Map<String, List<UomConversion>> declared = new java.util.HashMap<>();
            for (Object sku : (java.util.Collection<?>) call.getArgument(0)) {
                String code = ((Sku) sku).code();
                declared.put(code, List.of(unit(code, UUID.randomUUID(), "EACH", 1, true),
                        unit(code, code.equals(SKU) ? CARTON : UUID.nameUUIDFromBytes(code.getBytes()), "CARTON", 50,
                                false)));
            }
            return declared;
        });
    }

    private static UomConversion unit(String sku, UUID id, String code, int factor, boolean base) {
        return new UomConversion(id, sku, code, factor, base, null, null, null, null, false, true, 0, !base);
    }

    /** The customer's own SKUs, as {@code CustomerSkus.ownersOf} answers them. */
    private final Map<String, UUID> owned = new java.util.HashMap<>();

    private void ownedByTheCustomer(String sku) {
        owned.put(sku, customer);
        when(customerSkus.ownerOf(sku)).thenReturn(Optional.of(customer));
        when(customerSkus.ownersOf(anyCollection())).thenAnswer(call -> {
            Map<String, UUID> found = new java.util.HashMap<>();
            for (Object code : (java.util.Collection<?>) call.getArgument(0)) {
                if (owned.containsKey(code)) {
                    found.put((String) code, owned.get(code));
                }
            }
            return found;
        });
    }

    private static QuoteLine line(int lineNo, String sku, int cartons, Integer accepted, Money price) {
        return QuoteLine.carton(UUID.randomUUID(), lineNo, new Sku(sku), sku.equals(SKU) ? CARTON
                        : UUID.nameUUIDFromBytes(sku.getBytes()), 50, cartons,
                List.of(PriceTier.of(10, new BigDecimal("2000")), PriceTier.of(50, new BigDecimal("1800"))), accepted,
                price, "STANDARD", new BigDecimal("10"));
    }

    private Quote stored(QuoteStatus status) {
        boolean accepted = status == QuoteStatus.ACCEPTED || status == QuoteStatus.CONVERTED;
        QuoteLine line = line(1, SKU, 20, accepted ? 60 : null, accepted ? Money.vnd(1800) : null);
        return stored(status, new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("40"), NOW.plusSeconds(3600), null,
                List.of(line)));
    }

    private Quote stored(QuoteStatus status, Quote.Offer offer) {
        Quote quote = Quote.restore(UUID.randomUUID(), "QT-1", UUID.randomUUID(), customer, status, 1, offer,
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

    private Quotes.Create create(String sku, int cartons, List<Quotes.Tier> tiers) {
        return new Quotes.Create(UUID.randomUUID(), customer, new Quotes.Draft(PaymentTerm.DEPOSIT, null,
                NOW.plusSeconds(3600), null, List.of(new Quotes.Line(sku, cartons, tiers))));
    }

    @Test
    void aDraftIsCartonsOfTheCustomersOwnSkuInItsCartonUnitAndADepositShareTakesTheDefault() {
        when(quotes.findByRequestId(any())).thenReturn(Optional.empty());
        when(quotes.nextNumber(any())).thenReturn("QT-9");
        ArgumentCaptor<Quote> saved = ArgumentCaptor.forClass(Quote.class);

        Quotes.QuoteView view = service.create(sales, create(SKU, 20, List.of(new Quotes.Tier(10, new BigDecimal("2000")))));

        verify(quotes).save(saved.capture());
        QuoteLine line = saved.getValue().offer().lines().getFirst();
        assertThat(line.uomConversionId()).isEqualTo(CARTON);
        assertThat(line.unitsPerCarton()).as("the carton unit's factor, fixed on the line").isEqualTo(50);
        assertThat(view.lines()).singleElement().satisfies(l -> {
            assertThat(l.measure()).isEqualTo("CARTON");
            assertThat(l.cartons()).isEqualTo(20);
            assertThat(l.units()).isEqualTo(1000);
            assertThat(l.proposedLineTotal()).isEqualByComparingTo("2000000");
        });
        // Audit of #109: the share is the one the customer sees and accepts, not the default read again at conversion.
        assertThat(saved.getValue().offer().depositPercent()).isEqualByComparingTo("30");
    }

    @Test
    void anotherCustomersSkuAStockVariantOrASkuWithoutACartonUnitIsNotQuoted() {
        when(quotes.findByRequestId(any())).thenReturn(Optional.empty());
        List<Quotes.Tier> tiers = List.of(new Quotes.Tier(10, new BigDecimal("2000")));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.VARIANT_NOT_FOUND)).when(customerSkus)
                .requireVisibleTo(List.of("LY12-OTHER-01"), customer);
        assertThatThrownBy(() -> service.create(sales, create("LY12-OTHER-01", 20, tiers)))
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.VARIANT_NOT_FOUND);
        when(customerSkus.ownerOf("CUP-PP-500")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(sales, create("CUP-PP-500", 20, tiers)))
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.CUSTOMER_SKU_REQUIRED);
        ownedByTheCustomer("LY16-ACME-01");
        when(units.conversionsOf(anyCollection())).thenReturn(Map.of("LY16-ACME-01",
                List.of(unit("LY16-ACME-01", UUID.randomUUID(), "EACH", 1, true))));
        assertThatThrownBy(() -> service.create(sales, create("LY16-ACME-01", 20, tiers)))
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.CARTON_SPEC_MISSING);
        verify(quotes, never()).save(any());
    }

    @Test
    void aLineStartsFromTheCustomersOwnPriceNeverAGroupOrBasePrice() {
        when(quotes.findByRequestId(any())).thenReturn(Optional.empty());
        when(quotes.nextNumber(any())).thenReturn("QT-9");
        // No price of the customer's own (a group or base price is never asked for): Sales enters the tiers.
        assertThatThrownBy(() -> service.create(sales, create(SKU, 20, null))).as("nothing to start from")
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(catalog, never()).priceSchedule(any(), any());

        when(catalog.customerPrice(customer, SKU)).thenReturn(Optional.of(new PriceSchedule(SKU,
                PriceSchedule.PriceSource.CUSTOMER, 10, List.of(new PriceSchedule.Tier(10, Money.vnd(2100)),
                        new PriceSchedule.Tier(30, Money.vnd(1900))))));
        assertThatThrownBy(() -> service.create(sales, create(SKU, 5, null))).as("below its first tier, in cartons")
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        Quotes.QuoteView view = service.create(sales, create(SKU, 30, null));
        assertThat(view.lines().getFirst().tiers()).extracting(Quotes.Tier::minQuantity).containsExactly(10, 30);
        assertThat(view.lines().getFirst().proposedUnitPrice()).isEqualByComparingTo("1900");
    }

    @Test
    void aNewDesignIsAcceptedWithoutASample() {
        // Print samples are approved on the Production Order, outside the quote (kltn-docs 17): nothing to wait for.
        Quote quote = stored(QuoteStatus.SENT);

        Quotes.QuoteView accepted = service.accept(portal, quote.id(), new Quotes.Acceptance(7, Map.of()));

        assertThat(accepted.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(accepted.acceptedTotal()).as("20 cartons x 50 x 2,000, and 10% VAT (4B-3)")
                .isEqualByComparingTo("2200000");
        assertThat(accepted.accepted().subtotal()).isEqualByComparingTo("2000000");
    }

    @Test
    void conversionHandsTheOrderCoreTheAcceptedCartonsPiecesPricesAndTermsUnderADerivedRequestId() {
        Quote quote = stored(QuoteStatus.ACCEPTED);
        UUID orderId = UUID.randomUUID();
        when(quotedOrders.placeQuotedOrder(any())).thenReturn(new OrderSummary(orderId, "SO-1", customer,
                OrderStatus.PENDING_PAYMENT, Money.vnd(5_400_000), List.of(), NOW));

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
            assertThat(line.sku().code()).isEqualTo(SKU);
            assertThat(line.quantity()).as("60 cartons of 50 pieces").isEqualTo(3000);
            assertThat(line.unitPrice()).isEqualTo(Money.vnd(1800));
            assertThat(line.designSnapshotId()).as("the SKU is its design").isNull();
            assertThat(line.carton()).isEqualTo(new PlaceOrderCommand.Carton(CARTON, 60, 50));
        });
        assertThat(quote.status()).isEqualTo(QuoteStatus.CONVERTED);
        assertThat(quote.convertedOrderId()).isEqualTo(orderId);
    }

    @Test
    void conversionCarriesTheRequestedDeliveryDateOntoEveryLine() {
        java.time.LocalDate asked = java.time.LocalDate.of(2026, 12, 15);
        ownedByTheCustomer("LY16-ACME-01");
        Quote quote = stored(QuoteStatus.ACCEPTED, new Quote.Offer(PaymentTerm.PREPAID, null, NOW.plusSeconds(3600),
                null, asked, List.of(line(1, SKU, 20, 20, Money.vnd(2000)),
                        line(2, "LY16-ACME-01", 10, 10, Money.vnd(2000)))));
        when(quotedOrders.placeQuotedOrder(any())).thenReturn(new OrderSummary(UUID.randomUUID(), "SO-1", customer,
                OrderStatus.PENDING_PAYMENT, Money.vnd(3_000_000), List.of(), NOW));

        service.convert(sales, quote.id(), new Quotes.Conversion(7, null, null));

        ArgumentCaptor<PlaceOrderCommand> placed = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(quotedOrders).placeQuotedOrder(placed.capture());
        assertThat(placed.getValue().lines()).hasSize(2)
                .allSatisfy(line -> assertThat(line.requestedDeliveryDate()).isEqualTo(asked));
    }

    @Test
    void aQuoteWithoutADateConvertsToLinesWithoutOne() {
        Quote quote = stored(QuoteStatus.ACCEPTED);
        when(quotedOrders.placeQuotedOrder(any())).thenReturn(new OrderSummary(UUID.randomUUID(), "SO-1", customer,
                OrderStatus.PENDING_PAYMENT, Money.vnd(5_400_000), List.of(), NOW));

        service.convert(sales, quote.id(), new Quotes.Conversion(7, null, null));

        ArgumentCaptor<PlaceOrderCommand> placed = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(quotedOrders).placeQuotedOrder(placed.capture());
        assertThat(placed.getValue().lines()).singleElement()
                .satisfies(line -> assertThat(line.requestedDeliveryDate()).isNull());
    }

    @Test
    void aLegacyQuoteIsNeverPlacedAndAnAcceptedOneMayBeCancelledForThatAlone() {
        QuoteLine legacy = QuoteLine.legacy(UUID.randomUUID(), 1, new Sku("CUP-PP-500"), UUID.randomUUID(), null, 1000,
                List.of(PriceTier.of(1000, new BigDecimal("2000"))), 1000, Money.vnd(2000));
        Quote quote = stored(QuoteStatus.ACCEPTED, new Quote.Offer(PaymentTerm.PREPAID, null, NOW.plusSeconds(3600),
                null, List.of(legacy)));
        assertThat(service.get(sales, quote.id()).legacy()).isTrue();
        assertThat(service.get(sales, quote.id()).lines().getFirst().measure()).isEqualTo("UNIT");

        assertThatThrownBy(() -> service.convert(sales, quote.id(), new Quotes.Conversion(7, null, null)))
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.QUOTE_LINES_LEGACY);
        verify(quotedOrders, never()).placeQuotedOrder(any());

        service.cancel(sales, quote.id(), 7, "Drafted on blanks; quoted again on the customer's SKU");
        assertThat(quote.status()).isEqualTo(QuoteStatus.CANCELLED);
    }

    @Test
    void theRequestedDeliveryDateIsStoredWithTheDraftAndNeverInThePast() {
        when(quotes.findByRequestId(any())).thenReturn(Optional.empty());
        when(quotes.nextNumber(any())).thenReturn("QT-9");
        ArgumentCaptor<Quote> saved = ArgumentCaptor.forClass(Quote.class);
        // NOW is 2026-10-14 10:00 in Ho Chi Minh City: today there is a date that can still be asked for.
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 14);
        Quotes.Line line = new Quotes.Line(SKU, 20, List.of(new Quotes.Tier(10, new BigDecimal("2000"))));

        service.create(sales, new Quotes.Create(UUID.randomUUID(), customer, new Quotes.Draft(PaymentTerm.PREPAID, null,
                NOW.plusSeconds(3600), null, List.of(line), today)));
        verify(quotes).save(saved.capture());
        assertThat(saved.getValue().offer().requestedDeliveryDate()).isEqualTo(today);

        assertThatThrownBy(() -> service.create(sales, new Quotes.Create(UUID.randomUUID(), customer,
                new Quotes.Draft(PaymentTerm.PREPAID, null, NOW.plusSeconds(3600), null, List.of(line),
                        today.minusDays(1)))))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(quotes, org.mockito.Mockito.times(1)).save(any());
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
    void theConversionRequestIdIsStablePerQuoteAndDistinctAcrossQuotes() {
        UUID quote = UUID.randomUUID();
        assertThat(QuoteService.conversionRequestId(quote)).isEqualTo(QuoteService.conversionRequestId(quote));
        assertThat(QuoteService.conversionRequestId(quote)).isNotEqualTo(QuoteService.conversionRequestId(UUID.randomUUID()));
    }

    // ------------------------------------------------------------------------------------- 4B-3: VAT and fee lines

    private Quotes.Create createWithFees(List<Quotes.Fee> fees) {
        return new Quotes.Create(UUID.randomUUID(), customer, new Quotes.Draft(PaymentTerm.DEPOSIT, null,
                NOW.plusSeconds(3600), null, List.of(new Quotes.Line(SKU, 20, List.of(new Quotes.Tier(10,
                new BigDecimal("2000"))))), null, fees));
    }

    @Test
    void aFirstProductionGetsAPlateFeeTheSystemAddsEveryLineItsProductsVatAndTheTotalsAddUp() {
        when(quotes.findByRequestId(any())).thenReturn(Optional.empty());
        when(quotes.nextNumber(any())).thenReturn("QT-9");
        when(production.producedBefore(anyCollection())).thenReturn(Set.of());

        Quotes.QuoteView unpriced = service.create(sales, createWithFees(List.of(
                new Quotes.Fee(com.stockflow.order.api.FeeKind.SHIPPING_FEE, null, new BigDecimal("300000")))));
        assertThat(unpriced.lines().getFirst().taxClass()).isEqualTo("STANDARD");
        assertThat(unpriced.lines().getFirst().taxRate()).isEqualByComparingTo("10");
        assertThat(unpriced.lines().getFirst().proposedTax()).isEqualByComparingTo("200000");
        assertThat(unpriced.fees()).extracting(Quotes.FeeView::kind).containsExactly(
                com.stockflow.order.api.FeeKind.PLATE_FEE, com.stockflow.order.api.FeeKind.SHIPPING_FEE);
        assertThat(unpriced.fees().getFirst().sku()).isEqualTo(SKU);
        assertThat(unpriced.fees().getFirst().amount()).as("added by the system, priced by Sales").isNull();
        assertThat(unpriced.fees().get(1).taxClass()).as("D31").isEqualTo("STANDARD");
        assertThat(unpriced.proposed().subtotal()).isEqualByComparingTo("2000000");
        assertThat(unpriced.proposed().fees()).as("the plate fee is not priced yet").isNull();
        assertThat(unpriced.proposedTotal()).isNull();

        Quotes.QuoteView priced = service.create(sales, createWithFees(List.of(
                new Quotes.Fee(com.stockflow.order.api.FeeKind.PLATE_FEE, SKU, new BigDecimal("500000")),
                new Quotes.Fee(com.stockflow.order.api.FeeKind.SHIPPING_FEE, null, new BigDecimal("300000")))));
        // 2,000,000 of goods + 800,000 of fees; VAT 10% line by line: 200,000 + 50,000 + 30,000.
        assertThat(priced.proposed().subtotal()).isEqualByComparingTo("2000000");
        assertThat(priced.proposed().fees()).isEqualByComparingTo("800000");
        assertThat(priced.proposed().tax()).isEqualByComparingTo("280000");
        assertThat(priced.proposedTotal()).isEqualByComparingTo("3080000");
    }

    @Test
    void aPlateFeeIsForAFirstProductionOfASkuOnTheQuoteAndThereIsOneShippingFeeAtMost() {
        when(quotes.findByRequestId(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(sales, createWithFees(List.of(
                new Quotes.Fee(com.stockflow.order.api.FeeKind.PLATE_FEE, SKU, new BigDecimal("500000"))))))
                .as("produced before: a reorder has no plate fee (14 BR-17)")
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        when(production.producedBefore(anyCollection())).thenReturn(Set.of());
        assertThatThrownBy(() -> service.create(sales, createWithFees(List.of(
                new Quotes.Fee(com.stockflow.order.api.FeeKind.PLATE_FEE, "LY16-ACME-01", new BigDecimal("1"))))))
                .as("not a SKU on the quote").extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(sales, createWithFees(List.of(
                new Quotes.Fee(com.stockflow.order.api.FeeKind.SHIPPING_FEE, null, new BigDecimal("1")),
                new Quotes.Fee(com.stockflow.order.api.FeeKind.SHIPPING_FEE, null, new BigDecimal("2"))))))
                .extracting(QuoteServiceTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(sales, createWithFees(List.of(
                new Quotes.Fee(com.stockflow.order.api.FeeKind.SHIPPING_FEE, null, new BigDecimal("100.5"))))))
                .as("whole VND, refused before Money rounds it").extracting(QuoteServiceTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(quotes, never()).save(any());
    }

    @Test
    void conversionCarriesEachLinesVatRateAndTheFeeLines() {
        com.stockflow.order.internal.domain.QuoteFee plate = new com.stockflow.order.internal.domain.QuoteFee(
                UUID.randomUUID(), com.stockflow.order.api.FeeKind.PLATE_FEE, new Sku(SKU), Money.vnd(500_000),
                "STANDARD", new BigDecimal("10"));
        com.stockflow.order.internal.domain.QuoteFee shipping = new com.stockflow.order.internal.domain.QuoteFee(
                UUID.randomUUID(), com.stockflow.order.api.FeeKind.SHIPPING_FEE, null, Money.vnd(300_000), "STANDARD",
                new BigDecimal("10"));
        Quote quote = stored(QuoteStatus.ACCEPTED, new Quote.Offer(PaymentTerm.PREPAID, null, NOW.plusSeconds(3600),
                null, null, List.of(line(1, SKU, 20, 20, Money.vnd(2000))), List.of(plate, shipping)));
        when(quotedOrders.placeQuotedOrder(any())).thenReturn(new OrderSummary(UUID.randomUUID(), "SO-1", customer,
                OrderStatus.PENDING_PAYMENT, Money.vnd(3_080_000), List.of(), NOW));

        service.convert(sales, quote.id(), new Quotes.Conversion(7, null, null));

        ArgumentCaptor<PlaceOrderCommand> placed = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(quotedOrders).placeQuotedOrder(placed.capture());
        assertThat(placed.getValue().lines()).singleElement()
                .satisfies(l -> assertThat(l.taxRate()).isEqualByComparingTo("10"));
        assertThat(placed.getValue().fees()).extracting(PlaceOrderCommand.Fee::kind, f -> f.amount().amount().longValue())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(com.stockflow.order.api.FeeKind.PLATE_FEE, 500_000L),
                        org.assertj.core.groups.Tuple.tuple(com.stockflow.order.api.FeeKind.SHIPPING_FEE, 300_000L));
    }
}
