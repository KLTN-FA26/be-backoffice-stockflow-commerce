package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadShipmentJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentEventJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.OTHER_WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.WAREHOUSE;
import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.refusal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Shipments at dispatch (kltn-docs 09 §4.1 step 2, §4.3, §5, BR-02, BR-05): the carrier chosen before the handover,
 * the outcome staff record when the carrier reports back, and the order told in the same transaction.
 */
class ShipmentServiceTest {

    private final ShipmentJpaRepository shipments = mock(ShipmentJpaRepository.class);
    private final ShipmentLocks locks = mock(ShipmentLocks.class);
    private final ShipmentEventJpaRepository events = mock(ShipmentEventJpaRepository.class);
    private final PackageJpaRepository packages = mock(PackageJpaRepository.class);
    private final PickJpaRepository picks = mock(PickJpaRepository.class);
    private final CarrierJpaRepository carriers = mock(CarrierJpaRepository.class);
    private final DispatchLoadJpaRepository loads = mock(DispatchLoadJpaRepository.class);
    private final DispatchLoadShipmentJpaRepository members = mock(DispatchLoadShipmentJpaRepository.class);
    private final ShipmentTimeline timeline = mock(ShipmentTimeline.class);
    private final ShipmentContext context = mock(ShipmentContext.class);
    private final OrderService orders = mock(OrderService.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final ShipmentService service = new ShipmentService(shipments, locks, events, packages, picks, carriers,
            loads, members, timeline, context, orders, identities, Clock.fixed(NOW, ZoneOffset.UTC));

    private final com.stockflow.common.security.CurrentUser actor = FulfillmentFixtures.user();

    @BeforeEach
    void rowsEchoWhatTheyAreSaved() {
        when(shipments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private ShipmentJpaEntity locked(ShipmentJpaEntity row) {
        when(locks.lock(Set.of(row.getId()))).thenReturn(List.of(row));
        return row;
    }

    private CarrierJpaEntity carrier(String status) {
        CarrierJpaEntity carrier = new CarrierJpaEntity(UUID.randomUUID(), "GHN");
        carrier.apply("GHN", "GHN Express", null, null, status);
        when(carriers.findById(carrier.getId())).thenReturn(Optional.of(carrier));
        return carrier;
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("a shipment that does not exist is SHIPMENT_NOT_FOUND")
        void unknownShipment() {
            UUID id = UUID.randomUUID();
            when(shipments.findById(id)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.get(id)).errorCode()).isEqualTo(ErrorCode.SHIPMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("a shipment of a warehouse the caller is not assigned to is OUT_OF_DATA_SCOPE (BR-SEC-002)")
        void outOfScope() {
            ShipmentJpaEntity row = FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH);
            when(shipments.findById(row.getId())).thenReturn(Optional.of(row));

            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.get(row.getId())).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("the detail names its parcels, carrier, load and the people who recorded each step")
        void detailAssemblesTheView() {
            Shipment s = FulfillmentFixtures.readyShipment(DeliveryMode.PARCEL, 2, "250000");
            UUID carrierId = UUID.randomUUID();
            UUID loadId = UUID.randomUUID();
            UUID by = UUID.randomUUID();
            s.handOver(loadId, carrierId, null, false, by, NOW);
            ShipmentJpaEntity row = FulfillmentFixtures.row(s);
            when(shipments.findById(row.getId())).thenReturn(Optional.of(row));
            CarrierJpaEntity ghn = new CarrierJpaEntity(carrierId, "GHN");
            ghn.apply("GHN", "GHN Express", null, null, "ACTIVE");
            when(carriers.findById(carrierId)).thenReturn(Optional.of(ghn));
            when(carriers.findAllById(Set.of(carrierId))).thenReturn(List.of(ghn));
            var load = mock(com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity.class);
            when(load.getId()).thenReturn(loadId);
            when(load.getCode()).thenReturn("LD-0001");
            when(loads.findByIdIn(Set.of(loadId))).thenReturn(List.of(load));
            var step = new com.stockflow.fulfillment.internal.entity.ShipmentEventJpaEntity(UUID.randomUUID(),
                    row.getId(), "HANDED_OVER", NOW, null, by);
            when(events.findByShipmentIdOrderByOccurredAtAscIdAsc(row.getId())).thenReturn(List.of(step));
            when(identities.displayNames(List.of(by))).thenReturn(java.util.Map.of(by, "Dispatcher An"));
            when(context.currencies(any())).thenReturn(java.util.Map.of(row.getOrderId(), "VND"));

            Shipments.ShipmentDetail detail = service.get(row.getId());

            assertThat(detail.shipment().status()).isEqualTo("HANDED_OVER");
            assertThat(detail.shipment().carrierName()).isEqualTo("GHN Express");
            assertThat(detail.shipment().loadCode()).isEqualTo("LD-0001");
            assertThat(detail.shipment().currency()).isEqualTo("VND");
            assertThat(detail.shipment().packageCount()).isEqualTo(2);
            assertThat(detail.shipment().codAmount()).isEqualByComparingTo("250000");
            assertThat(detail.timeline()).singleElement().satisfies(t -> {
                assertThat(t.recordedByName()).isEqualTo("Dispatcher An");
                assertThat(t.status()).isEqualTo("HANDED_OVER");
            });
        }

        @Test
        @DisplayName("a list filtered by an unknown status is UNSUPPORTED_PARAMETER")
        void listRejectsUnknownStatus() {
            assertThat(refusal(() -> service.list(null, "SHIPPED", null, null, null, 0, 20)).errorCode())
                    .isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER);
        }

        @Test
        @DisplayName("a list asked for a warehouse the caller is not assigned to is OUT_OF_DATA_SCOPE")
        void listRejectsOtherWarehouse() {
            WithCurrentUser.run(FulfillmentFixtures.userOf(WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.list(OTHER_WAREHOUSE, null, null, null, null, 0, 20)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }

        @Test
        @DisplayName("a page of shipments is returned with each row's order number, newest first")
        void listPagesRows() {
            ShipmentJpaEntity row = FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH);
            when(shipments.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                    any(org.springframework.data.domain.Pageable.class)))
                    .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(row),
                            org.springframework.data.domain.PageRequest.of(0, 20), 1));
            var pick = mock(com.stockflow.fulfillment.internal.entity.PickJpaEntity.class);
            when(pick.getOrderId()).thenReturn(row.getOrderId());
            when(pick.getOrderNumber()).thenReturn("SO-0042");
            when(picks.findByOrderIdIn(Set.of(row.getOrderId()))).thenReturn(List.of(pick));

            var page = service.list(WAREHOUSE, "ready_to_dispatch", row.getOrderId(), null, " S0 ", 0, 20);

            assertThat(page.items()).singleElement().satisfies(r -> {
                assertThat(r.orderNumber()).isEqualTo("SO-0042");
                assertThat(r.status()).isEqualTo("READY_TO_DISPATCH");
                assertThat(r.currency()).isNull();
            });
        }
    }

    @Nested
    @DisplayName("choosing the carrier before the handover (09 §4.1 step 2)")
    class AssigningACarrier {

        @Test
        @DisplayName("a shipment that does not exist is SHIPMENT_NOT_FOUND")
        void unknownShipment() {
            when(locks.lock(any())).thenReturn(List.of());

            assertThat(refusal(() -> service.assignCarrier(UUID.randomUUID(), UUID.randomUUID(), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("an unknown carrier is CARRIER_NOT_FOUND")
        void unknownCarrier() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH));
            UUID carrierId = UUID.randomUUID();
            when(carriers.findById(carrierId)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.assignCarrier(row.getId(), carrierId, actor)).errorCode())
                    .isEqualTo(ErrorCode.CARRIER_NOT_FOUND);
        }

        @Test
        @DisplayName("an INACTIVE carrier takes no new shipment: CARRIER_INACTIVE")
        void inactiveCarrier() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH));
            CarrierJpaEntity carrier = carrier("INACTIVE");

            assertThat(refusal(() -> service.assignCarrier(row.getId(), carrier.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.CARRIER_INACTIVE);
            verify(shipments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an active carrier is recorded on the shipment waiting for dispatch")
        void assignsTheCarrier() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH));
            CarrierJpaEntity carrier = carrier("ACTIVE");

            Shipments.ShipmentDetail detail = service.assignCarrier(row.getId(), carrier.getId(), actor);

            assertThat(row.getCarrierId()).isEqualTo(carrier.getId());
            verify(shipments).saveAndFlush(row);
            assertThat(detail.shipment().shipmentId()).isEqualTo(row.getId());
        }

        @Test
        @DisplayName("null clears the carrier")
        void nullClearsTheCarrier() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.assignCarrier(UUID.randomUUID());
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));

            service.assignCarrier(row.getId(), null, actor);

            assertThat(row.getCarrierId()).isNull();
            verify(shipments).saveAndFlush(row);
            verify(carriers, never()).findById(any());
        }

        @Test
        @DisplayName("choosing the carrier it already has changes and saves nothing")
        void sameCarrierIsANoOp() {
            CarrierJpaEntity carrier = carrier("ACTIVE");
            Shipment s = FulfillmentFixtures.readyShipment();
            s.assignCarrier(carrier.getId());
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));

            service.assignCarrier(row.getId(), carrier.getId(), actor);

            verify(shipments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a shipment on a load cannot move to another carrier: SHIPMENT_ON_ANOTHER_LOAD")
        void onALoadTheCarrierIsFixed() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.assignCarrier(UUID.randomUUID());
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));
            CarrierJpaEntity other = carrier("ACTIVE");
            UUID loadId = UUID.randomUUID();
            when(members.findByShipmentIdInAndActiveTrue(Set.of(row.getId()))).thenReturn(List.of(
                    new DispatchLoadShipmentJpaEntity(UUID.randomUUID(), loadId, row.getId(), DeliveryMode.PARCEL)));
            when(loads.findById(loadId)).thenReturn(Optional.empty());

            BusinessException thrown = refusal(() -> service.assignCarrier(row.getId(), other.getId(), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SHIPMENT_ON_ANOTHER_LOAD);
            verify(shipments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("once handed over, its carrier is not changed: SHIPMENT_STATE_CONFLICT")
        void afterHandoverTheCarrierIsFixed() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));
            CarrierJpaEntity other = carrier("ACTIVE");

            assertThat(refusal(() -> service.assignCarrier(row.getId(), other.getId(), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a shipment of another warehouse is OUT_OF_DATA_SCOPE")
        void outOfScope() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH));

            WithCurrentUser.run(FulfillmentFixtures.userOf(OTHER_WAREHOUSE), DataScope.WAREHOUSE, () ->
                    assertThat(refusal(() -> service.assignCarrier(row.getId(), null, actor)).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
        }
    }

    @Nested
    @DisplayName("recording what the carrier reported (09 §4.3, BR-02, BR-05)")
    class RecordingAnOutcome {

        private OrderSummary orderKnowing(ShipmentJpaEntity row) {
            var known = new OrderSummary.Shipment(row.getId(), OrderShipmentStatus.HANDED_OVER, BigDecimal.ZERO,
                    BigDecimal.ZERO, NOW, null, null, List.of(), BigDecimal.ZERO, null);
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), List.of(known));
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            return order;
        }

        @Test
        @DisplayName("an outcome other than DELIVERED, DELIVERY_FAILED or RETURNED is VALIDATION_FAILED on 'outcome'")
        void unknownOutcome() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));

            for (String bad : new String[] {null, "", "SHIPPED", "CANCELLED"}) {
                BusinessException thrown = refusal(() ->
                        service.recordOutcome(row.getId(), new Shipments.Outcome(bad, null, null), actor));
                assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("outcome");
            }
        }

        @Test
        @DisplayName("a failed delivery without a reason is VALIDATION_FAILED on 'failureReason'")
        void failureNeedsAReason() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));

            BusinessException thrown = refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERY_FAILED", " ", null), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("failureReason");
        }

        @Test
        @DisplayName("a failure reason outside the list is VALIDATION_FAILED")
        void failureReasonFromTheList() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));

            assertThat(refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERY_FAILED", "BAD_WEATHER", null), actor)).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a failure for OTHER says what it was: VALIDATION_FAILED without a note")
        void otherNeedsANote() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));

            assertThat(refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERY_FAILED", "OTHER", "   "), actor)).errorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
            verifyNoInteractions(orders);
        }

        @Test
        @DisplayName("delivered: the shipment is DELIVERED, the order is told its shipment, the step is recorded")
        void deliveredTellsTheOrder() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentDelivered(row.getOrderId(), row.getId(), actor.userId())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("delivered", null, null), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            verify(orders).recordShipmentDelivered(row.getOrderId(), row.getId(), actor.userId());
            verify(shipments).saveAndFlush(row);
            verify(timeline).record(any(Shipment.class), eq(ShipmentStatus.HANDED_OVER), eq(order), eq(null), any(),
                    any());
        }

        @Test
        @DisplayName("not delivered: the reason and the trimmed note go to the order and stay on the shipment")
        void failedCarriesReasonAndNote() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentDeliveryFailed(any(), any(), any(), any(), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERY_FAILED", "customer_absent",
                    "  called twice  "), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);
            assertThat(row.getFailureReason()).isEqualTo(com.stockflow.fulfillment.internal.domain.DeliveryFailureReason.CUSTOMER_ABSENT);
            assertThat(row.getFailureNote()).isEqualTo("called twice");
            verify(orders).recordShipmentDeliveryFailed(row.getOrderId(), row.getId(),
                    com.stockflow.order.api.DeliveryFailureReason.CUSTOMER_ABSENT, "called twice", actor.userId());
        }

        @Test
        @DisplayName("returned after a failed delivery: the order is told the shipment is back (BR-06)")
        void returnedAfterFailure() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.DELIVERY_FAILED));
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentReturned(row.getOrderId(), row.getId(), actor.userId())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("RETURNED", null, null), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.RETURNED);
            verify(orders).recordShipmentReturned(row.getOrderId(), row.getId(), actor.userId());
        }

        @Test
        @DisplayName("a retry that finally delivers (DELIVERY_FAILED to DELIVERED) is allowed")
        void deliveredOnARetry() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.DELIVERY_FAILED));
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentDelivered(any(), any(), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        }

        @Test
        @DisplayName("an outcome along an edge the lifecycle does not have is SHIPMENT_STATE_CONFLICT, order untouched")
        void illegalEdges() {
            ShipmentJpaEntity waiting = locked(FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH));
            ShipmentJpaEntity handed = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));

            assertThat(refusal(() -> service.recordOutcome(waiting.getId(),
                    new Shipments.Outcome("DELIVERED", null, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
            assertThat(refusal(() -> service.recordOutcome(handed.getId(),
                    new Shipments.Outcome("RETURNED", null, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
            verify(orders, never()).recordShipmentDelivered(any(), any(), any());
            verify(shipments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("the same outcome again changes nothing: the order is not told twice")
        void repeatedOutcomeIsIdempotent() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.DELIVERED));

            Shipments.ShipmentDetail detail = service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERED", null, null), actor);

            assertThat(detail.shipment().status()).isEqualTo("DELIVERED");
            verifyNoInteractions(orders, timeline);
            verify(shipments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("an order that cannot be read is NOT_FOUND and the shipment is not saved")
        void orderMissing() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));
            when(orders.findById(row.getOrderId())).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERED", null, null), actor)).errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            verify(shipments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a shipment handed over as the whole order, unknown to the order, delivers the order itself")
        void wholeOrderPath() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), null, true, UUID.randomUUID(), NOW);
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), null);
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId())).thenReturn(List.of(row));
            when(orders.recordDelivery(row.getOrderId(), actor.userId())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null), actor);

            verify(orders).recordDelivery(row.getOrderId(), actor.userId());
            verify(orders, never()).recordShipmentDelivered(any(), any(), any());
        }

        @Test
        @DisplayName("the whole-order path maps a failure and a return to the order's own outcomes")
        void wholeOrderFailureAndReturn() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), null, true, UUID.randomUUID(), NOW);
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), List.of());
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId())).thenReturn(List.of(row));
            when(orders.recordDeliveryFailure(any(), any(), any(), any())).thenReturn(order);
            when(orders.recordReturnedToOrigin(any(), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERY_FAILED", "LOST", null), actor);
            service.recordOutcome(row.getId(), new Shipments.Outcome("RETURNED", null, null), actor);

            verify(orders).recordDeliveryFailure(row.getOrderId(), com.stockflow.order.api.DeliveryFailureReason.LOST,
                    null, actor.userId());
            verify(orders).recordReturnedToOrigin(row.getOrderId(), actor.userId());
        }

        @Test
        @DisplayName("a whole-order shipment beside another live shipment is SHIPMENT_STATE_CONFLICT: record by hand")
        void wholeOrderBesideOthersIsRefused() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), null, true, UUID.randomUUID(), NOW);
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), List.of());
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            ShipmentJpaEntity sibling = FulfillmentFixtures.rowIn(ShipmentStatus.READY_TO_DISPATCH);
            ShipmentJpaEntity cancelled = FulfillmentFixtures.rowIn(ShipmentStatus.CANCELLED);
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId()))
                    .thenReturn(List.of(row, sibling, cancelled));

            assertThat(refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERED", null, null), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_STATE_CONFLICT);
            verify(orders, never()).recordDelivery(any(), any());
        }

        @Test
        @DisplayName("a cancelled sibling does not block the whole-order outcome")
        void cancelledSiblingsDoNotCount() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), null, true, UUID.randomUUID(), NOW);
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), List.of());
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId()))
                    .thenReturn(List.of(row, FulfillmentFixtures.rowIn(ShipmentStatus.CANCELLED)));
            when(orders.recordDelivery(any(), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null), actor);

            verify(orders).recordDelivery(row.getOrderId(), actor.userId());
        }
    }
}
