package com.stockflow.order.internal.service;

import com.stockflow.contracts.ReturnedGoodsReceived;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.DeliveryFailureCase;
import com.stockflow.order.internal.domain.DeliveryFailureCases;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.OrderShipment;
import com.stockflow.order.internal.domain.SettlementCandidates;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.SettleShipmentReturnCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 Q-7 (BE-14, BE-32, L-I8), BE-13, BE-16, BE-20 on {@link DeliveryFailureService#goodsReceived}: the return
 * receipt of a shipment the carrier brought back closes its units and has payment settle them once, dated by the
 * transaction clock; a later receipt of the same shipment adds its cartons and closes nothing; an order from before
 * RTM-RETURNED still owing the units gets an OPEN case and nothing is closed; cartons carry the units counted.
 */
class DeliveryFailureGoodsReceivedTest {

    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final Instant CLOCK = NOW.plus(Duration.ofDays(5)).plusSeconds(77);

    private final OrderRepository orders = mock(OrderRepository.class);
    private final DeliveryFailureCases cases = mock(DeliveryFailureCases.class);
    private final OrderServiceImpl orderService = mock(OrderServiceImpl.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final DeliveryFailureService service = new DeliveryFailureService(orders, cases, orderService,
            mock(ShippingWarehouseService.class), payments, mock(OrderEventPublisher.class),
            mock(org.springframework.context.ApplicationEventPublisher.class), mock(SettlementCandidates.class),
            Clock.fixed(CLOCK, ZoneOffset.UTC));

    {
        when(cases.save(any())).thenAnswer(call -> call.getArgument(0));
        when(cases.findByShipment(any())).thenReturn(Optional.empty());
        when(payments.refundProgress(any())).thenReturn(com.stockflow.payment.api.RefundProgress.NONE);
        when(payments.installmentStanding(any())).thenAnswer(call ->
                com.stockflow.payment.api.InstallmentStanding.none(call.getArgument(0)));
    }

    private static Instant day(int days) {
        return NOW.plus(Duration.ofDays(days));
    }

    /** S1 (cups) delivered, S2 (lids) failed and reported back by the carrier. */
    private Order partlyReturned() {
        Order order = OrderFixtures.released();
        order.decidePartialShipment(true);
        order.startPicking(STAFF, NOW);
        order.markPacked(NOW, STAFF);
        order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 10)),
                NOW, STAFF));
        order.recordHandover(new ShipmentHandover(S2, List.of(new ShipmentHandover.Line(order.lines().get(1).id(), 5)),
                NOW, STAFF));
        order.recordShipmentDelivered(S1, day(1), STAFF);
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(2), STAFF);
        order.pullDomainEvents();
        when(orders.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    private static ReturnedGoodsReceived receipt(Order order, UUID receiptId, ReturnedGoodsReceived.Carton... cartons) {
        return new ReturnedGoodsReceived(receiptId, "RR-" + receiptId.toString().substring(0, 4), UUID.randomUUID(),
                order.id().value(), order.orderNumber().value(), order.customerId(), S2, null, "Q-01",
                List.of(cartons), STAFF, day(4), List.of(), List.of());
    }

    @Test
    @DisplayName("the first receipt closes the shipment's units and settles them once, dated by the clock, goods back "
            + "as of the receipt; its case records the counted units and is closed by the system")
    void firstReceiptClosesAndSettles() {
        Order order = partlyReturned();

        service.goodsReceived(receipt(order, UUID.randomUUID(),
                new ReturnedGoodsReceived.Carton("LPN-1", "LID-90", "L1", 5, "dented", 3, false)));

        ArgumentCaptor<Order.ShipmentClosure> closure = ArgumentCaptor.forClass(Order.ShipmentClosure.class);
        verify(orderService).settleShipmentClosure(eq(order), closure.capture(),
                eq(SettleShipmentReturnCommand.Cause.CARRIER_RETURN), any(), eq(STAFF), eq(CLOCK));
        assertThat(closure.getValue().goodsBackAt()).isEqualTo(day(4));
        assertThat(closure.getValue().goodsValue()).isEqualByComparingTo("100000");
        verify(orderService).saveClosed(order);
        assertThat(order.lines().get(1).cancelledQuantity()).isEqualTo(5);
        assertThat(order.deliveredInFullAt()).contains(day(4));
        assertThat(order.status()).as("nothing made refundable: never \"Hoàn một phần\" at 0 (BE-45, audit r2 M-6)")
                .isEqualTo(com.stockflow.order.api.OrderStatus.PARTIALLY_FULFILLED);

        ArgumentCaptor<DeliveryFailureCase> saved = ArgumentCaptor.forClass(DeliveryFailureCase.class);
        verify(cases, org.mockito.Mockito.times(2)).save(saved.capture());
        DeliveryFailureCase failure = saved.getAllValues().getLast();
        assertThat(failure.status()).isEqualTo(DeliveryFailureCase.Status.CANCELLED);
        assertThat(failure.decisionNote()).isEqualTo(DeliveryFailureService.systemClosureNote());
        assertThat(failure.cartons()).singleElement().satisfies(carton -> {
            assertThat(carton.quantity()).as("counted, not the label").isEqualTo(3);
            assertThat(carton.condition()).isEqualTo("NOT_STOCKED: dented");
        });
    }

    @Test
    @DisplayName("more of the same line still to ship (a later batch) does not make a carrier's return legacy: the "
            + "receipt closes the shipment's units")
    void sameLineStillToShip() {
        Order order = OrderFixtures.released();
        order.decidePartialShipment(true);
        order.startPicking(STAFF, NOW);
        order.recordHandover(new ShipmentHandover(S2, List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 6)),
                NOW, STAFF));
        order.recordShipmentDeliveryFailed(S2, DeliveryFailureReason.REFUSED, null, day(1), STAFF);
        order.recordShipmentReturned(S2, day(2), STAFF);
        when(orders.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        assertThat(order.unshippedUnits(order.lines().get(0).id())).as("4 cups still to ship").isEqualTo(4);

        service.goodsReceived(receipt(order, UUID.randomUUID(),
                new ReturnedGoodsReceived.Carton("LPN-1", "CUP-12OZ", "L1", 6, null)));

        verify(orderService).settleShipmentClosure(eq(order), any(),
                eq(SettleShipmentReturnCommand.Cause.CARRIER_RETURN), any(), eq(STAFF), eq(CLOCK));
        assertThat(order.lines().get(0).cancelledQuantity()).isEqualTo(6);
    }

    @Test
    @DisplayName("BE-13: a later receipt of the same shipment adds its cartons to the case and closes nothing")
    void laterReceiptAppends() {
        Order order = partlyReturned();
        order.closeReturnedShipment(S2, day(3), day(3), STAFF);
        DeliveryFailureCase existing = mock(DeliveryFailureCase.class);
        UUID caseId = UUID.randomUUID();
        when(existing.id()).thenReturn(caseId);
        when(cases.findByShipment(S2)).thenReturn(Optional.of(existing));
        when(cases.appendCartons(eq(caseId), anyList())).thenReturn(1);

        service.goodsReceived(receipt(order, UUID.randomUUID(),
                new ReturnedGoodsReceived.Carton("LPN-2", "LID-90", "L1", 5, null)));

        verify(cases).appendCartons(eq(caseId), anyList());
        verify(orderService, never()).settleShipmentClosure(any(), any(), any(), any(), any(), any());
        verify(cases, never()).save(any());
        assertThat(order.lines().get(1).cancelledQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("BE-16: an order from before RTM-RETURNED that still owes the units gets an OPEN case; nothing is "
            + "closed or settled for Sales")
    void legacyOwedUnitsOpenTheCase() {
        Order order = partlyReturned();
        // Before RTM-RETURNED the units of a returned shipment were owed again: the backfill marks them off the
        // shipment (units_closed_at) while the line still owes them.
        order.restoreShipments(order.shipments().stream().map(s -> s.id().equals(S2)
                ? copy(s).restoreUnitsClosed(day(2)) : s).toList());
        assertThat(order.owesUnitsOf(S2)).isTrue();

        service.goodsReceived(receipt(order, UUID.randomUUID(),
                new ReturnedGoodsReceived.Carton("LPN-1", "LID-90", "L1", 5, null)));

        verify(orderService, never()).settleShipmentClosure(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<DeliveryFailureCase> saved = ArgumentCaptor.forClass(DeliveryFailureCase.class);
        verify(cases).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(DeliveryFailureCase.Status.OPEN);
        assertThat(order.lines().get(1).cancelledQuantity()).isZero();
    }

    @Test
    @DisplayName("#377 rows: units closed when the shipment was reported - the receipt closes nothing again")
    void unitsClosedAtTheReportCloseNothingAgain() {
        Order order = partlyReturned();
        order.closeReturnedShipment(S2, day(2), day(2), STAFF);
        order.pullDomainEvents();

        service.goodsReceived(receipt(order, UUID.randomUUID(),
                new ReturnedGoodsReceived.Carton("LPN-1", "LID-90", "L1", 5, null)));

        verify(orderService, never()).settleShipmentClosure(any(), any(), any(), any(), any(), any());
        assertThat(order.lines().get(1).cancelledQuantity()).isEqualTo(5);
    }

    /** Cups on S1 (6 of 10, batch 1), the rest of the line still to ship; S1 failed as LOST. */
    private Order firstBatchLost() {
        Order order = OrderFixtures.released();
        order.decidePartialShipment(true);
        order.startPicking(STAFF, NOW);
        order.recordHandover(new ShipmentHandover(S1, List.of(new ShipmentHandover.Line(order.lines().get(0).id(), 6)),
                NOW, STAFF));
        order.recordShipmentDeliveryFailed(S1, DeliveryFailureReason.LOST, null, day(1), STAFF);
        when(orders.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("audit r2 lifecycle M-3: closed as lost, then found and brought back while 4 cups of the same line are "
            + "still to ship - not taken for a legacy row: the receipt closes and settles nothing again, its case is "
            + "a record closed at once, and no OPEN case keeps the order from settling")
    void aLostShipmentFoundLaterIsARecord() {
        Order order = firstBatchLost();
        order.closeLostShipment(S1, "Hãng xác nhận thất lạc", day(2), STAFF);
        order.recordShipmentReturned(S1, day(9), STAFF);
        order.pullDomainEvents();
        assertThat(order.unshippedUnits(order.lines().get(0).id())).as("batch 2 of the same line").isEqualTo(4);

        service.goodsReceived(new ReturnedGoodsReceived(UUID.randomUUID(), "RR-9", UUID.randomUUID(),
                order.id().value(), order.orderNumber().value(), order.customerId(), S1, null, "Q-01",
                List.of(new ReturnedGoodsReceived.Carton("LPN-1", "CUP-12OZ", "L1", 6, null)), STAFF, day(10),
                List.of(), List.of()));

        verify(orderService, never()).settleShipmentClosure(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<DeliveryFailureCase> saved = ArgumentCaptor.forClass(DeliveryFailureCase.class);
        verify(cases, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().getLast().status()).as("not an OPEN decision")
                .isEqualTo(DeliveryFailureCase.Status.CANCELLED);
    }

    @Test
    @DisplayName("audit r2 lifecycle M-3, the exit: an OPEN case left by the old check, its shipment owed to nobody - "
            + "Sales cancelling it close the case alone (not batch 2), and it never keeps the order from settling")
    void aStrayOpenCaseHasAnExit() {
        Order order = firstBatchLost();
        order.closeLostShipment(S1, "Hãng xác nhận thất lạc", day(2), STAFF);
        order.recordShipmentReturned(S1, day(9), STAFF);
        DeliveryFailureCase stray = DeliveryFailureCase.open(UUID.randomUUID(), order.id().value(), S1, null,
                UUID.randomUUID(), "RR-9", UUID.randomUUID(), DeliveryFailureReason.LOST, null, Map.of(
                        order.lines().get(0).id(), 6), List.of(), day(10));
        when(cases.find(stray.id())).thenReturn(Optional.of(stray));
        when(cases.ofOrder(order.id().value())).thenReturn(List.of(stray));
        int cancelledBefore = order.lines().get(0).cancelledQuantity();

        service.cancelUndelivered(order.id().value(), stray.id(), new DeliveryFailureService.Cancel(
                "Hàng thất lạc đã được hãng tìm thấy, không còn nợ khách", null), STAFF);

        assertThat(stray.status()).isEqualTo(DeliveryFailureCase.Status.CANCELLED);
        assertThat(order.lines().get(0).cancelledQuantity()).as("batch 2 is not closed with it")
                .isEqualTo(cancelledBefore);
        verify(orderService, never()).closeRemainder(any(), any(), any(), any(), any());
        verify(orderService, never()).closeUndelivered(any(), any(), any(), any(), any());
    }

    private static OrderShipment copy(OrderShipment s) {
        return new OrderShipment(s.id(), s.quantities(), s.amount(), s.handedOverAt(), s.handedOverBy(), s.status(),
                s.outcomeAt(), s.outcomeBy(), s.failureReason(), s.failureNote(), s.carriage())
                .restoreDeliveryBatch(s.deliveryBatchId());
    }
}
