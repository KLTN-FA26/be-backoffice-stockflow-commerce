package com.stockflow.reporting.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.reporting.api.ReportingService;
import com.stockflow.reporting.internal.domain.BusinessDay;
import com.stockflow.reporting.internal.repository.SalesRollupQueries;
import com.stockflow.support.Await;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.TestTokens;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-344 end to end against a real database: place and cancel orders and read the dashboard figures back over HTTP
 * with security on; replayed events change nothing; the rebuild gives the listeners' figures; the backfill fills in
 * history; the grants and the range limits hold.
 *
 * <p>Every figure is compared with a baseline taken first: the context's database is shared with the other test classes
 * of the same context, which place orders of their own.</p>
 */
@SecuredIntegrationTest
class SalesRollupIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final String REPORTS = "/api/v1/reports";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired OrderService orders;
    @Autowired ReportingService reports;
    @Autowired CustomerService customers;
    @Autowired SalesRollupRebuilder rebuilder;
    @Autowired SalesRollupQueries queries;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;

    private final List<UUID> placed = new ArrayList<>();

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            if (orders.findById(id).orElseThrow().status() != OrderStatus.CANCELLED) {
                orders.cancel(id, new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null));
            }
        }));
        placed.clear();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private OrderSummary place(UUID customer, int quantity, long unitPrice, PaymentTerm term) {
        OrderSummary order = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> orders.placeOrder(
                new PlaceOrderCommand(UUID.randomUUID(), customer, null, null, false,
                        List.of(new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(unitPrice), null)), term, null)));
        placed.add(order.orderId());
        return order;
    }

    private OrderSummary place(int quantity, long unitPrice) {
        return place(com.stockflow.support.DemoData.CUSTOMER_ID, quantity, unitPrice, null);
    }

    private OrderSummary cancel(OrderSummary order) {
        return WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(),
                new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, "changed mind", null, null)));
    }

    private static LocalDate dayOf(OrderSummary order) {
        return BusinessDay.of(order.placedAt());
    }

    private boolean applied(UUID orderId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM reporting.processed_order_events "
                + "WHERE order_id = ? AND event_type = ?", Integer.class, orderId, type) == 1;
    }

    private void awaitPlaced(OrderSummary order) {
        Await.until("OrderPlaced applied", ASYNC, () -> applied(order.orderId(), "PLACED"));
    }

    private void awaitCancelled(OrderSummary order) {
        Await.until("OrderCancelled applied", ASYNC, () -> applied(order.orderId(), "CANCELLED"));
    }

    /** No rollup event is waiting or running: everything published so far has been handled. */
    private void awaitRollupsIdle() {
        Await.until("rollup events handled", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                WHERE completion_date IS NULL AND listener_id LIKE '%SalesRollupListener%'""", Integer.class) == 0);
    }

    /**
     * Brings today's rollups to what a rebuild gives: other test classes of the same context insert orders of their
     * own by SQL, which no listener ever counted. After this the listeners and the rebuild start from the same
     * figures, so the comparisons below hold whatever ran before.
     */
    private void settleToday(LocalDate today) {
        awaitRollupsIdle();
        reports.rebuild(today, today);
    }

    private ReportingService.SalesDay dayRow(LocalDate day) {
        return reports.salesDaily(day, day).getFirst();
    }

    private long unitsOfSofa(LocalDate day) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity_sold), 0) FROM reporting.product_sales_summary "
                + "WHERE sku = ? AND period = ?", Long.class, SOFA.code(), BusinessDay.period(day));
    }

    private BigDecimal revenueOfSofa(LocalDate day) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(revenue), 0) FROM reporting.product_sales_summary "
                + "WHERE sku = ? AND period = ?", BigDecimal.class, SOFA.code(), BusinessDay.period(day));
    }

    /** Everything the rollups hold for one day, as comparable text. */
    private List<String> snapshot(LocalDate day) {
        List<String> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("SELECT 'day ' || orders_count || ' ' || revenue || ' ' || currency FROM "
                + "reporting.sales_daily_summary WHERE day = ? ORDER BY currency", String.class, java.sql.Date.valueOf(day)));
        rows.addAll(jdbc.queryForList("SELECT 'sku ' || sku || ' ' || quantity_sold || ' ' || revenue FROM "
                + "reporting.product_sales_summary WHERE period = ? ORDER BY sku", String.class, BusinessDay.period(day)));
        rows.addAll(jdbc.queryForList("SELECT 'ledger ' || order_id || ' ' || event_type || ' ' || total FROM "
                + "reporting.processed_order_events WHERE placed_day = ? ORDER BY order_id, event_type", String.class,
                java.sql.Date.valueOf(day)));
        return rows;
    }

    private String token(Role role) {
        return TestTokens.bearer(decoder, UUID.randomUUID(), role);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Role role) throws Exception {
        return mvc.perform(request.header("Authorization", token(role)));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    // ---- the listeners -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("place, then cancel: the day's orders, revenue and the SKU's units follow, on the placement day")
    void placeThenCancel() {
        LocalDate today = BusinessDay.today(clock);
        var before = dayRow(today);
        long unitsBefore = unitsOfSofa(today);
        BigDecimal revenueBefore = revenueOfSofa(today);

        OrderSummary order = place(2, 12_000_000);
        awaitPlaced(order);

        assertThat(dayOf(order)).isEqualTo(today);
        assertThat(dayRow(today).ordersCount()).isEqualTo(before.ordersCount() + 1);
        assertThat(dayRow(today).revenue()).isEqualByComparingTo(before.revenue().add(new BigDecimal("24000000")));
        assertThat(unitsOfSofa(today)).isEqualTo(unitsBefore + 2);
        assertThat(revenueOfSofa(today)).isEqualByComparingTo(revenueBefore.add(new BigDecimal("24000000")));

        cancel(order);
        awaitCancelled(order);

        assertThat(dayRow(today).ordersCount()).isEqualTo(before.ordersCount());
        assertThat(dayRow(today).revenue()).isEqualByComparingTo(before.revenue());
        assertThat(unitsOfSofa(today)).isEqualTo(unitsBefore);
        assertThat(revenueOfSofa(today)).isEqualByComparingTo(revenueBefore);
    }

    @Test
    @DisplayName("events delivered twice, and a cancellation twice, change nothing")
    void replayedEventsAreIgnored() {
        LocalDate today = BusinessDay.today(clock);
        var before = dayRow(today);
        OrderSummary order = place(1, 5_000_000);
        awaitPlaced(order);

        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderPlaced(order.orderId(), order.customerId(),
                "x@example.com", order.orderNumber(),
                List.of(new OrderPlaced.OrderLine(SOFA.code(), 1, new BigDecimal("5000000"), null)),
                new BigDecimal("5000000"), "VND")));
        awaitRollupsIdle();
        assertThat(dayRow(today).ordersCount()).isEqualTo(before.ordersCount() + 1);
        assertThat(dayRow(today).revenue()).isEqualByComparingTo(before.revenue().add(new BigDecimal("5000000")));

        cancel(order);
        awaitCancelled(order);
        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderCancelled(order.orderId(), order.orderNumber(),
                order.customerId(), "PENDING_PAYMENT", "CUSTOMER_REQUEST", null, BigDecimal.ZERO, BigDecimal.ZERO,
                "VND", null, Instant.now())));
        awaitRollupsIdle();

        assertThat(dayRow(today).ordersCount()).isEqualTo(before.ordersCount());
        assertThat(dayRow(today).revenue()).isEqualByComparingTo(before.revenue());
    }

    @Test
    @DisplayName("an ON_HOLD credit order is counted (DASHHOLD)")
    void onHoldOrderCounts() {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, true, false, true, CommercialTerm.PREPAID, null,
                new BigDecimal("0"), 30, "test terms", accountant, null));
        LocalDate today = BusinessDay.today(clock);
        var before = dayRow(today);

        OrderSummary order = place(customer, 1, 7_000_000, PaymentTerm.CREDIT);
        assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
        awaitPlaced(order);

        assertThat(dayRow(today).ordersCount()).isEqualTo(before.ordersCount() + 1);
        assertThat(dayRow(today).revenue()).isEqualByComparingTo(before.revenue().add(new BigDecimal("7000000")));
    }

    @Test
    @DisplayName("a return's credited value comes off the order's placed day - once, and again after a rebuild; a "
            + "replacement-only resolution takes nothing")
    void returnTakesRevenueOff() {
        LocalDate today = BusinessDay.today(clock);
        settleToday(today);
        OrderSummary order = place(3, 4_000_000);
        awaitPlaced(order);
        var before = dayRow(today);
        long unitsBefore = unitsOfSofa(today);
        UUID returnId = UUID.randomUUID();
        com.stockflow.contracts.ReturnResolved credited = new com.stockflow.contracts.ReturnResolved(returnId,
                "RMA-IT-9", order.orderId(), order.orderNumber(), order.customerId(), null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("4000000"), "VND", true,
                List.of(new com.stockflow.contracts.ReturnResolved.Line(UUID.randomUUID(), SOFA.code(), 1, 0, 1,
                        new BigDecimal("4000000"))), UUID.randomUUID(), Instant.now(), order.orderId());
        tx.executeWithoutResult(s -> publisher.publishEvent(credited));
        Await.until("return applied", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "reporting.processed_return_events WHERE return_request_id = ?", Integer.class, returnId) == 1);

        assertThat(dayRow(today).ordersCount()).as("the sale happened").isEqualTo(before.ordersCount());
        assertThat(dayRow(today).revenue()).isEqualByComparingTo(before.revenue().subtract(new BigDecimal("4000000")));
        assertThat(unitsOfSofa(today)).isEqualTo(unitsBefore - 1);

        tx.executeWithoutResult(s -> publisher.publishEvent(credited));
        tx.executeWithoutResult(s -> publisher.publishEvent(new com.stockflow.contracts.ReturnResolved(UUID.randomUUID(),
                "RMA-IT-10", order.orderId(), order.orderNumber(), order.customerId(), UUID.randomUUID(), "SO-X",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "VND", false, List.of(), UUID.randomUUID(),
                Instant.now(), order.orderId())));
        awaitRollupsIdle();
        assertThat(dayRow(today).revenue()).as("once, and nothing for a replacement")
                .isEqualByComparingTo(before.revenue().subtract(new BigDecimal("4000000")));

        try {
            List<String> listeners = snapshot(today);
            reports.rebuild(today, today);
            assertThat(snapshot(today)).as("the rebuild takes the return off again").isEqualTo(listeners);
            assertThat(dayRow(today).revenue())
                    .isEqualByComparingTo(before.revenue().subtract(new BigDecimal("4000000")));
        } finally {
            // The cleanup cancels the order, which a real one - delivered, returned - never is: the return goes first.
            tx.executeWithoutResult(s -> {
                jdbc.update("DELETE FROM reporting.processed_return_events WHERE return_request_id = ?", returnId);
                jdbc.update("UPDATE reporting.sales_daily_summary SET revenue = revenue + 4000000 WHERE day = ? "
                        + "AND currency = 'VND'", java.sql.Date.valueOf(today));
                jdbc.update("UPDATE reporting.product_sales_summary SET quantity_sold = quantity_sold + 1, "
                        + "revenue = revenue + 4000000 WHERE sku = ? AND period = ?", SOFA.code(),
                        BusinessDay.period(today));
            });
        }
    }

    // ---- the rebuild -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the rebuild gives exactly the figures the listeners produced, and again on a second run")
    void rebuildEqualsListeners() {
        LocalDate today = BusinessDay.today(clock);
        settleToday(today);
        OrderSummary kept = place(3, 4_000_000);
        OrderSummary cancelled = place(2, 4_000_000);
        awaitPlaced(kept);
        awaitPlaced(cancelled);
        cancel(cancelled);
        awaitCancelled(cancelled);
        List<String> listeners = snapshot(today);
        assertThat(listeners).isNotEmpty();

        ReportingService.RebuildResult first = reports.rebuild(today, today);

        assertThat(snapshot(today)).isEqualTo(listeners);
        assertThat(first.days()).isEqualTo(1);
        assertThat(first.ordersSeen()).isGreaterThanOrEqualTo(2);
        assertThat(first.ordersCounted()).isLessThan(first.ordersSeen());
        reports.rebuild(today, today);
        assertThat(snapshot(today)).isEqualTo(listeners);
        // An OrderCancelled arriving after the rebuild finds it recorded: nothing moves.
        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderCancelled(cancelled.orderId(),
                cancelled.orderNumber(), cancelled.customerId(), "PENDING_PAYMENT", "CUSTOMER_REQUEST", null,
                BigDecimal.ZERO, BigDecimal.ZERO, "VND", null, Instant.now())));
        awaitRollupsIdle();
        assertThat(snapshot(today)).isEqualTo(listeners);
    }

    @Test
    @DisplayName("the day is the Ho Chi Minh day: 23:59:59 stays on the day, 00:00:00 starts the next")
    void businessDayBoundaryInTheRebuild() {
        LocalDate today = BusinessDay.today(clock);
        LocalDate d14 = LocalDate.of(2024, 3, 14);
        LocalDate d15 = LocalDate.of(2024, 3, 15);
        OrderSummary late = place(1, 1_000_000);
        OrderSummary early = place(1, 2_000_000);
        awaitPlaced(late);
        awaitPlaced(early);
        // 2024-03-14T16:59:59Z is 23:59:59 on the 14th in Ho Chi Minh; one second later it is the 15th there.
        movePlacement(late, "2024-03-14T16:59:59Z");
        movePlacement(early, "2024-03-14T17:00:00Z");
        reports.rebuild(today, today);

        reports.rebuild(d14, d15);

        assertThat(dayRow(d14).ordersCount()).isEqualTo(1);
        assertThat(dayRow(d14).revenue()).isEqualByComparingTo("1000000");
        assertThat(dayRow(d15).ordersCount()).isEqualTo(1);
        assertThat(dayRow(d15).revenue()).isEqualByComparingTo("2000000");
        assertThat(unitsOfSofa(d14)).isEqualTo(1);
        assertThat(unitsOfSofa(d15)).isEqualTo(1);
    }

    private void movePlacement(OrderSummary order, String instant) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET placed_at = ?::timestamptz WHERE id = ?",
                instant, order.orderId()));
    }

    @Test
    @DisplayName("the startup backfill rolls up the history that predates the ledger, then does nothing")
    void backfillFillsHistoryOnce() {
        LocalDate today = BusinessDay.today(clock);
        LocalDate old = LocalDate.of(2024, 3, 10);
        OrderSummary order = place(1, 3_000_000);
        awaitPlaced(order);
        movePlacement(order, "2024-03-10T05:00:00Z");
        reports.rebuild(today, today);
        // Pretend the listeners never ran for that day: nothing rolled up, nothing in the ledger.
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM reporting.processed_order_events WHERE placed_day = ?", java.sql.Date.valueOf(old));
            jdbc.update("DELETE FROM reporting.sales_daily_summary WHERE day = ?", java.sql.Date.valueOf(old));
            jdbc.update("DELETE FROM reporting.product_sales_summary WHERE period = ?", BusinessDay.period(old));
        });
        assertThat(dayRow(old).ordersCount()).isZero();
        SalesRollupBackfill backfill = new SalesRollupBackfill(orders, queries, rebuilder, clock);

        var range = backfill.backfill();

        assertThat(range).isPresent();
        assertThat(range.get()[0]).isBeforeOrEqualTo(old);
        assertThat(dayRow(old).ordersCount()).isEqualTo(1);
        assertThat(dayRow(old).revenue()).isEqualByComparingTo("3000000");
        assertThat(backfill.backfill()).as("a second start has nothing older to roll up").isEmpty();
    }

    // ---- HTTP: shapes, grants, limits --------------------------------------------------------------------------

    @Test
    @DisplayName("sales-daily answers one zero-filled entry per day, with the documented shape")
    void salesDailyOverHttp() throws Exception {
        LocalDate today = BusinessDay.today(clock);
        OrderSummary order = place(1, 9_000_000);
        awaitPlaced(order);

        JsonNode days = data(send(get(REPORTS + "/sales-daily").param("from", today.minusDays(6).toString())
                .param("to", today.toString()), Role.ACCOUNTANT).andExpect(status().isOk()));

        assertThat(days).hasSize(7);
        assertThat(days.get(0).path("day").asText()).isEqualTo(today.minusDays(6).toString());
        assertThat(days.get(0).path("currency").asText()).isEqualTo("VND");
        assertThat(days.get(0).path("ordersCount").isInt()).isTrue();
        assertThat(days.get(6).path("day").asText()).isEqualTo(today.toString());
        assertThat(days.get(6).path("ordersCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(days.get(6).path("revenue").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("9000000"));
    }

    @Test
    @DisplayName("no dates given: the last 30 days")
    void defaultRange() throws Exception {
        assertThat(data(send(get(REPORTS + "/sales-daily"), Role.ACCOUNTANT).andExpect(status().isOk()))).hasSize(30);
    }

    @Test
    @DisplayName("top-products ranks by revenue, names the product, honours the limit")
    void topProductsOverHttp() throws Exception {
        LocalDate today = BusinessDay.today(clock);
        OrderSummary order = place(2, 11_000_000);
        awaitPlaced(order);

        JsonNode top = data(send(get(REPORTS + "/top-products").param("from", today.toString())
                .param("to", today.toString()).param("limit", "3"), Role.ECOMMERCE_ADMIN).andExpect(status().isOk()));

        assertThat(top.size()).isBetween(1, 3);
        JsonNode sofa = Stream.iterate(0, i -> i + 1).limit(top.size()).map(top::get)
                .filter(row -> SOFA.code().equals(row.path("sku").asText())).findFirst().orElseThrow();
        assertThat(sofa.path("quantitySold").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(sofa.path("revenue").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("22000000"));
        for (int i = 1; i < top.size(); i++) {
            assertThat(top.get(i - 1).path("revenue").decimalValue())
                    .isGreaterThanOrEqualTo(top.get(i).path("revenue").decimalValue());
        }
        send(get(REPORTS + "/top-products").param("limit", "0"), Role.ACCOUNTANT).andExpect(status().isBadRequest());
        send(get(REPORTS + "/top-products").param("limit", "101"), Role.ACCOUNTANT).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("order-status-counts lists every status, ON_HOLD included, live from the orders")
    void orderStatusCountsOverHttp() throws Exception {
        OrderSummary order = place(1, 1_000_000);

        JsonNode counts = data(send(get(REPORTS + "/order-status-counts"), Role.ORDER_COORDINATOR)
                .andExpect(status().isOk()));

        assertThat(counts).hasSize(OrderStatus.values().length);
        Map<String, Long> byStatus = new java.util.HashMap<>();
        counts.forEach(row -> byStatus.put(row.path("status").asText(), row.path("count").asLong()));
        assertThat(byStatus).containsKeys("ON_HOLD", "PENDING_PAYMENT", "CANCELLED", "DRAFT");
        assertThat(byStatus.get(order.status().name())).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("summary: totals of the range, the previous equally long period, and the percent change")
    void summaryOverHttp() throws Exception {
        LocalDate today = BusinessDay.today(clock);
        OrderSummary order = place(1, 6_000_000);
        awaitPlaced(order);
        tx.executeWithoutResult(s -> {
            // A previous period of known size: 2 orders, 10 000 000 on a day inside the previous period.
            LocalDate back = today.minusDays(7);
            jdbc.update("DELETE FROM reporting.sales_daily_summary WHERE day = ?", java.sql.Date.valueOf(back));
            jdbc.update("INSERT INTO reporting.sales_daily_summary (id, day, orders_count, revenue, currency, version, "
                    + "created_at) VALUES (?, ?, 2, 10000000, 'VND', 0, NOW())", UUID.randomUUID(), java.sql.Date.valueOf(back));
        });

        JsonNode summary = data(send(get(REPORTS + "/summary").param("from", today.minusDays(4).toString())
                .param("to", today.toString()), Role.ACCOUNTANT).andExpect(status().isOk()));

        assertThat(summary.path("from").asText()).isEqualTo(today.minusDays(4).toString());
        assertThat(summary.path("previousFrom").asText()).isEqualTo(today.minusDays(9).toString());
        assertThat(summary.path("previousTo").asText()).isEqualTo(today.minusDays(5).toString());
        assertThat(summary.path("currency").asText()).isEqualTo("VND");
        assertThat(summary.path("previous").path("ordersCount").asLong()).isEqualTo(2);
        assertThat(summary.path("previous").path("revenue").decimalValue()).isEqualByComparingTo("10000000");
        assertThat(summary.path("previous").path("averageOrderValue").decimalValue()).isEqualByComparingTo("5000000");
        assertThat(summary.path("current").path("ordersCount").asLong()).isGreaterThanOrEqualTo(1);
        long orders = summary.path("current").path("ordersCount").asLong();
        BigDecimal expected = BigDecimal.valueOf(orders - 2).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(2), 1, java.math.RoundingMode.HALF_UP);
        assertThat(summary.path("ordersChangePercent").decimalValue()).isEqualByComparingTo(expected);
        assertThat(summary.path("revenueChangePercent").isNumber()).isTrue();
        assertThat(summary.path("averageOrderValueChangePercent").isNumber()).isTrue();
    }

    @Test
    @DisplayName("a previous period with no orders gives a null change, not an infinite one")
    void summaryWithNothingToCompare() throws Exception {
        JsonNode summary = data(send(get(REPORTS + "/summary").param("from", "2001-02-01").param("to", "2001-02-07"),
                Role.ACCOUNTANT).andExpect(status().isOk()));

        assertThat(summary.path("current").path("ordersCount").asLong()).isZero();
        assertThat(summary.path("current").path("averageOrderValue").decimalValue()).isEqualByComparingTo("0");
        // Null fields are left out of the JSON (the API-wide inclusion rule).
        assertThat(summary.path("ordersChangePercent").isMissingNode()).isTrue();
        assertThat(summary.path("revenueChangePercent").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("a range over 366 days, an inverted one, or a malformed date is a 400")
    void rangeValidation() throws Exception {
        for (String path : List.of("/sales-daily", "/top-products", "/summary")) {
            send(get(REPORTS + path).param("from", "2025-01-01").param("to", "2026-10-24"), Role.ACCOUNTANT)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
            send(get(REPORTS + path).param("from", "2026-10-24").param("to", "2026-10-01"), Role.ACCOUNTANT)
                    .andExpect(status().isBadRequest());
        }
        send(get(REPORTS + "/sales-daily").param("from", "yesterday"), Role.ACCOUNTANT).andExpect(status().isBadRequest());
        send(post(REPORTS + "/rebuild").param("from", "2025-01-01").param("to", "2026-10-24"), Role.ACCOUNTANT)
                .andExpect(status().isBadRequest());
        // 366 days exactly is allowed.
        send(get(REPORTS + "/sales-daily").param("from", "2025-10-24").param("to", "2026-10-24"), Role.ACCOUNTANT)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("reads need reporting-reports:READ, the rebuild reporting-reports:EXPORT; anonymous callers get 401")
    void permissions() throws Exception {
        mvc.perform(get(REPORTS + "/summary")).andExpect(status().isUnauthorized());
        mvc.perform(post(REPORTS + "/rebuild")).andExpect(status().isUnauthorized());

        for (Role reader : List.of(Role.ACCOUNTANT, Role.ECOMMERCE_ADMIN, Role.SYSTEM_ADMIN, Role.WAREHOUSE_MANAGER,
                Role.ORDER_COORDINATOR)) {
            send(get(REPORTS + "/summary"), reader).andExpect(status().isOk());
            send(get(REPORTS + "/sales-daily"), reader).andExpect(status().isOk());
            send(get(REPORTS + "/top-products"), reader).andExpect(status().isOk());
            send(get(REPORTS + "/order-status-counts"), reader).andExpect(status().isOk());
        }
        for (Role other : List.of(Role.WAREHOUSE_STAFF, Role.SALES_STAFF, Role.CUSTOMER, Role.QC_STAFF,
                Role.PRODUCTION_STAFF)) {
            send(get(REPORTS + "/summary"), other).andExpect(status().isForbidden());
            send(get(REPORTS + "/sales-daily"), other).andExpect(status().isForbidden());
            send(get(REPORTS + "/top-products"), other).andExpect(status().isForbidden());
            send(get(REPORTS + "/order-status-counts"), other).andExpect(status().isForbidden());
            send(post(REPORTS + "/rebuild"), other).andExpect(status().isForbidden());
        }
        // READ is not EXPORT: the two managers read the figures but do not rebuild them.
        send(post(REPORTS + "/rebuild"), Role.WAREHOUSE_MANAGER).andExpect(status().isForbidden());
        send(post(REPORTS + "/rebuild"), Role.ORDER_COORDINATOR).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /rebuild recomputes the range and says what it did")
    void rebuildOverHttp() throws Exception {
        LocalDate today = BusinessDay.today(clock);
        settleToday(today);
        OrderSummary order = place(1, 2_500_000);
        awaitPlaced(order);
        List<String> before = snapshot(today);

        for (Role role : List.of(Role.ACCOUNTANT, Role.ECOMMERCE_ADMIN, Role.SYSTEM_ADMIN)) {
            JsonNode result = data(send(post(REPORTS + "/rebuild").param("from", today.minusDays(2).toString())
                    .param("to", today.toString()), role).andExpect(status().isOk()));
            assertThat(result.path("days").asInt()).isEqualTo(3);
            assertThat(result.path("ordersSeen").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(result.path("ordersCounted").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(result.path("from").asText()).isEqualTo(today.minusDays(2).toString());
        }
        assertThat(snapshot(today)).isEqualTo(before);
    }
}
