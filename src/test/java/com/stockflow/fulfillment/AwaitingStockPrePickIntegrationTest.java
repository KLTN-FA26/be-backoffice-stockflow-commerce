package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.PartialShipments;
import com.stockflow.order.internal.service.ProducerService;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An order AWAITING_STOCK picked before its release (owner decision RTM-AWAIT-1118; kltn-docs 07 §4.1 steps 1-2, §4.3
 * steps 7-8, §6 edge cases; 08 §4.1 step 1, §4.4 step 8; 17 §4.4 steps 15-16, §4.5 step 18, BR-15, BR-29), end to end
 * through the real filter chain, permission aspect, warehouse scope and database: the order holds 2 of the 4 cartons of
 * its one delivery batch and waits for a production order for the other 2. The warehouse manager finds it among the
 * orders waiting for a task, picks its part held to the staging area (to dispatch only when Sales allowed a partial
 * shipment), and when the rest arrives the top-up releases the batch and adds the rest to the same task - which moves
 * to dispatch whole. With a partial shipment allowed, the part held is checked and shipped first, never before the
 * batch's window, and the rest follows on the same task and check session.
 *
 * <p>Every state change goes through the code (contract, payment, shipping warehouse, production order, picking, the
 * check station); only the cartons that "arrive" are written as rows, as {@link CustomerSkuRows#stock} writes every
 * test's stock, and the putaway's top-up of waiting orders is then called as the putaway calls it.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.upload-inspection.enabled=false", "stockflow.notification.email.customer.enabled=false"})
class AwaitingStockPrePickIntegrationTest {

    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String BASE = "/api/v1/fulfillment";
    private static final Duration ASYNC = Duration.ofSeconds(20);

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired ProductionOrderService productionOrders;
    @Autowired ProducerService producers;
    @Autowired PartialShipments partialShipments;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired com.stockflow.common.storage.FileStorage storage;

    private UUID manager;
    private UUID picker;
    private UUID coordinator;
    private ConfirmedOrders confirmedOrders;
    private CustomerSkuRows.Issued sku;

    @BeforeEach
    void setUp() {
        CartonStationSupport.giveCartonsASize(tx, jdbc);
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        confirmedOrders = new ConfirmedOrders(context);
        sku = issueSku();
    }

    // ---- fixtures ----------------------------------------------------------------------------

    /**
     * The customer's own SKU as the design approval leaves it - made to a design the customer really confirmed (a
     * production order is drawn up only for such a SKU, 17 §4.3) - with its logistics and a carton of 10 measured for
     * the label (08 BR-07). {@code AwaitingStockReceiptIntegrationTest}'s fixture.
     */
    private CustomerSkuRows.Issued issueSku() {
        ReferenceRows.ProductRow product = tx.execute(s -> ReferenceRows.approvedProduct(entityManager));
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.products SET tax_class = 'STANDARD', kind = 'CUSTOMIZABLE' WHERE id = ?",
                product.productId()));
        com.stockflow.support.ConfirmedDesigns.Design design;
        try {
            design = com.stockflow.support.ConfirmedDesigns.confirmedFor(storage, jdbc, tx, entityManager,
                    DemoData.CUSTOMER_ID, product.productId());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return tx.execute(s -> {
            UUID variant = com.stockflow.common.id.Identifiers.newId();
            String code = product.sku() + "-P" + variant.toString().substring(24, 28).toUpperCase(Locale.ROOT);
            jdbc.update("""
                    INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature,
                        position, version, created_at, owner_customer_id, design_snapshot_id, source_variant_id)
                    VALUES (?, ?, ?, 'Printed cup', 'ACTIVE', FALSE, ?, 1, 0, NOW(), ?, ?, ?)""",
                    variant, product.productId(), code, "OWNED-SKU:" + code, DemoData.CUSTOMER_ID,
                    design.snapshotId(), product.variantId());
            UUID item = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, code);
            jdbc.update("""
                    UPDATE inventory.inventory_items SET weight_kg = 0.02, length_cm = 9, width_cm = 9, height_cm = 12
                           WHERE id = ?""", item);
            UUID carton = com.stockflow.common.id.Identifiers.newId();
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                        weight_kg, length_cm, width_cm, height_cm, default_purchase, active, version, created_at,
                        default_shipping)
                    VALUES (?, ?, 'CARTON', 10, FALSE, 1.2, 60, 40, 35, FALSE, TRUE, 0, NOW(), TRUE)""", carton, item);
            return new CustomerSkuRows.Issued(code, variant, product.productId(), design.snapshotId(), carton, 10);
        });
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

    private ResultActions as(UUID user, String role, MockHttpServletRequestBuilder request) throws Exception {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return mvc.perform(request.header("Authorization", "Bearer " + value));
    }

    private ResultActions qlk(MockHttpServletRequestBuilder request) throws Exception {
        return as(manager, "WAREHOUSE_MANAGER", request);
    }

    private ResultActions staff(MockHttpServletRequestBuilder request) throws Exception {
        return as(picker, "WAREHOUSE_STAFF", request);
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    /**
     * A CONFIRMED order of 4 cartons in one delivery batch (its window opens today), paid on signing, shipping from HCM
     * where 2 cartons of its SKU are free: it holds those 2, and a production order for the other 2 is approved and
     * sent - AWAITING_STOCK (17 §4.2 step 5).
     */
    private UUID awaitingStockHoldingTwoOfFour() {
        return awaitingStockHoldingTwoOfFour(ConfirmedOrders.paidOnSigning());
    }

    /** The same, its contract paying as {@code plan} says; the installments on signing are paid. */
    private UUID awaitingStockHoldingTwoOfFour(List<com.stockflow.order.internal.service.SalesContracts.Installment> plan) {
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 2 * sku.unitsPerCarton());
        UUID orderId = confirmedOrders.pay(confirmedOrders.signed(DemoData.CUSTOMER_ID, plan,
                ConfirmedOrders.Line.cartons(sku, 4, 6_000_000))).orderId();
        ShippingWarehouseService.Coverage coverage = confirmedOrders.ship(orderId, HCM);
        assertThat(coverage.fullyHeld()).isFalse();
        Producer producer = producers.register("PP-" + UUID.randomUUID().toString().substring(0, 8)
                .toUpperCase(Locale.ROOT), new Producer.Details("Pre-pick workshop", null, null, null,
                Producer.Channel.PDF, 14, null));
        ProductionOrder draft = productionOrders.drawUp(orderId, 1, producer.id(), coordinator);
        ProductionOrder waived = productionOrders.edit(draft.id(), draft.version(), new ProductionOrder.Draft(
                draft.producerId(), draft.needByDate(), draft.unitPrice(), draft.note(), false,
                "The customer approved the same print on paper", null));
        ProductionOrder pending = productionOrders.submit(waived.id(), waived.version(), coordinator);
        assertThat(productionOrders.approve(pending.id(), pending.version(), manager).status())
                .isEqualTo(ProductionOrderStatus.SENT);
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_reservation
                 WHERE order_id = ? AND allocated_from_id IS NULL AND status = 'HELD'""", Long.class,
                orderId)).as("the 2 cartons free at HCM are held for it, by SKU").isPositive();
        return orderId;
    }

    /** The other 2 cartons arrive at HCM and are put away: the putaway tops up the orders waiting for the SKU. */
    private void theRestArrives(UUID orderId) {
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 2 * sku.unitsPerCarton());
        shippingWarehouses.topUpWaiting(sku.sku());
        assertThat(shippingWarehouses.coverage(orderId).fullyHeld()).as("the batch is held in full now").isTrue();
    }

    private OrderStatus orderStatus(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    private ResultActions createTask(UUID orderId, String destination) throws Exception {
        return qlk(body(post(BASE + "/tasks"), "{\"orderId\":\"" + orderId + "\",\"assignedUserId\":\"" + picker
                + "\",\"destination\":\"" + destination + "\"}"));
    }

    private List<String> candidates() throws Exception {
        List<String> ids = new ArrayList<>();
        data(qlk(get(BASE + "/task-candidates").param("warehouseId", HCM.toString()).param("size", "100"))
                .andExpect(status().isOk())).path("items").forEach(c -> ids.add(c.path("orderId").asText() + ":"
                + c.path("orderStatus").asText() + ":" + c.path("warehouseId").asText()));
        return ids;
    }

    /** Every OPEN line of the task scanned at its bin and LPN, then picking completed. */
    private String pickOpenLines(UUID task) throws Exception {
        staff(post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList("SELECT id, location_code, lpn FROM fulfillment.pick_line "
                + "WHERE pick_id = ? AND status = 'OPEN'", task)) {
            staff(body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/confirmation"),
                    "{\"locationCode\":\"" + line.get("location_code") + "\",\"lpn\":\"" + line.get("lpn") + "\"}"))
                    .andExpect(status().isOk());
        }
        return data(staff(post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk()))
                .path("pickStatus").asText();
    }

    private long lines(UUID task, String lineStatus) {
        return jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick_line WHERE pick_id = ? AND status = ?",
                Long.class, task, lineStatus);
    }

    private JsonNode task(UUID task) throws Exception {
        return data(qlk(get(BASE + "/tasks/" + task)).andExpect(status().isOk()));
    }

    private long releases(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.delivery_batch_release WHERE order_id = ?",
                Long.class, orderId);
    }

    // ---- 07 §4.1, §4.3, §6: staging, then the rest -------------------------------------------------------------

    @Test
    @DisplayName("07 G2/G6: an order awaiting stock is listed, its part held is picked to staging (not to dispatch), "
            + "stays there until the rest arrives; the top-up releases the batch and adds the rest to the same task, "
            + "which moves to dispatch whole and is checked in full")
    void prePickToStagingThenTopUp() throws Exception {
        UUID orderId = awaitingStockHoldingTwoOfFour();

        assertThat(candidates()).as("07 §4.1 step 1: listed by its shipping warehouse, awaiting stock")
                .contains(orderId + ":AWAITING_STOCK:" + HCM);
        createTask(orderId, "DISPATCH").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_AWAITING_STOCK_DESTINATION"));
        assertThat(releases(orderId)).isZero();

        UUID task = UUID.fromString(data(createTask(orderId, "STAGING").andExpect(status().isCreated()))
                .path("taskId").asText());
        assertThat(orderStatus(orderId)).as("07 §4.1 step 2: it stays AWAITING_STOCK").isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(releases(orderId)).as("the batch is not released while short").isZero();
        assertThat(lines(task, "OPEN")).as("one line per carton held").isEqualTo(2);
        assertThat(candidates()).as("it has its task now").noneMatch(c -> c.startsWith(orderId.toString()));
        qlk(body(put(BASE + "/tasks/" + task + "/destination"), "{\"destination\":\"DISPATCH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_AWAITING_STOCK_DESTINATION"));

        assertThat(pickOpenLines(task)).isEqualTo("STAGED");
        assertThat(lines(task, "STAGED")).isEqualTo(2);
        qlk(post(BASE + "/tasks/" + task + "/dispatch-transfer")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_AWAITING_STOCK_NOT_READY"));
        assertThat(task(task).path("topUpAvailable").asBoolean()).as("nothing more is held yet").isFalse();

        theRestArrives(orderId);
        assertThat(task(task).path("topUpAvailable").asBoolean()).as("07 §4.3 step 7: the rest is held").isTrue();

        JsonNode added = data(qlk(post(BASE + "/tasks/" + task + "/top-up")).andExpect(status().isOk()));
        assertThat(added).as("the rest joins the same task").hasSize(2);
        assertThat(releases(orderId)).as("17 §4.4 step 16: held in full, the batch is released").isEqualTo(1);
        assertThat(orderStatus(orderId)).as("07 §4.3 step 7: READY_TO_FULFILL, then PICKING with its task")
                .isEqualTo(OrderStatus.PICKING);
        assertThat(task(task).path("pickStatus").asText()).isEqualTo("PICKING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick WHERE order_id = ?", Long.class,
                orderId)).as("07 BR-07: one task").isEqualTo(1);

        assertThat(pickOpenLines(task)).isEqualTo("STAGED");
        assertThat(lines(task, "STAGED")).isEqualTo(4);
        assertThat(data(qlk(post(BASE + "/tasks/" + task + "/dispatch-transfer")).andExpect(status().isOk()))
                .path("pickStatus").asText()).as("07 §4.3 step 8: the whole task, once").isEqualTo("PICKED");

        UUID shipment = CartonStationSupport.ship(this::staff, jdbc, json, task, null);
        assertThat(shipment).isNotNull();
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.PACKED);
    }

    // ---- 08 §4.1 / 17 §4.5 step 18: the part held goes first ---------------------------------------------------

    @Test
    @DisplayName("08 G3: Sales allow a partial shipment - the part held, picked to staging, moves to dispatch, is "
            + "checked and shipped first (not before the batch's window, BR-29); the rest arrives, is topped up onto "
            + "the same task and check session and completes the order")
    void partOfItGoesFirst() throws Exception {
        UUID orderId = awaitingStockHoldingTwoOfFour();
        WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL, () -> partialShipments.decide(orderId, true,
                "The customer takes what is ready", confirmedOrders.sales().userId()));

        UUID task = UUID.fromString(data(createTask(orderId, "STAGING").andExpect(status().isCreated()))
                .path("taskId").asText());
        assertThat(pickOpenLines(task)).isEqualTo("STAGED");
        assertThat(data(qlk(post(BASE + "/tasks/" + task + "/dispatch-transfer")).andExpect(status().isOk()))
                .path("pickStatus").asText()).as("07 §4.3 step 8: Sales allowed a partial shipment")
                .isEqualTo("PICKED");
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);

        UUID first = CartonStationSupport.ship(this::staff, jdbc, json, task,
                "{\"partial\":true,\"note\":\"2 cartons now, 2 when produced\"}");
        assertThat(first).isNotNull();
        assertThat(orderStatus(orderId)).as("17 §5.1: Awaiting Stock → Partially Fulfilled")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(jdbc.queryForObject("SELECT p.status FROM fulfillment.pack p WHERE p.pick_id = ?", String.class,
                task)).as("08 §4.4 step 8: the session stays open for the rest").isEqualTo("IN_PROGRESS");

        Instant yesterday = Instant.now().minus(Duration.ofDays(1));
        assertThat(orders.handoverBlocks(List.of(orderId), yesterday)).as("BR-29: not before its window")
                .singleElement().satisfies(block -> assertThat(block.reasonCodes())
                        .contains("DELIVERY_BATCH_WINDOW_NOT_OPEN"));
        assertThat(orders.handoverBlocks(List.of(orderId), Instant.now())).as("its window opened today").isEmpty();

        theRestArrives(orderId);
        JsonNode added = data(qlk(post(BASE + "/tasks/" + task + "/top-up")).andExpect(status().isOk()));
        assertThat(added).hasSize(2);
        assertThat(releases(orderId)).isEqualTo(1);
        assertThat(orderStatus(orderId)).as("released, it stays PARTIALLY_FULFILLED").isEqualTo(OrderStatus
                .PARTIALLY_FULFILLED);
        assertThat(pickOpenLines(task)).as("07 §4.3 step 7: the rest goes straight to dispatch").isEqualTo("PICKED");

        UUID rest = CartonStationSupport.ship(this::staff, jdbc, json, task, null);
        assertThat(rest).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT p.status FROM fulfillment.pack p WHERE p.pick_id = ?", String.class,
                task)).isEqualTo("COMPLETED");
        assertThat(orderStatus(orderId)).as("nothing handed over yet: the rest packed makes it PACKED")
                .isEqualTo(OrderStatus.PACKED);
    }

    @Test
    @DisplayName("07 §4.1 step 2: with a partial shipment allowed, the part held may go straight to dispatch; the "
            + "check station takes it while the order awaits stock")
    void partialAllowedStraightToDispatch() throws Exception {
        UUID orderId = awaitingStockHoldingTwoOfFour();
        WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL, () -> partialShipments.decide(orderId, true,
                "The customer takes what is ready", confirmedOrders.sales().userId()));

        UUID task = UUID.fromString(data(createTask(orderId, "DISPATCH").andExpect(status().isCreated()))
                .path("taskId").asText());
        assertThat(pickOpenLines(task)).isEqualTo("PICKED");
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);
        CartonStationSupport.startAndScanAll(this::staff, jdbc, task);
        CartonStationSupport.labelAll(this::staff, task);
        assertThat(jdbc.queryForObject("SELECT first_labeled_at FROM ordering.customer_order WHERE id = ?",
                java.sql.Timestamp.class, orderId)).as("08 BR-04: labelled, the order is locked").isNotNull();
    }

    // ---- SCOPE-PARTIAL-1105: the installment before delivery falls due at the first delivery ready -------------

    /** Half on signing, half before delivery (tied to no batch). */
    private static List<com.stockflow.order.internal.service.SalesContracts.Installment> halfBeforeDelivery() {
        return List.of(
                new com.stockflow.order.internal.service.SalesContracts.Installment("On signing", "ON_SIGNING", null,
                        new java.math.BigDecimal("50"), 0, null),
                new com.stockflow.order.internal.service.SalesContracts.Installment("Before delivery",
                        "BEFORE_DELIVERY", null, new java.math.BigDecimal("50"), null, null));
    }

    private String beforeDelivery(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM payment.installment WHERE order_id = ? "
                + "AND milestone = 'BEFORE_DELIVERY'", String.class, orderId);
    }

    @Test
    @DisplayName("SCOPE-PARTIAL-1105 (15 §4.1 step 2): picked ahead to staging, the installment before delivery is not "
            + "due; the part held, allowed to go first, moves to dispatch - the first delivery is ready, it falls due")
    void beforeDeliveryFallsDueWhenThePartHeldMovesToDispatch() throws Exception {
        UUID orderId = awaitingStockHoldingTwoOfFour(halfBeforeDelivery());
        WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL, () -> partialShipments.decide(orderId, true,
                "The customer takes what is ready", confirmedOrders.sales().userId()));

        UUID task = UUID.fromString(data(createTask(orderId, "STAGING").andExpect(status().isCreated()))
                .path("taskId").asText());
        assertThat(pickOpenLines(task)).isEqualTo("STAGED");
        assertThat(beforeDelivery(orderId)).as("staged only: no delivery is ready yet").isEqualTo("NOT_DUE");

        qlk(post(BASE + "/tasks/" + task + "/dispatch-transfer")).andExpect(status().isOk());

        com.stockflow.support.Await.until("the installment before delivery to fall due", ASYNC,
                () -> "DUE".equals(beforeDelivery(orderId)));
        assertThat(orderStatus(orderId)).as("not released: it still awaits stock").isEqualTo(OrderStatus.AWAITING_STOCK);
        assertThat(releases(orderId)).isZero();
    }

    @Test
    @DisplayName("SCOPE-PARTIAL-1105: the part held, allowed to go first, picked straight to dispatch - due at once")
    void beforeDeliveryFallsDueWhenThePartHeldIsPickedToDispatch() throws Exception {
        UUID orderId = awaitingStockHoldingTwoOfFour(halfBeforeDelivery());
        WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL, () -> partialShipments.decide(orderId, true,
                "The customer takes what is ready", confirmedOrders.sales().userId()));
        assertThat(beforeDelivery(orderId)).isEqualTo("NOT_DUE");

        createTask(orderId, "DISPATCH").andExpect(status().isCreated());

        com.stockflow.support.Await.until("the installment before delivery to fall due", ASYNC,
                () -> "DUE".equals(beforeDelivery(orderId)));
        assertThat(orderStatus(orderId)).isEqualTo(OrderStatus.AWAITING_STOCK);
    }
}
