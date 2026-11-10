package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.order.api.HoldReason;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-277 end to end, through the real filter chain, permissions, warehouse scope and database: a picker finds
 * damaged units at a line's bin and reports them with a photo. In one transaction the damaged units go to quarantine
 * as DAMAGED, the good ones stay to be picked, and the order is made whole - other stock of the warehouse replaces the
 * damaged units (FEFO), or, when there is none, a SHORT line waits for the coordinator's reallocation with the order
 * ON_HOLD (X-SHORT). Then the order is picked to the end.
 *
 * <p>Stock is whole sealed cartons (team plan 4C-2): each pick line is one carton, picked by its LPN, and a carton is
 * reported damaged at its bin whole - part of one is refused ({@code STOCK_PACKAGE_TAKEN_WHOLE}). Found at packing,
 * the carton was already taken whole, so any of its units may be reported. A test that needs arbitrary quantities
 * uses cartons of one unit; a SKU has one carton size, and each test owns its SKU.</p>
 *
 * <p>Each test owns a fresh SKU and its stock, so nothing here touches the demo stock other tests count on.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class PickDamageIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(10);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F',
            0, 1, 1, 0, 0, 1, 0, 1, 0, 0};

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired com.stockflow.order.internal.service.OrderReleases releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ObjectMapper json;

    private String sku;
    private LocalDate today;
    private UUID coordinator;
    private UUID manager;
    private UUID picker;
    private UUID otherPicker;
    private UUID pickerElsewhere;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        sku = "PKD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Pick damage fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Pick damage fixture','')", Identifiers.newId(), product, sku);
        });
        coordinator = staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        manager = staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        picker = staff("WAREHOUSE_STAFF", DemoData.WAREHOUSE_HCM);
        otherPicker = staff("WAREHOUSE_STAFF", DemoData.WAREHOUSE_HCM);
        pickerElsewhere = staff("WAREHOUSE_STAFF", otherWarehouse());
    }

    @AfterEach
    void cleanUp() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
            OrderStatus current = orders.findById(id).orElseThrow().status();
            if (current != OrderStatus.CANCELLED && current != OrderStatus.PACKED) {
                try {
                    orders.cancel(id, "TEST_CLEANUP");
                } catch (RuntimeException ignored) {
                    // An order the test took past cancellation stays as it is; its SKU is the test's own.
                }
            }
        }));
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    /**
     * {@code count} sealed AVAILABLE cartons of {@code factor} units of this test's SKU at {@code location}. The SKU's
     * carton size is fixed by the first call, so a test uses one factor throughout.
     */
    private List<CartonRows.Carton> cartons(String location, int count, int factor, String lot, LocalDate expiry) {
        return tx.execute(s -> CartonRows.stock(jdbc, sku, location, count, factor, lot, Instant.parse("2026-10-01T03:00:00Z"), "AVAILABLE"));
    }

    /** The SKU's AVAILABLE stock at a bin, over all its cartons. */
    private Map<String, Object> bin(String location) {
        return jdbc.queryForMap("""
                SELECT COALESCE(SUM(on_hand), 0)::int AS on_hand, COALESCE(SUM(reserved), 0)::int AS reserved
                  FROM inventory.stock_item WHERE sku = ? AND location_code = ? AND status = 'AVAILABLE'""",
                sku, location);
    }

    private String cartonStatus(String lpn) {
        return jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class, lpn);
    }

    private UUID otherWarehouse() {
        UUID warehouse = Identifiers.newId();
        String prefix = "P" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", warehouse, prefix));
        return warehouse;
    }

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

    /** A paid order of {@code quantity} units, released to HCM: allocated FEFO on this test's stock. */
    private OrderSummary releasedOrder(int quantity) {
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID,
                List.of(new PlaceOrderCommand.Line(new Sku(sku), quantity, Money.vnd(100_000), null))));
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
        return releases.release(order.orderId(), DemoData.WAREHOUSE_HCM, coordinator);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** The coordinator opens the task for {@link #picker}; the picker starts it. */
    private UUID startedTask(OrderSummary order) throws Exception {
        String body = "{\"orderId\":\"" + order.orderId() + "\",\"assignedUserId\":\"" + picker + "\"}";
        UUID task = UUID.fromString(body(mvc.perform(post("/api/v1/fulfillment/tasks")
                        .header("Authorization", token(coordinator, "WAREHOUSE_MANAGER"))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn()).path("taskId").asText());
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/picking-start")
                .header("Authorization", token(picker, "WAREHOUSE_STAFF"))).andExpect(status().isOk());
        return task;
    }

    private JsonNode lines(UUID task) throws Exception {
        return body(mvc.perform(get("/api/v1/fulfillment/tasks/" + task + "/pick-lines")
                        .header("Authorization", token(picker, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk()).andReturn()).path("items");
    }

    private String damageJson(UUID request, String location, int quantity) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", request);
        body.put("locationCode", location);
        body.put("damagedQuantity", quantity);
        body.put("reason", "BROKEN");
        body.put("note", "dropped from the top shelf");
        return json.writeValueAsString(body);
    }

    private ResultActions damage(UUID task, String line, UUID user, String role, String body, boolean photo)
            throws Exception {
        var request = multipart("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + line + "/damage")
                .file(new MockMultipartFile("report", "", "application/json", body.getBytes(StandardCharsets.UTF_8)));
        if (photo) {
            request.file(new MockMultipartFile("photos", "broken.jpg", "image/jpeg", JPEG));
        }
        return mvc.perform(request.header("Authorization", token(user, role)));
    }

    /** Scans the line's carton by its LPN; a line on loose stock (a restored unit) by the SKU. */
    private ResultActions confirm(UUID task, String line, String location, int quantity) throws Exception {
        String lpn = jdbc.queryForObject("SELECT lpn FROM fulfillment.pick_line WHERE id = ?::uuid", String.class, line);
        String code = lpn != null ? lpn : sku;
        return mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + line + "/confirmation")
                .header("Authorization", token(picker, "WAREHOUSE_STAFF")).contentType("application/json")
                .content("{\"locationCode\":\"" + location + "\",\"scannedCode\":\"" + code + "\",\"quantity\":"
                        + quantity + "}"));
    }

    /** Damage found at the packing bench (R8): no bin to scan, the units are the line's. */
    private ResultActions packDamage(UUID task, String line, UUID user, String role, UUID request, int quantity,
                                     boolean photo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", request);
        body.put("damagedQuantity", quantity);
        body.put("reason", "BROKEN");
        body.put("note", "cracked in the tote");
        var call = multipart("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + line + "/damage-after-pick")
                .file(new MockMultipartFile("report", "", "application/json", json.writeValueAsBytes(body)));
        if (photo) {
            call.file(new MockMultipartFile("photos", "broken.jpg", "image/jpeg", JPEG));
        }
        return mvc.perform(call.header("Authorization", token(user, role)));
    }

    /** Every open line of the task picked. */
    private void pickOpenLines(UUID task) throws Exception {
        for (JsonNode next : lines(task)) {
            if (next.path("status").asText().equals("OPEN")) {
                confirm(task, next.path("lineId").asText(), next.path("locationCode").asText(),
                        next.path("allocatedQty").asInt()).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("PICKED"));
            }
        }
    }

    /** Every open line of the task picked, the task completed and packing started by the picker. */
    private void pickedAndAtPacking(UUID task) throws Exception {
        pickOpenLines(task);
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                .header("Authorization", token(picker, "WAREHOUSE_STAFF"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/packing-start")
                .header("Authorization", token(picker, "WAREHOUSE_STAFF"))).andExpect(status().isOk());
    }

    private JsonNode packingLine(UUID task) throws Exception {
        return body(mvc.perform(get("/api/v1/fulfillment/tasks/" + task + "/packing-session")
                        .header("Authorization", token(picker, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk()).andReturn()).path("lines").get(0);
    }

    private int damagedInQuarantine() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item WHERE sku = ? AND status = 'DAMAGED'""",
                Integer.class, sku);
    }

    private OrderSummary order(UUID id) {
        return orders.findById(id).orElseThrow();
    }

    // ---- the flows ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a damaged carton at its bin: replaced FEFO by the next whole carton, the other lines stay to pick, the "
            + "order goes on")
    void damagedCartonIsReplacedAndTheOrderIsPicked() throws Exception {
        cartons("HCM-A01-1-A", 5, 1, "L1", today.plusDays(10));
        cartons("HCM-A01-1-B", 10, 1, "L2", today.plusDays(30));
        OrderSummary order = releasedOrder(4);
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String damagedLpn = line.path("lpn").asText();
        assertThat(line.path("locationCode").asText()).isEqualTo("HCM-A01-1-A");
        assertThat(line.path("wholeCarton").asBoolean()).isTrue();
        UUID hold = UUID.fromString(jdbc.queryForObject(
                "SELECT reservation_id::text FROM fulfillment.pick_line WHERE id = ?::uuid", String.class, lineId));

        JsonNode outcome = body(damage(task, lineId, picker, "WAREHOUSE_STAFF",
                damageJson(UUID.randomUUID(), "hcm-a01-1-a", 1), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.damagedQuantity").value(1))
                .andExpect(jsonPath("$.data.orderOnHold").value(false))
                .andExpect(jsonPath("$.data.damageLocationCode").value("HCM-QC01"))
                .andExpect(jsonPath("$.data.line.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.line.damagedQty").value(1))
                .andReturn());

        // The carton went to quarantine whole; FEFO replaces it with the last free carton of the same lot.
        JsonNode made = outcome.path("lines");
        assertThat(made).singleElement().satisfies(next -> {
            assertThat(next.path("locationCode").asText()).isEqualTo("HCM-A01-1-A");
            assertThat(next.path("allocatedQty").asInt()).isEqualTo(1);
            assertThat(next.path("lpn").asText()).isNotBlank().isNotEqualTo(damagedLpn);
            assertThat(next.path("status").asText()).isEqualTo("OPEN");
        });

        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 4).containsEntry("reserved", 4);
        assertThat(bin("HCM-A01-1-B")).containsEntry("on_hand", 10).containsEntry("reserved", 0);
        assertThat(cartonStatus(damagedLpn)).as("emptied into quarantine").isEqualTo("CONSUMED");
        assertThat(damagedInQuarantine()).isEqualTo(1);
        assertThat(order(order.orderId()).status()).isEqualTo(OrderStatus.PICKING);
        assertThat(order(order.orderId()).lines().get(0).reservationIds())
                .as("the three cartons still to pick and the replacement").hasSize(4).doesNotContain(hold);
        UUID reportId = UUID.fromString(outcome.path("damageReportId").asText());
        assertThat(jdbc.queryForMap("""
                SELECT origin, pick_line_id, order_id, reservation_id, impaired_reservation_id, quantity
                  FROM inventory.stock_damage_report WHERE id = ?""", reportId))
                .containsEntry("origin", "PICKING").containsEntry("pick_line_id", UUID.fromString(lineId))
                .containsEntry("order_id", order.orderId()).containsEntry("reservation_id", hold)
                .containsEntry("impaired_reservation_id", null).containsEntry("quantity", 1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE reference_type = 'DAMAGE_REPORT' AND reference_id = ?
                   AND order_id = ? AND to_status = 'DAMAGED'""", Integer.class, reportId, order.orderId())).isEqualTo(1);

        pickOpenLines(task);
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                        .header("Authorization", token(picker, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 0).containsEntry("reserved", 0);
        assertThat(bin("HCM-A01-1-B")).containsEntry("on_hand", 10).containsEntry("reserved", 0);
    }

    @Test
    @DisplayName("part of a carton damaged at its bin is refused - a carton is taken whole; the whole carton is reported")
    void partOfACartonIsNotReportedAtTheBin() throws Exception {
        String damagedLpn = cartons("HCM-A01-1-A", 1, 4, "L1", today.plusDays(10)).getFirst().lpn();
        cartons("HCM-A01-1-B", 1, 4, "L2", today.plusDays(30));
        OrderSummary order = releasedOrder(4);
        UUID task = startedTask(order);
        String lineId = lines(task).get(0).path("lineId").asText();

        damage(task, lineId, picker, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-A", 2), true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_PACKAGE_TAKEN_WHOLE"));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid", String.class,
                lineId)).isEqualTo("OPEN");
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 4).containsEntry("reserved", 4);
        assertThat(cartonStatus(damagedLpn)).isEqualTo("IN_STOCK");
        assertThat(damagedInQuarantine()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_damage_report WHERE sku = ?",
                Integer.class, sku)).isZero();

        JsonNode made = body(damage(task, lineId, picker, "WAREHOUSE_STAFF",
                damageJson(UUID.randomUUID(), "HCM-A01-1-A", 4), true).andExpect(status().isOk()).andReturn())
                .path("lines");
        assertThat(made).singleElement().satisfies(next -> {
            assertThat(next.path("locationCode").asText()).isEqualTo("HCM-A01-1-B");
            assertThat(next.path("allocatedQty").asInt()).isEqualTo(4);
        });
        assertThat(cartonStatus(damagedLpn)).isEqualTo("CONSUMED");
        assertThat(damagedInQuarantine()).isEqualTo(4);
    }

    @Test
    @DisplayName("damage with no other stock: SHORT line, order ON_HOLD; write-off waits; reallocated, picked, written off")
    void noOtherStockHoldsTheOrderUntilReallocated() throws Exception {
        cartons("HCM-A01-1-A", 3, 1, "L1", today.plusDays(10));
        OrderSummary order = releasedOrder(3);
        UUID task = startedTask(order);
        String lineId = lines(task).get(0).path("lineId").asText();

        JsonNode outcome = body(damage(task, lineId, picker, "WAREHOUSE_STAFF",
                damageJson(UUID.randomUUID(), "HCM-A01-1-A", 1), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderOnHold").value(true))
                .andReturn());
        JsonNode shortLine = outcome.path("lines").get(0);
        assertThat(outcome.path("lines")).hasSize(1);
        assertThat(shortLine.path("status").asText()).isEqualTo("SHORT");
        assertThat(shortLine.path("shortQty").asInt()).isEqualTo(1);
        assertThat(order(order.orderId()).status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(order(order.orderId()).lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        assertThat(bin("HCM-A01-1-A")).as("the other two cartons, still the order's")
                .containsEntry("on_hand", 2).containsEntry("reserved", 2);
        assertThat(jdbc.queryForMap("""
                SELECT on_hand, reserved FROM inventory.stock_item WHERE sku = ? AND status = 'DAMAGED'""", sku))
                .as("the order's share stays promised on the damaged units").containsEntry("on_hand", 1)
                .containsEntry("reserved", 1);

        // The damaged units are the order's until it is reallocated: no write-off yet.
        UUID reportId = UUID.fromString(outcome.path("damageReportId").asText());
        String writeOff = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"disposition\":\"WRITE_OFF\",\"quantity\":1}";
        mvc.perform(post("/api/v1/inventory/damage-reports/" + reportId + "/dispositions")
                        .header("Authorization", token(manager, "WAREHOUSE_MANAGER"))
                        .contentType("application/json").content(writeOff))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DAMAGED_STOCK_HELD"));
        String reallocate = "/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + shortLine.path("lineId").asText()
                + "/reallocation";
        mvc.perform(post(reallocate).header("Authorization", token(coordinator, "WAREHOUSE_MANAGER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"));

        // Cartons arrive on another bin: the coordinator reallocates the short line and the order goes on.
        cartons("HCM-A01-2-A", 2, 1, "L3", today.plusDays(40));
        JsonNode reallocated = body(mvc.perform(post(reallocate)
                        .header("Authorization", token(coordinator, "WAREHOUSE_MANAGER")))
                .andExpect(status().isOk()).andReturn());
        assertThat(reallocated).hasSize(1);
        assertThat(reallocated.get(0).path("locationCode").asText()).isEqualTo("HCM-A01-2-A");
        assertThat(order(order.orderId()).status()).as("resumed").isEqualTo(OrderStatus.PICKING);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory.stock_item WHERE sku = ? AND status = 'DAMAGED'",
                Integer.class, sku)).isZero();

        // The units the order no longer holds are written off now.
        mvc.perform(post("/api/v1/inventory/damage-reports/" + reportId + "/dispositions")
                        .header("Authorization", token(manager, "WAREHOUSE_MANAGER"))
                        .contentType("application/json").content(writeOff))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(damagedInQuarantine()).isZero();

        pickOpenLines(task);
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                        .header("Authorization", token(picker, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk());
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 0);
        assertThat(bin("HCM-A01-2-A")).containsEntry("on_hand", 1).containsEntry("reserved", 0);
    }

    @Test
    @DisplayName("a damaged carton of several units: its allocation closes DAMAGED and a whole carton elsewhere replaces it")
    void wholeLineDamaged() throws Exception {
        String damagedLpn = cartons("HCM-A01-1-A", 1, 2, "L1", today.plusDays(10)).getFirst().lpn();
        cartons("HCM-A01-1-B", 2, 2, "L2", today.plusDays(30));
        OrderSummary order = releasedOrder(2);
        UUID task = startedTask(order);
        String lineId = lines(task).get(0).path("lineId").asText();
        UUID hold = UUID.fromString(jdbc.queryForObject(
                "SELECT reservation_id::text FROM fulfillment.pick_line WHERE id = ?::uuid", String.class, lineId));

        JsonNode made = body(damage(task, lineId, picker, "WAREHOUSE_STAFF",
                damageJson(UUID.randomUUID(), "HCM-A01-1-A", 2), true).andExpect(status().isOk()).andReturn())
                .path("lines");

        assertThat(made).singleElement().satisfies(next -> {
            assertThat(next.path("locationCode").asText()).isEqualTo("HCM-A01-1-B");
            assertThat(next.path("allocatedQty").asInt()).isEqualTo(2);
        });
        assertThat(jdbc.queryForMap("SELECT status, release_reason FROM inventory.stock_reservation WHERE id = ?", hold))
                .containsEntry("status", "RELEASED").containsEntry("release_reason", "DAMAGED");
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 0).containsEntry("reserved", 0);
        assertThat(cartonStatus(damagedLpn)).isEqualTo("CONSUMED");
        assertThat(order(order.orderId()).lines().get(0).reservationIds()).doesNotContain(hold).hasSize(1);
    }

    // ---- damage found at packing (R8) ------------------------------------------------------------------------------

    @Test
    @DisplayName("damage at packing: back into quarantine from nowhere, replaced FEFO, task back to picking, packed whole")
    void damageAtPackingIsReplaced() throws Exception {
        cartons("HCM-A01-1-A", 5, 1, "L1", today.plusDays(10));
        cartons("HCM-A01-1-B", 10, 1, "L2", today.plusDays(30));
        OrderSummary order = releasedOrder(3);
        UUID task = startedTask(order);
        JsonNode planned = lines(task);
        String lineId = planned.get(0).path("lineId").asText();
        String goodLineId = planned.get(1).path("lineId").asText();
        UUID hold = UUID.fromString(jdbc.queryForObject(
                "SELECT reservation_id::text FROM fulfillment.pick_line WHERE id = ?::uuid", String.class, lineId));
        pickedAndAtPacking(task);
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 2).containsEntry("reserved", 0);
        UUID request = UUID.randomUUID();

        JsonNode outcome = body(packDamage(task, lineId, picker, "WAREHOUSE_STAFF", request, 1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderOnHold").value(false))
                .andExpect(jsonPath("$.data.damageLocationCode").value("HCM-QC01"))
                .andExpect(jsonPath("$.data.line.status").value("PICKED"))
                .andExpect(jsonPath("$.data.line.pickedQty").value(1))
                .andExpect(jsonPath("$.data.line.packingDamagedQty").value(1))
                .andReturn());
        JsonNode made = outcome.path("lines");
        assertThat(made).singleElement().satisfies(next -> {
            assertThat(next.path("status").asText()).isEqualTo("OPEN");
            assertThat(next.path("locationCode").asText()).as("FEFO: the same lot first").isEqualTo("HCM-A01-1-A");
            assertThat(next.path("allocatedQty").asInt()).isEqualTo(1);
        });
        UUID reportId = UUID.fromString(outcome.path("damageReportId").asText());
        assertThat(jdbc.queryForMap("""
                SELECT origin, pick_line_id, reservation_id, impaired_reservation_id, source_location_code, quantity
                  FROM inventory.stock_damage_report WHERE id = ?""", reportId))
                .containsEntry("origin", "PACKING").containsEntry("pick_line_id", UUID.fromString(lineId))
                .containsEntry("reservation_id", hold).containsEntry("impaired_reservation_id", null)
                .containsEntry("source_location_code", "HCM-A01-1-A").containsEntry("quantity", 1);
        assertThat(jdbc.queryForMap("""
                SELECT movement_type, from_location_code, to_location_code, to_status, quantity, order_id
                  FROM inventory.stock_movement WHERE reference_type = 'DAMAGE_REPORT' AND reference_id = ?""", reportId))
                .as("deducted when picked, so back into stock from nowhere")
                .containsEntry("movement_type", "PUT_BACK").containsEntry("from_location_code", null)
                .containsEntry("to_location_code", "HCM-QC01").containsEntry("to_status", "DAMAGED")
                .containsEntry("quantity", 1).containsEntry("order_id", order.orderId());
        assertThat(damagedInQuarantine()).isEqualTo(1);
        assertThat(bin("HCM-A01-1-A")).as("the bin keeps what it had, one carton of it now allocated")
                .containsEntry("on_hand", 2).containsEntry("reserved", 1);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task))
                .isEqualTo("PICKING");
        assertThat(order(order.orderId()).lines().get(0).reservationIds())
                .as("the consumed hold stays on record; the replacement joins the order's three").hasSize(4)
                .contains(hold);
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/packing-start")
                        .header("Authorization", token(picker, "WAREHOUSE_STAFF")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKING_NOT_READY"));

        // The same report again answers with what it made, though the task is back at picking.
        JsonNode again = body(packDamage(task, lineId, picker, "WAREHOUSE_STAFF", request, 1, true)
                .andExpect(status().isOk()).andReturn());
        assertThat(again.path("damageReportId").asText()).isEqualTo(reportId.toString());
        assertThat(again.path("lines")).hasSize(1);
        assertThat(damagedInQuarantine()).isEqualTo(1);

        pickedAndAtPacking(task);
        JsonNode packing = packingLine(task);
        assertThat(packing.path("available").asInt()).as("two good cartons and the replacement").isEqualTo(3);
        assertThat(packing.path("lots").get(0).path("available").asInt()).isEqualTo(3);
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 1).containsEntry("reserved", 0);

        // Cancelled now: a good line puts its carton back; the unit in quarantine stays there.
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(), "CUSTOMER_REQUEST"));
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + goodLineId + "/put-back")
                        .header("Authorization", token(picker, "WAREHOUSE_STAFF")).contentType("application/json")
                        .content("{\"locationCode\":\"HCM-A01-1-A\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.putBackQty").value(1));
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 2);
        assertThat(damagedInQuarantine()).isEqualTo(1);
    }

    @Test
    @DisplayName("damage at packing with no other stock: SHORT line on the impaired share, order ON_HOLD, task at picking")
    void damageAtPackingWithNoOtherStock() throws Exception {
        cartons("HCM-A01-1-A", 1, 2, "L1", today.plusDays(10));
        OrderSummary order = releasedOrder(2);
        UUID task = startedTask(order);
        String lineId = lines(task).get(0).path("lineId").asText();
        pickedAndAtPacking(task);

        JsonNode made = body(packDamage(task, lineId, picker, "WAREHOUSE_STAFF", UUID.randomUUID(), 2, true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderOnHold").value(true))
                .andReturn()).path("lines");
        assertThat(made).singleElement().satisfies(next -> {
            assertThat(next.path("status").asText()).isEqualTo("SHORT");
            assertThat(next.path("shortQty").asInt()).isEqualTo(2);
        });
        assertThat(order(order.orderId()).status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(order(order.orderId()).lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        assertThat(jdbc.queryForMap("""
                SELECT on_hand, reserved FROM inventory.stock_item WHERE sku = ? AND status = 'DAMAGED'""", sku))
                .as("the order's share stays promised on the damaged units").containsEntry("on_hand", 2)
                .containsEntry("reserved", 2);
        assertThat(packingLine(task).path("available").asInt()).isZero();
    }

    @Test
    @DisplayName("damage at packing: units in a parcel come out first; never more than the line brought; packer only")
    void damageAtPackingRefusals() throws Exception {
        cartons("HCM-A01-1-A", 3, 2, "L1", today.plusDays(10));
        OrderSummary order = releasedOrder(2);
        UUID task = startedTask(order);
        String lineId = lines(task).get(0).path("lineId").asText();

        // Nothing picked yet: damage on the shelf is reported at the line's bin.
        packDamage(task, lineId, picker, "WAREHOUSE_STAFF", UUID.randomUUID(), 1, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_LINE_NOT_OPEN"));
        pickedAndAtPacking(task);
        UUID parcel = UUID.fromString(body(mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/packages")
                        .header("Authorization", token(picker, "WAREHOUSE_STAFF")).contentType("application/json")
                        .content("{}")).andExpect(status().isCreated()).andReturn()).path("packageId").asText());
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/packages/" + parcel + "/scans")
                .header("Authorization", token(picker, "WAREHOUSE_STAFF")).contentType("application/json")
                .content("{\"scanId\":\"" + UUID.randomUUID() + "\",\"scannedCode\":\"" + sku
                        + "\",\"lotNumber\":\"L1\",\"quantity\":2}")).andExpect(status().isOk());

        packDamage(task, lineId, picker, "WAREHOUSE_STAFF", UUID.randomUUID(), 1, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACK_DAMAGED_UNITS_PACKED"));
        packDamage(task, lineId, picker, "WAREHOUSE_STAFF", UUID.randomUUID(), 3, true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        packDamage(task, lineId, otherPicker, "WAREHOUSE_STAFF", UUID.randomUUID(), 1, true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKING_TAKEN"));

        // Out of the parcel again: reported with a photo only.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/fulfillment/tasks/" + task + "/packages/" + parcel)
                .header("Authorization", token(picker, "WAREHOUSE_STAFF"))).andExpect(status().isOk());
        packDamage(task, lineId, picker, "WAREHOUSE_STAFF", UUID.randomUUID(), 1, false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("DAMAGE_PHOTO_REQUIRED"));
        assertThat(damagedInQuarantine()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_damage_report WHERE sku = ?",
                Integer.class, sku)).isZero();
    }

    @Test
    @DisplayName("damage at packing, picker gone home: the coordinator hands the reopened task to someone who is here")
    void reopenedTaskIsReassigned() throws Exception {
        cartons("HCM-A01-1-A", 5, 1, "L1", today.plusDays(10));
        OrderSummary order = releasedOrder(2);
        UUID task = startedTask(order);
        String lineId = lines(task).get(0).path("lineId").asText();
        pickedAndAtPacking(task);
        JsonNode replacement = body(packDamage(task, lineId, picker, "WAREHOUSE_STAFF", UUID.randomUUID(), 1, true)
                .andExpect(status().isOk()).andReturn()).path("lines").get(0);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/fulfillment/tasks/" + task + "/assignment")
                        .header("Authorization", token(coordinator, "WAREHOUSE_MANAGER")).contentType("application/json")
                        .content("{\"assignedUserId\":\"" + otherPicker + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + replacement.path("lineId").asText()
                        + "/confirmation").header("Authorization", token(otherPicker, "WAREHOUSE_STAFF"))
                        .contentType("application/json")
                        .content("{\"locationCode\":\"" + replacement.path("locationCode").asText()
                                + "\",\"scannedCode\":\"" + replacement.path("lpn").asText() + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                        .header("Authorization", token(otherPicker, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pickStatus").value("PICKED"));
    }

    @Test
    @DisplayName("nothing replaced the damaged units, then they prove fine: restored with the order's share, the "
            + "short line reallocated onto them where they are, picked")
    void impairedUnitsRestoredBackToTheOrder() throws Exception {
        cartons("HCM-A01-1-A", 2, 1, "L1", today.plusDays(10));
        OrderSummary order = releasedOrder(2);
        UUID task = startedTask(order);
        String lineId = lines(task).get(0).path("lineId").asText();
        JsonNode outcome = body(damage(task, lineId, picker, "WAREHOUSE_STAFF",
                damageJson(UUID.randomUUID(), "HCM-A01-1-A", 1), true).andExpect(status().isOk()).andReturn());
        UUID reportId = UUID.fromString(outcome.path("damageReportId").asText());
        assertThat(outcome.path("lines")).hasSize(1);
        String shortLine = outcome.path("lines").get(0).path("lineId").asText();
        assertThat(order(order.orderId()).status()).isEqualTo(OrderStatus.ON_HOLD);

        String writeOff = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"disposition\":\"WRITE_OFF\",\"quantity\":1}";
        mvc.perform(post("/api/v1/inventory/damage-reports/" + reportId + "/dispositions")
                        .header("Authorization", token(manager, "WAREHOUSE_MANAGER"))
                        .contentType("application/json").content(writeOff))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("DAMAGED_STOCK_HELD"));
        String restore = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"disposition\":\"RESTORE\",\"quantity\":1,"
                + "\"note\":\"Kiểm lại: chỉ trầy thùng, ly nguyên\",\"restoreLocationCode\":\"HCM-A01-2-A\"}";
        mvc.perform(post("/api/v1/inventory/damage-reports/" + reportId + "/dispositions")
                        .header("Authorization", token(manager, "WAREHOUSE_MANAGER"))
                        .contentType("application/json").content(restore))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(damagedInQuarantine()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT on_hand, reserved FROM inventory.stock_item WHERE sku = ? AND location_code = 'HCM-A01-2-A'""",
                sku)).as("the unit is the order's again, on the bin").containsEntry("on_hand", 1)
                .containsEntry("reserved", 1);

        JsonNode reallocated = body(mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + shortLine
                        + "/reallocation").header("Authorization", token(coordinator, "WAREHOUSE_MANAGER")))
                .andExpect(status().isOk()).andReturn());
        assertThat(reallocated).singleElement().satisfies(line -> {
            assertThat(line.path("locationCode").asText()).isEqualTo("HCM-A01-2-A");
            assertThat(line.path("status").asText()).isEqualTo("OPEN");
        });
        assertThat(order(order.orderId()).status()).as("resumed").isEqualTo(OrderStatus.PICKING);
        // The other carton at the first bin, and the restored unit where it now is.
        pickOpenLines(task);
        mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/picking-completion")
                        .header("Authorization", token(picker, "WAREHOUSE_STAFF")))
                .andExpect(status().isOk());
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 0);
        assertThat(bin("HCM-A01-2-A")).containsEntry("on_hand", 0);
    }

    // ---- retries, refusals, races ------------------------------------------------------------------------------

    @Test
    @DisplayName("the same report again answers with what it made; another one on the superseded line is refused")
    void replay() throws Exception {
        cartons("HCM-A01-1-A", 5, 1, "L1", today.plusDays(10));
        cartons("HCM-A01-1-B", 5, 1, "L2", today.plusDays(30));
        OrderSummary order = releasedOrder(3);
        UUID task = startedTask(order);
        String lineId = lines(task).get(0).path("lineId").asText();
        UUID request = UUID.randomUUID();
        String report = damageJson(request, "HCM-A01-1-A", 1);

        String first = body(damage(task, lineId, picker, "WAREHOUSE_STAFF", report, true)
                .andExpect(status().isOk()).andReturn()).path("damageReportId").asText();
        JsonNode again = body(damage(task, lineId, picker, "WAREHOUSE_STAFF", report, true)
                .andExpect(status().isOk()).andReturn());

        assertThat(again.path("damageReportId").asText()).isEqualTo(first);
        assertThat(again.path("reportNumber").asText()).startsWith("DMG-");
        assertThat(again.path("damageLocationCode").asText()).isEqualTo("HCM-QC01");
        assertThat(again.path("lines")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick_line WHERE pick_id = ?", Integer.class,
                task)).as("three cartons planned, one replacement").isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_damage_report WHERE sku = ?",
                Integer.class, sku)).isEqualTo(1);
        assertThat(damagedInQuarantine()).isEqualTo(1);
        damage(task, lineId, picker, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-A", 2), true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_LINE_NOT_OPEN"));
    }

    @Test
    @DisplayName("only the assignee, in the task's warehouse, at the line's bin, an open line, a photo: else nothing")
    void refusals() throws Exception {
        cartons("HCM-A01-1-A", 2, 2, "L1", today.plusDays(10));
        OrderSummary order = releasedOrder(2);
        UUID task = startedTask(order);
        JsonNode lines = lines(task);
        String lineId = lines.get(0).path("lineId").asText();

        damage(task, lineId, otherPicker, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-A", 1), true)
                .andExpect(status().isNotFound());
        damage(task, lineId, pickerElsewhere, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-A", 1),
                true).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        damage(task, lineId, picker, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-A", 3), true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        damage(task, lineId, picker, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-B", 2), true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_LOCATION_MISMATCH"));
        damage(task, lineId, picker, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-A", 2), false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("DAMAGE_PHOTO_REQUIRED"));
        assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 4).containsEntry("reserved", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_damage_report WHERE sku = ?",
                Integer.class, sku)).isZero();

        // A picked line, and a task whose order was cancelled, take no damage report.
        confirm(task, lineId, "HCM-A01-1-A", 2).andExpect(status().isOk());
        damage(task, lineId, picker, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-A", 2), true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_LINE_NOT_OPEN"));
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(order.orderId(), "CUSTOMER_REQUEST"));
        damage(task, lineId, picker, "WAREHOUSE_STAFF", damageJson(UUID.randomUUID(), "HCM-A01-1-A", 2), true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PICK_TASK_STATE_CONFLICT"));
        assertThat(damagedInQuarantine()).isZero();
    }

    @Test
    @DisplayName("a confirmation and a damage report of one line at the same moment: one wins, the other is refused")
    void confirmationRacesDamage() throws Exception {
        cartons("HCM-A01-1-A", 3, 2, "L1", today.plusDays(10));
        OrderSummary order = releasedOrder(2);
        UUID task = startedTask(order);
        JsonNode line = lines(task).get(0);
        String lineId = line.path("lineId").asText();
        String confirmHeader = token(picker, "WAREHOUSE_STAFF");
        String damageHeader = token(picker, "WAREHOUSE_STAFF");
        String scan = "{\"locationCode\":\"HCM-A01-1-A\",\"scannedCode\":\"" + line.path("lpn").asText()
                + "\",\"quantity\":2}";
        // The whole carton: part of one is refused whatever the race, and would prove nothing.
        byte[] report = damageJson(UUID.randomUUID(), "HCM-A01-1-A", 2).getBytes(StandardCharsets.UTF_8);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            Future<Integer> confirming = pool.submit(() -> {
                go.await();
                return mvc.perform(post("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId
                                + "/confirmation").header("Authorization", confirmHeader)
                                .contentType("application/json").content(scan))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> damaging = pool.submit(() -> {
                go.await();
                return mvc.perform(multipart("/api/v1/fulfillment/tasks/" + task + "/pick-lines/" + lineId + "/damage")
                                .file(new MockMultipartFile("report", "", "application/json", report))
                                .file(new MockMultipartFile("photos", "broken.jpg", "image/jpeg", JPEG))
                                .header("Authorization", damageHeader))
                        .andReturn().getResponse().getStatus();
            });
            go.countDown();
            statuses.add(confirming.get(60, TimeUnit.SECONDS));
            statuses.add(damaging.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        String status = jdbc.queryForObject("SELECT status FROM fulfillment.pick_line WHERE id = ?::uuid", String.class,
                lineId);
        if (status.equals("PICKED")) {
            assertThat(damagedInQuarantine()).isZero();
            assertThat(bin("HCM-A01-1-A")).containsEntry("on_hand", 4).containsEntry("reserved", 0);
        } else {
            assertThat(status).isEqualTo("CANCELLED");
            assertThat(damagedInQuarantine()).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM inventory.stock_movement WHERE movement_type = 'PICK' AND reference_id = ?::uuid""",
                    Integer.class, lineId)).isZero();
        }
    }
}
