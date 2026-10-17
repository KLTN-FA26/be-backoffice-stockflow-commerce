package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.contracts.StockDeducted;
import com.stockflow.fulfillment.internal.service.PickLists;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.Await;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-267/274/275 through the real filter chain, permission aspect, warehouse scope and database: a pick
 * line confirmed by scan deducts its hold in the same transaction (stock row, hold, ledger, event), every
 * wrong scan is refused with its own code and writes nothing, two simultaneous confirmations deduct once,
 * and the goods of an order cancelled after picking go back onto a bin exactly once.
 *
 * <p>The demo SOFA stock is shared with the rest of the suite, so every unit a test takes off the books
 * is put back in {@link #cleanUp} (which also exercises the put-back as the system).</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class, PickConfirmationIntegrationTest.DeductionListener.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class PickConfirmationIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Sku TABLE = new Sku("TABLE-OAK-160");
    private static final String SOFA_BARCODE = "8930000000011";
    private static final Duration ASYNC = Duration.ofSeconds(10);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;

    /**
     * Something in the application listening to {@code StockDeducted} the way a module would: with a
     * listener present, Spring Modulith keeps the publication in {@code event_publication}, which is what
     * proves the deduction's event is durable (it commits with the deduction). Nothing in main listens yet.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class DeductionListener {
        @Component
        static class Received {
            private final List<StockDeducted> events = new CopyOnWriteArrayList<>();

            @ApplicationModuleListener
            public void on(StockDeducted event) {
                events.add(event);
            }

            /** Read through a method: the bean is proxied, and a proxy's own fields are empty. */
            public List<StockDeducted> events() {
                return events;
            }
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired InventoryService inventory;
    @Autowired PickLists pickLists;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ObjectMapper json;
    @Autowired DeductionListener.Received deductions;

    private UUID hn;
    private String hnBin;
    private UUID coordinator;
    private UUID managerHcm;
    private UUID managerHn;
    private UUID pickerHcm;
    private UUID otherPickerHcm;
    private UUID pickerHn;
    private UUID qcHcm;
    private UUID customerUser;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hn = Identifiers.newId();
        String prefix = "F" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        hnBin = prefix + "-A01-1-A";
        // A second warehouse with one active bin, so a put-back across warehouses has somewhere real to aim.
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                     version, created_at)
                    VALUES (?, 'Second warehouse', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", hn, prefix);
            UUID zone = Identifiers.newId();
            UUID shelf = Identifiers.newId();
            UUID level = Identifiers.newId();
            UUID location = Identifiers.newId();
            jdbc.update("INSERT INTO warehouse.zone (id, warehouse_id, name, color) VALUES (?, ?, 'Zone', '#4F81BD')",
                    zone, hn);
            jdbc.update("""
                    INSERT INTO warehouse.shelf (id, warehouse_id, zone_id, code, name, x, y, width, length, rotation,
                                                 is_obstacle, pick_north, pick_east, pick_south, pick_west,
                                                 default_storage_class, status)
                    VALUES (?, ?, ?, 'A01', 'Shelf', 5, 5, 10, 1.2, 0, TRUE, FALSE, FALSE, TRUE, FALSE, 'OVERSIZE',
                            'ACTIVE')""", shelf, hn, zone);
            jdbc.update("""
                    INSERT INTO warehouse.shelf_level (id, shelf_id, level_index, elevation, usable_height, max_weight)
                    VALUES (?, ?, 1, 0, 1.4, 500)""", level, shelf);
            jdbc.update("""
                    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                                                            capacity_units, max_weight, is_pickable,
                                                            is_putaway_target, status)
                    VALUES (?, ?, 'BIN', ?, 'OVERSIZE', 40, 250, TRUE, TRUE, 'ACTIVE')""", location, hn, hnBin);
            jdbc.update("""
                    INSERT INTO warehouse.bin (id, level_id, location_id, code, x, y, width, length, rotation, type)
                    VALUES (?, ?, ?, 'A', 0, 0, 5, 1.2, 0, 'PALLET')""", Identifiers.newId(), level, location);
        });
        coordinator = staff("ORDER_COORDINATOR");
        managerHcm = staff("WAREHOUSE_MANAGER", HCM);
        managerHn = staff("WAREHOUSE_MANAGER", hn);
        pickerHcm = staff("WAREHOUSE_STAFF", HCM);
        otherPickerHcm = staff("WAREHOUSE_STAFF", HCM);
        pickerHn = staff("WAREHOUSE_STAFF", hn);
        qcHcm = staff("QC_STAFF", HCM);
        customerUser = staff("CUSTOMER");
        deductions.events().clear();
    }

    /**
     * Cancels what the test placed and puts every picked unit back where it came from, so the shared demo
     * stock ends where it started.
     */
    @AfterEach
    void cleanUp() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus current = orders.findById(id).orElseThrow().status();
            if (current != OrderStatus.CANCELLED) {
                orders.cancel(id, "TEST_CLEANUP");
            }
        }));
        CurrentUser system = new CurrentUser(managerHcm, "cleanup", Set.of(Role.WAREHOUSE_MANAGER), Set.of(),
                DataScope.ALL, Set.of());
        for (UUID order : placed) {
            List<Map<String, Object>> picked = jdbc.queryForList("""
                    SELECT l.id, l.pick_id, l.location_code FROM fulfillment.pick_line l
                      JOIN fulfillment.pick p ON p.id = l.pick_id
                     WHERE p.order_id = ? AND l.status = 'PICKED'""", order);
            if (picked.isEmpty()) {
                continue;
            }
            Await.until("task of the cancelled order cancelled", ASYNC, () -> "CANCELLED".equals(jdbc.queryForObject(
                    "SELECT status FROM fulfillment.pick WHERE order_id = ?", String.class, order)));
            for (Map<String, Object> line : picked) {
                pickLists.putBack((UUID) line.get("pick_id"), (UUID) line.get("id"),
                        (String) line.get("location_code"), system);
            }
        }
    }

    // ---- fixtures ----------------------------------------------------------------------------

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

    /** A paid stock-only order of the demo customer, CONFIRMED, its stock held at HCM. */
    private OrderSummary confirmedOrder(PlaceOrderCommand.Line... lines) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID,
                List.of(lines)));
        placed.add(order.orderId());
        BigDecimal total = order.total().amount();
        UUID payment = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, ?, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", payment, order.orderId(), total));
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(order.orderId(), payment, total, "VND",
                PaymentMethod.BANK_TRANSFER)));
        Await.until("order confirmed", ASYNC,
                () -> orders.findById(order.orderId()).orElseThrow().status() == OrderStatus.CONFIRMED);
        return orders.findById(order.orderId()).orElseThrow();
    }

    private static PlaceOrderCommand.Line sofa(int quantity) {
        return new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(12_000_000), null);
    }

    private ResultActions call(RequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** OC opens the task for {@link #pickerHcm}; the picker starts it. Returns the task id. */
    private UUID startedTask(OrderSummary order) throws Exception {
        String body = "{\"orderId\":\"" + order.orderId() + "\",\"assignedUserId\":\"" + pickerHcm + "\"}";
        UUID task = UUID.fromString(body(call(post("/api/v1/fulfillment/tasks")
                .header("Authorization", token(coordinator, "ORDER_COORDINATOR"))
                .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn()).path("taskId").asText());
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-start")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("PICKING"));
        return task;
    }

    private JsonNode lines(UUID task) throws Exception {
        return body(call(get("/api/v1/fulfillment/tasks/" + task + "/pick-lines")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk()).andReturn()).path("items");
    }

    private static String scan(String location, String code, int quantity) {
        return "{\"locationCode\":\"" + location + "\",\"scannedCode\":\"" + code + "\",\"quantity\":" + quantity + "}";
    }

    private ResultActions confirm(UUID task, String line, String user, String role, String scan) throws Exception {
        return call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + line + "/confirmation")
                .header("Authorization", token(UUID.fromString(user), role))
                .contentType("application/json").content(scan));
    }

    private int onHand(String location, Sku sku) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item WHERE sku = ? AND location_code = ?",
                Integer.class, sku.code(), location);
    }

    private int ledgerRows(String type, String line) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement
                 WHERE movement_type = ? AND reference_type = 'PICK_LINE' AND reference_id = ?::uuid""",
                Integer.class, type, line);
    }

    private String holdStatus(String line) {
        return jdbc.queryForObject("""
                SELECT r.status FROM inventory.stock_reservation r JOIN fulfillment.pick_line l ON l.reservation_id = r.id
                 WHERE l.id = ?::uuid""", String.class, line);
    }

    private String lineStatus(String line) {
        return jdbc.queryForObject("SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid", String.class, line);
    }

    // ---- confirming ----------------------------------------------------------------------------

    @Test
    @DisplayName("X-SHORT: a short line stops the order and deducts nothing; reallocated elsewhere, it is picked there")
    void shortPickIsReallocatedAndPicked() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        int before = onHand(location, SOFA);

        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/short")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))
                .contentType("application/json").content("{\"locationCode\":\"" + location + "\",\"foundQuantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHORT"))
                .andExpect(jsonPath("$.data.shortQty").value(1));
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(onHand(location, SOFA)).as("nothing deducted").isEqualTo(before);
        assertThat(ledgerRows("PICK", lineId)).isZero();

        JsonNode added = body(call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/reallocation")
                .header("Authorization", token(coordinator, "ORDER_COORDINATOR")))
                .andExpect(status().isOk()).andReturn());
        assertThat(added).hasSize(1);
        String newLine = added.get(0).path("lineId").asText();
        String newLocation = added.get(0).path("locationCode").asText();
        assertThat(newLocation).as("never the short bin").isNotEqualTo(location);
        assertThat(lineStatus(lineId)).isEqualTo("CANCELLED");
        assertThat(holdStatus(lineId)).isEqualTo("RELEASED");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.IN_FULFILMENT);
        assertThat(orders.findById(order.orderId()).orElseThrow().lines().get(0).reservationIds())
                .containsExactly(UUID.fromString(jdbc.queryForObject(
                        "SELECT reservation_id::text FROM fulfillment.pick_line WHERE id = ?::uuid", String.class, newLine)));

        confirm(task, newLine, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(newLocation, "SOFA-3S-GREY", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKED"));
        assertThat(ledgerRows("PICK", newLine)).isEqualTo(1);
        assertThat(holdStatus(newLine)).isEqualTo("CONSUMED");
    }


    @Test
    @DisplayName("a confirmed line deducts its hold: on_hand, hold CONSUMED, one PICK ledger row, StockDeducted in the registry")
    void confirmationDeductsWithLedgerAndEvent() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode lines = lines(task);
        assertThat(lines).hasSize(1);
        JsonNode line = lines.get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        assertThat(line.path("sku").asText()).isEqualTo("SOFA-3S-GREY");
        assertThat(line.path("barcode").asText()).isEqualTo(SOFA_BARCODE);
        assertThat(line.path("allocatedQty").asInt()).isEqualTo(1);
        assertThat(line.path("status").asText()).isEqualTo("OPEN");
        assertThat(line.path("sequenceNo").asInt()).isEqualTo(1);
        assertThat(line.path("orderLineId").asText()).isEqualTo(order.lines().getFirst().lineId().toString());
        int before = onHand(location, SOFA);

        // Scanned by barcode, the location in lower case: both accepted.
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF",
                scan(location.toLowerCase(Locale.ROOT), SOFA_BARCODE, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKED"))
                .andExpect(jsonPath("$.data.pickedQty").value(1))
                .andExpect(jsonPath("$.data.pickedAt").exists());

        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);
        assertThat(holdStatus(lineId)).isEqualTo("CONSUMED");
        assertThat(ledgerRows("PICK", lineId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT from_location_code, to_location_code, quantity, actor_id, from_status FROM inventory.stock_movement
                 WHERE reference_type = 'PICK_LINE' AND reference_id = ?::uuid""", lineId))
                .containsEntry("from_location_code", location).containsEntry("to_location_code", null)
                .containsEntry("quantity", 1).containsEntry("actor_id", pickerHcm)
                .containsEntry("from_status", "AVAILABLE");
        assertThat(jdbc.queryForMap("""
                SELECT consumed_at IS NOT NULL AS consumed, scanned_location_code, scanned_code, picked_by
                  FROM fulfillment.pick_line WHERE id = ?::uuid""", lineId))
                .containsEntry("consumed", true).containsEntry("scanned_code", SOFA_BARCODE)
                .containsEntry("picked_by", pickerHcm);
        // The event committed with the deduction, in the publication registry, and reached a listener.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                 WHERE event_type LIKE '%StockDeducted' AND serialized_event LIKE ?""", Integer.class,
                "%" + order.orderId() + "%")).isEqualTo(1);
        Await.until("StockDeducted delivered", ASYNC, () -> deductions.events().stream()
                .anyMatch(event -> event.orderId().equals(order.orderId()) && event.quantity() == 1));

        // The same scan again: 200, nothing more deducted.
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKED"));
        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);
        assertThat(ledgerRows("PICK", lineId)).isEqualTo(1);

        // Every line picked: the task completes.
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
        // The audit trail has the confirmation.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'fulfillment-task' AND resource_id = ?
                   AND action = 'TRANSITION' AND outcome = 'SUCCESS'""", Integer.class, task.toString()))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("an order of two SKUs gets one line per hold in walking order, with the lot of each")
    void linesFollowTheHolds() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1), new PlaceOrderCommand.Line(TABLE, 1, Money.vnd(3_000_000), null));
        UUID task = startedTask(order);

        JsonNode lines = lines(task);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).path("locationCode").asText())
                .isLessThan(lines.get(1).path("locationCode").asText());
        JsonNode table = lines.get(0).path("sku").asText().equals("TABLE-OAK-160") ? lines.get(0) : lines.get(1);
        assertThat(table.path("lotNumber").asText()).isEqualTo("LOT-2026-03");
        assertThat(table.path("locationCode").asText()).isEqualTo("HCM-B01-1-A");
        // Completion waits for both.
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_LINES_INCOMPLETE"));
    }

    @Test
    @DisplayName("every wrong scan is refused with its own code and nothing is written")
    void refusals() throws Exception {
        OrderSummary order = confirmedOrder(sofa(2));
        String body = "{\"orderId\":\"" + order.orderId() + "\",\"assignedUserId\":\"" + pickerHcm + "\"}";
        UUID task = UUID.fromString(body(call(post("/api/v1/fulfillment/tasks")
                .header("Authorization", token(coordinator, "ORDER_COORDINATOR"))
                .contentType("application/json").content(body)).andReturn()).path("taskId").asText());
        // Lines exist from creation; confirming before picking started is a state conflict.
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        String picker = pickerHcm.toString();
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY", 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_TASK_STATE_CONFLICT"));
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-start")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))).andExpect(status().isOk());
        int before = onHand(location, SOFA);
        String otherBin = location.equals("HCM-A01-2-B") ? "HCM-A02-1-A" : "HCM-A01-2-B";

        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(otherBin, "SOFA-3S-GREY", 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_LOCATION_MISMATCH"));
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, "TABLE-OAK-160", 2))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_ITEM_MISMATCH"));
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY", 3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_QUANTITY_EXCEEDS_ALLOCATION"));
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY", 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_QUANTITY_SHORT"));
        confirm(task, UUID.randomUUID().toString(), picker, "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY", 2))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", "{\"locationCode\":\"" + location
                + "\",\"scannedCode\":\"SOFA-3S-GREY\",\"quantity\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", "{\"scannedCode\":\"SOFA-3S-GREY\",\"quantity\":2}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("locationCode"));
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_LINES_INCOMPLETE"));

        assertThat(onHand(location, SOFA)).isEqualTo(before);
        assertThat(lineStatus(lineId)).isEqualTo("OPEN");
        assertThat(holdStatus(lineId)).isEqualTo("HELD");
        assertThat(ledgerRows("PICK", lineId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task))
                .isEqualTo("PICKING");
    }

    @Test
    @DisplayName("a hold released under the line: 409 RESERVATION_NOT_ACTIVE and nothing written")
    void holdNoLongerActive() throws Exception {
        // An allocation never expires (X-RESV, ck_stock_reservation_allocation): only a release can close it.
        for (String how : List.of("RELEASED")) {
            OrderSummary order = confirmedOrder(sofa(1));
            UUID task = startedTask(order);
            JsonNode line = lines(task).get(0);
            String lineId = line.path("lineId").asText();
            String location = line.path("locationCode").asText();
            UUID hold = jdbc.queryForObject("SELECT reservation_id FROM fulfillment.pick_line WHERE id = ?::uuid",
                    UUID.class, lineId);
            if (how.equals("RELEASED")) {
                WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> inventory.release(hold, "MANUAL_OVERRIDE"));
            } else {
                // What the sweeper leaves behind, written directly so no expiry event cancels the order meanwhile.
                tx.executeWithoutResult(s -> {
                    jdbc.update("""
                            UPDATE inventory.stock_reservation SET status = 'EXPIRED', release_reason = 'RESERVATION_EXPIRED',
                                   expires_at = NOW() - INTERVAL '1 minute', closed_at = NOW() WHERE id = ?""", hold);
                    jdbc.update("""
                            UPDATE inventory.stock_item SET reserved = reserved - 1
                             WHERE id = (SELECT stock_item_id FROM inventory.stock_reservation WHERE id = ?)""", hold);
                });
            }
            int before = onHand(location, SOFA);

            confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY", 1))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("RESERVATION_NOT_ACTIVE"));

            assertThat(onHand(location, SOFA)).as(how).isEqualTo(before);
            assertThat(lineStatus(lineId)).as(how).isEqualTo("OPEN");
            assertThat(holdStatus(lineId)).as(how).isEqualTo(how);
            assertThat(ledgerRows("PICK", lineId)).as(how).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM event_publication WHERE event_type LIKE '%StockDeducted' AND serialized_event LIKE ?""",
                    Integer.class, "%" + order.orderId() + "%")).as(how).isZero();
        }
    }

    @Test
    @DisplayName("two confirmations of one line at the same moment deduct it once")
    void concurrentConfirmationsDeductOnce() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        int before = onHand(location, SOFA);
        String scan = scan(location, "SOFA-3S-GREY", 1);
        String header = token(pickerHcm, "WAREHOUSE_STAFF");
        String url = "/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/confirmation";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(post(url).header("Authorization", header).contentType("application/json")
                            .content(scan)).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);
        assertThat(ledgerRows("PICK", lineId)).isEqualTo(1);
        assertThat(holdStatus(lineId)).isEqualTo("CONSUMED");
        assertThat(lineStatus(lineId)).isEqualTo("PICKED");
    }

    // ---- who may do it -----------------------------------------------------------------------------

    @Test
    @DisplayName("scope: another warehouse 403, a picker not assigned 404, QC / OC / customer 403; readers see the lines")
    void scope() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String scan = scan(line.path("locationCode").asText(), "SOFA-3S-GREY", 1);

        confirm(task, lineId, pickerHn.toString(), "WAREHOUSE_STAFF", scan)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        confirm(task, lineId, managerHn.toString(), "WAREHOUSE_MANAGER", scan)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        confirm(task, lineId, otherPickerHcm.toString(), "WAREHOUSE_STAFF", scan)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        // The manager of the warehouse is not the assignee either: only the assignee confirms.
        confirm(task, lineId, managerHcm.toString(), "WAREHOUSE_MANAGER", scan)
                .andExpect(status().isNotFound());
        confirm(task, lineId, qcHcm.toString(), "QC_STAFF", scan)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        confirm(task, lineId, coordinator.toString(), "ORDER_COORDINATOR", scan)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        confirm(task, lineId, customerUser.toString(), "CUSTOMER", scan)
                .andExpect(status().isForbidden());
        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/confirmation")
                .contentType("application/json").content(scan)).andExpect(status().isUnauthorized());
        assertThat(lineStatus(lineId)).isEqualTo("OPEN");
        assertThat(ledgerRows("PICK", lineId)).isZero();

        String url = "/api/v1/fulfillment/tasks/" + task + "/pick-lines";
        call(get(url).header("Authorization", token(coordinator, "ORDER_COORDINATOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        call(get(url).header("Authorization", token(qcHcm, "QC_STAFF"))).andExpect(status().isOk());
        call(get(url).header("Authorization", token(managerHcm, "WAREHOUSE_MANAGER"))).andExpect(status().isOk());
        call(get(url).header("Authorization", token(managerHn, "WAREHOUSE_MANAGER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        call(get(url).header("Authorization", token(otherPickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isNotFound());
        call(get(url).header("Authorization", token(customerUser, "CUSTOMER"))).andExpect(status().isForbidden());
        call(get("/api/v1/fulfillment/tasks/" + UUID.randomUUID() + "/pick-lines")
                .header("Authorization", token(coordinator, "ORDER_COORDINATOR"))).andExpect(status().isNotFound());
    }

    // ---- tasks opened before pick lines existed --------------------------------------------------

    @Test
    @DisplayName("a task with no lines gets them at picking-start; a PICKING one cannot complete until they are picked")
    void linesArePlannedLazilyForOlderTasks() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        String body = "{\"orderId\":\"" + order.orderId() + "\",\"assignedUserId\":\"" + pickerHcm + "\"}";
        UUID task = UUID.fromString(body(call(post("/api/v1/fulfillment/tasks")
                .header("Authorization", token(coordinator, "ORDER_COORDINATOR"))
                .contentType("application/json").content(body)).andReturn()).path("taskId").asText());
        // As it was before V20261014001000: a task, already picking, without a single line.
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM fulfillment.pick_line WHERE pick_id = ?", task);
            jdbc.update("UPDATE fulfillment.pick SET status = 'PICKING', started_at = NOW() WHERE id = ?", task);
        });
        String picker = token(pickerHcm, "WAREHOUSE_STAFF");

        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion").header("Authorization", picker))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_LINES_INCOMPLETE"));
        assertThat(lines(task)).isEmpty();

        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-start").header("Authorization", picker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("PICKING"));
        JsonNode lines = lines(task);
        assertThat(lines).hasSize(1);
        // Starting again plans nothing more.
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-start").header("Authorization", picker))
                .andExpect(status().isOk());
        assertThat(lines(task)).hasSize(1);

        confirm(task, lines.get(0).path("lineId").asText(), pickerHcm.toString(), "WAREHOUSE_STAFF",
                scan(lines.get(0).path("locationCode").asText(), "SOFA-3S-GREY", 1)).andExpect(status().isOk());
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion").header("Authorization", picker))
                .andExpect(status().isOk());
    }

    // ---- put-back -------------------------------------------------------------------------------

    @Test
    @DisplayName("cancelled after picking: the line waits in /put-backs; put back restores on_hand once, with a ledger row")
    void putBackAfterCancellation() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        int before = onHand(location, SOFA);
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY", 1))
                .andExpect(status().isOk());
        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);
        String putBackUrl = "/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/put-back";
        String picker = token(otherPickerHcm, "WAREHOUSE_STAFF");   // anyone of the warehouse, not only the picker

        // Not cancelled yet: nothing to put back.
        call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                .content("{\"locationCode\":\"" + location + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PUT_BACK_NOT_REQUIRED"));

        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(), "CUSTOMER_REQUEST"));
        Await.until("task cancelled", ASYNC, () -> "CANCELLED".equals(
                jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task)));
        assertThat(jdbc.queryForObject("SELECT needs_put_back FROM fulfillment.pick WHERE id = ?", Boolean.class, task))
                .isTrue();
        // The hold was consumed, so cancelling did not give the unit back: it is still off the books.
        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);

        call(get("/api/v1/fulfillment/put-backs").param("warehouseId", HCM.toString()).param("size", "200")
                .header("Authorization", picker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].lineId", hasItem(lineId)))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].orderNumber")
                        .value(org.hamcrest.Matchers.contains(order.orderNumber())))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].fromLocationCode")
                        .value(org.hamcrest.Matchers.contains(location)))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].pickedQty")
                        .value(org.hamcrest.Matchers.contains(1)));
        // Picking a cancelled task's line again is refused.
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_TASK_STATE_CONFLICT"));

        // Wrong targets: a bin of another warehouse, an area, an unknown code.
        for (String target : List.of(hnBin, "HCM-PACK01", "HCM-ZZ9-9-Z")) {
            call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                    .content("{\"locationCode\":\"" + target + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("PUT_BACK_LOCATION_INVALID"));
        }
        // Another warehouse's staff cannot reach the task at all.
        call(post(putBackUrl).header("Authorization", token(pickerHn, "WAREHOUSE_STAFF"))
                .contentType("application/json").content("{\"locationCode\":\"" + location + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        call(post(putBackUrl).header("Authorization", token(qcHcm, "QC_STAFF"))
                .contentType("application/json").content("{\"locationCode\":\"" + location + "\"}"))
                .andExpect(status().isForbidden());
        call(post(putBackUrl).header("Authorization", picker).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("locationCode"));
        assertThat(ledgerRows("PUT_BACK", lineId)).isZero();
        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);

        call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                .content("{\"locationCode\":\"" + location + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUT_BACK"))
                .andExpect(jsonPath("$.data.putBackQty").value(1))
                .andExpect(jsonPath("$.data.putBackLocationCode").value(location));
        assertThat(onHand(location, SOFA)).isEqualTo(before);
        assertThat(ledgerRows("PUT_BACK", lineId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT from_location_code, to_location_code, to_status, actor_id FROM inventory.stock_movement
                 WHERE movement_type = 'PUT_BACK' AND reference_type = 'PICK_LINE' AND reference_id = ?::uuid""", lineId))
                .containsEntry("from_location_code", null).containsEntry("to_location_code", location)
                .containsEntry("to_status", "AVAILABLE").containsEntry("actor_id", otherPickerHcm);

        // Again: 200, nothing more.
        call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                .content("{\"locationCode\":\"" + location + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUT_BACK"));
        assertThat(onHand(location, SOFA)).isEqualTo(before);
        assertThat(ledgerRows("PUT_BACK", lineId)).isEqualTo(1);
        call(get("/api/v1/fulfillment/put-backs").param("warehouseId", HCM.toString()).param("size", "200")
                .header("Authorization", picker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].lineId", not(hasItem(lineId))));
    }

    @Test
    @DisplayName("put-backs list: limited to the caller's warehouses; QC and coordinators have no put-back screen")
    void putBacksListScope() throws Exception {
        call(get("/api/v1/fulfillment/put-backs").param("warehouseId", HCM.toString())
                .header("Authorization", token(pickerHn, "WAREHOUSE_STAFF")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        call(get("/api/v1/fulfillment/put-backs").header("Authorization", token(pickerHn, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].warehouseId", not(hasItem(HCM.toString()))));
        call(get("/api/v1/fulfillment/put-backs").header("Authorization", token(managerHcm, "WAREHOUSE_MANAGER")))
                .andExpect(status().isOk());
        call(get("/api/v1/fulfillment/put-backs").header("Authorization", token(qcHcm, "QC_STAFF")))
                .andExpect(status().isForbidden());
        call(get("/api/v1/fulfillment/put-backs").header("Authorization", token(coordinator, "ORDER_COORDINATOR")))
                .andExpect(status().isForbidden());
        call(get("/api/v1/fulfillment/put-backs").header("Authorization", token(customerUser, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }
}
