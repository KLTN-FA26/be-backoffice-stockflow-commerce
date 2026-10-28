package com.stockflow.order.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderPayments;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.entity.OrderHoldJpaEntity;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import com.stockflow.payment.api.OrderLedger;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static com.stockflow.order.internal.service.OrderFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The warehouse, carrier and coordinator steps of an order as {@link OrderServiceImpl} drives them (kltn-docs 09 §4,
 * 17 §4, §5): each step locks the order, asks the aggregate, saves and announces only when something changed, and a
 * repeated step answers with the order as it is.
 */
class OrderServiceImplLifecycleTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final OrderHoldJpaRepository holds = mock(OrderHoldJpaRepository.class);
    private final CancellationRequestRepository requests = mock(CancellationRequestRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final OrderServiceImpl service = new OrderServiceImpl(repository, mock(OrderSearchRepository.class),
            inventory, events, Clock.fixed(NOW, ZoneOffset.UTC), mock(DesignService.class), holds,
            mock(CustomerService.class), mock(com.stockflow.catalog.api.CatalogService.class),
            mock(OrderPayments.class), requests, mock(CreditChecks.class), paymentService);

    private Order loaded(Order order) {
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(repository.findById(order.id())).thenReturn(Optional.of(order));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(paymentService.ledgerOf(order.id().value())).thenReturn(new OrderLedger(order.paidAmount(),
                BigDecimal.ZERO));
        return order;
    }

    private static UUID cups(Order order) {
        return order.lines().get(0).id();
    }

    private static UUID lids(Order order) {
        return order.lines().get(1).id();
    }

    @Test
    @DisplayName("picking starts once: the first call moves the order and saves, the second answers with it unchanged")
    void pickingIsIdempotent() {
        Order order = loaded(OrderFixtures.releasedPrepaid());

        OrderSummary first = service.startPicking(order.id().value(), STAFF);
        OrderSummary second = service.startPicking(order.id().value(), STAFF);

        assertThat(first.status()).isEqualTo(OrderStatus.PICKING);
        assertThat(second.status()).isEqualTo(OrderStatus.PICKING);
        verify(repository, org.mockito.Mockito.times(1)).save(order);
        verify(events, org.mockito.Mockito.times(1)).publishEventsOf(order);
    }

    @Test
    @DisplayName("the summary carries the lines, totals, payment block and lifecycle of the saved order")
    void summaryReflectsTheOrder() {
        Order order = loaded(OrderFixtures.releasedPrepaid());

        OrderSummary summary = service.markPacked(order.id().value(), STAFF);

        assertThat(summary.status()).isEqualTo(OrderStatus.PACKED);
        assertThat(summary.orderNumber()).isEqualTo(order.orderNumber().value());
        assertThat(summary.total().amount()).isEqualByComparingTo("1100000");
        assertThat(summary.lines()).extracting(OrderSummary.LineSummary::sku).containsExactly("CUP-12OZ", "LID-90");
        assertThat(summary.payment().paidAmount()).isEqualByComparingTo("1100000");
        assertThat(summary.warehouseId()).isEqualTo(OrderFixtures.WAREHOUSE);
        assertThat(summary.lifecycle().packedAt()).isEqualTo(NOW);
        assertThat(summary.cancellation()).as("only a cancelled order has a cancellation block").isNull();
    }

    @Test
    @DisplayName("an unknown order is NOT_FOUND on every locked step")
    void unknownOrder() {
        when(repository.findByIdForUpdate(any())).thenReturn(Optional.empty());
        UUID id = UUID.randomUUID();

        assertThat(codeOf(() -> service.startPicking(id, STAFF))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> service.markPacked(id, STAFF))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> service.recordDelivery(id, STAFF))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> service.putOnHold(id, HoldReason.ADDRESS, "x", STAFF))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> service.resolveHold(id, "x", STAFF))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> service.recordPrintingStarted(id))).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> service.recordPacked(id))).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a delivery is recorded once; a second report for the same order changes nothing (09 §4.3)")
    void deliveryIsIdempotent() {
        Order order = OrderFixtures.releasedPrepaid();
        order.markPacked(NOW, STAFF);
        order.markShipped(NOW, STAFF);
        loaded(order);

        assertThat(service.recordDelivery(order.id().value(), STAFF).status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(service.recordDelivery(order.id().value(), STAFF).status()).isEqualTo(OrderStatus.DELIVERED);

        verify(repository, org.mockito.Mockito.times(1)).save(order);
        assertThat(order.deliveredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a failed delivery needs a note for OTHER (VALIDATION_FAILED) and is idempotent for the same report")
    void deliveryFailure() {
        Order order = OrderFixtures.releasedPrepaid();
        order.markPacked(NOW, STAFF);
        order.markShipped(NOW, STAFF);
        loaded(order);

        assertThat(codeOf(() -> service.recordDeliveryFailure(order.id().value(), DeliveryFailureReason.OTHER, " ",
                STAFF))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(service.recordDeliveryFailure(order.id().value(), DeliveryFailureReason.CUSTOMER_ABSENT, null, STAFF)
                .status()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        service.recordDeliveryFailure(order.id().value(), DeliveryFailureReason.CUSTOMER_ABSENT, null, STAFF);

        verify(repository, org.mockito.Mockito.times(1)).save(order);
    }

    @Test
    @DisplayName("goods that came back from a failed delivery put the order in RETURNED, once")
    void returnedToOrigin() {
        Order order = OrderFixtures.releasedPrepaid();
        order.markPacked(NOW, STAFF);
        order.markShipped(NOW, STAFF);
        order.markDeliveryFailed(DeliveryFailureReason.CUSTOMER_ABSENT, null, NOW, STAFF);
        loaded(order);

        assertThat(service.recordReturnedToOrigin(order.id().value(), STAFF).status()).isEqualTo(OrderStatus.RETURNED);
        service.recordReturnedToOrigin(order.id().value(), STAFF);

        verify(repository, org.mockito.Mockito.times(1)).save(order);
    }

    @Test
    @DisplayName("putting an order on hold saves a hold row with the reason, note and who; resolving closes that row")
    void holdAndResolve() {
        Order order = loaded(OrderFixtures.releasedPrepaid());

        service.putOnHold(order.id().value(), HoldReason.ADDRESS, "dented cartons", STAFF);

        assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
        ArgumentCaptor<OrderHoldJpaEntity> row = ArgumentCaptor.forClass(OrderHoldJpaEntity.class);
        verify(holds).save(row.capture());
        assertThat(row.getValue().getReason()).isEqualTo("ADDRESS");
        assertThat(row.getValue().getNote()).isEqualTo("dented cartons");
        assertThat(row.getValue().getRaisedBy()).isEqualTo(STAFF);
        assertThat(row.getValue().getRaisedAt()).isEqualTo(NOW);

        when(holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(order.id().value()))
                .thenReturn(Optional.of(row.getValue()));
        OrderSummary resolved = service.resolveHold(order.id().value(), "  replaced  ", STAFF);

        assertThat(resolved.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(row.getValue().getResolvedAt()).isEqualTo(NOW);
        verify(holds, org.mockito.Mockito.times(2)).save(row.getValue());
    }

    @Test
    @DisplayName("a hold for OTHER must say why, and an order not on hold cannot be resolved (ORDER_NOT_ON_HOLD)")
    void holdGuards() {
        Order order = loaded(OrderFixtures.releasedPrepaid());

        assertThat(codeOf(() -> service.putOnHold(order.id().value(), HoldReason.OTHER, null, STAFF)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(codeOf(() -> service.resolveHold(order.id().value(), "x", STAFF)))
                .isEqualTo(ErrorCode.ORDER_NOT_ON_HOLD);
        verify(holds, never()).save(any());
    }

    @Test
    @DisplayName("a locked read answers with the order's status, or empty; a withdrawn line is a cancelled or fully shipped one")
    void lockedReads() {
        Order order = loaded(OrderFixtures.releasedPrepaid());

        assertThat(service.lockedStatus(order.id().value())).contains(OrderStatus.READY_TO_FULFILL);
        assertThat(service.lockedStatus(UUID.randomUUID())).isEmpty();
        assertThat(service.lockedLineWithdrawn(order.id().value(), cups(order))).isFalse();
        assertThat(service.lockedLineWithdrawn(order.id().value(), UUID.randomUUID()))
                .as("a line the order does not have is not wanted").isTrue();
        assertThat(service.lockedLineWithdrawn(UUID.randomUUID(), cups(order)))
                .as("an order that does not exist wants nothing").isTrue();
    }

    @Test
    @DisplayName("a cancelled order's lines are all withdrawn")
    void cancelledOrderWithdrawsEverything() {
        Order order = OrderFixtures.draft(com.stockflow.order.api.PaymentTerm.PREPAID, UUID.randomUUID());
        order.submit();
        order.cancel(CancellationReasonCode.CUSTOMER_REQUEST, null, null, STAFF, NOW);
        loaded(order);

        assertThat(service.lockedLineWithdrawn(order.id().value(), cups(order))).isTrue();
    }

    @Test
    @DisplayName("printing started and packing started are recorded once each (LP-1: packing locks the delivery mode)")
    void factsAreRecordedOnce() {
        Order order = loaded(OrderFixtures.releasedPrepaid());

        service.recordPackingStarted(order.id().value());
        service.recordPackingStarted(order.id().value());

        assertThat(order.packingStartedAt()).isEqualTo(NOW);
        assertThat(order.deliveryModeLocked()).isTrue();
        verify(repository, org.mockito.Mockito.times(1)).save(order);
    }

    @Test
    @DisplayName("a package completed closes a pending cancellation request: it can no longer be decided")
    void packedClosesThePendingRequest() {
        Order order = loaded(OrderFixtures.releasedPrepaid());
        CancellationRequest pending = CancellationRequest.open(order.id().value(), UUID.randomUUID(),
                CancellationReasonCode.CUSTOMER_REQUEST, null, NOW);
        when(requests.findPending(order.id().value())).thenReturn(Optional.of(pending));

        service.recordPacked(order.id().value());

        assertThat(order.packedAt()).isEqualTo(NOW);
        verify(requests).save(pending);
        assertThat(pending.decisionNote()).contains("can no longer be cancelled");
    }

    @Test
    @DisplayName("a partial handover moves the order to PARTIALLY_FULFILLED and the rest can be closed, freeing its holds")
    void closingTheRestReleasesTheHoldsOfTheClosedUnits() {
        Order order = OrderFixtures.releasedPrepaid();
        order.decidePartialShipment(true);
        order.startPicking(STAFF, NOW);
        loaded(order);
        UUID shipment = UUID.randomUUID();
        List<UUID> lidHolds = order.lines().get(1).reservationIds();

        OrderSummary after = service.recordHandover(order.id().value(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(cups(order), 10)), NOW, STAFF));
        assertThat(after.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        OrderSummary closed = service.closeRemainder(order.id().value(), CancellationReasonCode.OUT_OF_STOCK, "no lids",
                STAFF);

        assertThat(closed.lifecycle().lines()).extracting(OrderSummary.LineProgress::cancelledQuantity)
                .containsExactly(0, 5);
        lidHolds.forEach(hold -> verify(inventory).release(hold, "ORDER_CANCELLED"));
        verify(inventory, never()).scrapCancelledPrint(any());
        assertThat(lids(order)).isNotNull();
    }

    @Test
    @DisplayName("closing the rest of an order that is not partly fulfilled is refused, nothing released")
    void closingTheRestNeedsAPartlyFulfilledOrder() {
        Order order = loaded(OrderFixtures.releasedPrepaid());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.closeRemainder(order.id().value(),
                CancellationReasonCode.OUT_OF_STOCK, null, STAFF)).isInstanceOf(
                com.stockflow.order.internal.domain.InvalidOrderTransitionException.class);
        verify(inventory, never()).release(any(), anyString());
    }

    @Test
    @DisplayName("the hold states of no orders is an empty answer without asking the repository for anything")
    void holdStatesOfNothing() {
        when(repository.holdStates(any())).thenReturn(List.of());

        assertThat(service.holdStates(null)).isEmpty();
        assertThat(service.holdStates(List.of())).isEmpty();
    }
}
