package com.stockflow.order.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.CustomerActedOnQuote;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.api.DesignService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.PriceTier;
import com.stockflow.order.internal.domain.ProductionRecords;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteLine;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.repository.QuoteSearch;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.Purchasability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The quote lifecycle of kltn-docs 03 §18 as {@link QuoteService} drives it: Sales sends and cancels, only the
 * customer answers, a stale version is refused, a sent quote past its validity reads as expired (BR-ORD-008), and
 * every transition is written to the history. {@code QuoteServiceTest} covers the sample rule and conversion.
 */
class QuoteServiceTransitionsTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final long VERSION = 7;

    private final QuoteRepository quotes = mock(QuoteRepository.class);
    private final QuoteSearch search = mock(QuoteSearch.class);
    private final ProductionRecords production = mock(ProductionRecords.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final ProductService products = mock(ProductService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final QuoteService service = new QuoteService(quotes, search, production, customers,
            mock(DesignService.class), mock(QuotedOrders.class), mock(OrderService.class), new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC), mock(com.stockflow.catalog.api.CatalogService.class), events, products);

    private final UUID customer = UUID.randomUUID();
    private final UUID portalUser = UUID.randomUUID();
    private final UUID design = UUID.randomUUID();
    private final UUID salesUser = UUID.randomUUID();
    private final CurrentUser sales = new CurrentUser(salesUser, "sales", Set.of(Role.SALES_STAFF), Set.of(),
            DataScope.ALL, Set.of(), Set.of());
    private final CurrentUser portal = new CurrentUser(portalUser, "buyer", Set.of(Role.CUSTOMER), Set.of(),
            DataScope.ALL, Set.of(), Set.of());

    @BeforeEach
    void stubs() {
        CustomerSummary summary = new CustomerSummary(customer, portalUser, "A", "a@x.vn", null, null, null, "ACTIVE", 0,
                NOW);
        when(customers.findById(customer)).thenReturn(Optional.of(summary));
        when(customers.findByUserId(portalUser)).thenReturn(Optional.of(summary));
        when(customers.creditTerms(customer)).thenReturn(new CreditTerms(customer, true, true, true, false,
                CommercialTerm.DEPOSIT, new BigDecimal("30"), null, null, "VND", null, null, null, 1));
        when(search.findRow(any())).thenAnswer(call -> Optional.of(new QuoteSearch.Row(call.getArgument(0), "QT-1",
                customer, QuoteStatus.SENT, 1, PaymentTerm.DEPOSIT, NOW.plusSeconds(3600), NOW, null, NOW, "sales",
                VERSION)));
        when(search.history(any())).thenReturn(List.of());
        when(products.lockedPurchasability(any())).thenReturn(Map.of());
        when(production.approvedSampleOf(any(), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
    }

    private Quote stored(QuoteStatus status, Instant validUntil) {
        boolean answered = status == QuoteStatus.ACCEPTED || status == QuoteStatus.CHANGES_REQUESTED;
        QuoteLine line = new QuoteLine(UUID.randomUUID(), 1, new Sku("CUP-PP-500"), design, null, 1000,
                List.of(PriceTier.of(1000, new BigDecimal("2000")), PriceTier.of(5000, new BigDecimal("1800"))),
                status == QuoteStatus.ACCEPTED ? 1000 : null, status == QuoteStatus.ACCEPTED ? Money.vnd(2000) : null);
        Quote quote = Quote.restore(UUID.randomUUID(), "QT-1", UUID.randomUUID(), customer, status, 1,
                new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("40"), validUntil, null, List.of(line)),
                status == QuoteStatus.DRAFT ? null : NOW, status == QuoteStatus.DRAFT ? null : salesUser,
                answered ? NOW : null, answered ? portalUser : null, null, null, null, null);
        when(quotes.findByIdForUpdate(quote.id())).thenReturn(Optional.of(quote));
        when(quotes.findById(quote.id())).thenReturn(Optional.of(quote));
        return quote;
    }

    private Quote stored(QuoteStatus status) {
        return stored(status, NOW.plusSeconds(3600));
    }

    private static ErrorCode code(Runnable call) {
        BusinessException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(BusinessException.class,
                call::run);
        assertThat(thrown).as("a BusinessException").isNotNull();
        return thrown.errorCode();
    }

    @Test
    @DisplayName("Sales sends a draft: it becomes SENT with who and when, and the offer is frozen into the history row")
    void sendingADraft() {
        Quote quote = stored(QuoteStatus.DRAFT);

        service.send(sales, quote.id(), VERSION);

        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quote.sentBy()).isEqualTo(salesUser);
        assertThat(quote.sentAt()).isEqualTo(NOW);
        verify(quotes).save(quote);
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(quotes).recordTransition(eq(quote.id()), eq(1), eq(QuoteStatus.DRAFT), eq(QuoteStatus.SENT), eq(null),
                snapshot.capture(), eq(NOW), eq(salesUser));
        assertThat(snapshot.getValue()).contains("CUP-PP-500");
    }

    @Test
    @DisplayName("a stale version is refused (OPTIMISTIC_LOCK) before anything changes")
    void staleVersion() {
        Quote quote = stored(QuoteStatus.DRAFT);

        assertThat(code(() -> service.send(sales, quote.id(), VERSION - 1))).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);
        verify(quotes, never()).save(any());
    }

    @Test
    @DisplayName("a quote holding a SKU that is no longer active cannot be sent: SKU_NOT_SELLABLE names the line")
    void inactiveSkuBlocksSending() {
        Quote quote = stored(QuoteStatus.DRAFT);
        when(products.lockedPurchasability(any())).thenReturn(Map.of("CUP-PP-500", Purchasability.PRODUCT_DISCONTINUED));

        BusinessException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(BusinessException.class,
                () -> service.send(sales, quote.id(), VERSION));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SKU_NOT_SELLABLE);
        assertThat(thrown.fieldErrors()).singleElement()
                .satisfies(error -> assertThat(error.field()).isEqualTo("lines[0].blankSku"));
        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);
    }

    @Test
    @DisplayName("a quote is offered only on a payment term the customer's commercial terms allow (15 BR-01)")
    void termNotAllowedBlocksSending() {
        Quote quote = stored(QuoteStatus.DRAFT);
        when(customers.creditTerms(customer)).thenReturn(CreditTerms.prepaidOnly(customer));

        assertThat(code(() -> service.send(sales, quote.id(), VERSION))).isEqualTo(ErrorCode.PAYMENT_TERM_NOT_ALLOWED);
    }

    @Test
    @DisplayName("quotes go to ACTIVE customers only (BR-ORD-007)")
    void inactiveCustomerBlocksSending() {
        Quote quote = stored(QuoteStatus.DRAFT);
        when(customers.findById(customer)).thenReturn(Optional.of(new CustomerSummary(customer, portalUser, "A",
                "a@x.vn", null, null, null, "SUSPENDED", 0, NOW)));

        assertThat(code(() -> service.send(sales, quote.id(), VERSION))).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);
    }

    @Test
    @DisplayName("the customer accepts: ACCEPTED at the proposed quantity, history written, Sales told by event")
    void customerAccepts() {
        Quote quote = stored(QuoteStatus.SENT);

        service.accept(portal, quote.id(), new Quotes.Acceptance(VERSION, Map.of()));

        assertThat(quote.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(quote.respondedBy()).isEqualTo(portalUser);
        assertThat(quote.offer().lines().get(0).acceptedQuantity()).isEqualTo(1000);
        verify(quotes).recordTransition(eq(quote.id()), eq(1), eq(QuoteStatus.SENT), eq(QuoteStatus.ACCEPTED), eq(null),
                eq(null), eq(NOW), eq(portalUser));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(CustomerActedOnQuote.class, acted -> {
            assertThat(acted.action()).isEqualTo(CustomerActedOnQuote.Action.ACCEPTED);
            assertThat(acted.salesUserId()).isEqualTo(salesUser);
            assertThat(acted.quoteNumber()).isEqualTo("QT-1");
        });
    }

    @Test
    @DisplayName("staff do not answer for the customer: accepting as Sales is FORBIDDEN")
    void staffCannotAccept() {
        Quote quote = stored(QuoteStatus.SENT);

        assertThat(code(() -> service.accept(sales, quote.id(), new Quotes.Acceptance(VERSION, Map.of()))))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(code(() -> service.requestChanges(sales, quote.id(), VERSION, "cheaper")))
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("a sent quote past its validity is answered QUOTE_EXPIRED, whatever its samples (BR-ORD-008)")
    void expiredQuoteIsNotAccepted() {
        Quote quote = stored(QuoteStatus.SENT, NOW.minusSeconds(1));

        assertThat(code(() -> service.accept(portal, quote.id(), new Quotes.Acceptance(VERSION, Map.of()))))
                .isEqualTo(ErrorCode.QUOTE_EXPIRED);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
        verify(quotes, never()).save(any());
    }

    @Test
    @DisplayName("a customer cannot see or answer another customer's quote, nor a working draft: NOT_FOUND")
    void quotesOfOthersAreHidden() {
        Quote draft = stored(QuoteStatus.DRAFT);
        UUID stranger = UUID.randomUUID();
        when(customers.findByUserId(stranger)).thenReturn(Optional.of(new CustomerSummary(UUID.randomUUID(), stranger,
                "B", "b@x.vn", null, null, null, "ACTIVE", 0, NOW)));
        CurrentUser other = new CurrentUser(stranger, "other", Set.of(Role.CUSTOMER), Set.of(), DataScope.ALL, Set.of(),
                Set.of());
        Quote sent = stored(QuoteStatus.SENT);

        assertThat(code(() -> service.get(portal, draft.id()))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(code(() -> service.get(other, sent.id()))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(code(() -> service.accept(other, sent.id(), new Quotes.Acceptance(VERSION, Map.of()))))
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(service.get(portal, sent.id()).status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("asking for changes records the feedback on the history row, not on the event")
    void requestChanges() {
        Quote quote = stored(QuoteStatus.SENT);

        service.requestChanges(portal, quote.id(), VERSION, "  lower the price  ");

        assertThat(quote.status()).isEqualTo(QuoteStatus.CHANGES_REQUESTED);
        assertThat(quote.customerFeedback()).isEqualTo("lower the price");
        verify(quotes).recordTransition(eq(quote.id()), eq(1), eq(QuoteStatus.SENT),
                eq(QuoteStatus.CHANGES_REQUESTED), eq("lower the price"), eq(null), eq(NOW), eq(portalUser));
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().toString()).doesNotContain("lower the price");
    }

    @Test
    @DisplayName("feedback is required (VALIDATION_FAILED)")
    void feedbackIsRequired() {
        Quote quote = stored(QuoteStatus.SENT);

        assertThat(code(() -> service.requestChanges(portal, quote.id(), VERSION, "  ")))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("Sales reopens a quote that came back with changes: DRAFT again, next revision")
    void reopen() {
        Quote quote = stored(QuoteStatus.CHANGES_REQUESTED);

        service.reopen(sales, quote.id(), VERSION);

        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(quote.revision()).isEqualTo(2);
        assertThat(quote.customerFeedback()).isNull();
        verify(quotes).recordTransition(eq(quote.id()), eq(2), eq(QuoteStatus.CHANGES_REQUESTED), eq(QuoteStatus.DRAFT),
                eq(null), eq(null), eq(NOW), eq(salesUser));
    }

    @Test
    @DisplayName("cancelling a draft needs a reason and keeps it in the history")
    void cancelDraft() {
        Quote quote = stored(QuoteStatus.DRAFT);

        assertThat(code(() -> service.cancel(sales, quote.id(), VERSION, " "))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        service.cancel(sales, quote.id(), VERSION, "customer went elsewhere");

        assertThat(quote.status()).isEqualTo(QuoteStatus.CANCELLED);
        verify(quotes).recordTransition(eq(quote.id()), eq(1), eq(QuoteStatus.DRAFT), eq(QuoteStatus.CANCELLED),
                eq("customer went elsewhere"), eq(null), eq(NOW), eq(salesUser));
    }

    @Test
    @DisplayName("an accepted quote is cancelled only when a SKU on it stopped being active (decision 2026-10-17)")
    void acceptedQuoteNeedsAStoppedSku() {
        Quote quote = stored(QuoteStatus.ACCEPTED);

        assertThat(code(() -> service.cancel(sales, quote.id(), VERSION, "changed mind")))
                .isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        assertThat(quote.status()).isEqualTo(QuoteStatus.ACCEPTED);

        when(products.lockedPurchasability(any())).thenReturn(Map.of("CUP-PP-500", Purchasability.PRODUCT_INACTIVE));
        service.cancel(sales, quote.id(), VERSION, "SKU stopped");

        assertThat(quote.status()).isEqualTo(QuoteStatus.CANCELLED);
    }

    @Test
    @DisplayName("a portal customer cannot send or cancel: those are back-office steps")
    void customersDoNotRunSales() {
        Quote quote = stored(QuoteStatus.SENT);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.cancel(portal, quote.id(), VERSION, "x"))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.send(portal, quote.id(), VERSION))
                .isInstanceOf(RuntimeException.class);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    @DisplayName("the sweep writes EXPIRED for sent quotes past their validity, with no actor, and counts them")
    void expiryAppliesToDueQuotes() {
        Quote due = stored(QuoteStatus.SENT, NOW.minusSeconds(10));
        Quote notYet = stored(QuoteStatus.SENT, NOW.plusSeconds(10));
        when(quotes.sentAndExpiredBy(NOW, 50)).thenReturn(List.of(due.id(), notYet.id(), UUID.randomUUID()));

        assertThat(service.expireDue(50)).isEqualTo(1);

        assertThat(due.status()).isEqualTo(QuoteStatus.EXPIRED);
        assertThat(notYet.status()).isEqualTo(QuoteStatus.SENT);
        verify(quotes).save(due);
        verify(quotes).recordTransition(due.id(), 1, QuoteStatus.SENT, QuoteStatus.EXPIRED, null, null, NOW, null);
        verify(quotes, never()).save(notYet);
    }

    @Test
    @DisplayName("lists show a sent quote past its validity as EXPIRED before the sweep has run")
    void listReadsExpiredEarly() {
        UUID id = UUID.randomUUID();
        when(search.search(any(), any(org.springframework.data.domain.Pageable.class))).thenReturn(
                new org.springframework.data.domain.PageImpl<>(List.of(new QuoteSearch.Row(id, "QT-2", customer,
                        QuoteStatus.SENT, 1, PaymentTerm.DEPOSIT, NOW.minusSeconds(1), NOW.minusSeconds(100), null, NOW,
                        "sales", 3))));

        var page = service.list(sales, new Quotes.Criteria(null, List.of(), null, 0, 20, null));

        assertThat(page.items()).singleElement().satisfies(row -> assertThat(row.status()).isEqualTo(QuoteStatus.EXPIRED));
    }
}
