package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-287 PR EST: the advisory load estimate through the real filter chain, permission aspect, resolver, planner and
 * database (design v2 §2-§5; LP2-1 to LP2-5, LP2-10). A released order with a pallet-unit SKU (the default shipping
 * unit, with a remainder carton), a kit item (one unit = 2 same-size boxes) and an item never measured: blocked with
 * 422 naming that item; once measured, estimated into the viewer's Layout contract, repeatably, superseding the
 * previous estimate. Who may estimate is decided by permission codes and the warehouse scope alone.
 */
@SecuredIntegrationTest
class LoadEstimateIntegrationTest {

    private static final String BASE = "/api/v1/fulfillment/load-estimates";
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc http;
    private UUID coordinator;
    private UUID manager;
    private UUID managerElsewhere;
    private UUID staff;
    private UUID customer;
    private UUID container;

    private String palletSku;
    private String kitSku;
    private String bareSku;
    private UUID bareItem;
    private UUID order;
    private Map<String, UUID> lineOf;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        coordinator = user("ORDER_COORDINATOR");
        manager = user("WAREHOUSE_MANAGER", HCM);
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        managerElsewhere = user("WAREHOUSE_MANAGER", elsewhere);
        staff = user("WAREHOUSE_STAFF", HCM);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        container = tx.execute(s -> vehicleType("ACTIVE"));

        palletSku = tx.execute(s -> ReferenceRows.product(entityManager).sku());
        kitSku = tx.execute(s -> ReferenceRows.product(entityManager).sku());
        bareSku = tx.execute(s -> ReferenceRows.product(entityManager).sku());
        tx.executeWithoutResult(s -> {
            // The seeded LYG-1L-12OZ: a carton 52 x 42 x 60 cm, 6.5 kg shipped; 24 on a pallet of 181 kg, flagged.
            jdbc.update("""
                    UPDATE inventory.inventory_items SET weight_kg = 5.98, length_cm = 50, width_cm = 40, height_cm = 58,
                        package_weight_kg = 6.5, package_length_cm = 52, package_width_cm = 42, package_height_cm = 60
                    WHERE sku = ?""", palletSku);
            jdbc.update("""
                    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                        weight_kg, length_cm, width_cm, height_cm, default_purchase, active, default_shipping, created_by)
                    SELECT ?, i.id, 'PALLET', 24, FALSE, 181, 120, 100, 150, FALSE, TRUE, TRUE, 'test'
                    FROM inventory.inventory_items i WHERE i.sku = ?""", Identifiers.newId(), palletSku);
            // A table that ships as 2 boxes of 160 x 90 x 12 cm, 24 kg each (LP2-5).
            jdbc.update("""
                    UPDATE inventory.inventory_items SET weight_kg = 45, length_cm = 160, width_cm = 90, height_cm = 75,
                        package_count = 2, package_weight_kg = 24, package_length_cm = 160, package_width_cm = 90,
                        package_height_cm = 12
                    WHERE sku = ?""", kitSku);
        });
        bareItem = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class, bareSku);
        order = tx.execute(s -> order("READY_TO_FULFILL", Map.of(palletSku, 30, kitSku, 3, bareSku, 2)));
        lineOf = new HashMap<>();
        jdbc.query("SELECT sku, id FROM ordering.order_line WHERE order_id = ?",
                rs -> { lineOf.put(rs.getString(1), rs.getObject(2, UUID.class)); }, order);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private UUID otherWarehouse() {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id,
                "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT));
        return id;
    }

    private UUID user(String role, UUID... warehouses) {
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

    /** Its own 40-foot high cube, so no other test's edits of the presets reach this one. */
    private UUID vehicleType(String status) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO fulfillment.vehicle_type (id, code, name, kind, inner_length_mm, inner_width_mm,
                    inner_height_mm, gusset_mm, max_payload_kg, available_count, is_preset, status, created_at)
                VALUES (?, ?, 'Estimate test 40HC', 'CONTAINER', 12032, 2352, 2698, 150, 26500, 3, FALSE, ?, NOW())""",
                id, "EST" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT), status);
        return id;
    }

    /** A placed order in {@code status} with these lines; released to HCM when the status says it was. */
    private UUID order(String status, Map<String, Integer> lines) {
        UUID id = Identifiers.newId();
        boolean released = !Set.of("DRAFT", "PENDING_PAYMENT", "CONFIRMED", "ON_HOLD").contains(status);
        jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, payment_term, credit_term_days, released_at, released_by, warehouse_id,
                    shipped_at, hold_reason, version, created_at)
                VALUES (?, ?, ?, ?, ?, 1000, 'VND', NOW(), 'CREDIT', 30, ?, ?, ?, ?,
                        CASE WHEN ? = 'ON_HOLD' THEN 'CREDIT' END, 0, NOW())""",
                id, "SO-20261022-" + java.util.concurrent.ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L), customer, Identifiers.newId(), status,
                released ? java.sql.Timestamp.from(java.time.Instant.now()) : null, released ? coordinator : null,
                released ? HCM : null,
                status.equals("SHIPPED") ? java.sql.Timestamp.from(java.time.Instant.now()) : null, status);
        new java.util.TreeMap<>(lines).forEach((sku, quantity) -> jdbc.update("""
                INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                VALUES (?, ?, ?, ?, 1000, 'VND')""", Identifiers.newId(), id, sku, quantity));
        return id;
    }

    private void measureTheBareItem() {
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE inventory.inventory_items SET weight_kg = 1.2, length_cm = 30, width_cm = 20, height_cm = 10
                WHERE id = ?""", bareItem));
    }

    private String body(UUID source, String type, UUID... types) {
        return bodyFor(source, type, null, types);
    }

    private String bodyFor(UUID source, String type, UUID warehouse, UUID... types) {
        StringBuilder ids = new StringBuilder();
        for (UUID t : types) {
            ids.append(ids.isEmpty() ? "" : ",").append('"').append(t).append('"');
        }
        return """
                {"sources":[{"type":"%s","id":"%s"}],%s"vehicleTypeIds":[%s],"selectionMode":"SINGLE_TYPE"}"""
                .formatted(type, source, warehouse == null ? "" : "\"warehouseId\":\"" + warehouse + "\",", ids);
    }

    private ResultActions as(Role role, UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return http.asRole(role, user, request);
    }

    private ResultActions estimate(Role role, UUID user, String body) throws Exception {
        return as(role, user, post(BASE).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    // ---- tests ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("LP2-3: an item never measured blocks only its line: estimated without it, listed in missing")
    void missingPackagingBlocksOnlyThatLine() throws Exception {
        JsonNode estimate = data(estimate(Role.ORDER_COORDINATOR, coordinator, body(order, "ORDER", container))
                .andExpect(status().isCreated()));

        assertThat(estimate.path("incomplete").asBoolean()).isTrue();
        assertThat(estimate.path("missing")).hasSize(1);
        JsonNode missing = estimate.path("missing").get(0);
        assertThat(missing.path("orderLineId").asText()).isEqualTo(lineOf.get(bareSku).toString());
        assertThat(missing.path("sku").asText()).isEqualTo(bareSku);
        assertThat(missing.path("inventoryItemId").asText()).isEqualTo(bareItem.toString());
        assertThat(json.convertValue(missing.path("missingFields"), List.class))
                .containsExactly("lengthCm", "widthCm", "heightCm", "weightKg");
        assertThat(estimate.path("lines")).extracting(l -> l.path("sku").asText())
                .containsExactlyInAnyOrder(palletSku, kitSku);
        assertThat(estimate.path("vehicles").get(0).path("boxes")).hasSize(2 + 3 * 2);
        // Stored with the estimate: GET answers the same.
        as(Role.ORDER_COORDINATOR, coordinator, get(BASE + "?sourceType=ORDER&sourceId=" + order))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.incomplete").value(true))
                .andExpect(jsonPath("$.data.missing[0].inventoryItemId").value(bareItem.toString()));
        assertThat(jdbc.queryForObject("SELECT incomplete FROM fulfillment.load_estimate WHERE source_id = ?",
                Boolean.class, order)).isTrue();
    }

    @Test
    @DisplayName("LP2-3: when no line resolves, 422 naming each item; only the fields really missing; nothing stored")
    void noLineResolvesIsRefused() throws Exception {
        String weightless = tx.execute(s -> ReferenceRows.product(entityManager).sku());
        UUID weightlessItem = jdbc.queryForObject("SELECT id FROM inventory.inventory_items WHERE sku = ?", UUID.class,
                weightless);
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE inventory.inventory_items SET length_cm = 30, width_cm = 20, height_cm = 10, weight_kg = NULL
                WHERE id = ?""", weightlessItem));
        UUID unmeasured = tx.execute(s -> order("READY_TO_FULFILL", Map.of(bareSku, 2, weightless, 1)));

        estimate(Role.ORDER_COORDINATOR, coordinator, body(unmeasured, "ORDER", container))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("LOAD_ESTIMATE_PACKAGING_MISSING"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='inventoryItems[" + bareItem + "]')].code")
                        .value("PackagingMissing"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='inventoryItems[" + weightlessItem + "]')].message")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.endsWith(
                                "has no weightKg on its inventory item"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.load_estimate WHERE source_id = ?",
                Integer.class, unmeasured)).isZero();
    }

    @Test
    @DisplayName("only what is still to ship: units shipped (not returned) and closed out are not loaded again")
    void remainingUnitsOnly() throws Exception {
        measureTheBareItem();
        UUID partial = tx.execute(s -> order("PARTIALLY_FULFILLED", Map.of(palletSku, 30, kitSku, 3, bareSku, 2)));
        Map<String, UUID> lines = new HashMap<>();
        jdbc.query("SELECT sku, id FROM ordering.order_line WHERE order_id = ?",
                rs -> { lines.put(rs.getString(1), rs.getObject(2, UUID.class)); }, partial);
        // PACK-EARLY: 24 pallet units and every measured item went out; one returned shipment does not count.
        UUID shipped = com.stockflow.support.ShipmentRows.ready(jdbc, tx, partial, Identifiers.newId());
        UUID returned = com.stockflow.support.ShipmentRows.ready(jdbc, tx, partial, Identifiers.newId());
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at)
                    VALUES (?, ?, 'HANDED_OVER', 1000, NOW())""", shipped, partial);
            jdbc.update("""
                    INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, outcome_at)
                    VALUES (?, ?, 'RETURNED', 1000, NOW(), NOW())""", returned, partial);
            jdbc.update("INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity) VALUES (?, ?, 24)",
                    shipped, lines.get(palletSku));
            jdbc.update("INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity) VALUES (?, ?, 2)",
                    shipped, lines.get(bareSku));
            jdbc.update("INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity) VALUES (?, ?, 3)",
                    returned, lines.get(kitSku));
            // One table closed out of the order (#150).
            jdbc.update("UPDATE ordering.order_line SET cancelled_quantity = 1 WHERE id = ?", lines.get(kitSku));
        });

        JsonNode estimate = data(estimate(Role.ORDER_COORDINATOR, coordinator, body(partial, "ORDER", container))
                .andExpect(status().isCreated()));

        Map<String, JsonNode> bySku = new HashMap<>();
        estimate.path("lines").forEach(l -> bySku.put(l.path("sku").asText(), l));
        assertThat(bySku).containsOnlyKeys(palletSku, kitSku);
        assertThat(bySku.get(palletSku).path("quantity").asInt()).isEqualTo(6);
        assertThat(bySku.get(palletSku).path("cartons").asInt()).isEqualTo(1);
        assertThat(bySku.get(palletSku).path("partial").path("units").asInt()).isEqualTo(6);
        assertThat(bySku.get(kitSku).path("quantity").asInt()).isEqualTo(2);
        assertThat(estimate.path("vehicles").get(0).path("boxes")).hasSize(1 + 2 * 2);
        assertThat(estimate.path("incomplete").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("CONFIRMED: estimated in the release dialog for the warehouse it will go to, within the caller's scope")
    void confirmedOrderNeedsItsTargetWarehouse() throws Exception {
        measureTheBareItem();
        UUID confirmed = tx.execute(s -> order("CONFIRMED", Map.of(palletSku, 30)));
        estimate(Role.ORDER_COORDINATOR, coordinator, body(confirmed, "ORDER", container))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("warehouseId"));
        estimate(Role.ORDER_COORDINATOR, coordinator, bodyFor(confirmed, "ORDER", UUID.randomUUID(), container))
                .andExpect(status().isNotFound());
        estimate(Role.WAREHOUSE_MANAGER, managerElsewhere, bodyFor(confirmed, "ORDER", HCM, container))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));

        JsonNode estimate = data(estimate(Role.WAREHOUSE_MANAGER, manager, bodyFor(confirmed, "ORDER", HCM, container))
                .andExpect(status().isCreated()));
        assertThat(estimate.path("warehouseId").asText()).isEqualTo(HCM.toString());
        as(Role.WAREHOUSE_MANAGER, managerElsewhere, get(BASE + "/" + estimate.path("id").asText()))
                .andExpect(status().isForbidden());

        // A released order keeps its own warehouse.
        estimate(Role.ORDER_COORDINATOR, coordinator, bodyFor(order, "ORDER", UUID.randomUUID(), container))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].code").value("WarehouseMismatch"));
    }

    @Test
    @DisplayName("an order cancelled while it was planned: 409 under its row lock, and nothing is saved")
    void orderChangedDuringTheSolveIsNotSaved() throws Exception {
        measureTheBareItem();
        javax.sql.DataSource dataSource = jdbc.getDataSource();
        try (java.sql.Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT id FROM ordering.customer_order WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, order);
                lock.executeQuery();
            }
            java.util.concurrent.CompletableFuture<ResultActions> request = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return estimate(Role.ORDER_COORDINATOR, coordinator, body(order, "ORDER", container));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
            // The request has read and planned; it now waits for the order's row lock.
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            // (pg_stat_activity.query is cut at 1 kB, before the FROM of Hibernate's long select: look at the lock.)
            while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted AND pid <> pg_backend_pid()",
                    Integer.class) == 0) {
                if (request.isDone()) {
                    throw new AssertionError("the request did not wait for the order's lock: "
                            + request.join().andReturn().getResponse().getContentAsString());
                }
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(50);
            }
            try (var cancel = holder.prepareStatement("""
                    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason = 'test',
                        cancellation_reason_code = 'OTHER' WHERE id = ?""")) {
                cancel.setObject(1, order);
                cancel.executeUpdate();
            }
            holder.commit();
            request.get(30, java.util.concurrent.TimeUnit.SECONDS)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.load_estimate WHERE source_id = ?",
                Integer.class, order)).isZero();
    }

    @Test
    @DisplayName("pallet with a remainder, a kit of 2 boxes, a measured item: lines and the viewer's Layout")
    void estimatesIntoTheLayoutContract() throws Exception {
        measureTheBareItem();
        JsonNode estimate = data(estimate(Role.ORDER_COORDINATOR, coordinator, body(order, "ORDER", container))
                .andExpect(status().isCreated()));

        assertThat(estimate.path("status").asText()).isEqualTo("CURRENT");
        assertThat(estimate.path("sourceType").asText()).isEqualTo("ORDER");
        assertThat(estimate.path("sourceId").asText()).isEqualTo(order.toString());
        assertThat(estimate.path("warehouseId").asText()).isEqualTo(HCM.toString());
        assertThat(estimate.path("missing")).isEmpty();
        assertThat(estimate.path("incomplete").asBoolean()).isFalse();
        Map<String, JsonNode> lines = new HashMap<>();
        estimate.path("lines").forEach(l -> lines.put(l.path("sku").asText(), l));
        assertThat(lines).hasSize(3);

        JsonNode pallet = lines.get(palletSku);
        assertThat(pallet.path("orderLineId").asText()).isEqualTo(lineOf.get(palletSku).toString());
        assertThat(pallet.path("rule").asText()).isEqualTo("DEFAULT_SHIPPING_UNIT");
        assertThat(pallet.path("uom").asText()).isEqualTo("PALLET");
        assertThat(pallet.path("factor").asInt()).isEqualTo(24);
        assertThat(pallet.path("cartons").asInt()).isEqualTo(2);
        assertThat(pallet.path("partial").path("units").asInt()).isEqualTo(6);
        // LP2-4: 6 cartons of 6.5 kg plus the pallet's 25 kg of wood.
        assertThat(pallet.path("partial").path("weightKg").decimalValue()).isEqualByComparingTo("64");
        assertThat(pallet.path("dimsMm").path("l").asInt()).isEqualTo(1200);
        assertThat(pallet.path("dimsMm").path("w").asInt()).isEqualTo(1000);
        assertThat(pallet.path("dimsMm").path("h").asInt()).isEqualTo(1500);
        assertThat(pallet.path("weightKg").decimalValue()).isEqualByComparingTo("181");
        assertThat(pallet.path("kitParts").asInt()).isEqualTo(1);

        JsonNode kit = lines.get(kitSku);
        assertThat(kit.path("rule").asText()).isEqualTo("ITEM_BOX");
        assertThat(kit.path("kitParts").asInt()).isEqualTo(2);
        assertThat(kit.path("cartons").asInt()).isEqualTo(3);
        assertThat(kit.hasNonNull("partial")).isFalse();
        assertThat(lines.get(bareSku).path("rule").asText()).isEqualTo("ITEM_UNIT");

        assertThat(estimate.path("unplaced")).isEmpty();
        assertThat(estimate.path("vehicles")).hasSize(1);
        JsonNode vehicle = estimate.path("vehicles").get(0);
        assertThat(vehicle.path("seq").asInt()).isEqualTo(1);
        assertThat(vehicle.path("vehicleTypeId").asText()).isEqualTo(container.toString());
        assertThat(vehicle.path("innerMm").path("l").asInt()).isEqualTo(12032);
        assertThat(vehicle.path("maxPayloadKg").decimalValue()).isEqualByComparingTo("26500");
        assertThat(vehicle.path("gussets")).hasSize(2);
        JsonNode boxes = vehicle.path("boxes");
        assertThat(boxes).hasSize(2 + 3 * 2 + 2);
        Set<String> refs = new HashSet<>();
        Map<String, Integer> perKitGroup = new HashMap<>();
        for (JsonNode box : boxes) {
            refs.add(box.path("ref").asText());
            for (String field : List.of("xMm", "yMm", "zMm", "wMm", "hMm", "dMm", "label", "partName", "origMm",
                    "orientation", "rotatable", "weightKg")) {
                assertThat(box.has(field)).as(field).isTrue();
            }
            String line = box.path("sourceRef").asText();
            assertThat(box.path("ref").asText()).startsWith(line + "#");
            if (box.hasNonNull("kitGroup")) {
                assertThat(box.path("partName").asText()).isIn(kitSku + "/1", kitSku + "/2");
                assertThat(box.path("label").asText()).isEqualTo(kitSku);
                perKitGroup.merge(box.path("kitGroup").asText(), 1, Integer::sum);
            }
        }
        assertThat(refs).hasSize(10);
        assertThat(perKitGroup).hasSize(3).allSatisfy((group, parts) -> assertThat(parts).isEqualTo(2));
        assertThat(vehicle.path("usedWeightKg").decimalValue()).isEqualByComparingTo("391.4"); // 181+64+6*24+2*1.2
        JsonNode meta = estimate.path("meta");
        assertThat(meta.path("seed").asLong()).isZero();
        assertThat(meta.path("budgetExhausted").asBoolean()).isFalse();
        assertThat(meta.path("placedBoxes").asInt()).isEqualTo(10);

        // GET by id answers what the POST did.
        JsonNode read = data(as(Role.ORDER_COORDINATOR, coordinator, get(BASE + "/" + estimate.path("id").asText()))
                .andExpect(status().isOk()));
        assertThat(read).isEqualTo(estimate);
    }

    @Test
    @DisplayName("the fixed seed: estimating again gives the same layout, and supersedes the earlier estimate")
    void repeatableAndSuperseding() throws Exception {
        measureTheBareItem();
        JsonNode first = data(estimate(Role.ORDER_COORDINATOR, coordinator, body(order, "ORDER", container))
                .andExpect(status().isCreated()));
        JsonNode second = data(estimate(Role.WAREHOUSE_MANAGER, manager, body(order, "ORDER", container))
                .andExpect(status().isCreated()));

        assertThat(second.path("id")).isNotEqualTo(first.path("id"));
        assertThat(second.path("vehicles")).isEqualTo(first.path("vehicles"));
        assertThat(second.path("lines")).isEqualTo(first.path("lines"));

        as(Role.ORDER_COORDINATOR, coordinator, get(BASE + "/" + first.path("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUPERSEDED"));
        as(Role.ORDER_COORDINATOR, coordinator, get(BASE + "?sourceType=ORDER&sourceId=" + order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(second.path("id").asText()))
                .andExpect(jsonPath("$.data.status").value("CURRENT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.load_estimate WHERE source_id = ? AND "
                + "status = 'CURRENT'", Integer.class, order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.load_estimate WHERE source_id = ?",
                Integer.class, order)).isEqualTo(2);
    }

    @Test
    @DisplayName("the database keeps one current estimate per source")
    void oneCurrentPerSource() {
        measureTheBareItem();
        String insert = """
                INSERT INTO fulfillment.load_estimate (id, source_type, source_id, warehouse_id, mode, status, created_at)
                VALUES (?, 'ORDER', ?, ?, 'SINGLE_TYPE', 'CURRENT', NOW())""";
        tx.executeWithoutResult(s -> jdbc.update(insert, Identifiers.newId(), order, HCM));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                        jdbc.update(insert, Identifiers.newId(), order, HCM)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DRAFT, PENDING_PAYMENT, ON_HOLD, SHIPPED: 409; TRANSFER is reserved (400); an unknown id is 404")
    void eligibility() throws Exception {
        measureTheBareItem();
        for (String status : List.of("DRAFT", "PENDING_PAYMENT", "ON_HOLD", "SHIPPED")) {
            UUID refused = tx.execute(s -> order(status, Map.of(palletSku, 1)));
            estimate(Role.ORDER_COORDINATOR, coordinator, bodyFor(refused, "ORDER", HCM, container))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("LOAD_ESTIMATE_SOURCE_NOT_ELIGIBLE"));
        }
        UUID confirmed = tx.execute(s -> order("CONFIRMED", Map.of(palletSku, 1)));
        estimate(Role.ORDER_COORDINATOR, coordinator, body(UUID.randomUUID(), "TRANSFER", container))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("LOAD_ESTIMATE_SOURCE_UNSUPPORTED"));
        estimate(Role.ORDER_COORDINATOR, coordinator, body(UUID.randomUUID(), "ORDER", container))
                .andExpect(status().isNotFound());
        UUID retired = tx.execute(s -> vehicleType("INACTIVE"));
        estimate(Role.ORDER_COORDINATOR, coordinator, body(order, "ORDER", retired))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("VEHICLE_TYPE_INACTIVE"));
        as(Role.ORDER_COORDINATOR, coordinator, get(BASE + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LOAD_ESTIMATE_NOT_FOUND"));
        as(Role.ORDER_COORDINATOR, coordinator, get(BASE + "?sourceType=ORDER&sourceId=" + confirmed))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LOAD_ESTIMATE_NOT_FOUND"));
    }

    @Test
    @DisplayName("permission codes decide who estimates; a manager of another warehouse neither estimates nor reads")
    void permissionsAndScope() throws Exception {
        measureTheBareItem();
        String body = body(order, "ORDER", container);
        estimate(Role.WAREHOUSE_STAFF, staff, body).andExpect(status().isForbidden());
        estimate(Role.CUSTOMER, tx.execute(s -> ReferenceRows.user(entityManager)), body)
                .andExpect(status().isForbidden());
        estimate(Role.WAREHOUSE_MANAGER, managerElsewhere, body).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));

        String id = data(estimate(Role.WAREHOUSE_MANAGER, manager, body).andExpect(status().isCreated()))
                .path("id").asText();
        as(Role.WAREHOUSE_MANAGER, managerElsewhere, get(BASE + "/" + id)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        as(Role.WAREHOUSE_MANAGER, managerElsewhere, get(BASE + "?sourceType=ORDER&sourceId=" + order))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        as(Role.WAREHOUSE_STAFF, staff, get(BASE + "/" + id)).andExpect(status().isForbidden());
        as(Role.SYSTEM_ADMIN, tx.execute(s -> ReferenceRows.user(entityManager)), get(BASE + "/" + id))
                .andExpect(status().isOk());
    }
}
