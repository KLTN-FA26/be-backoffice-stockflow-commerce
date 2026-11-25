package com.stockflow.order.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.contracts.QuoteStatusChanged;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.order.internal.domain.PriceTier;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteLine;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Decision D-1 (2026-11-25): a quote sent with a carton line more than the configured share below the customer's own
 * price at the line's cartons is told to the holders of the sales manager's permission, once per revision. The rest of
 * the flow is untouched, so only the listener is under test, with its collaborators mocked.
 */
class QuoteDiscountAlertsTest {

    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    private static final String SKU = "LY12-ACME-01";
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    private final QuoteRepository quotes = mock(QuoteRepository.class);
    private final CatalogService catalog = mock(CatalogService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final UUID customer = UUID.randomUUID();

    private QuoteDiscountAlerts alerts(String percent) {
        return new QuoteDiscountAlerts(quotes, catalog, identities, inbox, new BigDecimal(percent));
    }

    /** The customer's own price of {@link #SKU}: 2,000 from 10 cartons, 1,700 from 50. */
    private void ownPrice() {
        when(catalog.customerPrice(customer, SKU)).thenReturn(Optional.of(new PriceSchedule(SKU, 10, List.of(
                new PriceSchedule.Tier(10, Money.vnd(2000)), new PriceSchedule.Tier(50, Money.vnd(1700))))));
    }

    private void managers(UUID... ids) {
        when(identities.notificationRecipientsHolding(QuoteDiscountAlerts.MANAGES_SALES, null)).thenReturn(
                java.util.Arrays.stream(ids).map(id -> new StaffContact(id, id + "@x.vn")).toList());
        when(inbox.post(anyCollection(), any())).thenReturn(ids.length);
    }

    private static QuoteLine line(int cartons, long price) {
        return QuoteLine.carton(UUID.randomUUID(), 1, new Sku(SKU), UUID.randomUUID(), 50, cartons,
                List.of(PriceTier.of(10, BigDecimal.valueOf(price))), null, null, "STANDARD", new BigDecimal("10"));
    }

    private Quote sent(int revision, QuoteLine... lines) {
        Quote quote = Quote.restore(UUID.randomUUID(), "QT-7", UUID.randomUUID(), customer, QuoteStatus.SENT, revision,
                new Quote.Offer(NOW.plusSeconds(86_400), null, List.of(lines)), NOW, UUID.randomUUID(), null, null,
                null, null, null, null);
        when(quotes.findById(quote.id())).thenReturn(Optional.of(quote));
        return quote;
    }

    private QuoteStatusChanged event(Quote quote, int revision, QuoteStatus to) {
        return new QuoteStatusChanged(quote.id(), quote.quoteNumber(), customer, revision, QuoteStatus.DRAFT.name(),
                to.name(), UUID.randomUUID(), NOW);
    }

    @Test
    void aLineMoreThanTenPercentBelowTheCustomersOwnPriceIsToldToEveryManagerInTheInbox() {
        ownPrice();
        managers(MANAGER, ADMIN);
        Quote quote = sent(1, line(20, 1799));

        alerts("10").on(event(quote, 1, QuoteStatus.SENT));

        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(MANAGER, ADMIN)), message.capture());
        assertThat(message.getValue().severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(message.getValue().title()).contains("QT-7");
        assertThat(message.getValue().body()).contains("10%").contains(SKU).contains("1.799").contains("2.000");
        assertThat(message.getValue().link()).isEqualTo("/admin/sales/quotes/" + quote.id());
        assertThat(message.getValue().dedupeKey()).isEqualTo("quote-discount:" + quote.id() + ":1");
    }

    @Test
    void exactlyTenPercentBelowIsNotTold() {
        ownPrice();
        managers(MANAGER);
        Quote quote = sent(1, line(20, 1800));

        alerts("10").on(event(quote, 1, QuoteStatus.SENT));

        verify(inbox, never()).post(anyCollection(), any());
    }

    @Test
    void theOwnPriceIsTheTierOfTheLinesCartons() {
        ownPrice();
        // 60 cartons: the customer's own price is 1,700 there, so the floor is 1,530, not 1,800 of the first tier.
        assertThat(alerts("10").below(sent(1, line(60, 1531)))).isEmpty();
        assertThat(alerts("10").below(sent(1, line(60, 1529)))).singleElement()
                .isEqualTo(new QuoteDiscountAlerts.Below(SKU, 60, BigDecimal.valueOf(1529), BigDecimal.valueOf(1700)));
    }

    @Test
    void aLineWithNothingToCompareWithIsSkipped() {
        when(catalog.customerPrice(customer, SKU)).thenReturn(Optional.empty());
        Quote noOwnPrice = sent(1, line(20, 100));
        assertThat(alerts("10").below(noOwnPrice)).isEmpty();

        // Below the customer's own minimum the schedule has no price to compare with.
        when(catalog.customerPrice(customer, SKU)).thenReturn(Optional.of(new PriceSchedule(SKU, 30, List.of(
                new PriceSchedule.Tier(30, Money.vnd(2000))))));
        Quote belowOwnMinimum = sent(1, line(20, 100));
        assertThat(alerts("10").below(belowOwnMinimum)).isEmpty();
    }

    @Test
    void theThresholdIsConfigured() {
        ownPrice();
        Quote quote = sent(1, line(20, 1900));

        assertThat(alerts("10").below(quote)).isEmpty();
        assertThat(alerts("0").below(quote)).hasSize(1);
        assertThatThrownBy(() -> alerts("100")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> alerts("-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyASendIsChecked() {
        Quote quote = sent(1, line(20, 100));

        alerts("10").on(event(quote, 1, QuoteStatus.ACCEPTED));

        verifyNoInteractions(catalog, identities, inbox);
    }

    @Test
    void aRevisionReplacedBeforeTheListenerRanIsNotChecked() {
        ownPrice();
        managers(MANAGER);
        Quote quote = sent(2, line(20, 100));

        alerts("10").on(event(quote, 1, QuoteStatus.SENT));

        verifyNoInteractions(catalog, inbox);
    }

    /** The inbox posts once per recipient and key, so the key is what makes the alert once per revision. */
    @Test
    void theInboxKeyIsTheQuoteAndTheRevisionSent() {
        ownPrice();
        managers(MANAGER);
        Quote quote = sent(3, line(20, 100));

        alerts("10").on(event(quote, 3, QuoteStatus.SENT));

        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(anyCollection(), message.capture());
        assertThat(message.getValue().dedupeKey()).isEqualTo("quote-discount:" + quote.id() + ":3");
    }

    @Test
    void nobodyHoldingThePermissionPostsNothing() {
        ownPrice();
        managers();
        Quote quote = sent(1, line(20, 100));

        alerts("10").on(event(quote, 1, QuoteStatus.SENT));

        verify(inbox, never()).post(anyCollection(), any());
    }
}
