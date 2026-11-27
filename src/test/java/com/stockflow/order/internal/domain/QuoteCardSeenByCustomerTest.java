package com.stockflow.order.internal.domain;

import com.stockflow.order.api.QuoteCards;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QuoteCards.Card#seenByCustomer} is published for other modules, which cannot read {@link QuoteStatus}: it must
 * say what the order module's own rule says for every status, or a draft could quote prices the portal never showed.
 */
class QuoteCardSeenByCustomerTest {

    @Test
    @DisplayName("a card is seen by its customer exactly when its status is, for every status")
    void inStepWithTheStatus() {
        for (QuoteStatus status : QuoteStatus.values()) {
            QuoteCards.Card card = new QuoteCards.Card(UUID.randomUUID(), "QT-1", UUID.randomUUID(), status.name(), 1,
                    null, null, List.of(), null, null, null, null, null, null, 0);
            assertThat(card.seenByCustomer()).as(status.name()).isEqualTo(status.seenByCustomer());
        }
    }
}
