package com.stockflow.fulfillment;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.DataScope;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.internal.service.CycleCounts;
import com.stockflow.inventory.internal.service.StockOperations;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The count variance of a carton an order holds (WF4-X01; kltn-docs 20 §4.2 step 8, 07 §4.4 steps 9-11), through the real
 * filter chain, permission aspect, warehouse scope and database. The carton's hold is handled as a shortage: approving
 * the whole-carton {@code COUNT_VARIANCE} writes the carton off (LOST, one {@code ADJUSTMENT} ledger line), gives its hold
 * up and, after the commit, fulfillment turns the pick line that stood on it SHORT and holds the order for stock
 * ({@code INVENTORY}); the warehouse manager reallocates.
 *
 * <p>Covered: a line the picker reported short (the blind count a short pick opens), approved before and after the
 * reallocation; a line nobody reported (a released task, a manual count); a released order with no task yet; a hold
 * of an order not released (a SKU-level hold, no carton is held); and the event handled twice.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
// The same properties and mock as FulfillmentTaskContextIntegrationTest, so the application context is shared.
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class CountVarianceOfShortCartonIntegrationTest {

    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String API = "/api/v1/fulfillment";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired StockOperations operations;
    @Autowired com.stockflow.fulfillment.internal.service.PickLists pickLists;
    @Autowired CycleCounts cycleCounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;

    private ConfirmedOrders confirmedOrders;
    private Sku sofa;
    private CustomerSkuRows.Issued sofaCarton;
    private UUID coordinator;
    private UUID manager;
    private UUID picker;
    /** Counts and submits the blind count; never the approver (four eyes). */
    private UUID counter;
    /** Decides the variance. */
    private UUID approver;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        sofaCarton = CustomerSkuRows.like(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, "SOFA-3S-GREY", 2);
        sofa = new Sku(sofaCarton.sku());
        // Five cartons of two at HCM: the order takes one, a reallocation has others to take.
        CustomerSkuRows.stock(jdbc, tx, sofa.code(), 10);
        coordinator = staff("SYSTEM_ADMIN");
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        counter = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    @AfterEach
    void cleanUp() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus current = orders.findById(id).orElseThrow().status();
            if (current != OrderStatus.CANCELLED) {
                orders.cancel(id, "TEST_CLEANUP");
            }
        }));
    }

    // ------------------------------------------------------------------ fixtures

    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, warehouse);
            }
        });
        return user;
    }

    private String token(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private ResultActions send(String token, String url, String body) throws Exception {
        var request = post(url).header("Authorization", token);
        return mvc.perform(body == null ? request : request.contentType("application/json").content(body));
    }

    /** What the scenario shares: the order, its task, the line the picker reported short, and the carton's variance. */
    private record Scenario(UUID orderId, UUID task, UUID shortLine, String lpn, UUID carton, UUID hold,
                            StockAdjustmentSummary variance) { }

    /**
     * WF4-X01 steps 1-2: an order of one carton, released from HCM, its task started; the picker reports the line's
     * carton NOT_FOUND at its bin; the blind count that opens is counted (carton not seen) and submitted, asking one
     * pending COUNT_VARIANCE. Nothing is approved.
     */
    private Scenario shortPickWithPendingVariance() throws Exception {
        UUID order = confirmedOrders.release(
                confirmedOrders.confirmed(DemoData.CUSTOMER_ID, ConfirmedOrders.Line.cartons(sofaCarton, 1, 12_000_000))
                        .orderId(), HCM, coordinator).orderId();
        placed.add(order);
        String admin = token(coordinator, "SYSTEM_ADMIN");
        String pickerToken = token(picker, "WAREHOUSE_STAFF");
        UUID task = taskIdOf(send(admin, API + "/tasks",
                "{\"orderId\":\"" + order + "\",\"assignedUserId\":\"" + picker + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        send(pickerToken, API + "/tasks/" + task + "/picking-start", null).andExpect(status().isOk());

        Map<String, Object> line = jdbc.queryForMap("""
                SELECT id, lpn, location_code, reservation_id FROM fulfillment.pick_line
                 WHERE pick_id = ? AND status = 'OPEN'""", task);
        UUID lineId = (UUID) line.get("id");
        send(pickerToken, API + "/tasks/" + task + "/pick-lines/" + lineId + "/short",
                "{\"locationCode\":\"" + line.get("location_code") + "\",\"reason\":\"NOT_FOUND\"}")
                .andExpect(status().isOk());
        assertThat(lineStatus(lineId)).isEqualTo("SHORT");
        OrderSummary held = orders.findById(order).orElseThrow();
        assertThat(held.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(held.lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);

        String lpn = (String) line.get("lpn");
        UUID carton = jdbc.queryForObject("SELECT id FROM inventory.stock_package WHERE lpn = ?", UUID.class, lpn);
        Map<String, Object> countLine = jdbc.queryForMap("""
                SELECT l.id, l.cycle_count_id FROM inventory.cycle_count_line l
                  JOIN inventory.cycle_count c ON c.id = l.cycle_count_id
                 WHERE l.stock_package_id = ? AND c.trigger_type = 'SHORT_PICK'""", carton);
        UUID count = (UUID) countLine.get("cycle_count_id");
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> {
            cycleCounts.count(count, (UUID) countLine.get("id"), false, counter, false);
            cycleCounts.submit(count, counter, false);
        });
        UUID adjustment = jdbc.queryForObject("""
                SELECT id FROM inventory.stock_adjustment WHERE stock_package_id = ? AND status = 'PENDING_APPROVAL'
                   AND reason_code = 'COUNT_VARIANCE'""", UUID.class, carton);
        StockAdjustmentSummary variance = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> operations.findAdjustment(adjustment).orElseThrow());
        assertThat(variance.status()).isEqualTo(StockAdjustmentStatus.PENDING_APPROVAL);
        return new Scenario(order, task, lineId, lpn, carton, (UUID) line.get("reservation_id"), variance);
    }

    private static UUID taskIdOf(String body) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"taskId\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(body);
        assertThat(matcher.find()).as(body).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private String lineStatus(UUID line) {
        return jdbc.queryForObject("SELECT status FROM fulfillment.pick_line WHERE id = ?", String.class, line);
    }

    private Map<String, Object> cartonState(UUID carton) {
        return jdbc.queryForMap("""
                SELECT p.status, (p.closed_at IS NOT NULL) AS closed,
                       (SELECT COALESCE(SUM(s.on_hand), 0) FROM inventory.stock_item s WHERE s.stock_package_id = p.id)
                           AS on_hand,
                       (SELECT COUNT(*) FROM inventory.stock_reservation r JOIN inventory.stock_item s
                            ON s.id = r.stock_item_id WHERE s.stock_package_id = p.id AND r.status = 'HELD') AS live_holds
                  FROM inventory.stock_package p WHERE p.id = ?""", carton);
    }

    private Map<String, Object> holdState(UUID hold) {
        return jdbc.queryForMap("SELECT status, release_reason FROM inventory.stock_reservation WHERE id = ?", hold);
    }

    private List<Map<String, Object>> liveHolds(UUID order) {
        return jdbc.queryForList("""
                SELECT r.id, r.quantity, s.stock_package_id FROM inventory.stock_reservation r
                  JOIN inventory.stock_item s ON s.id = r.stock_item_id
                 WHERE r.order_id = ? AND r.status = 'HELD'""", order);
    }

    private List<UUID> holdsRecordedOnTheOrder(UUID order) {
        return jdbc.queryForList("""
                SELECT lr.reservation_id FROM ordering.order_line_reservation lr
                  JOIN ordering.order_line l ON l.id = lr.order_line_id WHERE l.order_id = ?""", UUID.class, order);
    }

    private List<Map<String, Object>> ledgerOf(UUID adjustment) {
        return jdbc.queryForList("""
                SELECT movement_type, quantity, from_stock_package_id, to_stock_package_id, from_location_code
                  FROM inventory.stock_movement WHERE reference_id = ? AND reference_type = 'STOCK_ADJUSTMENT'""",
                adjustment);
    }

    private StockAdjustmentSummary approve(Scenario s) {
        return WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> operations.approve(s.variance().adjustmentId(), approver));
    }

    private List<Map<String, Object>> reallocate(Scenario s) throws Exception {
        send(token(manager, "WAREHOUSE_MANAGER"), API + "/tasks/" + s.task() + "/pick-lines/" + s.shortLine()
                + "/reallocation", null).andExpect(status().isOk());
        return jdbc.queryForList("SELECT id, lpn, status, reservation_id FROM fulfillment.pick_line WHERE pick_id = ?"
                + " AND status <> 'CANCELLED'", s.task());
    }

    /** What must be true at the end, whichever order the two steps came in. */
    private void assertSettled(Scenario s) {
        assertThat(cartonState(s.carton())).as("the carton is written off whole and LOST")
                .containsEntry("status", "LOST").containsEntry("closed", true).containsEntry("on_hand", 0L)
                .containsEntry("live_holds", 0L);
        assertThat(ledgerOf(s.variance().adjustmentId())).as("one ADJUSTMENT line, leaving the carton").singleElement()
                .satisfies(row -> {
                    assertThat(row.get("movement_type")).isEqualTo("ADJUSTMENT");
                    assertThat(row.get("quantity")).isEqualTo(2);
                    assertThat(row.get("from_stock_package_id")).isEqualTo(s.carton());
                    assertThat(row.get("to_stock_package_id")).isNull();
                });
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_adjustment WHERE id = ?", String.class,
                s.variance().adjustmentId())).isEqualTo("POSTED");
        assertThat(holdState(s.hold())).as("the short line's hold was given up as a short pick")
                .containsEntry("status", "RELEASED").containsEntry("release_reason", "SHORT_PICKED");
        assertThat(lineStatus(s.shortLine())).as("the superseded line").isEqualTo("CANCELLED");
        List<Map<String, Object>> live = liveHolds(s.orderId());
        assertThat(live).as("the order holds one carton, another one").singleElement().satisfies(hold -> {
            assertThat(hold.get("quantity")).isEqualTo(2);
            assertThat(hold.get("stock_package_id")).isNotEqualTo(s.carton());
        });
        assertThat(holdsRecordedOnTheOrder(s.orderId())).as("the order line records exactly its live hold")
                .containsExactly((UUID) live.getFirst().get("id"));
        assertThat(orders.findById(s.orderId()).orElseThrow().status()).as("the hold INVENTORY is resolved")
                .isNotEqualTo(OrderStatus.ON_HOLD);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM fulfillment.pick_line WHERE pick_id = ? AND status = 'OPEN'""", Integer.class,
                s.task())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("WF4-X01: approve the variance first - the short line's hold goes with the carton, the line stays SHORT "
            + "and the order held, then the reallocation takes another carton and resumes the order")
    void approveThenReallocate() throws Exception {
        Scenario s = shortPickWithPendingVariance();
        assertThat(holdState(s.hold())).containsEntry("status", "HELD");

        StockAdjustmentSummary posted = approve(s);

        assertThat(posted.status()).isEqualTo(StockAdjustmentStatus.POSTED);
        assertThat(cartonState(s.carton())).containsEntry("status", "LOST").containsEntry("on_hand", 0L)
                .containsEntry("live_holds", 0L);
        assertThat(holdState(s.hold())).containsEntry("status", "RELEASED").containsEntry("release_reason",
                "SHORT_PICKED");
        assertThat(ledgerOf(s.variance().adjustmentId())).hasSize(1);
        // The shortage path: the line stays SHORT, the order stays held for stock, no hold is left on the carton.
        assertThat(lineStatus(s.shortLine())).isEqualTo("SHORT");
        OrderSummary stillHeld = orders.findById(s.orderId()).orElseThrow();
        assertThat(stillHeld.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(stillHeld.lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        assertThat(liveHolds(s.orderId())).isEmpty();

        List<Map<String, Object>> lines = reallocate(s);

        assertThat(lines).singleElement().satisfies(line -> assertThat(line.get("lpn")).isNotEqualTo(s.lpn()));
        assertSettled(s);
    }

    @Test
    @DisplayName("WF4-X01 the other way: reallocate first, then approve the variance - it posts on a free carton")
    void reallocateThenApprove() throws Exception {
        Scenario s = shortPickWithPendingVariance();

        List<Map<String, Object>> lines = reallocate(s);

        assertThat(lines).singleElement().satisfies(line -> assertThat(line.get("lpn")).isNotEqualTo(s.lpn()));
        assertThat(cartonState(s.carton())).as("not written off yet").containsEntry("status", "IN_STOCK")
                .containsEntry("live_holds", 0L);
        assertThat(orders.findById(s.orderId()).orElseThrow().status()).isNotEqualTo(OrderStatus.ON_HOLD);

        assertThat(approve(s).status()).isEqualTo(StockAdjustmentStatus.POSTED);

        assertSettled(s);
    }

    // ------------------------------------------------------------------ a shortage nobody reported at a bin

    /** A confirmed order of one carton, released from HCM. */
    private UUID releasedOrder() {
        UUID order = confirmedOrders.release(
                confirmedOrders.confirmed(DemoData.CUSTOMER_ID, ConfirmedOrders.Line.cartons(sofaCarton, 1, 12_000_000))
                        .orderId(), HCM, coordinator).orderId();
        placed.add(order);
        return order;
    }

    private UUID openTask(UUID order) throws Exception {
        return taskIdOf(send(token(coordinator, "SYSTEM_ADMIN"), API + "/tasks",
                "{\"orderId\":\"" + order + "\",\"assignedUserId\":\"" + picker + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /**
     * A manual count of the bin that does not find {@code carton} (the other cartons are seen): one pending
     * COUNT_VARIANCE of it, asked as a submitted count asks it.
     */
    private StockAdjustmentSummary manualCountMisses(UUID carton) {
        UUID count = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> cycleCounts.plan(
                new CycleCounts.PlanCycleCountCommand(HCM, null, null, true, "Manual count", List.of(), List.of(),
                        List.of("HCM-A01-1-A"), List.of(sofa.code())), false).count().id());
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> {
            for (Map<String, Object> line : jdbc.queryForList(
                    "SELECT id, stock_package_id FROM inventory.cycle_count_line WHERE cycle_count_id = ?", count)) {
                cycleCounts.count(count, (UUID) line.get("id"), !carton.equals(line.get("stock_package_id")), counter,
                        false);
            }
            cycleCounts.submit(count, counter, false);
        });
        UUID adjustment = jdbc.queryForObject("SELECT id FROM inventory.stock_adjustment WHERE stock_package_id = ? "
                + "AND status = 'PENDING_APPROVAL'", UUID.class, carton);
        return WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> operations.findAdjustment(adjustment).orElseThrow());
    }

    private void awaitLine(UUID line, String wanted) {
        com.stockflow.support.Await.until("line " + line + " is " + wanted, java.time.Duration.ofSeconds(20),
                () -> wanted.equals(lineStatus(line)));
    }

    private void awaitHold(UUID order) {
        com.stockflow.support.Await.until("order " + order + " is held for stock", java.time.Duration.ofSeconds(20),
                () -> orders.findById(order).orElseThrow().status() == OrderStatus.ON_HOLD);
    }

    @Test
    @DisplayName("a carton on a released task line that was never reported short, found missing by a count: approved, the "
            + "line turns SHORT and the order is held INVENTORY, then the manager reallocates")
    void aLineNobodyReportedShortBecomesTheShortage() throws Exception {
        UUID order = releasedOrder();
        UUID task = openTask(order);
        Map<String, Object> line = jdbc.queryForMap("SELECT id, lpn, reservation_id FROM fulfillment.pick_line "
                + "WHERE pick_id = ? AND status = 'OPEN'", task);
        UUID lineId = (UUID) line.get("id");
        UUID carton = jdbc.queryForObject("SELECT id FROM inventory.stock_package WHERE lpn = ?", UUID.class,
                line.get("lpn"));
        Scenario s = new Scenario(order, task, lineId, (String) line.get("lpn"), carton,
                (UUID) line.get("reservation_id"), manualCountMisses(carton));
        assertThat(orders.findById(order).orElseThrow().status()).isNotEqualTo(OrderStatus.ON_HOLD);

        assertThat(approve(s).status()).isEqualTo(StockAdjustmentStatus.POSTED);

        // After the commit: the line is the shortage a picker's report would have made, the order held for stock.
        awaitLine(lineId, "SHORT");
        awaitHold(order);
        OrderSummary held = orders.findById(order).orElseThrow();
        assertThat(held.lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        assertThat(cartonState(carton)).containsEntry("status", "LOST").containsEntry("on_hand", 0L)
                .containsEntry("live_holds", 0L);
        assertThat(holdState(s.hold())).containsEntry("status", "RELEASED").containsEntry("release_reason",
                "SHORT_PICKED");
        assertThat(liveHolds(order)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT short_reason FROM fulfillment.pick_line WHERE id = ?", String.class,
                lineId)).isEqualTo("NOT_FOUND");

        assertThat(reallocate(s)).singleElement().satisfies(l -> assertThat(l.get("lpn")).isNotEqualTo(s.lpn()));
        assertSettled(s);
    }

    @Test
    @DisplayName("the event handled twice changes nothing the second time: one hold on the order, the line SHORT once")
    void theEventIsIdempotent() throws Exception {
        UUID order = releasedOrder();
        UUID task = openTask(order);
        Map<String, Object> line = jdbc.queryForMap("SELECT id, lpn, reservation_id FROM fulfillment.pick_line "
                + "WHERE pick_id = ? AND status = 'OPEN'", task);
        UUID carton = jdbc.queryForObject("SELECT id FROM inventory.stock_package WHERE lpn = ?", UUID.class,
                line.get("lpn"));
        StockAdjustmentSummary variance = manualCountMisses(carton);
        WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> operations.approve(variance.adjustmentId(),
                approver));
        awaitLine((UUID) line.get("id"), "SHORT");
        awaitHold(order);
        int transitions = jdbc.queryForObject("SELECT count(*) FROM ordering.order_status_history WHERE order_id = ?",
                Integer.class, order);

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL,
                () -> pickLists.shortenForWrittenOffCarton(order, (UUID) line.get("reservation_id"), approver));

        assertThat(lineStatus((UUID) line.get("id"))).isEqualTo("SHORT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_status_history WHERE order_id = ?",
                Integer.class, order)).isEqualTo(transitions);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_hold WHERE order_id = ?", Integer.class,
                order)).isEqualTo(1);
    }

    @Test
    @DisplayName("a released order with no task yet: the carton's allocation is written off and the order is held "
            + "INVENTORY, so it does not go to the floor short of a carton")
    void aReleasedOrderWithoutATaskIsHeld() {
        UUID order = releasedOrder();
        UUID hold = (UUID) liveHolds(order).getFirst().get("id");
        UUID carton = (UUID) liveHolds(order).getFirst().get("stock_package_id");
        StockAdjustmentSummary variance = manualCountMisses(carton);

        WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> operations.approve(variance.adjustmentId(),
                approver));

        awaitHold(order);
        assertThat(orders.findById(order).orElseThrow().lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        assertThat(cartonState(carton)).containsEntry("status", "LOST").containsEntry("live_holds", 0L);
        assertThat(holdState(hold)).containsEntry("status", "RELEASED").containsEntry("release_reason", "SHORT_PICKED");
        assertThat(liveHolds(order)).isEmpty();
    }

    @Test
    @DisplayName("a Confirmed order not released yet holds by SKU, no carton: a carton counted missing is written off, "
            + "nothing of the order's is touched")
    void aSkuLevelHoldDoesNotBlockTheWriteOff() {
        UUID order = confirmedOrders.confirmed(DemoData.CUSTOMER_ID,
                ConfirmedOrders.Line.cartons(sofaCarton, 1, 12_000_000)).orderId();
        placed.add(order);
        confirmedOrders.ship(order, HCM);
        assertThat(liveHolds(order)).as("no allocation on a carton before the release").isEmpty();
        UUID carton = jdbc.queryForObject("""
                SELECT p.id FROM inventory.stock_package p JOIN inventory.stock_item s ON s.stock_package_id = p.id
                 WHERE s.sku = ? AND p.status = 'IN_STOCK' ORDER BY p.lpn LIMIT 1""", UUID.class, sofa.code());
        StockAdjustmentSummary variance = manualCountMisses(carton);

        assertThat(WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, () -> operations.approve(
                variance.adjustmentId(), approver)).status()).isEqualTo(StockAdjustmentStatus.POSTED);

        assertThat(cartonState(carton)).containsEntry("status", "LOST").containsEntry("on_hand", 0L);
        assertThat(ledgerOf(variance.adjustmentId())).hasSize(1);
        assertThat(orders.findById(order).orElseThrow().status()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
