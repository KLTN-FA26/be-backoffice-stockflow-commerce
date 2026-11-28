package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProducerRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one lead-time rule behind PO_NEEDED and the expected delivery date of the shortage choice card (17 §4.12 BR-30,
 * B44; §4.14 step 56): the longest standard lead time of the producers the order's production orders went to, the
 * cancelled ones left out, else the configured default.
 */
class ProductionLeadTimeTest {

    private final ProducerRepository producers = mock(ProducerRepository.class);
    private final ProductionLeadTime leadTime = new ProductionLeadTime(producers, 14);

    private ProductionOrder madeBy(UUID producer, ProductionOrderStatus status, int leadDays) {
        ProductionOrder order = mock(ProductionOrder.class);
        when(order.producerId()).thenReturn(producer);
        when(order.status()).thenReturn(status);
        Producer found = mock(Producer.class);
        when(found.standardLeadDays()).thenReturn(leadDays);
        when(producers.findById(producer)).thenReturn(Optional.of(found));
        return order;
    }

    @Test
    void theDefaultAppliesWhileNothingWasDrawnUp() {
        assertThat(leadTime.leadDays(List.of())).isEqualTo(14);
    }

    @Test
    void theLongestOfTheProducersTheOrderWentToWins() {
        List<ProductionOrder> production = List.of(madeBy(UUID.randomUUID(), ProductionOrderStatus.SENT, 7),
                madeBy(UUID.randomUUID(), ProductionOrderStatus.IN_PRODUCTION, 21));
        assertThat(leadTime.leadDays(production)).isEqualTo(21);
    }

    @Test
    void aCancelledProductionOrderCountsForNothing() {
        List<ProductionOrder> production = List.of(madeBy(UUID.randomUUID(), ProductionOrderStatus.CANCELLED, 40),
                madeBy(UUID.randomUUID(), ProductionOrderStatus.SENT, 9));
        assertThat(leadTime.leadDays(production)).isEqualTo(9);
    }

    @Test
    void aNegativeDefaultIsRefused() {
        assertThatThrownBy(() -> new ProductionLeadTime(producers, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
