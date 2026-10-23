package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transfer orders through packing and load planning (SCRUM-287 PR T; kltn-docs 10 §4 step 5, §5, BR-04; 09 §4.2 step 1
 * and 5, BR-09, BR-14, BR-15; lead's decisions LP-9, LP2-1, LP2-8) through the real filter chain, permission aspect,
 * solver and database: a transfer packed as one kit and one unopened carton shipped as it is, planned on two trucks,
 * booked, loaded and signed one by one - IN_TRANSIT with the packed quantities at the last signature; customer orders
 * and transfers kept apart; picking not cancelled once goods left, nor while a vehicle loads; an estimate of a
 * transfer; guards and scope.
 *
 * <p>Shares {@link FtlDispatchIntegrationTest}'s context (the same configuration, one application context fewer for the
 * build's heap - CI ran out of it with one context per class); every fixture here is its own (SKUs, transfers,
 * cartons), and each test compares stock with what it was before.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.packing.dim-divisor=6000", "stockflow.fulfillment.load-planning.solver-budget=PT2S",
        "stockflow.test.context=ftl-dispatch"})
class TransferLoadPlanningIntegrationTest {

    private static final String SOFA = "SOFA-3S-GREY";
    private static final String SOFA_BARCODE = "8930000000011";
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String SHELF = "HCM-A02-1-A";
    private static final String BASE = "/api/v1/fulfillment";
    private static final String PACKS = BASE + "/transfer-packs";
    private static final String PLANS = BASE + "/load-plans";
    private static final String LOADS = BASE + "/loads";
    private static final String TRANSFERS = "/api/v1/inventory/transfer-orders";
    /** The sofa (kit part 1), 2150 x 950 x 900 mm, and its cushions (part 2), 950 x 900 x 400 mm. */
    private static final String SOFA_MEASURES = "{\"weightKg\":52.5,\"lengthCm\":215,\"widthCm\":95,\"heightCm\":90}";
    private static final String PART2_MEASURES = "{\"weightKg\":20,\"lengthCm\":95,\"widthCm\":90,\"heightCm\":40}";
    /** The carton as weighed and measured: too heavy to share a 1.25 t truck with the sofa. */
    private static final String CARTON_MEASURES =
            "{\"weightKg\":1195,\"lengthCm\":121,\"widthCm\":100,\"heightCm\":100}";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;

    private UUID packer;
    private UUID dispatcher;
    private UUID staffElsewhere;
    private UUID coordinator;
    private UUID approver;
    private UUID destination;
    private UUID truck1t25;
    private UUID ghn;
    private String cartonSku;
    private UUID cartonUnit;

    @BeforeEach
    void setUp() {
        packer = staff("WAREHOUSE_STAFF", HCM);
        dispatcher = staff("WAREHOUSE_STAFF", HCM);
        coordinator = staff("ORDER_COORDINATOR");
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        destination = tx.execute(s -> warehouse("Destination"));
        UUID elsewhere = tx.execute(s -> warehouse("Elsewhere"));
        staffElsewhere = staff("WAREHOUSE_STAFF", elsewhere);
        truck1t25 = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'",
                UUID.class);
        ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        // A SKU received by the carton of 10: the carton unit says 120 x 100 x 100 cm, 1200 kg (LP2-8).
        cartonSku = "TCTN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        cartonUnit = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, cartonSku,
                    "Carton fixture", cartonSku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, cartonSku, "Carton fixture");
            jdbc.update("update inventory.inventory_items set removal_strategy='FIFO', policy_configured=true,"
                    + " weight_kg=110, length_cm=50, width_cm=40, height_cm=40 where sku=?", cartonSku);
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class,
                    cartonSku);
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor,"
                    + " weight_kg, length_cm, width_cm, height_cm) values (?, ?, 'CTN10', 10, 1200, 120, 100, 100)",
                    cartonUnit, item);
        });
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private UUID warehouse(String name) {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, ?, 'ACTIVE', ?, '1 Destination Road', 'M', 50, 50, 0, NOW())""", id, name,
                "T" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT));
        return id;
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

    private ResultActions packer(MockHttpServletRequestBuilder request) throws Exception {
        return as(packer, "WAREHOUSE_STAFF", request);
    }

    private ResultActions dispatch(MockHttpServletRequestBuilder request) throws Exception {
        return as(dispatcher, "WAREHOUSE_STAFF", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    /** Loose sofas on the shelf, and one unopened carton of 10 of the carton SKU (#129) plus 5 loose ones before it. */
    private String stock() {
        String lpn = "LPN-" + cartonSku + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                        created_at, received_at)
                    VALUES (?, ?, ?, 1, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '400 days')""",
                    Identifiers.newId(), SOFA, SHELF);
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class,
                    cartonSku);
            UUID carton = Identifiers.newId();
            jdbc.update("""
                    insert into inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty,
                        received_qty, location_code, status) values (?, ?, ?, ?, 10, 10, ?, 'IN_STOCK')""",
                    carton, lpn, item, cartonUnit, SHELF);
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, received_at, on_hand, reserved, status,
                        version, created_at, stock_package_id)
                    values (?, ?, ?, now() - interval '10 days', 10, 0, 'AVAILABLE', 0, now(), ?)""",
                    Identifiers.newId(), cartonSku, SHELF, carton);
            // Received earlier: FIFO alone would take these first - the carton shipped as it is must give its own.
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, received_at, on_hand, reserved, status,
                        version, created_at)
                    values (?, ?, 'HCM-A01-1-B', now() - interval '300 days', 5, 0, 'AVAILABLE', 0, now())""",
                    Identifiers.newId(), cartonSku);
        });
        return lpn;
    }

    /** An APPROVED transfer from HCM: one sofa (a kit of 2) and 10 of the carton SKU; FULL_TRUCKLOAD unless named. */
    private UUID approvedTransfer(String deliveryMode) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO inventory.transfer_order (id, transfer_number, from_warehouse_id, to_warehouse_id,
                        status, reason, approved_by, approved_at, delivery_mode, version, created_at)
                    VALUES (?, ?, ?, ?, 'APPROVED', 'REBALANCING', ?, NOW(), ?, 0, NOW())""", id,
                    "TO-20991231-%04d".formatted(ThreadLocalRandom.current().nextInt(10_000)), HCM, destination,
                    approver, deliveryMode);
            jdbc.update("""
                    INSERT INTO inventory.transfer_order_line (id, transfer_order_id, line_no, sku, requested_qty)
                    VALUES (?, ?, 1, ?, 1), (?, ?, 2, ?, 10)""", Identifiers.newId(), id, SOFA, Identifiers.newId(), id,
                    cartonSku);
        });
        return id;
    }

    private UUID lineOf(UUID transfer, int lineNo) {
        return jdbc.queryForObject("SELECT id FROM inventory.transfer_order_line WHERE transfer_order_id = ? AND "
                + "line_no = ?", UUID.class, transfer, lineNo);
    }

    private String transferStatus(UUID transfer) {
        return jdbc.queryForObject("SELECT status FROM inventory.transfer_order WHERE id = ?", String.class, transfer);
    }

    /** Packed at HCM: the sofa as a kit of two parcels, the carton as it is. Returns the packing session. */
    private JsonNode packed(UUID transfer, String lpn) throws Exception {
        JsonNode session = data(packer(body(post(PACKS), "{\"transferOrderId\":\"" + transfer + "\"}"))
                .andExpect(status().isCreated()));
        String pack = session.path("packId").asText();
        JsonNode kit = data(packer(body(post(PACKS + "/" + pack + "/package-kits"), "{\"transferLineId\":\""
                + lineOf(transfer, 1) + "\"}")).andExpect(status().isCreated()));
        String part1 = kit.path("packages").get(0).path("packageId").asText();
        String part2 = kit.path("packages").get(1).path("packageId").asText();
        packer(body(post(PACKS + "/" + pack + "/packages/" + part1 + "/scans"), "{\"scanId\":\"" + UUID.randomUUID()
                + "\",\"scannedCode\":\"" + SOFA_BARCODE + "\",\"quantity\":1}")).andExpect(status().isOk());
        packer(body(post(PACKS + "/" + pack + "/packages/" + part1 + "/seal"), SOFA_MEASURES))
                .andExpect(status().isOk());
        packer(body(post(PACKS + "/" + pack + "/packages/" + part2 + "/seal"), PART2_MEASURES))
                .andExpect(status().isOk());
        if (lpn == null) {
            // Only the sofa: the carton SKU stays behind, said so (10 step 8 "chênh lệch").
            return data(packer(body(post(PACKS + "/" + pack + "/completion"),
                    "{\"partial\":true,\"note\":\"carton not ready\"}")).andExpect(status().isOk()));
        }
        JsonNode carton = data(packer(body(post(PACKS + "/" + pack + "/cartons"), "{\"lpn\":\"" + lpn + "\"}"))
                .andExpect(status().isCreated()));
        packer(body(post(PACKS + "/" + pack + "/packages/" + carton.path("parcel").path("packageId").asText()
                + "/seal"), CARTON_MEASURES)).andExpect(status().isOk());
        return data(packer(post(PACKS + "/" + pack + "/completion")).andExpect(status().isOk()));
    }

    private JsonNode plan(UUID transfer) throws Exception {
        return data(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"sourceType\":\"TRANSFER\",\"transferOrderIds\":[\"" + transfer + "\"],\"vehicleTypeIds\":[\""
                + truck1t25 + "\"]}")).andExpect(status().isCreated()));
    }

    private JsonNode step(JsonNode plan, String path, String content) throws Exception {
        return data(dispatch(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + path), content))
                .andExpect(status().isOk()));
    }

    private static String version(JsonNode plan) {
        return "{\"expectedVersion\":" + plan.path("version").asLong() + "}";
    }

    private JsonNode confirmed(UUID transfer) throws Exception {
        JsonNode plan = plan(transfer);
        plan = step(plan, "/calculation", version(plan));
        assertThat(plan.path("unplacedCount").asInt()).isZero();
        plan = step(plan, "/confirmation", version(plan));
        assertThat(plan.path("status").asText()).isEqualTo("CONFIRMED");
        return plan;
    }

    private static String loadOf(JsonNode plan, int seq) {
        for (JsonNode v : plan.path("vehicles")) {
            if (v.path("seq").asInt() == seq) {
                return v.path("load").path("loadId").asText();
            }
        }
        throw new AssertionError("no vehicle " + seq);
    }

    private JsonNode load(String loadId) throws Exception {
        return data(dispatch(get(LOADS + "/" + loadId)).andExpect(status().isOk()));
    }

    private void bookAndLoad(String loadId, String plate) throws Exception {
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + ghn + "\",\"vehiclePlate\":\""
                + plate + "\"}")).andExpect(status().isOk());
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
    }

    private void scanAll(String loadId) throws Exception {
        for (JsonNode line : load(loadId).path("shipments")) {
            for (JsonNode parcel : line.path("packages")) {
                dispatch(body(post(LOADS + "/" + loadId + "/scans"), "{\"scanId\":\"" + UUID.randomUUID()
                        + "\",\"packageCode\":\"" + parcel.path("labelCode").asText() + "\"}"))
                        .andExpect(status().isOk());
            }
        }
    }

    private ResultActions handOver(String loadId) throws Exception {
        long version = load(loadId).path("version").asLong();
        return dispatch(body(post(LOADS + "/" + loadId + "/handover"), "{\"version\":" + version
                + ",\"carrierRepresentative\":\"Nguyen Van Tai\",\"signed\":true}"));
    }

    private int onHand(String sku) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(on_hand), 0) FROM inventory.stock_item WHERE sku = ? AND "
                + "location_code LIKE 'HCM-%'", Integer.class, sku);
    }

    // ---- the whole flow ----------------------------------------------------------------------

    @Test
    @DisplayName("10 step 5: a transfer packed as one kit and one carton as it is (LP2-8) is a candidate whole; planned "
            + "on two 1.25 t trucks, booked, loaded and signed - vehicle 1 records goods left (picking no longer "
            + "cancelled, BR-04), vehicle 2 dispatches it: IN_TRANSIT with the packed quantities, the carton's own stock "
            + "taken, the plan DISPATCHED")
    void packedPlannedLoadedInTransit() throws Exception {
        String lpn = stock();
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        int sofasBefore = onHand(SOFA);
        int cartonSkuBefore = onHand(cartonSku);

        // Opening the session starts picking (10 §5 Approved -> Picking); the sofa's line suggests a kit.
        JsonNode opened = data(packer(body(post(PACKS), "{\"transferOrderId\":\"" + transfer + "\"}"))
                .andExpect(status().isCreated()));
        assertThat(transferStatus(transfer)).isEqualTo("PICKING");
        assertThat(opened.path("deliveryMode").asText()).isEqualTo("FULL_TRUCKLOAD");
        assertThat(opened.path("lines")).hasSize(2);
        assertThat(opened.path("lines").get(0).path("kitSuggested").asBoolean()).isTrue();
        // Nothing packed yet: completing is refused (08 BR-01).
        packer(post(PACKS + "/" + opened.path("packId").asText() + "/completion")).andExpect(status().isConflict());

        JsonNode session = packed(transfer, lpn);
        assertThat(session.path("packStatus").asText()).isEqualTo("PACKED");
        assertThat(session.path("packages")).hasSize(3);
        session.path("packages").forEach(p -> assertThat(p.has("labelOutdated")
                && !p.path("labelOutdated").asBoolean()).isTrue());
        JsonNode cartonParcel = null;
        for (JsonNode p : session.path("packages")) {
            if (!p.path("stockPackageId").isNull() && !p.path("stockPackageId").isMissingNode()) {
                cartonParcel = p;
            }
        }
        assertThat(cartonParcel).isNotNull();
        assertThat(cartonParcel.path("contents").get(0).path("quantity").asInt()).isEqualTo(10);
        assertThat(cartonParcel.path("weightKg").decimalValue()).isEqualByComparingTo("1195");
        assertThat(jdbc.queryForObject("SELECT packed_at IS NOT NULL FROM inventory.transfer_order WHERE id = ?",
                Boolean.class, transfer)).isTrue();
        // The packages are the transfer's, never a shipment's.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.package WHERE transfer_order_id = ? AND "
                + "shipment_id IS NULL AND status = 'SEALED'", Integer.class, transfer)).isEqualTo(3);

        // A candidate of its source warehouse, whole.
        JsonNode candidates = data(dispatch(get(BASE + "/load-plan-candidates").param("warehouseId", HCM.toString())
                .param("sourceType", "TRANSFER")).andExpect(status().isOk()));
        JsonNode mine = null;
        for (JsonNode c : candidates) {
            if (c.path("transferOrderId").asText().equals(transfer.toString())) {
                mine = c;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.path("sourceType").asText()).isEqualTo("TRANSFER");
        assertThat(mine.path("packageCount").asInt()).isEqualTo(3);
        assertThat(mine.path("toWarehouseId").asText()).isEqualTo(destination.toString());

        // Planned on two trucks: the carton and the kit do not share a 1.25 t payload.
        JsonNode plan = confirmed(transfer);
        assertThat(plan.path("sourceType").asText()).isEqualTo("TRANSFER");
        assertThat(plan.path("transferOrder").path("transferOrderId").asText()).isEqualTo(transfer.toString());
        assertThat(plan.path("shipments")).isEmpty();
        assertThat(plan.path("vehicles")).hasSize(2);
        String v1 = loadOf(plan, 1);
        String v2 = loadOf(plan, 2);
        JsonNode first = load(v1);
        assertThat(first.path("shipments")).singleElement().satisfies(line -> {
            assertThat(line.path("transferOrderId").asText()).isEqualTo(transfer.toString());
            assertThat(line.path("shipmentId").isNull() || line.path("shipmentId").isMissingNode()).isTrue();
        });
        assertThat(first.path("codTotal").decimalValue()).isEqualByComparingTo("0");
        // #165 on a TRANSFER plan's vehicles: each parcel carries its label flag; the loads list finds them by mode
        // and by the plan's code.
        first.path("shipments").get(0).path("packages").forEach(p -> {
            assertThat(p.path("labelOutdated").asBoolean(true)).isFalse();
            assertThat(p.has("labelOutdated")).isTrue();
        });
        JsonNode byPlan = data(dispatch(get(LOADS).param("mode", "FULL_TRUCKLOAD")
                .param("search", plan.path("code").asText())).andExpect(status().isOk()));
        List<String> found = new ArrayList<>();
        byPlan.path("items").forEach(r -> found.add(r.path("loadId").asText()));
        assertThat(found).containsExactlyInAnyOrder(v1, v2);
        assertThat(data(dispatch(get(LOADS).param("mode", "PARCEL").param("search", plan.path("code").asText()))
                .andExpect(status().isOk())).path("items")).isEmpty();
        JsonNode manifestV1 = data(dispatch(get(LOADS + "/" + v1 + "/manifest")).andExpect(status().isOk()));
        manifestV1.path("shipments").get(0).path("packages").forEach(p ->
                assertThat(p.has("labelOutdated") && !p.path("labelOutdated").asBoolean()).isTrue());

        // Packed for a load plan: no manual dispatch (its last vehicle dispatches it).
        String line1 = lineOf(transfer, 1).toString();
        dispatch(body(post(TRANSFERS + "/" + transfer + "/dispatch"), "{\"lines\":[{\"lineId\":\"" + line1
                + "\",\"shippedQuantity\":1}]}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_DISPATCH_BY_LOAD_PLAN"));

        // Vehicle 1 leaves: goods left, the transfer still being picked, nothing taken from stock yet.
        bookAndLoad(v1, "51C-111.11");
        scanAll(v1);
        handOver(v1).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"));
        assertThat(transferStatus(transfer)).isEqualTo("PICKING");
        assertThat(jdbc.queryForObject("SELECT goods_left_at IS NOT NULL FROM inventory.transfer_order WHERE id = ?",
                Boolean.class, transfer)).isTrue();
        assertThat(onHand(SOFA)).isEqualTo(sofasBefore);
        // 10 BR-04: once goods left, picking is no longer cancelled.
        dispatch(post(TRANSFERS + "/" + transfer + "/picking-cancellation")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_GOODS_LEFT"));

        // Vehicle 2, the last: IN_TRANSIT with the packed quantities; the carton gave its own stock.
        bookAndLoad(v2, "51C-222.22");
        JsonNode manifest = data(dispatch(get(LOADS + "/" + v2 + "/manifest")).andExpect(status().isOk()));
        assertThat(manifest.path("shipments").get(0).path("recipientName").asText()).isEqualTo("Destination");
        assertThat(manifest.path("codTotal").decimalValue()).isEqualByComparingTo("0");
        scanAll(v2);
        handOver(v2).andExpect(status().isOk());
        assertThat(transferStatus(transfer)).isEqualTo("IN_TRANSIT");
        Map<String, Object> shipped = new java.util.HashMap<>();
        jdbc.queryForList("SELECT sku, shipped_qty FROM inventory.transfer_order_line WHERE transfer_order_id = ?",
                transfer).forEach(row -> shipped.put((String) row.get("sku"), row.get("shipped_qty")));
        assertThat(shipped).containsEntry(SOFA, 1).containsEntry(cartonSku, 10);
        assertThat(onHand(SOFA)).isEqualTo(sofasBefore - 1);
        assertThat(onHand(cartonSku)).isEqualTo(cartonSkuBefore - 10);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class, lpn))
                .isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("SELECT on_hand FROM inventory.stock_item WHERE sku = ? AND location_code = "
                + "'HCM-A01-1-B'", Integer.class, cartonSku)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.load_plan WHERE id = ?::uuid", String.class,
                plan.path("loadPlanId").asText())).isEqualTo("DISPATCHED");
    }

    // ---- one source per plan -----------------------------------------------------------------

    @Test
    @DisplayName("09 §3 / §4.2 step 1: a TRANSFER plan takes one packed transfer and no shipment, an ORDER plan no "
            + "transfer (LOAD_PLAN_SOURCE_MISMATCH); a transfer not packed is TRANSFER_NOT_PACKED; the database "
            + "refuses a parcel of a shipment and a transfer at once")
    void oneSourcePerPlan() throws Exception {
        String lpn = stock();
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        UUID unpacked = approvedTransfer("FULL_TRUCKLOAD");
        packed(transfer, lpn);

        dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"transferOrderIds\":[\"" + transfer + "\"]}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_SOURCE_MISMATCH"));
        dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"sourceType\":\"TRANSFER\",\"shipmentIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_SOURCE_MISMATCH"));
        dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"sourceType\":\"TRANSFER\",\"transferOrderIds\":[\"" + unpacked + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("TRANSFER_NOT_PACKED"));

        // An ORDER plan's inputs refuse a transfer.
        JsonNode orderPlan = data(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\"}")).andExpect(status().isCreated()));
        assertThat(orderPlan.path("sourceType").asText()).isEqualTo("ORDER");
        dispatch(body(put(PLANS + "/" + orderPlan.path("loadPlanId").asText() + "/inputs"), "{\"expectedVersion\":"
                + orderPlan.path("version").asLong() + ",\"vehicleTypeIds\":[\"" + truck1t25
                + "\"],\"transferOrderIds\":[\"" + transfer + "\"]}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_SOURCE_MISMATCH"));

        // The tables say it again (V20261023000400).
        UUID parcel = jdbc.queryForObject("SELECT id FROM fulfillment.package WHERE transfer_order_id = ? LIMIT 1",
                UUID.class, transfer);
        List<UUID> shipments = jdbc.queryForList("SELECT id FROM fulfillment.shipment LIMIT 1", UUID.class);
        if (!shipments.isEmpty()) {
            UUID anyShipment = shipments.getFirst();
            Throwable both = catchThrowable(() -> jdbc.update("UPDATE fulfillment.package SET shipment_id = ? WHERE "
                    + "id = ?", anyShipment, parcel));
            assertThat(both).hasMessageContaining("ck_package_owner");
        }
        Throwable orderPlanRow = catchThrowable(() -> jdbc.update("""
                INSERT INTO fulfillment.load_plan_package (id, load_plan_id, package_id, transfer_order_id, added_at)
                VALUES (gen_random_uuid(), ?::uuid, ?, ?, NOW())""", orderPlan.path("loadPlanId").asText(), parcel,
                transfer));
        assertThat(orderPlanRow).hasMessageContaining("tg_load_plan_package_check");
    }

    // ---- picking cancelled ---------------------------------------------------------------------

    @Test
    @DisplayName("10 §5 Picking -> Approved before anything left: refused while a vehicle loads "
            + "(LOAD_PLAN_LOADING_STARTED, 09 BR-14); before loading, the plan and its booked vehicles are cancelled, "
            + "the parcels voided, the carton free again and the transfer APPROVED, unpacked")
    void pickingCancelled() throws Exception {
        String lpnA = stock();
        UUID loading = approvedTransfer("FULL_TRUCKLOAD");
        packed(loading, lpnA);
        JsonNode planA = confirmed(loading);
        assertThat(holds(loading, "HELD")).isEqualTo(11);
        bookAndLoad(loadOf(planA, 1), "51C-333.33");
        dispatch(post(TRANSFERS + "/" + loading + "/picking-cancellation")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_LOADING_STARTED"));
        assertThat(transferStatus(loading)).isEqualTo("PICKING");

        String lpnB = stock();
        UUID booked = approvedTransfer("FULL_TRUCKLOAD");
        int cartonAtpBefore = atp(cartonSku);
        int sofaAtpBefore = atp(SOFA);
        packed(booked, lpnB);
        assertThat(atp(cartonSku)).isEqualTo(cartonAtpBefore - 10);
        JsonNode planB = confirmed(booked);
        String bookedLoad = loadOf(planB, 1);
        dispatch(body(post(LOADS + "/" + bookedLoad + "/booking"), "{\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isOk());
        JsonNode cancelled = data(dispatch(post(TRANSFERS + "/" + booked + "/picking-cancellation"))
                .andExpect(status().isOk()));
        assertThat(cancelled.path("status").asText()).isEqualTo("APPROVED");
        assertThat(cancelled.path("packedAt").isNull() || cancelled.path("packedAt").isMissingNode()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.load_plan WHERE id = ?::uuid", String.class,
                planB.path("loadPlanId").asText())).isEqualTo("CANCELLED");
        assertThat(load(bookedLoad).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(load(bookedLoad).path("carrierBookingToCancel").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.package WHERE transfer_order_id = ? AND "
                + "status <> 'VOIDED'", Integer.class, booked)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE transfer_order_id = ?",
                String.class, booked)).isEqualTo("CANCELLED");
        // Its allocation is released: the source's units are free again (10 BR-02).
        assertThat(holds(booked, "HELD")).isZero();
        assertThat(holds(booked, "RELEASED")).isGreaterThanOrEqualTo(11);
        // ... exactly what it was before the transfer held anything.
        assertThat(atp(cartonSku)).isEqualTo(cartonAtpBefore);
        assertThat(atp(SOFA)).isEqualTo(sofaAtpBefore);
        // Packed again from scratch: the carton ships as it is again.
        JsonNode again = packed(booked, lpnB);
        assertThat(again.path("packStatus").asText()).isEqualTo("PACKED");
    }

    // ---- the estimate ---------------------------------------------------------------------------

    @Test
    @DisplayName("#160 for a transfer: its lines estimated at its source warehouse (sourceType TRANSFER), each line's "
            + "carton from the resolver - the sofa a kit of 2 boxes, the carton SKU by its 10-unit carton")
    void estimateOfATransfer() throws Exception {
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        JsonNode estimate = data(as(coordinator, "ORDER_COORDINATOR", body(post(BASE + "/load-estimates"),
                "{\"sources\":[{\"type\":\"TRANSFER\",\"id\":\"" + transfer + "\"}],\"vehicleTypeIds\":[\""
                        + truck1t25 + "\"]}")).andExpect(status().isCreated()));
        assertThat(estimate.path("sourceType").asText()).isEqualTo("TRANSFER");
        assertThat(estimate.path("sourceId").asText()).isEqualTo(transfer.toString());
        assertThat(estimate.path("warehouseId").asText()).isEqualTo(HCM.toString());
        List<String> lines = new ArrayList<>();
        estimate.path("lines").forEach(l -> lines.add(l.path("orderLineId").asText()));
        assertThat(lines).containsExactlyInAnyOrder(lineOf(transfer, 1).toString(), lineOf(transfer, 2).toString());
        for (JsonNode l : estimate.path("lines")) {
            if (l.path("sku").asText().equals(SOFA)) {
                assertThat(l.path("kitParts").asInt()).isEqualTo(2);
            } else {
                assertThat(l.path("uom").asText()).isEqualTo("CTN10");
                assertThat(l.path("cartons").asLong()).isEqualTo(1);
            }
        }
        assertThat(estimate.path("vehicles")).isNotEmpty();
    }

    // ---- guards ----------------------------------------------------------------------------------

    @Test
    @DisplayName("guards: a PARCEL transfer is not packed for a load plan; staff of another warehouse get "
            + "OUT_OF_DATA_SCOPE; the coordinator may not pack (403); a carton of another SKU, or one opened, is "
            + "STOCK_CARTON_NOT_SHIPPABLE; the same carton twice is refused")
    void guards() throws Exception {
        UUID parcel = approvedTransfer("PARCEL");
        packer(body(post(PACKS), "{\"transferOrderId\":\"" + parcel + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_NOT_FULL_TRUCKLOAD"));
        assertThat(transferStatus(parcel)).isEqualTo("APPROVED");

        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        as(staffElsewhere, "WAREHOUSE_STAFF", body(post(PACKS), "{\"transferOrderId\":\"" + transfer + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        as(coordinator, "ORDER_COORDINATOR", body(post(PACKS), "{\"transferOrderId\":\"" + transfer + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(transferStatus(transfer)).isEqualTo("APPROVED");

        String lpn = stock();
        String pack = data(packer(body(post(PACKS), "{\"transferOrderId\":\"" + transfer + "\"}"))
                .andExpect(status().isCreated())).path("packId").asText();
        packer(body(post(PACKS + "/" + pack + "/cartons"), "{\"lpn\":\"NO-SUCH-LPN\"}"))
                .andExpect(status().isNotFound());
        String cartonParcel = data(packer(body(post(PACKS + "/" + pack + "/cartons"), "{\"lpn\":\"" + lpn + "\"}"))
                .andExpect(status().isCreated())).path("parcel").path("packageId").asText();
        assertThat(cartonRowHeldBy(transfer, lpn)).isEqualTo(10);
        packer(body(post(PACKS + "/" + pack + "/cartons"), "{\"lpn\":\"" + lpn + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_CARTON_NOT_SHIPPABLE"));
        // Taken apart: the line's units are held again from its stock, the carton free to ship as it is again.
        packer(delete(PACKS + "/" + pack + "/packages/" + cartonParcel)).andExpect(status().isOk());
        assertThat(holds(transfer, "HELD")).isEqualTo(11);
        packer(body(post(PACKS + "/" + pack + "/cartons"), "{\"lpn\":\"" + lpn + "\"}"))
                .andExpect(status().isCreated());
        assertThat(cartonRowHeldBy(transfer, lpn)).isEqualTo(10);
        String opened = stock();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.stock_package SET unopened = FALSE WHERE lpn = ?",
                opened));
        packer(body(post(PACKS + "/" + pack + "/cartons"), "{\"lpn\":\"" + opened + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_CARTON_NOT_SHIPPABLE"));
        // Completing with the sofa missing: everything requested, or a partial with a note.
        packer(post(PACKS + "/" + pack + "/completion")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_SEALED"));
    }

    private int holds(UUID transfer, String status) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_reservation WHERE "
                + "transfer_order_id = ? AND status = ?", Integer.class, transfer, status);
    }

    private int atp(String sku) {
        return tx.execute(s -> inventory.availableToPromise(new com.stockflow.common.domain.Sku(sku)));
    }

    private int cartonRowHeldBy(UUID transfer, String lpn) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(r.quantity), 0) FROM inventory.stock_reservation r JOIN "
                + "inventory.stock_item s ON s.id = r.stock_item_id JOIN inventory.stock_package p ON p.id = "
                + "s.stock_package_id WHERE r.transfer_order_id = ? AND r.status = 'HELD' AND p.lpn = ?",
                Integer.class, transfer, lpn);
    }

    private UUID confirmedOrder() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                    currency, placed_at, version, created_at, paid_amount, paid_in_full_at)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 1, 'VND', NOW(), 0, NOW(), 1, NOW())""", order,
                "SO-20991230-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)), DemoData.CUSTOMER_ID,
                UUID.randomUUID()));
        return order;
    }

    /** Both vehicles (or the one) of a confirmed plan booked, loaded, scanned and signed for, in seq order. */
    private void shipAll(JsonNode plan) throws Exception {
        int seq = 0;
        for (JsonNode v : plan.path("vehicles")) {
            String loadId = v.path("load").path("loadId").asText();
            bookAndLoad(loadId, "51C-9%02d.11".formatted(seq++));
            scanAll(loadId);
            handOver(loadId).andExpect(status().isOk());
        }
    }

    // ---- review of #164 --------------------------------------------------------------------------

    @Test
    @DisplayName("10 step 4, BR-02/03: packing completed holds the packed units at the source - the carton's own rows "
            + "and the sofa - so an order cannot reserve them (only the 5 loose units are free); the last signature "
            + "consumes exactly those holds: IN_TRANSIT, the carton CONSUMED, the loose units still the order's")
    void stockHeldFromPackingToDispatch() throws Exception {
        String lpn = stock();
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        int atpBefore = atp(cartonSku);
        packer(body(post(PACKS), "{\"transferOrderId\":\"" + transfer + "\"}")).andExpect(status().isCreated());
        // Held from the moment packing starts: every requested unit (review of #164).
        assertThat(holds(transfer, "HELD")).isEqualTo(11);
        assertThat(atp(cartonSku)).isEqualTo(atpBefore - 10);
        packed(transfer, lpn);
        assertThat(holds(transfer, "HELD")).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation r JOIN inventory.stock_item s "
                + "ON s.id = r.stock_item_id JOIN inventory.stock_package p ON p.id = s.stock_package_id WHERE "
                + "r.transfer_order_id = ? AND r.status = 'HELD' AND p.lpn = ?", Integer.class, transfer, lpn))
                .isEqualTo(1);

        // An order now finds only the 5 loose units of the carton SKU free: the 10 in the carton are the transfer's.
        UUID order = confirmedOrder();
        Throwable tooMany = catchThrowable(() -> tx.executeWithoutResult(s -> inventory.reserve(
                new com.stockflow.inventory.api.ReserveStockCommand(UUID.randomUUID(),
                        new com.stockflow.common.domain.Sku(cartonSku), 6, order))));
        assertThat(tooMany).isNotNull();
        tx.executeWithoutResult(s -> inventory.reserve(new com.stockflow.inventory.api.ReserveStockCommand(
                UUID.randomUUID(), new com.stockflow.common.domain.Sku(cartonSku), 5, order)));

        shipAll(confirmed(transfer));
        assertThat(transferStatus(transfer)).isEqualTo("IN_TRANSIT");
        assertThat(holds(transfer, "HELD")).isZero();
        assertThat(holds(transfer, "CONSUMED")).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class, lpn))
                .isEqualTo("CONSUMED");
        // #147: the order's checkout reservation is on the SKU, still live and still covered by the 5 loose units.
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_reservation WHERE "
                + "order_id = ? AND stock_item_id IS NULL AND status = 'HELD'", Integer.class, order)).isEqualTo(5);
        assertThat(onHand(cartonSku)).isEqualTo(5);
    }

    @Test
    @DisplayName("LP2-8, review of #164: a carton part of which an order was allocated is never swapped for other "
            + "stock - shipping it as it is is STOCK_CARTON_NOT_SHIPPABLE, the transfer's holds and the order's unchanged")
    void aReservedCartonIsNotSwapped() throws Exception {
        String lpn = stock();
        // 10 more loose units, newest: room for the transfer once an order took part of the carton.
        tx.executeWithoutResult(s -> jdbc.update("insert into inventory.stock_item (id, sku, location_code, "
                + "received_at, on_hand, reserved, status, version, created_at) values (?, ?, ?, now(), 10, 0, "
                + "'AVAILABLE', 0, now())", Identifiers.newId(), cartonSku, SHELF));
        // An order reserves 10 and is released to HCM: #147 allocates its bins and lots - part of the carton among
        // them (the oldest units first).
        UUID order = confirmedOrder();
        tx.executeWithoutResult(s -> {
            inventory.reserve(new com.stockflow.inventory.api.ReserveStockCommand(UUID.randomUUID(),
                    new com.stockflow.common.domain.Sku(cartonSku), 10, order));
            inventory.allocate(new com.stockflow.inventory.api.AllocateStockCommand(order, "HCM"));
        });
        int orderOnCarton = jdbc.queryForObject("SELECT s.reserved FROM inventory.stock_item s JOIN "
                + "inventory.stock_package p ON p.id = s.stock_package_id WHERE p.lpn = ?", Integer.class, lpn);
        assertThat(orderOnCarton).isPositive();
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        String pack = data(packer(body(post(PACKS), "{\"transferOrderId\":\"" + transfer + "\"}"))
                .andExpect(status().isCreated())).path("packId").asText();
        assertThat(holds(transfer, "HELD")).isEqualTo(11);
        // The carton is not whole and free: refused, never swapped for other units; nothing changed.
        packer(body(post(PACKS + "/" + pack + "/cartons"), "{\"lpn\":\"" + lpn + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_CARTON_NOT_SHIPPABLE"));
        assertThat(holds(transfer, "HELD")).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.package WHERE transfer_order_id = ? AND "
                + "stock_package_id IS NOT NULL", Integer.class, transfer)).isZero();
        assertThat(jdbc.queryForObject("SELECT s.reserved FROM inventory.stock_item s JOIN inventory.stock_package p "
                + "ON p.id = s.stock_package_id WHERE p.lpn = ?", Integer.class, lpn))
                .isGreaterThanOrEqualTo(orderOnCarton);
    }

    @Test
    @DisplayName("09 §4.2 step 1 / 10 step 5: a TRANSFER plan leaves nothing behind - a pickup cannot take the 1.2 t "
            + "carton, and acknowledging or confirming with it unplaced is TRANSFER_SHIPS_WHOLE")
    void transferShipsWhole() throws Exception {
        String lpn = stock();
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        packed(transfer, lpn);
        UUID pickup = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'PICKUP'", UUID.class);
        JsonNode plan = data(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\""
                + HCM + "\",\"sourceType\":\"TRANSFER\",\"transferOrderIds\":[\"" + transfer
                + "\"],\"vehicleTypeIds\":[\"" + pickup + "\"]}")).andExpect(status().isCreated()));
        plan = step(plan, "/calculation", version(plan));
        assertThat(plan.path("unplacedCount").asInt()).isPositive();
        String id = plan.path("loadPlanId").asText();
        dispatch(body(post(PLANS + "/" + id + "/unplaced-acknowledgement"), version(plan)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("TRANSFER_SHIPS_WHOLE"));
        dispatch(body(post(PLANS + "/" + id + "/confirmation"), version(plan)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("TRANSFER_SHIPS_WHOLE"));
    }

    @Test
    @DisplayName("10 step 8: a transfer packed in part (with a note) holds and ships only what was packed - the sofa; "
            + "the carton SKU's line ships 0 and its stock stays at the source")
    void partialPackingShipsWhatWasPacked() throws Exception {
        String lpn = stock();
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        int cartonSkuBefore = onHand(cartonSku);
        JsonNode session = packed(transfer, null);
        assertThat(session.path("packStatus").asText()).isEqualTo("PACKED");
        assertThat(holds(transfer, "HELD")).isEqualTo(1);
        JsonNode plan = confirmed(transfer);
        assertThat(plan.path("vehicles")).hasSize(1);
        shipAll(plan);
        assertThat(transferStatus(transfer)).isEqualTo("IN_TRANSIT");
        Map<String, Object> shipped = new java.util.HashMap<>();
        jdbc.queryForList("SELECT sku, shipped_qty FROM inventory.transfer_order_line WHERE transfer_order_id = ?",
                transfer).forEach(row -> shipped.put((String) row.get("sku"), row.get("shipped_qty")));
        assertThat(shipped).containsEntry(SOFA, 1).containsEntry(cartonSku, 0);
        assertThat(onHand(cartonSku)).isEqualTo(cartonSkuBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class, lpn))
                .isEqualTo("IN_STOCK");
    }

    @Test
    @DisplayName("10 step 5: a FULL_TRUCKLOAD transfer is never dispatched by hand - not even after the old "
            + "start-picking endpoint, with no packing session (TRANSFER_DISPATCH_BY_LOAD_PLAN); a PARCEL one is")
    void noManualDispatchOfAFullTruckload() throws Exception {
        stock();
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        dispatch(post(TRANSFERS + "/" + transfer + "/picking")).andExpect(status().isOk());
        dispatch(body(post(TRANSFERS + "/" + transfer + "/dispatch"), "{\"lines\":[{\"lineId\":\""
                + lineOf(transfer, 1) + "\",\"shippedQuantity\":1}]}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("TRANSFER_DISPATCH_BY_LOAD_PLAN"));
        assertThat(transferStatus(transfer)).isEqualTo("PICKING");

        UUID parcel = approvedTransfer("PARCEL");
        dispatch(post(TRANSFERS + "/" + parcel + "/picking")).andExpect(status().isOk());
        dispatch(body(post(TRANSFERS + "/" + parcel + "/dispatch"), "{\"lines\":[{\"lineId\":\""
                + lineOf(parcel, 1) + "\",\"shippedQuantity\":1}]}")).andExpect(status().isOk());
        assertThat(transferStatus(parcel)).isEqualTo("IN_TRANSIT");
    }

    @Test
    @DisplayName("FE F9 contract: a reader finds the transfer's pack session by transfer (404 before) without starting "
            + "picking; the session names its packer; a sealed parcel reopens and is sealed again; its label names the "
            + "transfer, n of N and both warehouses, and printing it makes an outdated label current; the transfer list "
            + "row carries deliveryMode and packedAt")
    void contractForTheTransferPackingScreen() throws Exception {
        String lpn = stock();
        UUID transfer = approvedTransfer("FULL_TRUCKLOAD");
        dispatch(get(PACKS).param("transferOrderId", transfer.toString())).andExpect(status().isNotFound());
        assertThat(transferStatus(transfer)).isEqualTo("APPROVED");      // a reader starts nothing

        JsonNode session = packed(transfer, lpn);
        String pack = session.path("packId").asText();
        JsonNode found = data(dispatch(get(PACKS).param("transferOrderId", transfer.toString()))
                .andExpect(status().isOk()));
        assertThat(found.path("packId").asText()).isEqualTo(pack);
        assertThat(found.path("packerId").asText()).isEqualTo(packer.toString());
        assertThat(found.path("packerName").asText()).isNotBlank();

        // The label of the carton parcel: transfer, n of 3, from HCM to the destination; no COD.
        String carton = null;
        for (JsonNode p : found.path("packages")) {
            if (p.hasNonNull("stockPackageId")) {
                carton = p.path("packageId").asText();
            }
        }
        tx.executeWithoutResult(s -> jdbc.update("UPDATE fulfillment.package SET label_outdated_at = NOW() WHERE "
                + "transfer_order_id = ?", transfer));
        String cartonId = carton;
        JsonNode label = data(packer(get(PACKS + "/" + pack + "/packages/" + cartonId + "/label"))
                .andExpect(status().isOk()));
        assertThat(label.path("transferOrderId").asText()).isEqualTo(transfer.toString());
        assertThat(label.path("packageCount").asInt()).isEqualTo(3);
        assertThat(label.path("toWarehouseName").asText()).isEqualTo("Destination");
        assertThat(label.path("fromWarehouseName").asText()).isNotBlank();
        assertThat(label.path("weightKg").decimalValue()).isEqualByComparingTo("1195");
        assertThat(jdbc.queryForObject("SELECT label_outdated_at IS NULL FROM fulfillment.package WHERE id = ?::uuid",
                Boolean.class, cartonId)).isTrue();

        // The transfer list row: how it travels and when its packing completed.
        String number = jdbc.queryForObject("SELECT transfer_number FROM inventory.transfer_order WHERE id = ?",
                String.class, transfer);
        JsonNode row = data(dispatch(get(TRANSFERS).param("search", number)).andExpect(status().isOk()))
                .path("items").get(0);
        assertThat(row.path("deliveryMode").asText()).isEqualTo("FULL_TRUCKLOAD");
        assertThat(row.hasNonNull("packedAt")).isTrue();

        // Reopening a sealed parcel, before completion, on a second transfer (a packed session no longer changes).
        String lpn2 = stock();
        UUID other = approvedTransfer("FULL_TRUCKLOAD");
        String otherPack = data(packer(body(post(PACKS), "{\"transferOrderId\":\"" + other + "\"}"))
                .andExpect(status().isCreated())).path("packId").asText();
        String parcel = data(packer(body(post(PACKS + "/" + otherPack + "/cartons"), "{\"lpn\":\"" + lpn2 + "\"}"))
                .andExpect(status().isCreated())).path("parcel").path("packageId").asText();
        packer(body(post(PACKS + "/" + otherPack + "/packages/" + parcel + "/seal"), CARTON_MEASURES))
                .andExpect(status().isOk());
        assertThat(data(packer(post(PACKS + "/" + otherPack + "/packages/" + parcel + "/reopening"))
                .andExpect(status().isOk())).path("status").asText()).isEqualTo("OPEN");
        assertThat(data(packer(body(post(PACKS + "/" + otherPack + "/packages/" + parcel + "/seal"), CARTON_MEASURES))
                .andExpect(status().isOk())).path("status").asText()).isEqualTo("SEALED");
        packer(post(PACKS + "/" + pack + "/packages/" + cartonId + "/reopening")).andExpect(status().isConflict());
    }

    private static MockHttpServletRequestBuilder put(String url) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url);
    }
}
