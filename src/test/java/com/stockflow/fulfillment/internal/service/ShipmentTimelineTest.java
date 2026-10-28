package com.stockflow.fulfillment.internal.service;

import com.stockflow.contracts.ShipmentStatusChanged;
import com.stockflow.fulfillment.internal.domain.DeliveryFailureReason;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.ShipmentEventJpaEntity;
import com.stockflow.fulfillment.internal.repository.ShipmentEventJpaRepository;
import com.stockflow.order.api.OrderSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Every shipment step leaves one timeline row (09 BR-02) and one announcement, in order. */
class ShipmentTimelineTest {

    private final ShipmentEventJpaRepository events = mock(ShipmentEventJpaRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ShipmentTimeline timeline = new ShipmentTimeline(events, publisher);

    private ShipmentStatusChanged[] published(int expected) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(expected)).publishEvent(captor.capture());
        return captor.getAllValues().stream().map(ShipmentStatusChanged.class::cast)
                .toArray(ShipmentStatusChanged[]::new);
    }

    @Test
    @DisplayName("a shipment just born is announced from nothing to READY_TO_DISPATCH, with the order number given")
    void newShipmentHasNoPreviousStatus() {
        Shipment shipment = FulfillmentFixtures.readyShipment(DeliveryMode.PARCEL, 3, "0");

        timeline.record(shipment, null, null, "SO-0042", null, null);

        ShipmentStatusChanged event = published(1)[0];
        assertThat(event.previousStatus()).isNull();
        assertThat(event.status()).isEqualTo("READY_TO_DISPATCH");
        assertThat(event.orderNumber()).isEqualTo("SO-0042");
        assertThat(event.customerId()).isNull();
        assertThat(event.packageCount()).isEqualTo(3);
        assertThat(event.shipmentCode()).isEqualTo(shipment.code());
        verify(events).save(any(ShipmentEventJpaEntity.class));
    }

    @Test
    @DisplayName("the order's number and customer win over the fallback number")
    void orderSuppliesNumberAndCustomer() {
        Shipment shipment = FulfillmentFixtures.readyShipment();
        OrderSummary order = FulfillmentFixtures.order(shipment.orderId(), null);

        timeline.record(shipment, null, order, "ignored", null, null);

        ShipmentStatusChanged event = published(1)[0];
        assertThat(event.orderNumber()).isEqualTo(order.orderNumber());
        assertThat(event.customerId()).isEqualTo(order.customerId());
    }

    @Test
    @DisplayName("steps taken together are announced in order, each from the status before it, with carrier and load")
    void severalStepsChainTheirPreviousStatus() {
        Shipment shipment = FulfillmentFixtures.readyShipment();
        shipment.pullRecorded();
        UUID by = UUID.randomUUID();
        shipment.handOver(UUID.randomUUID(), UUID.randomUUID(), null, false, by, NOW);
        shipment.recordDelivered(by, NOW.plusSeconds(60));

        timeline.record(shipment, ShipmentStatus.READY_TO_DISPATCH, null, "SO-1", "GHN Express", "LD-7");

        ShipmentStatusChanged[] sent = published(2);
        assertThat(sent[0].previousStatus()).isEqualTo("READY_TO_DISPATCH");
        assertThat(sent[0].status()).isEqualTo("HANDED_OVER");
        assertThat(sent[1].previousStatus()).isEqualTo("HANDED_OVER");
        assertThat(sent[1].status()).isEqualTo("DELIVERED");
        assertThat(sent[1].carrierName()).isEqualTo("GHN Express");
        assertThat(sent[1].loadCode()).isEqualTo("LD-7");
        assertThat(sent[1].recordedBy()).isEqualTo(by);
        verify(events, times(2)).save(any(ShipmentEventJpaEntity.class));
        assertThat(shipment.pullRecorded()).as("the steps are consumed").isEmpty();
    }

    @Test
    @DisplayName("only a failed delivery carries its reason code")
    void failureReasonOnlyOnTheFailureStep() {
        Shipment shipment = FulfillmentFixtures.readyShipment();
        shipment.pullRecorded();
        UUID by = UUID.randomUUID();
        shipment.handOver(UUID.randomUUID(), UUID.randomUUID(), null, false, by, NOW);
        shipment.recordFailed(DeliveryFailureReason.REFUSED, "no", by, NOW.plusSeconds(5));

        timeline.record(shipment, ShipmentStatus.READY_TO_DISPATCH, null, "SO-1", null, null);

        ShipmentStatusChanged[] sent = published(2);
        assertThat(sent[0].failureReasonCode()).isNull();
        assertThat(sent[1].failureReasonCode()).isEqualTo("REFUSED");
    }

    @Test
    @DisplayName("a shipment with no new step writes and announces nothing")
    void nothingRecordedNothingSent() {
        Shipment shipment = FulfillmentFixtures.readyShipment();
        shipment.pullRecorded();

        timeline.record(shipment, ShipmentStatus.READY_TO_DISPATCH, null, null, null, null);

        verify(events, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
