package com.stockflow.fulfillment.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import com.stockflow.fulfillment.internal.domain.CarrierReport;
import com.stockflow.fulfillment.internal.domain.LoadStatus;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierEventJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierBookingJpaRepository;
import com.stockflow.fulfillment.internal.repository.CarrierEventJpaRepository;
import com.stockflow.fulfillment.internal.repository.DeliveryOutcomeCorrectionJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.TrackingMilestoneJpaRepository;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CarrierEventApplier}'s second step over mocked repositories: one dispatched load, its shipments and their
 * states, the reports applied before - what the staleness rule and the outcomes read. Each shipment outcome the applier
 * asks {@link ShipmentService#carrierOutcome} for is recorded and answered as changed.
 */
final class CarrierApplierHarness {

    static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    final CarrierEventJpaRepository events = mock(CarrierEventJpaRepository.class);
    final DispatchLoadJpaRepository loads = mock(DispatchLoadJpaRepository.class);
    final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    final TrackingMilestoneJpaRepository milestones = mock(TrackingMilestoneJpaRepository.class);
    final DeliveryOutcomeCorrectionJpaRepository corrections = mock(DeliveryOutcomeCorrectionJpaRepository.class);
    final ShipmentService outcomes = mock(ShipmentService.class);
    final CarrierEventApplier applier = new CarrierEventApplier(events, loads, mock(CarrierBookingJpaRepository.class),
            shipments, milestones, corrections, mock(Dispatching.class), outcomes, mock(ApplicationEventPublisher.class),
            new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    final UUID loadId = UUID.randomUUID();
    final List<ShipmentJpaRepository.ShipmentState> onLoad = new ArrayList<>();
    /** Every {@code carrierOutcome} asked: shipment id and target. */
    final List<Map.Entry<UUID, ShipmentStatus>> applied = new ArrayList<>();

    CarrierApplierHarness() {
        DispatchLoadJpaRepository.LoadFacts load = mock(DispatchLoadJpaRepository.LoadFacts.class);
        when(load.getStatus()).thenReturn(LoadStatus.DISPATCHED);
        when(loads.findFactsById(loadId)).thenReturn(Optional.of(load));
        when(shipments.statesOnLoad(loadId)).thenReturn(onLoad);
        when(corrections.correctedAmong(anyList())).thenReturn(List.of());
        when(milestones.recordFromCarrier(any(), any(), anyString(), any(), any(), any())).thenReturn(1);
        when(outcomes.carrierOutcome(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            applied.add(Map.entry(call.getArgument(0), call.getArgument(1)));
            return true;
        });
    }

    /** A shipment on the load, in {@code status}. */
    UUID shipment(String code, ShipmentStatus status) {
        UUID id = UUID.randomUUID();
        ShipmentJpaRepository.ShipmentState state = mock(ShipmentJpaRepository.ShipmentState.class);
        when(state.getId()).thenReturn(id);
        when(state.getCode()).thenReturn(code);
        when(state.getStatus()).thenReturn(status);
        onLoad.add(state);
        return id;
    }

    static CarrierJpaEntity carrier(CarrierConnectionType type) {
        CarrierJpaEntity carrier = mock(CarrierJpaEntity.class);
        when(carrier.getId()).thenReturn(UUID.randomUUID());
        when(carrier.getConnectionType()).thenReturn(type.name());
        when(carrier.getFailureReasonMap()).thenReturn(Map.of());
        return carrier;
    }

    /** A received call, matched to the load and - when {@code shipmentId} is not null - to one shipment. */
    CarrierEventApplier.Matched matched(CarrierEventKind kind, UUID shipmentId, Instant reportedAt) {
        UUID eventId = UUID.randomUUID();
        CarrierEventJpaEntity event = new CarrierEventJpaEntity(eventId, UUID.randomUUID(), "TRUCK", NOW, "{}", true,
                null);
        when(events.findById(eventId)).thenReturn(Optional.of(event));
        CarrierReport report = new CarrierReport(kind, "LOAD-1", null, null, reportedAt, null, null, null, null,
                kind == CarrierEventKind.DELIVERY_FAILED ? "khách hẹn lại" : null);
        lastEvent = event;
        return new CarrierEventApplier.Matched(report, loadId, "LOAD-1", UUID.randomUUID(), null, null, shipmentId,
                "key-" + eventId, List.of(), null);
    }

    /** The event the last {@link #matched} made: what {@link #apply} decided, and why. */
    CarrierEventJpaEntity lastEvent;

    CarrierEventResult apply(CarrierJpaEntity carrier, CarrierEventApplier.Matched matched) {
        return applier.apply(lastEvent.getId(), carrier, matched);
    }
}
