package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-443: the back-office order list and the status history every save now records.
 *
 * <p>Since 4B-10 an order reaches the list the way production makes one: a contract's, signed and waiting for its
 * installment on signing ({@link ConfirmedOrders#signed}) - a checkout order never leaves PENDING_PAYMENT any more, and
 * checkout itself goes with 5-2. The orders are of the demo customer's own SKU (14 BR-04).</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class AdminOrderListIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired OrderService orders;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private CustomerSkuRows.Issued sku;

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, 50);
    }

    /** A contract's order of the demo customer, signed, waiting for its installment on signing: PENDING_PAYMENT. */
    private OrderSummary place() {
        UUID orderId = confirmedOrders.signed(DemoData.CUSTOMER_ID,
                ConfirmedOrders.Line.cartons(sku, 1, 12_000)).orderId();
        return orders.findById(orderId).orElseThrow();
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

        assertThatThrownBy(() -> orders.list(new ListOrdersQuery(0, 20, null, List.of(), null, TODAY,
                TODAY.minusDays(1), null)))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        var byNumber = orders.list(query(null, List.of(), "orderNumber,asc")).items();
        assertThat(byNumber).extracting(OrderSummary::orderNumber).isSorted();

        cancel(order.orderId(), "TEST_CLEANUP");
    }

    @Test
    @DisplayName("history records every transition of a contract's order, with the cancellation reason")
    void history() {
        OrderSummary order = place();
        cancel(order.orderId(), "CUSTOMER_CHANGED_MIND");

        List<OrderStatusChange> history = orders.history(order.orderId());
        assertThat(history).extracting(OrderStatusChange::from, OrderStatusChange::to)
                .as("sent, signed, cancelled (14 §4.3 steps 13-15)")
                .endsWith(
                        Tuple.tuple(OrderStatus.DRAFT, OrderStatus.PENDING_CONTRACT),
                        Tuple.tuple(OrderStatus.PENDING_CONTRACT, OrderStatus.PENDING_PAYMENT),
                        Tuple.tuple(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED));
        assertThat(history.getFirst().from()).as("the first row is the order's creation").isNull();
        assertThat(history.getLast().reason()).isEqualTo("OTHER: CUSTOMER_CHANGED_MIND");
        assertThat(orders.history(UUID.randomUUID())).isEmpty();
    }
}
