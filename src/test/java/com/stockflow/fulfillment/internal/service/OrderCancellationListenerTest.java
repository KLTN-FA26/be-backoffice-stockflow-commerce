package com.stockflow.fulfillment.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** kltn-docs 17 §4.5 BR-03: cancelling an order before it ships stops the warehouse work, once. */
class OrderCancellationListenerTest {

    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final PackJpaRepository packs = mock(PackJpaRepository.class);
    private final PickListService pickLists = mock(PickListService.class);
    private final PackingService packing = mock(PackingService.class);
    private final OrderCancellationListener listener = new OrderCancellationListener(picks, packs, pickLists, packing);

    private final UUID orderId = UUID.randomUUID();
    private final OrderCancelled event = new OrderCancelled(orderId, "SO-0001", UUID.randomUUID(), "RELEASED", "CUSTOMER",
            null, BigDecimal.ZERO, BigDecimal.ZERO, "VND", UUID.randomUUID(), NOW, BigDecimal.ZERO);

    private PickJpaEntity pick(PickStatus status) {
        return new PickJpaEntity(UUID.randomUUID(), null, orderId, "SO-0001", WAREHOUSE, status);
    }

    @Test
    @DisplayName("an order that never reached fulfillment has nothing to cancel")
    void orderWithoutAPick() {
        when(picks.lockByOrderId(orderId)).thenReturn(Optional.empty());

        listener.on(event);

        verify(picks, never()).save(any());
        verifyNoInteractions(pickLists, packing, packs);
    }

    @Test
    @DisplayName("a pick not yet started is cancelled with its open lines, without a put-back")
    void pendingPickIsCancelled() {
        PickJpaEntity pick = pick(PickStatus.PENDING);
        when(picks.lockByOrderId(orderId)).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.empty());

        listener.on(event);

        assertThat(pick.getStatus()).isEqualTo(PickStatus.CANCELLED);
        assertThat(pick.isNeedsPutBack()).isFalse();
        verify(picks).save(pick);
        verify(pickLists).cancelOpenLines(pick);
        verifyNoInteractions(packing);
    }

    @Test
    @DisplayName("a pick already started is flagged so its goods are put back (SCRUM-460)")
    void startedPickNeedsPutBack() {
        PickJpaEntity pick = pick(PickStatus.PICKING);
        when(picks.lockByOrderId(orderId)).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.empty());

        listener.on(event);

        assertThat(pick.isNeedsPutBack()).isTrue();
    }

    @Test
    @DisplayName("a pack is voided with its parcels and labels, then cancelled (08 BR-05)")
    void packIsCancelledAfterItsParcelsAreVoided() {
        PickJpaEntity pick = pick(PickStatus.PICKED);
        PackJpaEntity pack = new PackJpaEntity(UUID.randomUUID(), pick.getId(), PackStatus.PACKING, null);
        when(picks.lockByOrderId(orderId)).thenReturn(Optional.of(pick));
        when(packs.findByPickId(pick.getId())).thenReturn(Optional.of(pack));

        listener.on(event);

        verify(packing).cancelForOrder(pick, pack);
        assertThat(pack.getStatus()).isEqualTo(PackStatus.CANCELLED);
        verify(packs).save(pack);
    }

    @Test
    @DisplayName("the event delivered twice does nothing the second time")
    void redeliveryIsIdempotent() {
        PickJpaEntity pick = pick(PickStatus.CANCELLED);
        when(picks.lockByOrderId(orderId)).thenReturn(Optional.of(pick));

        listener.on(event);

        verify(picks, never()).save(any());
        verifyNoInteractions(pickLists, packing, packs);
    }
}
