package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
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
 * Shipments at dispatch (kltn-docs 09 §4.3, §5, BR-02, BR-05): reading them, the outcome staff record when the carrier
 * reports back, and the order told in the same transaction.
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
            loads, members, timeline, context, orders, identities,
            mock(com.stockflow.fulfillment.internal.repository.DeliveryOutcomeCorrectionJpaRepository.class),
            mock(com.stockflow.fulfillment.internal.repository.FailureReasonCorrectionJpaRepository.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * The warehouse manager: every outcome recorded by hand takes {@code fulfillment-delivery-outcomes:APPROVE} (09
     * BR-02; WF-05 Q-7 - RETURNED too).
     */
    private final com.stockflow.common.security.CurrentUser actor = new com.stockflow.common.security.CurrentUser(
            UUID.randomUUID(), "manager", Set.of(com.stockflow.common.security.Role.WAREHOUSE_MANAGER),
            Set.of(com.stockflow.common.security.PermissionCode.of(FulfillmentPermissions.DELIVERY_OUTCOMES,
                    com.stockflow.common.security.Action.APPROVE)),
            DataScope.ALL, Set.of(), Set.of());

    @BeforeEach
    void rowsEchoWhatTheyAreSaved() {
        when(shipments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private ShipmentJpaEntity locked(ShipmentJpaEntity row) {
        when(locks.lock(Set.of(row.getId()))).thenReturn(List.of(row));
        return row;
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
            Shipment s = FulfillmentFixtures.readyShipment(2);
            UUID carrierId = UUID.randomUUID();
            UUID loadId = UUID.randomUUID();
            UUID by = UUID.randomUUID();
            s.handOver(loadId, carrierId, false, by, NOW);
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

            Shipments.ShipmentDetail detail = service.get(row.getId());

            assertThat(detail.shipment().status()).isEqualTo("HANDED_OVER");
            assertThat(detail.shipment().carrierName()).isEqualTo("GHN Express");
            assertThat(detail.shipment().loadCode()).isEqualTo("LD-0001");
            assertThat(detail.shipment().packageCount()).isEqualTo(2);
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
            });
        }
    }

    @Nested
    @DisplayName("recording what the carrier reported (09 §4.3, BR-02, BR-05)")
    class RecordingAnOutcome {

        private OrderSummary orderKnowing(ShipmentJpaEntity row) {
            var known = new OrderSummary.Shipment(row.getId(), OrderShipmentStatus.HANDED_OVER, BigDecimal.ZERO, NOW, null, null, List.of(), null);
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
            when(orders.recordShipmentDelivered(eq(row.getOrderId()), eq(row.getId()), any(java.time.Instant.class),
                    eq(actor.userId()))).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("delivered", null, null), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            verify(orders).recordShipmentDelivered(eq(row.getOrderId()), eq(row.getId()), any(java.time.Instant.class),
                    eq(actor.userId()));
            verify(shipments).saveAndFlush(row);
            verify(timeline).record(any(Shipment.class), eq(ShipmentStatus.HANDED_OVER), eq(order), eq(null), any(),
                    any());
        }

        @Test
        @DisplayName("not delivered: the reason and the trimmed note go to the order and stay on the shipment")
        void failedCarriesReasonAndNote() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.HANDED_OVER));
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentDeliveryFailed(any(), any(), any(), any(), any(java.time.Instant.class), any()))
                    .thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERY_FAILED", "customer_absent",
                    "  called twice  "), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);
            assertThat(row.getFailureReason()).isEqualTo(com.stockflow.fulfillment.internal.domain.DeliveryFailureReason.CUSTOMER_ABSENT);
            assertThat(row.getFailureNote()).isEqualTo("called twice");
            verify(orders).recordShipmentDeliveryFailed(eq(row.getOrderId()), eq(row.getId()),
                    eq(com.stockflow.order.api.DeliveryFailureReason.CUSTOMER_ABSENT), eq("called twice"),
                    any(java.time.Instant.class), eq(actor.userId()));
        }

        @Test
        @DisplayName("returned after a failed delivery: the order is told the shipment is back (BR-06)")
        void returnedAfterFailure() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.DELIVERY_FAILED));
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentReturned(eq(row.getOrderId()), eq(row.getId()), any(java.time.Instant.class),
                    eq(actor.userId()))).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("RETURNED", null, null), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.RETURNED);
            verify(orders).recordShipmentReturned(eq(row.getOrderId()), eq(row.getId()), any(java.time.Instant.class),
                    eq(actor.userId()));
        }

        @Test
        @DisplayName("a retry that finally delivers (DELIVERY_FAILED to DELIVERED) is allowed")
        void deliveredOnARetry() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.DELIVERY_FAILED));
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentDelivered(any(), any(), any(java.time.Instant.class), any())).thenReturn(order);

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
            verify(orders, never()).recordShipmentDelivered(any(), any(), any(java.time.Instant.class), any());
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
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), true, UUID.randomUUID(), NOW);
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), null);
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId())).thenReturn(List.of(row));
            when(orders.recordDelivery(eq(row.getOrderId()), any(java.time.Instant.class), eq(actor.userId()))).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null), actor);

            verify(orders).recordDelivery(eq(row.getOrderId()), any(java.time.Instant.class), eq(actor.userId()));
            verify(orders, never()).recordShipmentDelivered(any(), any(), any(java.time.Instant.class), any());
        }

        @Test
        @DisplayName("the whole-order path maps a failure and a return to the order's own outcomes")
        void wholeOrderFailureAndReturn() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), true, UUID.randomUUID(), NOW);
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), List.of());
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId())).thenReturn(List.of(row));
            when(orders.recordDeliveryFailure(any(), any(), any(), any(java.time.Instant.class), any())).thenReturn(order);
            when(orders.recordReturnedToOrigin(any(), any(java.time.Instant.class), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERY_FAILED", "LOST", null), actor);
            service.recordOutcome(row.getId(), new Shipments.Outcome("RETURNED", null, null), actor);

            verify(orders).recordDeliveryFailure(eq(row.getOrderId()),
                    eq(com.stockflow.order.api.DeliveryFailureReason.LOST), eq(null), any(java.time.Instant.class),
                    eq(actor.userId()));
            verify(orders).recordReturnedToOrigin(eq(row.getOrderId()), any(java.time.Instant.class), eq(actor.userId()));
        }

        @Test
        @DisplayName("a whole-order shipment beside another live shipment is SHIPMENT_STATE_CONFLICT: record by hand")
        void wholeOrderBesideOthersIsRefused() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), true, UUID.randomUUID(), NOW);
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
            verify(orders, never()).recordDelivery(any(), any(java.time.Instant.class), any());
        }

        @Test
        @DisplayName("a cancelled sibling does not block the whole-order outcome")
        void cancelledSiblingsDoNotCount() {
            Shipment s = FulfillmentFixtures.readyShipment();
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), true, UUID.randomUUID(), NOW);
            ShipmentJpaEntity row = locked(FulfillmentFixtures.row(s));
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), List.of());
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            when(shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId()))
                    .thenReturn(List.of(row, FulfillmentFixtures.rowIn(ShipmentStatus.CANCELLED)));
            when(orders.recordDelivery(any(), any(java.time.Instant.class), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null), actor);

            verify(orders).recordDelivery(eq(row.getOrderId()), any(java.time.Instant.class), eq(actor.userId()));
        }
    }

    @Nested
    @DisplayName("the delivery time a person enters (09 §4.3 step 2)")
    class TheDeliveryTime {

        private final java.time.Instant handedOverAt = NOW.minus(java.time.Duration.ofHours(5));

        /** A shipment handed over five hours before the clock's now, in the given status. */
        private ShipmentJpaEntity handedOver(ShipmentStatus status) {
            Shipment s = FulfillmentFixtures.readyShipment();
            UUID by = UUID.randomUUID();
            s.handOver(UUID.randomUUID(), UUID.randomUUID(), false, by, handedOverAt);
            if (status == ShipmentStatus.DELIVERY_FAILED) {
                s.recordFailed(com.stockflow.fulfillment.internal.domain.DeliveryFailureReason.REFUSED, null, by,
                        handedOverAt.plusSeconds(60));
            }
            return locked(FulfillmentFixtures.row(s));
        }

        private OrderSummary orderKnowing(ShipmentJpaEntity row) {
            var known = new OrderSummary.Shipment(row.getId(), OrderShipmentStatus.HANDED_OVER, BigDecimal.ZERO, NOW,
                    null, null, List.of(), null);
            OrderSummary order = FulfillmentFixtures.order(row.getOrderId(), List.of(known));
            when(orders.findById(row.getOrderId())).thenReturn(Optional.of(order));
            return order;
        }

        @Test
        @DisplayName("a time in the past, after the handover, dates the shipment, its timeline step and the order")
        void aPastTimeIsTheDeliverys() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.HANDED_OVER);
            OrderSummary order = orderKnowing(row);
            java.time.Instant at = NOW.minus(java.time.Duration.ofHours(2));
            when(orders.recordShipmentDelivered(any(), any(), any(java.time.Instant.class), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null, at), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            assertThat(row.getOutcomeAt()).isEqualTo(at);
            verify(orders).recordShipmentDelivered(row.getOrderId(), row.getId(), at, actor.userId());
            var recorded = org.mockito.ArgumentCaptor.forClass(Shipment.class);
            verify(timeline).record(recorded.capture(), eq(ShipmentStatus.HANDED_OVER), eq(order), eq(null), any(),
                    any());
            assertThat(recorded.getValue().pullRecorded()).singleElement()
                    .satisfies(step -> assertThat(step.at()).isEqualTo(at));
        }

        @Test
        @DisplayName("no time: now, as before")
        void noTimeIsNow() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.HANDED_OVER);
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentDelivered(any(), any(), any(java.time.Instant.class), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null), actor);

            assertThat(row.getOutcomeAt()).isEqualTo(NOW);
            verify(orders).recordShipmentDelivered(row.getOrderId(), row.getId(), NOW, actor.userId());
        }

        @Test
        @DisplayName("exactly now and exactly the handover are allowed")
        void theBoundsAreInclusive() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.HANDED_OVER);
            OrderSummary order = orderKnowing(row);
            when(orders.recordShipmentDelivered(any(), any(), any(java.time.Instant.class), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null, handedOverAt), actor);

            assertThat(row.getOutcomeAt()).isEqualTo(handedOverAt);
            ShipmentJpaEntity again = handedOver(ShipmentStatus.HANDED_OVER);
            orderKnowing(again);
            service.recordOutcome(again.getId(), new Shipments.Outcome("DELIVERED", null, null, NOW), actor);
            assertThat(again.getOutcomeAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a time in the future is SHIPMENT_DELIVERY_TIME_IN_FUTURE on 'deliveredAt': nothing recorded")
        void notInTheFuture() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.HANDED_OVER);

            BusinessException thrown = refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERED", null, null, NOW.plusSeconds(1)), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_IN_FUTURE);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("deliveredAt");
            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.HANDED_OVER);
            verifyNoInteractions(orders, timeline);
            verify(shipments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a time before the handover is SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER on 'deliveredAt'")
        void notBeforeTheHandover() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.HANDED_OVER);

            BusinessException thrown = refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERED", null, null, handedOverAt.minusSeconds(1)), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("deliveredAt");
            verifyNoInteractions(orders, timeline);
            verify(shipments, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a delivery time goes with DELIVERED only: with a failure or a return it is VALIDATION_FAILED")
        void onlyWithADelivery() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.HANDED_OVER);

            BusinessException thrown = refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERY_FAILED", "LOST", null, NOW.minusSeconds(60)), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("deliveredAt");
            verifyNoInteractions(orders, timeline);
        }

        @Test
        @DisplayName("a retry that delivers after a failure takes the time too")
        void aRetryTakesTheTime() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.DELIVERY_FAILED);
            OrderSummary order = orderKnowing(row);
            java.time.Instant at = NOW.minus(java.time.Duration.ofHours(1));
            when(orders.recordShipmentDelivered(any(), any(), any(java.time.Instant.class), any())).thenReturn(order);

            service.recordOutcome(row.getId(), new Shipments.Outcome("DELIVERED", null, null, at), actor);

            assertThat(row.getOutcomeAt()).isEqualTo(at);
            verify(orders).recordShipmentDelivered(row.getOrderId(), row.getId(), at, actor.userId());
        }

        @Test
        @DisplayName("a retry dated before the failure it follows is SHIPMENT_DELIVERY_TIME_BEFORE_FAILURE")
        void aRetryBeforeTheFailure() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.DELIVERY_FAILED);

            BusinessException thrown = refusal(() -> service.recordOutcome(row.getId(),
                    new Shipments.Outcome("DELIVERED", null, null, handedOverAt.plusSeconds(30)), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_BEFORE_FAILURE);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("deliveredAt");
            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.DELIVERY_FAILED);
            verifyNoInteractions(orders, timeline);
        }

        @Test
        @DisplayName("a correction to delivered takes the time for the shipment and the order; the correction is dated now")
        void aCorrectionToDelivered() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.DELIVERY_FAILED);
            OrderSummary order = orderKnowing(row);
            java.time.Instant at = NOW.minus(java.time.Duration.ofHours(1));
            when(orders.recordShipmentDelivered(any(), any(), any(java.time.Instant.class), any())).thenReturn(order);

            service.correctOutcome(row.getId(), new Shipments.Correction("DELIVERED", null, null, "found", at), actor);

            assertThat(row.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            assertThat(row.getOutcomeAt()).isEqualTo(at);
            verify(orders).recordShipmentDelivered(row.getOrderId(), row.getId(), at, actor.userId());
        }

        @Test
        @DisplayName("a correction to delivered with a time in the future or before the handover is refused")
        void aCorrectionIsBoundedToo() {
            ShipmentJpaEntity row = handedOver(ShipmentStatus.DELIVERY_FAILED);

            assertThat(refusal(() -> service.correctOutcome(row.getId(),
                    new Shipments.Correction("DELIVERED", null, null, "found", NOW.plusSeconds(60)), actor)).errorCode())
                    .isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_IN_FUTURE);
            assertThat(refusal(() -> service.correctOutcome(row.getId(),
                    new Shipments.Correction("DELIVERED", null, null, "found", handedOverAt.minusSeconds(60)), actor))
                    .errorCode()).isEqualTo(ErrorCode.SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER);
            verifyNoInteractions(orders, timeline);
        }

        @Test
        @DisplayName("a correction to a failure takes no delivery time: VALIDATION_FAILED on 'deliveredAt'")
        void aCorrectionToAFailureTakesNone() {
            ShipmentJpaEntity row = locked(FulfillmentFixtures.rowIn(ShipmentStatus.DELIVERED));

            BusinessException thrown = refusal(() -> service.correctOutcome(row.getId(),
                    new Shipments.Correction("DELIVERY_FAILED", "LOST", null, "not there", NOW), actor));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("deliveredAt");
        }
    }
}
