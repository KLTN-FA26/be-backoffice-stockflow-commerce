package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.ShortageCard;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The expected delivery date of the "wait for production" option (kltn-docs 17 §4.14 step 56, D48): the latest expected
 * completion of an open production order of the batch that makes one of the short SKUs, else the day the card is sent
 * plus the standard lead time.
 */
class ShortageCardExpectedDateTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-28");
    private final UUID batch = UUID.randomUUID();
    private final UUID shortLine = UUID.randomUUID();

    private ProductionOrder production(UUID forBatch, ProductionOrderStatus status, UUID orderLine) {
        ProductionOrder order = mock(ProductionOrder.class);
        ProductionOrder.Line line = mock(ProductionOrder.Line.class);
        when(line.orderLineId()).thenReturn(orderLine);
        when(order.id()).thenReturn(UUID.randomUUID());
        when(order.deliveryBatchId()).thenReturn(forBatch);
        when(order.status()).thenReturn(status);
        when(order.lines()).thenReturn(List.of(line));
        return order;
    }

    @Test
    void withNoProductionOrderItIsTodayPlusTheStandardLeadTime() {
        ShortageCardService.Expected expected = ShortageCardService.expectedDelivery(Set.of(shortLine), batch, List.of(),
                Map.of(), TODAY, 14);
        assertThat(expected.date()).isEqualTo(LocalDate.parse("2026-12-12"));
        assertThat(expected.source()).isEqualTo(ShortageCard.DateSource.STANDARD_LEAD_TIME);
    }

    @Test
    void theLatestExpectedCompletionOfTheOpenOrdersCoveringTheShortSkuWins() {
        ProductionOrder early = production(batch, ProductionOrderStatus.IN_PRODUCTION, shortLine);
        ProductionOrder late = production(batch, ProductionOrderStatus.PARTIALLY_RECEIVED, shortLine);
        ShortageCardService.Expected expected = ShortageCardService.expectedDelivery(Set.of(shortLine), batch,
                List.of(early, late), Map.of(early.id(), LocalDate.parse("2026-12-05"),
                        late.id(), LocalDate.parse("2026-12-20")), TODAY, 14);
        assertThat(expected.date()).isEqualTo(LocalDate.parse("2026-12-20"));
        assertThat(expected.source()).isEqualTo(ShortageCard.DateSource.PRODUCTION_ORDER);
    }

    @Test
    void aClosedOrderAnotherBatchOrAnotherSkuDoesNotCover() {
        ProductionOrder closed = production(batch, ProductionOrderStatus.CLOSED, shortLine);
        ProductionOrder otherBatch = production(UUID.randomUUID(), ProductionOrderStatus.SENT, shortLine);
        ProductionOrder otherSku = production(batch, ProductionOrderStatus.SENT, UUID.randomUUID());
        ShortageCardService.Expected expected = ShortageCardService.expectedDelivery(Set.of(shortLine), batch,
                List.of(closed, otherBatch, otherSku), Map.of(closed.id(), LocalDate.parse("2027-01-01"),
                        otherBatch.id(), LocalDate.parse("2027-01-01"), otherSku.id(), LocalDate.parse("2027-01-01")),
                TODAY, 7);
        assertThat(expected.date()).isEqualTo(LocalDate.parse("2026-12-05"));
        assertThat(expected.source()).isEqualTo(ShortageCard.DateSource.STANDARD_LEAD_TIME);
    }

    @Test
    void anOpenOrderWithNoExpectedDateFallsBackToTheLeadTime() {
        ProductionOrder sent = production(batch, ProductionOrderStatus.SENT, shortLine);
        ShortageCardService.Expected expected = ShortageCardService.expectedDelivery(Set.of(shortLine), batch,
                List.of(sent), Map.of(), TODAY, 21);
        assertThat(expected.date()).isEqualTo(LocalDate.parse("2026-12-19"));
        assertThat(expected.source()).isEqualTo(ShortageCard.DateSource.STANDARD_LEAD_TIME);
    }
}
