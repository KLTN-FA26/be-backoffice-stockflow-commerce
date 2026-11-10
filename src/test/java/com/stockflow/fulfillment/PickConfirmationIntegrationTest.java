package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.StockDeducted;
import com.stockflow.fulfillment.internal.service.PickLists;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.Await;
import com.stockflow.support.CartonRows;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * <p>Since team plan 4C-2 an order is given whole sealed cartons only, and a carton's line is confirmed by its LPN
 * alone. The demo sofas are cartons of one; each test brings sofa cartons of its own ({@link #sofaCartons}), received
 * long before the demo ones, which the allocator hands out first (FIFO), so the shared demo cartons are not used up.
 * Every unit a test takes off the books is put back in {@link #cleanUp} (which also exercises the put-back as the
 * system).</p>
 */
@org.junit.jupiter.api.Disabled("Round 3 of the 4B-10 tests: its orders were made by placeOrder and paid through the money ledger, both gone; "
        + "they have to be contract orders of cartons of two or more (support/ConfirmedOrders) and every count re-derived. See reports/C3-tests.md.")
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class, PickConfirmationIntegrationTest.DeductionListener.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class PickConfirmationIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    com.stockflow.common.security.RoleAuthorizationLookup grants;

    /** The role's permissions as seeded: services check permissions, not role names. */
    private java.util.Set<com.stockflow.common.security.PermissionCode> seeded(Role role) {
        return grants.resolve(java.util.List.of(role.authority())).permissions();
    }

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
    @MockitoSpyBean com.stockflow.inventory.consumption.PickConsumption pickConsumption;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired com.stockflow.order.internal.service.OrderReleases releases;
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
    private UUID customerUser;
    private final List<UUID> placed = new ArrayList<>();

    /** The two bins the demo sofas are on; this class's own sofa cartons go there too. */
    private static final String SOFA_BIN = "HCM-A01-2-B";
    private static final String OTHER_SOFA_BIN = "HCM-A02-1-A";

    /**
     * Sofa cartons of this test, the first the allocator hands out (see the class doc): two in each sofa bin, because a
     * short pick freezes its carton and reallocation looks in another bin, and an order of two sofas takes two cartons.
     */
    private void sofaCartons() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(500));
        tx.executeWithoutResult(s -> {
            CartonRows.stock(jdbc, SOFA.code(), SOFA_BIN, 2, 1, null, longAgo);
            CartonRows.stock(jdbc, SOFA.code(), OTHER_SOFA_BIN, 2, 1, null, longAgo);
        });
    }

    @BeforeEach
    void setUp() {
        sofaCartons();
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
        CurrentUser system = new CurrentUser(managerHcm, "cleanup", Set.of(Role.WAREHOUSE_MANAGER), seeded(Role.WAREHOUSE_MANAGER),
                DataScope.ALL, Set.of());
        for (UUID order : placed) {
            List<Map<String, Object>> picked = jdbc.queryForList("""
                    SELECT l.id, l.pick_id, l.location_code, l.lpn FROM fulfillment.pick_line l
                      JOIN fulfillment.pick p ON p.id = l.pick_id
                     WHERE p.order_id = ? AND l.status = 'PICKED'""", order);
            if (picked.isEmpty()) {
                continue;
            }
            Await.until("task of the cancelled order cancelled", ASYNC, () -> "CANCELLED".equals(jdbc.queryForObject(
                    "SELECT status FROM fulfillment.pick WHERE order_id = ?", String.class, order)));
            for (Map<String, Object> line : picked) {
                // The carton, whole, onto the bin it left: IN_STOCK again, its row refilled.
                pickLists.putBack((UUID) line.get("pick_id"), (UUID) line.get("id"),
                        (String) line.get("location_code"), (String) line.get("lpn"), system);
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

    /** A paid stock-only order of the demo customer, its stock held at HCM, released there (READY_TO_FULFILL). */
    private OrderSummary confirmedOrder(PlaceOrderCommand.Line... lines) {
        throw new UnsupportedOperationException("to be rebuilt on support/ConfirmedOrders (round 3)");
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

    private static String otherSofaBin(String location) {
        return location.equals(SOFA_BIN) ? OTHER_SOFA_BIN : SOFA_BIN;
    }

    /** The LPN of the carton a line picks: the only code that confirms it. */
    private String lpn(String line) {
        return jdbc.queryForObject("SELECT lpn FROM fulfillment.pick_line WHERE id = ?::uuid", String.class, line);
    }

    /** The body of a confirmation: the bin, then the carton's LPN (kltn-docs 07 section 4.2 step 5) - nothing else. */
    private static String scan(String location, String lpn) {
        return "{\"locationCode\":\"" + location + "\",\"lpn\":\"" + lpn + "\"}";
    }

    private static String putBackBody(String location, String lpn) {
        return "{\"locationCode\":\"" + location + "\",\"lpn\":\"" + lpn + "\"}";
    }

    private static String shortBody(String location, String reason) {
        return "{\"locationCode\":\"" + location + "\",\"reason\":\"" + reason + "\"}";
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

    private String cartonStatus(String lpn) {
        return jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class, lpn);
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
                .contentType("application/json").content(shortBody(location, "NOT_FOUND")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHORT"))
                .andExpect(jsonPath("$.data.shortQty").value(1))
                .andExpect(jsonPath("$.data.shortReason").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data.lpn").value(lpn(lineId)));
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(orders.findById(order.orderId()).orElseThrow().lifecycle().holdReason())
                .as("X-SHORT: held for stock").isEqualTo(com.stockflow.order.api.HoldReason.INVENTORY);
        assertThat(onHand(location, SOFA)).as("nothing deducted").isEqualTo(before);
        assertThat(ledgerRows("PICK", lineId)).isZero();
        // X-SHORT step 03: a blind recount of that bin and SKU, opened once even if the short is reported again.
        String recount = "SELECT count(*) FROM inventory.cycle_count c JOIN inventory.cycle_count_line l"
                + " ON l.cycle_count_id = c.id WHERE c.trigger_type = 'SHORT_PICK' AND c.status = 'PLANNED' AND c.is_blind"
                + " AND l.stock_package_id = (SELECT id FROM inventory.stock_package WHERE lpn = ?)";
        assertThat(jdbc.queryForObject(recount, Integer.class, lpn(lineId))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT c.note FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                 WHERE c.trigger_type = 'SHORT_PICK' AND c.status = 'PLANNED'
                   AND l.stock_package_id = (SELECT id FROM inventory.stock_package WHERE lpn = ?)""", String.class,
                lpn(lineId)))
                .as("the picker took none of the carton: found 0").contains("found 0");
        assertThat(jdbc.queryForObject("""
                SELECT short_reason FROM fulfillment.pick_line WHERE id = ?::uuid""", String.class, lineId))
                .isEqualTo("NOT_FOUND");
        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/short")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))
                .contentType("application/json").content(shortBody(location, "NOT_FOUND")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(recount, Integer.class, lpn(lineId))).as("the same report again").isEqualTo(1);
        // Another reason on a line already short is not the same report: the first reason stands.
        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/short")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))
                .contentType("application/json").content(shortBody(location, "DAMAGED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_LINE_NOT_OPEN"));
        assertThat(jdbc.queryForObject("SELECT short_reason FROM fulfillment.pick_line WHERE id = ?::uuid",
                String.class, lineId)).isEqualTo("NOT_FOUND");

        JsonNode added = body(call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/reallocation")
                .header("Authorization", token(coordinator, "ORDER_COORDINATOR")))
                .andExpect(status().isOk()).andReturn());
        assertThat(added).hasSize(1);
        String newLine = added.get(0).path("lineId").asText();
        String newLocation = added.get(0).path("locationCode").asText();
        assertThat(lpn(newLine)).as("never the short carton (07 BR-09)").isNotEqualTo(lpn(lineId));
        assertThat(lineStatus(lineId)).isEqualTo("CANCELLED");
        assertThat(holdStatus(lineId)).isEqualTo("RELEASED");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).as("back where it was held from")
                .isEqualTo(OrderStatus.PICKING);
        assertThat(orders.findById(order.orderId()).orElseThrow().lines().get(0).reservationIds())
                .containsExactly(UUID.fromString(jdbc.queryForObject(
                        "SELECT reservation_id::text FROM fulfillment.pick_line WHERE id = ?::uuid", String.class, newLine)));

        // Whatever whole carton the allocator found next is confirmed by its LPN.
        String newScan = lpn(newLine);
        confirm(task, newLine, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(newLocation, newScan))
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
        String lpn = lpn(lineId);
        assertThat(lpn).as("a whole carton is picked by its LPN").isNotNull();
        int before = onHand(location, SOFA);

        // A carton's line: neither the barcode nor the SKU confirms it, only the carton's LPN.
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, SOFA_BARCODE))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_ITEM_MISMATCH"));
        // Scanned by LPN, the bin and the LPN in lower case: accepted (07 BR-10).
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF",
                scan(location.toLowerCase(Locale.ROOT), lpn.toLowerCase(Locale.ROOT)))
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
                .containsEntry("consumed", true).containsEntry("scanned_code", lpn)
                .containsEntry("picked_by", pickerHcm);
        // The event committed with the deduction, in the publication registry, and reached a listener.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                 WHERE event_type LIKE '%StockDeducted' AND serialized_event LIKE ?""", Integer.class,
                "%" + order.orderId() + "%")).isEqualTo(1);
        Await.until("StockDeducted delivered", ASYNC, () -> deductions.events().stream()
                .anyMatch(event -> event.orderId().equals(order.orderId()) && event.quantity() == 1));

        // The same scan again: 200, nothing more deducted.
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, lpn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PICKED"));
        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);
        assertThat(ledgerRows("PICK", lineId)).isEqualTo(1);

        // Every line picked: the task completes.
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
        // SCRUM-482: completing again (a retried scan) is answered with the task; stock moved once.
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);
        assertThat(ledgerRows("PICK", lineId)).isEqualTo(1);
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
        String lpn = lpn(lineId);
        // Each sofa carton holds one: the line is that carton, one unit.
        assertThat(line.path("allocatedQty").asInt()).isEqualTo(1);
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, lpn))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_TASK_STATE_CONFLICT"));
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-start")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))).andExpect(status().isOk());
        int before = onHand(location, SOFA);
        String otherBin = otherSofaBin(location);

        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(otherBin, lpn))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_LOCATION_MISMATCH"));
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, "TABLE-OAK-160"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_ITEM_MISMATCH"));
        // The SKU is not enough for a carton's line: it would let the picker take any sofa carton of the bin.
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, "SOFA-3S-GREY"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_ITEM_MISMATCH"));
        // Nor is the barcode.
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, SOFA_BARCODE))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_ITEM_MISMATCH"));
        // Nor another carton of the same SKU: the order's second line is a carton of its own.
        String otherLpn = lpn(lines(task).get(1).path("lineId").asText());
        assertThat(otherLpn).isNotEqualTo(lpn);
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, otherLpn))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_ITEM_MISMATCH"));
        confirm(task, UUID.randomUUID().toString(), picker, "WAREHOUSE_STAFF", scan(location, lpn))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        // The request is the bin and the LPN: either one missing or blank is a validation error, naming the field.
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", "{\"locationCode\":\"" + location + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lpn"));
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lpn"));
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", "{\"lpn\":\"" + lpn + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("locationCode"));
        // An LPN is at most 30 characters (stock_package.lpn): a longer scan is a validation error, not a mismatch.
        confirm(task, lineId, picker, "WAREHOUSE_STAFF", scan(location, "L".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lpn"));
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

            confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, lpn(lineId)))
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
        String scan = scan(location, lpn(lineId));
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
        String scan = scan(line.path("locationCode").asText(), lpn(lineId));

        confirm(task, lineId, pickerHn.toString(), "WAREHOUSE_STAFF", scan)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        confirm(task, lineId, managerHn.toString(), "WAREHOUSE_MANAGER", scan)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        confirm(task, lineId, otherPickerHcm.toString(), "WAREHOUSE_STAFF", scan)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        // The manager of the warehouse is not the assignee either: only the assignee confirms.
        confirm(task, lineId, managerHcm.toString(), "WAREHOUSE_MANAGER", scan)
                .andExpect(status().isNotFound());
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
                scan(lines.get(0).path("locationCode").asText(), lpn(lines.get(0).path("lineId").asText())))
                .andExpect(status().isOk());
        call(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion").header("Authorization", picker))
                .andExpect(status().isOk());
    }

    // ---- put-back -------------------------------------------------------------------------------

    @Test
    @DisplayName("cancelled after picking: the line waits in /put-backs; put back restores on_hand once, with a ledger row")
    void putBackAfterCancellation() throws Exception {
        // The whole carton comes back, IN_STOCK and sealed, onto the bin it left. Another bin is
        // covered by putBackOntoAnotherBin.
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        String target = location;
        String lpn = lpn(lineId);
        int before = onHand(location, SOFA);
        int beforeTarget = before - 1;   // the bin the carton left: its row is empty until it comes back
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, lpn))
                .andExpect(status().isOk());
        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);
        String putBackUrl = "/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/put-back";
        String picker = token(otherPickerHcm, "WAREHOUSE_STAFF");   // anyone of the warehouse, not only the picker

        // Not cancelled yet: nothing to put back.
        call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                .content(putBackBody(target, lpn)))
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
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].lpn")
                        .value(org.hamcrest.Matchers.contains(lpn)))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].fromLocationCode")
                        .value(org.hamcrest.Matchers.contains(location)))
                .andExpect(jsonPath("$.data.items[?(@.lineId=='" + lineId + "')].pickedQty")
                        .value(org.hamcrest.Matchers.contains(1)));
        // Picking a cancelled task's line again is refused.
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, lpn(lineId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_TASK_STATE_CONFLICT"));

        // Wrong targets: a bin of another warehouse, an area, an unknown code.
        for (String wrong : List.of(hnBin, "HCM-PACK01", "HCM-ZZ9-9-Z")) {
            call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                    .content(putBackBody(wrong, lpn)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("PUT_BACK_LOCATION_INVALID"));
        }
        // Another warehouse's staff cannot reach the task at all.
        call(post(putBackUrl).header("Authorization", token(pickerHn, "WAREHOUSE_STAFF"))
                .contentType("application/json").content(putBackBody(target, lpn)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        call(post(putBackUrl).header("Authorization", picker).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                .content("{\"locationCode\":\"" + target + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lpn"));
        // The carton is scanned: the SKU, the barcode and another carton are not it.
        for (String wrong : List.of("SOFA-3S-GREY", SOFA_BARCODE, "T-NOT-THIS-CARTON")) {
            call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                    .content(putBackBody(target, wrong)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("PICK_ITEM_MISMATCH"));
        }
        assertThat(cartonStatus(lpn)).as("nothing of the above moved the carton").isEqualTo("CONSUMED");
        assertThat(ledgerRows("PUT_BACK", lineId)).isZero();
        assertThat(onHand(location, SOFA)).isEqualTo(before - 1);
        assertThat(onHand(target, SOFA)).isEqualTo(beforeTarget);

        call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                .content(putBackBody(target, lpn.toLowerCase(Locale.ROOT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUT_BACK"))
                .andExpect(jsonPath("$.data.putBackQty").value(1))
                .andExpect(jsonPath("$.data.putBackLocationCode").value(target));
        assertThat(onHand(target, SOFA)).isEqualTo(beforeTarget + 1);
        assertThat(onHand(location, SOFA)).isEqualTo(before);
        assertThat(ledgerRows("PUT_BACK", lineId)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT from_location_code, to_location_code, to_status, actor_id,
                       to_stock_package_id = (SELECT id FROM inventory.stock_package WHERE lpn = ?) AS names_the_carton
                  FROM inventory.stock_movement
                 WHERE movement_type = 'PUT_BACK' AND reference_type = 'PICK_LINE' AND reference_id = ?::uuid""",
                lpn, lineId))
                .containsEntry("from_location_code", null).containsEntry("to_location_code", target)
                .containsEntry("to_status", "AVAILABLE").containsEntry("actor_id", otherPickerHcm)
                .containsEntry("names_the_carton", true);
        // The carton is back: IN_STOCK at the target, sealed, holding what was counted in; its row follows it.
        assertThat(jdbc.queryForMap("""
                SELECT status, location_code, unopened AS sealed FROM inventory.stock_package WHERE lpn = ?""",
                lpn)).containsEntry("status", "IN_STOCK").containsEntry("location_code", target)
                .containsEntry("sealed", true);
        assertThat(jdbc.queryForMap("""
                SELECT i.status, i.location_code, i.on_hand, i.reserved FROM inventory.stock_item i
                  JOIN inventory.stock_package p ON p.id = i.stock_package_id WHERE p.lpn = ?""", lpn))
                .containsEntry("status", "AVAILABLE").containsEntry("location_code", target)
                .containsEntry("on_hand", 1).containsEntry("reserved", 0);

        // Again: 200, nothing more.
        call(post(putBackUrl).header("Authorization", picker).contentType("application/json")
                .content(putBackBody(target, lpn)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUT_BACK"));
        assertThat(onHand(target, SOFA)).isEqualTo(beforeTarget + 1);
        assertThat(ledgerRows("PUT_BACK", lineId)).isEqualTo(1);
        call(get("/api/v1/fulfillment/put-backs").param("warehouseId", HCM.toString()).param("size", "200")
                .header("Authorization", picker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].lineId", not(hasItem(lineId))));
    }

    @Test
    @DisplayName("a carton put back onto ANOTHER bin: IN_STOCK sealed there, its row with it, one ledger line naming it")
    void putBackOntoAnotherBin() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        String lpn = lpn(lineId);
        String target = otherSofaBin(location);
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, lpn)).andExpect(status().isOk());
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(), "CUSTOMER_REQUEST"));
        Await.until("task cancelled", ASYNC, () -> "CANCELLED".equals(
                jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task)));

        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/put-back")
                .header("Authorization", token(otherPickerHcm, "WAREHOUSE_STAFF")).contentType("application/json")
                .content(putBackBody(target, lpn))).andExpect(status().isOk());

        assertThat(cartonStatus(lpn)).isEqualTo("IN_STOCK");
        assertThat(jdbc.queryForObject("SELECT location_code FROM inventory.stock_package WHERE lpn = ?", String.class,
                lpn)).isEqualTo(target);
        assertThat(ledgerRows("PUT_BACK", lineId)).isEqualTo(1);
    }

    @Test
    @DisplayName("two put-backs of one line at the same moment put the carton back once: one ledger line")
    void concurrentPutBacksPutBackOnce() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        String lpn = lpn(lineId);
        int before = onHand(location, SOFA);
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, lpn)).andExpect(status().isOk());
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(), "CUSTOMER_REQUEST"));
        Await.until("task cancelled", ASYNC, () -> "CANCELLED".equals(
                jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task)));
        String header = token(otherPickerHcm, "WAREHOUSE_STAFF");
        String url = "/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/put-back";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(post(url).header("Authorization", header).contentType("application/json")
                            .content(putBackBody(location, lpn))).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ledgerRows("PUT_BACK", lineId)).isEqualTo(1);
        assertThat(onHand(location, SOFA)).isEqualTo(before);
        assertThat(cartonStatus(lpn)).isEqualTo("IN_STOCK");
        assertThat(lineStatus(lineId)).isEqualTo("PUT_BACK");
    }

    @Test
    @DisplayName("a short pick whose blind count is refused keeps nothing: the line stays OPEN and the order is not held")
    void shortPickIsAtomic() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        org.mockito.Mockito.doThrow(new com.stockflow.common.error.BusinessException(
                        com.stockflow.common.error.ErrorCode.CONFLICT, "the count is refused"))
                .when(pickConsumption).requestRecount(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        try {
            call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/short")
                    .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))
                    .contentType("application/json").content(shortBody(location, "NOT_FOUND")))
                    .andExpect(status().isConflict());
        } finally {
            org.mockito.Mockito.reset(pickConsumption);
        }

        assertThat(lineStatus(lineId)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT short_reason FROM fulfillment.pick_line WHERE id = ?::uuid", String.class,
                lineId)).isNull();
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isNotEqualTo(OrderStatus.ON_HOLD);
        // And the same report, once the count can be asked, goes through.
        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/short")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))
                .contentType("application/json").content(shortBody(location, "NOT_FOUND")))
                .andExpect(status().isOk());
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);
    }

    @Test
    @DisplayName("a carton put back onto the very bin it was picked from refills its own row; no loose row appears")
    void putBackOntoTheBinItLeft() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        String lpn = lpn(lineId);
        int before = onHand(location, SOFA);
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, lpn))
                .andExpect(status().isOk());
        assertThat(cartonStatus(lpn)).isEqualTo("CONSUMED");
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(), "CUSTOMER_REQUEST"));
        Await.until("task cancelled", ASYNC, () -> "CANCELLED".equals(
                jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task)));

        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/put-back")
                .header("Authorization", token(otherPickerHcm, "WAREHOUSE_STAFF")).contentType("application/json")
                .content(putBackBody(location, lpn)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.putBackLocationCode").value(location));
        assertThat(onHand(location, SOFA)).isEqualTo(before);
        assertThat(cartonStatus(lpn)).as("the carton is IN_STOCK again").isEqualTo("IN_STOCK");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_item WHERE sku = 'SOFA-3S-GREY' AND stock_package_id IS NULL
                   AND on_hand > 0 AND location_code = ?""", Integer.class, location)).as("no loose row: the carton's own row took its units back").isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_item i JOIN inventory.stock_package p ON p.id = i.stock_package_id
                 WHERE p.lpn = ? AND i.on_hand = 1 AND i.status = 'AVAILABLE' AND i.location_code = ?""",
                Integer.class, lpn, location)).isEqualTo(1);
        assertThat(ledgerRows("PUT_BACK", lineId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT p.lpn FROM inventory.stock_movement m JOIN inventory.stock_package p"
                + " ON p.id = m.to_stock_package_id WHERE m.movement_type = 'PUT_BACK' AND m.reference_id = ?::uuid",
                String.class, lineId)).as("the ledger names the carton").isEqualTo(lpn(lineId));
    }

    @Test
    @DisplayName("a carton put back is allocatable again: the next order is given it, sealed, by the same FEFO/FIFO order")
    void putBackCartonIsAllocatableAgain() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        String lpn = lpn(lineId);
        confirm(task, lineId, pickerHcm.toString(), "WAREHOUSE_STAFF", scan(location, lpn)).andExpect(status().isOk());
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(), "CUSTOMER_REQUEST"));
        Await.until("task cancelled", ASYNC, () -> "CANCELLED".equals(
                jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task)));
        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/put-back")
                .header("Authorization", token(otherPickerHcm, "WAREHOUSE_STAFF")).contentType("application/json")
                .content(putBackBody(location, lpn))).andExpect(status().isOk());

        // Nothing is left half-way: no hold on the carton's row, no reservation counted, the books add up.
        assertThat(jdbc.queryForObject("""
                SELECT i.reserved FROM inventory.stock_item i JOIN inventory.stock_package p ON p.id = i.stock_package_id
                 WHERE p.lpn = ?""", Integer.class, lpn)).isZero();
        OrderSummary next = confirmedOrder(sofa(1));
        assertThat(next.lines().getFirst().reservationIds()).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_reservation r JOIN inventory.stock_item i ON i.id = r.stock_item_id
                 WHERE r.id = ?""", Integer.class, next.lines().getFirst().reservationIds().getFirst()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("X-SHORT, reason DAMAGED: the same stop and blind count; the reason is on the line, the order is held for stock")
    void shortPickDamagedReason() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        int before = onHand(location, SOFA);

        call(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/short")
                .header("Authorization", token(pickerHcm, "WAREHOUSE_STAFF"))
                .contentType("application/json").content(shortBody(location, "DAMAGED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHORT"))
                .andExpect(jsonPath("$.data.shortReason").value("DAMAGED"))
                .andExpect(jsonPath("$.data.shortQty").value(1));

        OrderSummary held = orders.findById(order.orderId()).orElseThrow();
        assertThat(held.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(held.lifecycle().holdReason()).isEqualTo(com.stockflow.order.api.HoldReason.INVENTORY);
        assertThat(onHand(location, SOFA)).as("nothing deducted").isEqualTo(before);
        assertThat(holdStatus(lineId)).as("the allocation is kept until the coordinator reallocates").isEqualTo("HELD");
        assertThat(ledgerRows("PICK", lineId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                 WHERE c.trigger_type = 'SHORT_PICK' AND c.is_blind AND c.status = 'PLANNED'
                   AND l.stock_package_id = (SELECT id FROM inventory.stock_package WHERE lpn = ?)""", Integer.class,
                lpn(lineId))).isEqualTo(1);
    }

    @Test
    @DisplayName("a short pick needs a reason, a known one, and the line's own bin")
    void shortPickNeedsAReason() throws Exception {
        OrderSummary order = confirmedOrder(sofa(1));
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String location = line.path("locationCode").asText();
        String url = "/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/short";
        String picker = token(pickerHcm, "WAREHOUSE_STAFF");

        call(post(url).header("Authorization", picker).contentType("application/json")
                .content("{\"locationCode\":\"" + location + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        call(post(url).header("Authorization", picker).contentType("application/json")
                .content(shortBody(location, "LOST_IN_TRANSIT")))
                .andExpect(status().is4xxClientError());
        call(post(url).header("Authorization", picker).contentType("application/json")
                .content(shortBody(otherSofaBin(location), "NOT_FOUND")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_LOCATION_MISMATCH"));
        // The old body - a count of units found - is not a short report any more.
        call(post(url).header("Authorization", picker).contentType("application/json")
                .content("{\"locationCode\":\"" + location + "\",\"foundQuantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));

        assertThat(lineStatus(lineId)).isEqualTo("OPEN");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isNotEqualTo(OrderStatus.ON_HOLD);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.cycle_count c JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                 WHERE c.trigger_type = 'SHORT_PICK' AND c.status = 'PLANNED'
                   AND l.stock_package_id = (SELECT id FROM inventory.stock_package WHERE lpn = ?)""", Integer.class,
                lpn(lineId))).isZero();
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
        call(get("/api/v1/fulfillment/put-backs").header("Authorization", token(coordinator, "ORDER_COORDINATOR")))
                .andExpect(status().isForbidden());
        call(get("/api/v1/fulfillment/put-backs").header("Authorization", token(customerUser, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }
}
