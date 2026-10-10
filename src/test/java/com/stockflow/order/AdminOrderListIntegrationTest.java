package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-443: the back-office order list and the status history every save now records. */
@IntegrationTest
@Import(PostgresContainer.class)
class AdminOrderListIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired OrderService orders;

    private OrderSummary place() {
        return orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID, List.of(
                new PlaceOrderCommand.Line(SOFA, 1, Money.vnd(12_000_000), null))));
    }

    private void cancel(UUID orderId, String reason) {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(orderId, reason));
    }

    private static ListOrdersQuery query(String search, List<OrderStatus> statuses, String sort) {
        return new ListOrdersQuery(0, 200, search, statuses, null, null, null, sort);
    }

    @Test
    @DisplayName("search finds an order by part of its number; status filters; rows carry no lines")
    void searchAndStatus() {
        OrderSummary open = place();
        OrderSummary cancelled = place();
        cancel(cancelled.orderId(), "TEST_CLEANUP");

        String fragment = open.orderNumber().substring(open.orderNumber().length() - 6).toLowerCase();
        var found = orders.list(query(fragment, List.of(), null));
        assertThat(found.items()).extracting(OrderSummary::orderId).contains(open.orderId());
        assertThat(found.items()).allSatisfy(row -> assertThat(row.lines()).isEmpty());

        var onlyCancelled = orders.list(query(null, List.of(OrderStatus.CANCELLED), null));
        assertThat(onlyCancelled.items()).extracting(OrderSummary::orderId)
                .contains(cancelled.orderId()).doesNotContain(open.orderId());
        assertThat(onlyCancelled.items()).allSatisfy(row -> assertThat(row.status()).isEqualTo(OrderStatus.CANCELLED));

        var both = orders.list(query(null, List.of(OrderStatus.CANCELLED, OrderStatus.PENDING_PAYMENT), null));
        assertThat(both.items()).extracting(OrderSummary::orderId).contains(open.orderId(), cancelled.orderId());

        // A literal % in the search box is a character, not a wildcard.
        assertThat(orders.list(query("%", List.of(), null)).items()).isEmpty();

        cancel(open.orderId(), "TEST_CLEANUP");
    }

    @Test
    @DisplayName("date range is inclusive in Saigon days; an inverted range is a 400; sort is whitelisted")
    void datesAndSort() {
        OrderSummary order = place();

        var today = orders.list(new ListOrdersQuery(0, 200, null, List.of(), DemoData.CUSTOMER_ID, TODAY, TODAY, null));
        assertThat(today.items()).extracting(OrderSummary::orderId).contains(order.orderId());
        var tomorrow = orders.list(new ListOrdersQuery(0, 200, null, List.of(), null, TODAY.plusDays(1), null, null));
        assertThat(tomorrow.items()).extracting(OrderSummary::orderId).doesNotContain(order.orderId());

        assertThatThrownBy(() -> orders.list(new ListOrdersQuery(0, 20, null, List.of(), null, TODAY, TODAY.minusDays(1), null)))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        var byNumber = orders.list(query(null, List.of(), "orderNumber,asc")).items();
        assertThat(byNumber).extracting(OrderSummary::orderNumber).isSorted();

        cancel(order.orderId(), "TEST_CLEANUP");
    }

    @Test
    @DisplayName("history records the placed status and every transition, with the cancellation reason")
    void history() {
        OrderSummary order = place();
        cancel(order.orderId(), "CUSTOMER_CHANGED_MIND");

        List<OrderStatusChange> history = orders.history(order.orderId());
        assertThat(history).extracting(OrderStatusChange::from, OrderStatusChange::to)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(null, OrderStatus.PENDING_PAYMENT),
                        org.assertj.core.groups.Tuple.tuple(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED));
        assertThat(history.get(1).reason()).isEqualTo("OTHER: CUSTOMER_CHANGED_MIND");
        assertThat(orders.history(UUID.randomUUID())).isEmpty();
    }
}
