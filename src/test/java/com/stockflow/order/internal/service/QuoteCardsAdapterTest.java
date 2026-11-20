package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.QuoteCards;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteRepository;
import com.stockflow.order.internal.domain.QuoteStatus;
import com.stockflow.order.internal.repository.QuoteSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The quote card in the customer's conversation links to the order the quote became only once the customer can open it
 * (17 §5.2): while the order is a DRAFT - its contract not sent yet - a link led the customer to a 404.
 */
class QuoteCardsAdapterTest {

    private final Quotes quotes = mock(Quotes.class);
    private final QuoteRepository repository = mock(QuoteRepository.class);
    private final QuoteSearch search = mock(QuoteSearch.class);
    private final QuoteCardsAdapter adapter = new QuoteCardsAdapter(quotes, repository, search);

    private final UUID quote = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    private void converted(OrderStatus status) {
        when(repository.findById(quote)).thenReturn(Optional.of(mock(Quote.class)));
        when(quotes.get(any(), eq(quote))).thenReturn(new Quotes.QuoteView(quote, "QT-1", UUID.randomUUID(),
                QuoteStatus.CONVERTED, 1, Instant.now(), null, null, false, List.of(), List.of(), true, null, null,
                BigDecimal.TEN, BigDecimal.TEN, Instant.now(), UUID.randomUUID(), null, null, null, null,
                new Quotes.ConvertedOrder(order, "SO-1", status, BigDecimal.TEN), Instant.now(), List.of(),
                Instant.now(), "sales", 3));
    }

    @Test
    @DisplayName("a DRAFT order is not on the card; once its contract is sent it is, and the order finds its quote")
    void theCardLinksTheOrderOnceTheCustomerCanOpenIt() {
        converted(OrderStatus.DRAFT);
        QuoteCards.Card drafted = adapter.quote(quote).orElseThrow();
        assertThat(drafted.status()).isEqualTo("CONVERTED");
        assertThat(drafted.convertedOrderId()).isNull();
        assertThat(drafted.convertedOrderNumber()).isNull();

        converted(OrderStatus.PENDING_CONTRACT);
        when(search.quoteConvertedInto(order)).thenReturn(Optional.of(quote));
        QuoteCards.Card sent = adapter.quoteOfOrder(order).orElseThrow();
        assertThat(sent.convertedOrderId()).isEqualTo(order);
        assertThat(sent.convertedOrderNumber()).isEqualTo("SO-1");

        assertThat(adapter.quoteOfOrder(UUID.randomUUID())).as("an order no quote became").isEmpty();
    }
}
