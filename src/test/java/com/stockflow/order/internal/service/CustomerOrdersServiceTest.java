package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a customer sees of their own orders (SCRUM-460, kltn-docs 17 §2): only their own, filtered by Saigon days, with the
 * actions the portal may offer taken from the same rules the order applies.
 */
class CustomerOrdersServiceTest {

    private final OrderSearchRepository search = mock(OrderSearchRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final CreditChecks creditChecks = mock(CreditChecks.class);
    private final CancellationRequestRepository requests = mock(CancellationRequestRepository.class);
    private final com.stockflow.order.internal.domain.OrderRepository orderRepository =
            mock(com.stockflow.order.internal.domain.OrderRepository.class);
    private final ReturnAllowances allowances = mock(ReturnAllowances.class);
    private final LineNames lineNames = mock(LineNames.class);
    private final com.stockflow.order.internal.repository.DeliveredAndReturnedUnits deliveredAndReturned =
            mock(com.stockflow.order.internal.repository.DeliveredAndReturnedUnits.class);
    private final CustomerOrdersService service = new CustomerOrdersService(search, orders, creditChecks, requests,
            orderRepository, allowances, deliveredAndReturned, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
            lineNames);

    private CustomerOrders.Query query(LocalDate from, LocalDate to) {
        return new CustomerOrders.Query(0, 20, List.of(OrderStatus.CONFIRMED), "SO-1", from, to, null);
    }

    @Nested
    @DisplayName("the list")
    class MyOrders {

        @Test
        @DisplayName("the days a customer filters by are Saigon days: from 00:00 of the first, up to 00:00 after the last")
        void saigonDays() {
            UUID customer = UUID.randomUUID();
            when(search.search(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.myOrders(customer, query(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));

            ArgumentCaptor<OrderSearchRepository.Criteria> criteria =
                    ArgumentCaptor.forClass(OrderSearchRepository.Criteria.class);
            verify(search).search(criteria.capture(), any(Pageable.class));
            assertThat(criteria.getValue().customerId()).isEqualTo(customer);
            assertThat(criteria.getValue().statuses()).containsExactly(OrderStatus.CONFIRMED);
            assertThat(criteria.getValue().search()).isEqualTo("SO-1");
            assertThat(criteria.getValue().placedFrom()).isEqualTo(Instant.parse("2026-09-30T17:00:00Z"));
            assertThat(criteria.getValue().placedBefore()).isEqualTo(Instant.parse("2026-10-31T17:00:00Z"));
        }

        @Test
        @DisplayName("open-ended filters stay open")
        void noBounds() {
            when(search.search(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            service.myOrders(UUID.randomUUID(), query(null, null));

            ArgumentCaptor<OrderSearchRepository.Criteria> criteria =
                    ArgumentCaptor.forClass(OrderSearchRepository.Criteria.class);
            verify(search).search(criteria.capture(), any(Pageable.class));
            assertThat(criteria.getValue().placedFrom()).isNull();
            assertThat(criteria.getValue().placedBefore()).isNull();
        }

        @Test
        @DisplayName("a range that ends before it starts is VALIDATION_FAILED and nothing is searched")
        void backwardsRange() {
            assertThat(codeOf(() -> service.myOrders(UUID.randomUUID(),
                    query(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 9))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(search, never()).search(any(), any());
        }

        @Test
        @DisplayName("a card carries the line count, the status group and the latest cancellation request's status")
        void cards() {
            Order order = OrderFixtures.released();
            OrderSummary row = OrderFixtures.summaryOf(order);
            UUID id = order.id().value();
            when(search.search(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
            when(search.lineCounts(List.of(id))).thenReturn(Map.of(id, 2));
            when(requests.latestStatuses(List.of(id))).thenReturn(Map.of(id, CancellationRequestStatus.PENDING));

            PageResponse<CustomerOrders.Card> page = service.myOrders(order.customerId(), query(null, null));

            assertThat(page.items()).singleElement().satisfies(card -> {
                assertThat(card.orderNumber()).isEqualTo(order.orderNumber().value());
                assertThat(card.statusGroup()).isEqualTo(CustomerOrders.StatusGroup.PREPARING);
                assertThat(card.total()).isEqualByComparingTo("1100000");
                assertThat(card.currency()).isEqualTo("VND");
                assertThat(card.lineCount()).isEqualTo(2);
                assertThat(card.deliveryState()).isEqualTo(CustomerOrders.DeliveryState.NOT_SHIPPED);
                assertThat(card.cancellationRequestStatus()).isEqualTo("PENDING");
            });
        }

        @Test
        @DisplayName("an order the customer never asked to cancel has no request status, and a missing count reads 0")
        void cardWithoutRequest() {
            Order order = OrderFixtures.released();
            OrderSummary row = OrderFixtures.summaryOf(order);
            when(search.search(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row)));
            when(search.lineCounts(any())).thenReturn(Map.of());
            when(requests.latestStatuses(any())).thenReturn(Map.of());

            PageResponse<CustomerOrders.Card> page = service.myOrders(order.customerId(), query(null, null));

            assertThat(page.items()).singleElement().satisfies(card -> {
                assertThat(card.cancellationRequestStatus()).isNull();
                assertThat(card.lineCount()).isZero();
            });
        }
    }

    @Nested
    @DisplayName("one order")
    class MyOrder {

        private Order order(Order order) {
            OrderSummary summary = OrderFixtures.summaryOf(order);
            when(orders.findById(order.id().value())).thenReturn(Optional.of(summary));
            when(orders.history(order.id().value())).thenReturn(List.of());
            when(requests.latestOf(order.id().value())).thenReturn(Optional.empty());
            return order;
        }

        @Test
        @DisplayName("someone else's order, or none, is NOT_FOUND: which of the two is not revealed")
        void notTheirs() {
            Order order = order(OrderFixtures.released());

            assertThat(codeOf(() -> service.myOrder(UUID.randomUUID(), order.id().value()))).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(codeOf(() -> service.myOrder(order.customerId(), UUID.randomUUID()))).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("blank-only orders need no production; the detail carries the lines and an order-start timeline")
        void blankOrderDetail() {
            Order order = order(OrderFixtures.released());

            CustomerOrders.Detail detail = service.myOrder(order.customerId(), order.id().value());

            assertThat(detail.lines()).extracting(CustomerOrders.Line::sku).containsExactly("CUP-12OZ", "LID-90");
            assertThat(detail.lines()).allSatisfy(line -> assertThat(line.production().state())
                    .isEqualTo(CustomerOrders.ProductionState.NOT_REQUIRED));
            assertThat(detail.holdReasonPublic()).isNull();
            assertThat(detail.timeline()).as("no history reads as one entry, the status it is in").singleElement()
                    .satisfies(entry -> assertThat(entry.status()).isEqualTo(OrderStatus.READY_TO_FULFILL));
            assertThat(detail.cancellationRequest()).isNull();
            assertThat(detail.shipments()).isEmpty();
            verify(orderRepository, never()).findById(any());
            verify(allowances, never()).claimable(any(), any());
        }

        @Test
        @DisplayName("each line says what its SKU is called, read once for the whole order")
        void lineNames() {
            Order order = order(OrderFixtures.released());
            when(lineNames.of(anyCollection())).thenReturn(Map.of(
                    "CUP-12OZ", new LineNames.Names("Paper cup", "Cup 12oz"),
                    "LID-90", new LineNames.Names("Lid", null)));

            CustomerOrders.Detail detail = service.myOrder(order.customerId(), order.id().value());

            assertThat(detail.lines()).extracting(CustomerOrders.Line::productName).containsExactly("Paper cup", "Lid");
            assertThat(detail.lines()).extracting(CustomerOrders.Line::variantName).containsExactly("Cup 12oz", null);
            verify(lineNames).of(anyCollection());
        }

        @Test
        @DisplayName("a print line of an order not released yet is not made yet")
        void printLineBeforeRelease() {
            Order order = order(OrderFixtures.confirmedWithPrintLine(UUID.randomUUID()));

            CustomerOrders.Detail detail = service.myOrder(order.customerId(), order.id().value());

            assertThat(detail.lines().get(0).production().state()).isEqualTo(CustomerOrders.ProductionState.NOT_STARTED);
            assertThat(detail.lines().get(0).production().goodQuantity()).isZero();
        }

        @Test
        @DisplayName("before release a customer asks to cancel too, never at once (4B-5)")
        void actionsBeforeRelease() {
            Order order = order(OrderFixtures.confirmed());

            CustomerOrders.AllowedActions actions = service.myOrder(order.customerId(), order.id().value()).allowedActions();

            // 4B-5 (17 §4.8): the customer asks, before release too; Sales decides.
            assertThat(actions.cancel()).isFalse();
            assertThat(actions.requestCancellation()).isTrue();
        }

        @Test
        @DisplayName("after release the customer asks instead, one request at a time")
        void actionsAfterRelease() {
            Order order = order(OrderFixtures.released());

            assertThat(service.myOrder(order.customerId(), order.id().value()).allowedActions())
                    .isEqualTo(new CustomerOrders.AllowedActions(false, true));

            when(requests.hasPending(order.id().value())).thenReturn(true);
            assertThat(service.myOrder(order.customerId(), order.id().value()).allowedActions().requestCancellation())
                    .isFalse();
        }

        @Test
        @DisplayName("once a package is complete (goods packed for good) the customer can only return, not cancel or ask")
        void actionsAfterPacking() {
            Order order = OrderFixtures.released();
            order.markPacked(NOW, OrderFixtures.STAFF);
            order(order);

            assertThat(service.myOrder(order.customerId(), order.id().value()).allowedActions())
                    .isEqualTo(new CustomerOrders.AllowedActions(false, false));
        }

        @Test
        @DisplayName("once a carton is labelled (4C-6, 08 BR-04) the order is locked although nothing is packed yet: the "
                + "portal offers no cancellation request the order would refuse (review L4)")
        void actionsAfterTheFirstLabel() {
            Order order = OrderFixtures.released();
            order.recordFirstLabel(NOW);
            order(order);

            OrderSummary summary = OrderFixtures.summaryOf(order);
            assertThat(summary.lifecycle().packedAt()).isNull();
            assertThat(summary.lifecycle().firstLabeledAt()).isEqualTo(NOW);
            assertThat(summary.lifecycle().packedForGood()).isTrue();
            assertThat(service.myOrder(order.customerId(), order.id().value()).allowedActions())
                    .isEqualTo(new CustomerOrders.AllowedActions(false, false));
        }

        @Test
        @DisplayName("the latest cancellation request is shown with how it was decided, without who decided")
        void cancellationRequestIsShown() {
            Order order = order(OrderFixtures.released());
            CancellationRequest request = CancellationRequest.open(order.id().value(), UUID.randomUUID(),
                    CancellationReasonCode.CUSTOMER_REQUEST, "wrong logo", NOW);
            request.reject(UUID.randomUUID(), "already printed", NOW);
            when(requests.latestOf(order.id().value())).thenReturn(Optional.of(request));

            CustomerOrders.CancellationRequestView view =
                    service.myOrder(order.customerId(), order.id().value()).cancellationRequest();

            assertThat(view.status()).isEqualTo("REJECTED");
            assertThat(view.note()).isEqualTo("wrong logo");
            assertThat(view.decisionNote()).isEqualTo("already printed");
        }

        @Test
        @DisplayName("an order on hold reads as UNDER_REVIEW, or AWAITING_CREDIT_APPROVAL when a credit check is undecided")
        void holdReasonIsPublicOnly() {
            Order order = OrderFixtures.draft(UUID.randomUUID());
            order.sendContract(OrderFixtures.STAFF, OrderFixtures.NOW);
            order.contractSigned(true, false, OrderFixtures.STAFF, OrderFixtures.NOW);
            order(order);
            when(creditChecks.latest(order.id().value())).thenReturn(Optional.of(new CreditChecks.Check(
                    order.id().value(), NOW, java.math.BigDecimal.TEN, java.math.BigDecimal.TEN, java.math.BigDecimal.TEN,
                    CreditChecks.Outcome.OVER_LIMIT, null, null, null)));

            assertThat(service.myOrder(order.customerId(), order.id().value()).holdReasonPublic())
                    .isEqualTo("AWAITING_CREDIT_APPROVAL");

            when(creditChecks.latest(order.id().value())).thenReturn(Optional.empty());
            assertThat(service.myOrder(order.customerId(), order.id().value()).holdReasonPublic())
                    .isEqualTo("UNDER_REVIEW");
        }

        @Test
        @DisplayName("the timeline takes the cancellation's reason code from the order, never the typed reason")
        void timelineExposesOnlyTheCode() {
            Order order = OrderFixtures.draft(UUID.randomUUID());
            order.sendContract(OrderFixtures.STAFF, NOW);
            order.contractSigned(false, true, OrderFixtures.STAFF, NOW);
            order.cancel(CancellationReasonCode.DUPLICATE_ORDER, "secret note", null, null, NOW);
            order(order);
            when(orders.history(order.id().value())).thenReturn(List.of(
                    new OrderStatusChange(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "DUPLICATE_ORDER: secret note",
                            NOW)));

            CustomerOrders.Detail detail = service.myOrder(order.customerId(), order.id().value());

            assertThat(detail.timeline()).singleElement().satisfies(entry -> {
                assertThat(entry.status()).isEqualTo(OrderStatus.CANCELLED);
                assertThat(entry.reasonCode()).isEqualTo(CancellationReasonCode.DUPLICATE_ORDER);
            });
        }
    }

    @Nested
    @DisplayName("WF-05 TH-1: the shipments a return is opened against")
    class Shipments {

        @Test
        @DisplayName("an order delivered in two shipments shows each one's id, oldest first: the portal names it when "
                + "the customer opens a return (RETURN_SHIPMENT_REQUIRED otherwise)")
        void eachShipmentCarriesItsId() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            OrderSummary summary = mock(OrderSummary.class);
            when(summary.lifecycle()).thenReturn(new OrderSummary.Lifecycle(null, null, null, null, NOW, NOW, null,
                    null, null, null, null, List.of(), List.of(
                    new OrderSummary.Shipment(second, com.stockflow.order.api.OrderShipmentStatus.DELIVERED, null,
                            NOW.plusSeconds(3_600), NOW.plusSeconds(7_200), null, List.of(), null),
                    new OrderSummary.Shipment(first, com.stockflow.order.api.OrderShipmentStatus.DELIVERED, null,
                            NOW, NOW.plusSeconds(600), null, List.of(), null)),
                    true, NOW, NOW));

            assertThat(CustomerOrdersService.shipments(summary, com.stockflow.order.internal.domain.ReturnRequest
                    .Claimable.NONE)).extracting(CustomerOrders.Shipment::shipmentId).containsExactly(first, second);
        }

        @Test
        @DisplayName("each shipment carries its own lines with what a return of it may still claim; the shipment made "
                + "up for an order that left as a whole carries the order's lines")
        void eachShipmentCarriesItsLines() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID cups = UUID.randomUUID();
            UUID lids = UUID.randomUUID();
            OrderSummary summary = mock(OrderSummary.class);
            when(summary.lifecycle()).thenReturn(new OrderSummary.Lifecycle(null, null, null, null, NOW, NOW, null,
                    null, null, null, null, List.of(), List.of(
                    new OrderSummary.Shipment(first, com.stockflow.order.api.OrderShipmentStatus.DELIVERED, null,
                            NOW, NOW.plusSeconds(600), null, List.of(), null),
                    new OrderSummary.Shipment(second, com.stockflow.order.api.OrderShipmentStatus.HANDED_OVER, null,
                            NOW.plusSeconds(3_600), null, null, List.of(), null)),
                    true, NOW, NOW));
            var claimable = new com.stockflow.order.internal.domain.ReturnRequest.Claimable(Map.of(
                    first, List.of(new com.stockflow.order.internal.domain.ReturnRequest.ClaimableLine(cups, "CUP-12OZ",
                                    60, 45),
                            new com.stockflow.order.internal.domain.ReturnRequest.ClaimableLine(lids, "LID-90", 40, 0)),
                    second, List.of(new com.stockflow.order.internal.domain.ReturnRequest.ClaimableLine(cups,
                            "CUP-12OZ", 40, 0))), List.of());

            List<CustomerOrders.Shipment> shipments = CustomerOrdersService.shipments(summary, claimable);

            assertThat(shipments.get(0).lines()).containsExactly(new CustomerOrders.ShipmentLine(cups, "CUP-12OZ", 60, 45),
                    new CustomerOrders.ShipmentLine(lids, "LID-90", 40, 0));
            assertThat(shipments.get(1).lines()).containsExactly(new CustomerOrders.ShipmentLine(cups, "CUP-12OZ", 40,
                    0));

            OrderSummary whole = mock(OrderSummary.class);
            when(whole.status()).thenReturn(OrderStatus.DELIVERED);
            when(whole.lifecycle()).thenReturn(new OrderSummary.Lifecycle(null, null, null, null, NOW, NOW, null,
                    null, null, null, null, List.of(), List.of(), false, NOW, NOW));
            var asAWhole = new com.stockflow.order.internal.domain.ReturnRequest.Claimable(Map.of(), List.of(
                    new com.stockflow.order.internal.domain.ReturnRequest.ClaimableLine(cups, "CUP-12OZ", 100, 70)));
            assertThat(CustomerOrdersService.shipments(whole, asAWhole)).singleElement().satisfies(shipment -> {
                assertThat(shipment.shipmentId()).isNull();
                assertThat(shipment.lines()).containsExactly(new CustomerOrders.ShipmentLine(cups, "CUP-12OZ", 100, 70));
            });
        }

        @Test
        @DisplayName("the detail reads the lines from the order and its returns once, for a delivered order")
        void theDetailReadsTheLines() {
            Order order = OrderFixtures.released();
            OrderSummary real = OrderFixtures.summaryOf(order);
            OrderSummary summary = org.mockito.Mockito.spy(real);
            UUID line = real.lines().getFirst().lineId();
            when(summary.status()).thenReturn(OrderStatus.DELIVERED);
            when(summary.lifecycle()).thenReturn(new OrderSummary.Lifecycle(null, null, null, null, NOW, NOW, null,
                    null, null, null, null, List.of(), List.of(), false, NOW, NOW));
            when(orders.findById(order.id().value())).thenReturn(Optional.of(summary));
            when(orders.history(order.id().value())).thenReturn(List.of());
            when(requests.latestOf(order.id().value())).thenReturn(Optional.empty());
            when(orderRepository.findById(order.id())).thenReturn(Optional.of(order));
            when(allowances.claimable(order, NOW)).thenReturn(new com.stockflow.order.internal.domain.ReturnRequest
                    .Claimable(Map.of(), List.of(new com.stockflow.order.internal.domain.ReturnRequest.ClaimableLine(
                    line, "CUP-12OZ", 10, 7))));

            CustomerOrders.Detail detail = service.myOrder(order.customerId(), order.id().value());

            assertThat(detail.shipments()).singleElement().satisfies(shipment -> assertThat(shipment.lines())
                    .containsExactly(new CustomerOrders.ShipmentLine(line, "CUP-12OZ", 10, 7)));
            verify(allowances).claimable(order, NOW);
        }
    }
}
