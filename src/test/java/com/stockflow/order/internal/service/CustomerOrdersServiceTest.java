package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.CancellationRequestStatus;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.ProductionRecords;
import com.stockflow.order.internal.domain.Quote;
import com.stockflow.order.internal.domain.QuoteRepository;
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
    private final ProductionRecords production = mock(ProductionRecords.class);
    private final CreditChecks creditChecks = mock(CreditChecks.class);
    private final CancellationRequestRepository requests = mock(CancellationRequestRepository.class);
    private final QuoteRepository quotes = mock(QuoteRepository.class);
    private final CustomerOrdersService service =
            new CustomerOrdersService(search, orders, production, creditChecks, requests, quotes);

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
            Order order = OrderFixtures.releasedPrepaid();
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
            Order order = OrderFixtures.releasedPrepaid();
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
            when(quotes.findByConvertedOrderId(order.id().value())).thenReturn(Optional.empty());
            return order;
        }

        @Test
        @DisplayName("someone else's order, or none, is NOT_FOUND: which of the two is not revealed")
        void notTheirs() {
            Order order = order(OrderFixtures.releasedPrepaid());

            assertThat(codeOf(() -> service.myOrder(UUID.randomUUID(), order.id().value()))).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(codeOf(() -> service.myOrder(order.customerId(), UUID.randomUUID()))).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("blank-only orders do not ask production; the detail carries the lines, payment and an order-start timeline")
        void blankOrderDetail() {
            Order order = order(OrderFixtures.releasedPrepaid());

            CustomerOrders.Detail detail = service.myOrder(order.customerId(), order.id().value());

            verify(production, never()).producedByLine(any());
            assertThat(detail.lines()).extracting(CustomerOrders.Line::sku).containsExactly("CUP-12OZ", "LID-90");
            assertThat(detail.lines()).allSatisfy(line -> assertThat(line.production().state())
                    .isEqualTo(CustomerOrders.ProductionState.NOT_REQUIRED));
            assertThat(detail.payment().paidAmount()).isEqualByComparingTo("1100000");
            assertThat(detail.paymentTerm()).isEqualTo(PaymentTerm.PREPAID);
            assertThat(detail.holdReasonPublic()).isNull();
            assertThat(detail.timeline()).as("no history reads as one entry, the status it is in").singleElement()
                    .satisfies(entry -> assertThat(entry.status()).isEqualTo(OrderStatus.READY_TO_FULFILL));
            assertThat(detail.cancellationRequest()).isNull();
            assertThat(detail.shipments()).isEmpty();
            assertThat(detail.deliveryMode()).isNotNull();
        }

        @Test
        @DisplayName("an order with a print line reads its good units from production, once")
        void printLineAsksProduction() {
            UUID design = UUID.randomUUID();
            Order order = order(OrderFixtures.confirmedWithPrintLine(design));
            UUID printLine = order.printLines().get(0).id();
            when(production.producedByLine(order.id().value())).thenReturn(Map.of(printLine, 120));

            CustomerOrders.Detail detail = service.myOrder(order.customerId(), order.id().value());

            assertThat(detail.lines().get(0).production().state()).isEqualTo(CustomerOrders.ProductionState.NOT_STARTED);
            assertThat(detail.lines().get(0).production().goodQuantity()).isEqualTo(120);
            verify(production, org.mockito.Mockito.times(1)).producedByLine(order.id().value());
        }

        @Test
        @DisplayName("before release a customer may cancel at once and cannot ask; a quote-born order can be reordered")
        void actionsBeforeRelease() {
            Order order = order(OrderFixtures.confirmedPrepaid());
            when(quotes.findByConvertedOrderId(order.id().value())).thenReturn(Optional.of(mock(Quote.class)));

            CustomerOrders.AllowedActions actions = service.myOrder(order.customerId(), order.id().value()).allowedActions();

            assertThat(actions.cancel()).isTrue();
            assertThat(actions.requestCancellation()).isFalse();
            assertThat(actions.reorder()).isTrue();
        }

        @Test
        @DisplayName("after release the customer asks instead, one request at a time")
        void actionsAfterRelease() {
            Order order = order(OrderFixtures.releasedPrepaid());

            assertThat(service.myOrder(order.customerId(), order.id().value()).allowedActions())
                    .isEqualTo(new CustomerOrders.AllowedActions(false, true, false));

            when(requests.hasPending(order.id().value())).thenReturn(true);
            assertThat(service.myOrder(order.customerId(), order.id().value()).allowedActions().requestCancellation())
                    .isFalse();
        }

        @Test
        @DisplayName("once a package is complete (goods packed for good) the customer can only return, not cancel or ask")
        void actionsAfterPacking() {
            Order order = OrderFixtures.releasedPrepaid();
            order.markPacked(NOW, OrderFixtures.STAFF);
            order(order);

            assertThat(service.myOrder(order.customerId(), order.id().value()).allowedActions())
                    .isEqualTo(new CustomerOrders.AllowedActions(false, false, false));
        }

        @Test
        @DisplayName("the latest cancellation request is shown with how it was decided, without who decided")
        void cancellationRequestIsShown() {
            Order order = order(OrderFixtures.releasedPrepaid());
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
            Order order = OrderFixtures.draft(PaymentTerm.CREDIT, UUID.randomUUID());
            order.submitOnCredit(false);
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
            Order order = OrderFixtures.draft(PaymentTerm.PREPAID, UUID.randomUUID());
            order.submit();
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
}
