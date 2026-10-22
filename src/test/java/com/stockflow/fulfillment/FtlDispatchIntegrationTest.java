package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.order.api.HoldReason;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-truckload dispatch per vehicle (SCRUM-392; kltn-docs 09 §4.2 step 5, §5, BR-14, BR-15; LP-3, LP-11, LP2-1 and
 * the PR E guidance of the #161 review) through the real filter chain, permission aspect, solver and database: one
 * shipment across two vehicles booked, loaded, scanned (a wrong vehicle's parcel refused, a missing kit part blocking)
 * and signed one by one - the shipment handed over at the last signature and the plan DISPATCHED; booking cancelled
 * before and refused after loading; reopening cancels booked vehicles; an order cancelled while its vehicle loads; a
 * shipment split at confirmation; permissions and warehouse scope.
 *
 * <p>Its own context, hence its own database.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.packing.dim-divisor=6000", "stockflow.fulfillment.load-planning.solver-budget=PT2S",
        "stockflow.test.context=ftl-dispatch"})
class FtlDispatchIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final String SOFA_BARCODE = "8930000000011";
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String ORDERS = "/api/v1/orders";
    private static final String BASE = "/api/v1/fulfillment";
    private static final String PLANS = BASE + "/load-plans";
    private static final String LOADS = BASE + "/loads";
    private static final String ONE_CARTON = "{\"kitOverrideReason\":\"test: packed as one carton\"}";
    /** A sofa carton, and kit part 1 (the sofa itself): 2150 x 950 x 900 mm. */
    private static final String SOFA_MEASURES = "{\"weightKg\":52.5,\"lengthCm\":215,\"widthCm\":95,\"heightCm\":90}";
    /** Kit part 2 (the cushions): 950 x 900 x 400 mm. */
    private static final String PART2_MEASURES = "{\"weightKg\":20,\"lengthCm\":95,\"widthCm\":90,\"heightCm\":40}";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ObjectMapper json;

    private UUID coordinator;
    private UUID picker;
    private UUID dispatcher;
    private UUID dispatcherElsewhere;
    private UUID truck5t;
    private UUID truck1t25;
    private UUID ghn;
    private UUID ghtk;

    @BeforeEach
    void setUp() {
        coordinator = staff("ORDER_COORDINATOR");
        picker = staff("WAREHOUSE_STAFF", HCM);
        dispatcher = staff("WAREHOUSE_STAFF", HCM);
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        dispatcherElsewhere = staff("WAREHOUSE_STAFF", elsewhere);
        truck5t = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-5T'", UUID.class);
        truck1t25 = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'",
                UUID.class);
        ghn = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHN'", UUID.class);
        ghtk = jdbc.queryForObject("SELECT id FROM fulfillment.carrier WHERE code = 'GHTK'", UUID.class);
    }

    // ---- fixtures ----------------------------------------------------------------------------

    private UUID otherWarehouse() {
        UUID id = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Elsewhere', 'ACTIVE', ?, 'Somewhere', 'M', 50, 50, 0, NOW())""", id,
                "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(java.util.Locale.ROOT));
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

    private String token(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private ResultActions as(UUID user, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", token(user, role)));
    }

    private ResultActions dispatch(MockHttpServletRequestBuilder request) throws Exception {
        return as(dispatcher, "WAREHOUSE_STAFF", request);
    }

    private ResultActions oc(MockHttpServletRequestBuilder request) throws Exception {
        return as(coordinator, "ORDER_COORDINATOR", request);
    }

    private ResultActions packer(MockHttpServletRequestBuilder request) throws Exception {
        return as(picker, "WAREHOUSE_STAFF", request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType("application/json").content(content);
    }

    private void stock(int sofas) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version,
                    created_at, received_at)
                VALUES (?, ?, 'HCM-A02-1-A', ?, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '400 days')""",
                Identifiers.newId(), SOFA.code(), sofas));
    }

    /** A paid order of the demo customer for {@code sofas} sofas, released at HCM as a full truckload (LP-1). */
    private OrderSummary ftlOrder(int sofas) throws Exception {
        stock(sofas);
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID,
                List.of(new PlaceOrderCommand.Line(SOFA, sofas, Money.vnd(12_000_000), null))));
        BigDecimal total = order.total().amount();
        UUID payment = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO payment.payment (id, order_id, amount, currency, method, status, captured_at, created_at)
                VALUES (?, ?, ?, 'VND', 'BANK_TRANSFER', 'CAPTURED', NOW(), NOW())""", payment, order.orderId(), total));
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(order.orderId(), payment, total, "VND",
                PaymentMethod.BANK_TRANSFER)));
        Await.until("order confirmed", ASYNC,
                () -> orders.findById(order.orderId()).orElseThrow().status() == OrderStatus.CONFIRMED);
        oc(body(post(ORDERS + "/" + order.orderId() + "/release"), "{\"warehouseId\":\"" + HCM
                + "\",\"deliveryMode\":\"FULL_TRUCKLOAD\"}")).andExpect(status().isOk());
        return order;
    }

    /** The order picked and packed: as kits of 2 (LP2-6) or one carton per sofa. Returns the shipment and task. */
    private UUID[] packed(OrderSummary order, boolean kits) throws Exception {
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        packer(post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, sku, allocated_qty FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/confirmation"),
                    "{\"locationCode\":\"" + line.get("location_code") + "\",\"scannedCode\":\"" + line.get("sku")
                            + "\",\"quantity\":" + line.get("allocated_qty") + "}")).andExpect(status().isOk());
        }
        packer(post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk());
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        int sofas = order.lines().getFirst().quantity();
        UUID line = order.lines().getFirst().lineId();
        for (int i = 0; i < sofas; i++) {
            if (kits) {
                JsonNode kit = data(packer(body(post(BASE + "/tasks/" + task + "/package-kits"), "{\"orderLineId\":\""
                        + line + "\"}")).andExpect(status().isCreated()));
                String part1 = kit.path("packages").get(0).path("packageId").asText();
                String part2 = kit.path("packages").get(1).path("packageId").asText();
                scan(task, part1);
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + part1 + "/seal"), SOFA_MEASURES))
                        .andExpect(status().isOk());
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + part2 + "/seal"), PART2_MEASURES))
                        .andExpect(status().isOk());
            } else {
                String parcel = data(packer(body(post(BASE + "/tasks/" + task + "/packages"), ONE_CARTON))
                        .andExpect(status().isCreated())).path("packageId").asText();
                scan(task, parcel);
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/seal"), SOFA_MEASURES))
                        .andExpect(status().isOk());
            }
        }
        String shipment = data(packer(post(BASE + "/tasks/" + task + "/packing-completion")).andExpect(status().isOk()))
                .path("shipmentId").asText();
        return new UUID[] {UUID.fromString(shipment), task};
    }

    private void scan(UUID task, String parcel) throws Exception {
        packer(body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/scans"), "{\"scanId\":\""
                + UUID.randomUUID() + "\",\"scannedCode\":\"" + SOFA_BARCODE + "\",\"quantity\":1}"))
                .andExpect(status().isOk());
    }

    private static String ids(List<UUID> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).map(s -> "[" + s + "]")
                .orElse("[]");
    }

    private JsonNode plan(List<UUID> shipments, UUID type) throws Exception {
        return data(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"shipmentIds\":" + ids(shipments) + ",\"vehicleTypeIds\":[\"" + type
                + "\"],\"selectionMode\":\"SINGLE_TYPE\"}")).andExpect(status().isCreated()));
    }

    private JsonNode step(JsonNode plan, String path, String content) throws Exception {
        return data(dispatch(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + path), content))
                .andExpect(status().isOk()));
    }

    private static String version(JsonNode plan) {
        return "{\"expectedVersion\":" + plan.path("version").asLong() + "}";
    }

    /** A plan of these shipments on this vehicle type, calculated and confirmed. */
    private JsonNode confirmed(List<UUID> shipments, UUID type) throws Exception {
        JsonNode plan = plan(shipments, type);
        plan = step(plan, "/calculation", version(plan));
        plan = step(plan, "/confirmation", version(plan));
        assertThat(plan.path("status").asText()).isEqualTo("CONFIRMED");
        return plan;
    }

    private String loadOf(JsonNode plan, int seq) {
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

    private JsonNode book(String loadId, UUID carrier, String plate) throws Exception {
        return data(dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier + "\""
                + (plate == null ? "" : ",\"vehiclePlate\":\"" + plate + "\"") + ",\"driverName\":\"Tai\"}"))
                .andExpect(status().isOk()));
    }

    private ResultActions scanOnto(String loadId, String label) throws Exception {
        return dispatch(body(post(LOADS + "/" + loadId + "/scans"), "{\"scanId\":\"" + UUID.randomUUID()
                + "\",\"packageCode\":\"" + label + "\"}"));
    }

    private ResultActions handOver(String loadId) throws Exception {
        long version = load(loadId).path("version").asLong();
        return dispatch(body(post(LOADS + "/" + loadId + "/handover"), "{\"version\":" + version
                + ",\"carrierRepresentative\":\"Nguyen Van Tai\",\"signed\":true}"));
    }

    /** The labels of the parcels a load shows (those its plan placed on the vehicle). */
    private static List<String> labels(JsonNode load) {
        List<String> out = new ArrayList<>();
        load.path("shipments").forEach(s -> s.path("packages").forEach(p -> out.add(p.path("labelCode").asText())));
        return out;
    }

    private String shipmentStatus(UUID shipment) {
        return jdbc.queryForObject("SELECT status FROM fulfillment.shipment WHERE id = ?", String.class, shipment);
    }

    private String planStatus(JsonNode plan) {
        return jdbc.queryForObject("SELECT status FROM fulfillment.load_plan WHERE id = ?::uuid", String.class,
                plan.path("loadPlanId").asText());
    }

    // ---- LP-3: one shipment, two vehicles ----------------------------------------------------

    @Test
    @DisplayName("LP-3 / BR-15: one shipment of two kits on two 1.25 t trucks - both vehicles on its manifest; loading "
            + "before booking 409; booking cancelled and booked again; BR-14 once loading; a parcel of vehicle 2 "
            + "scanned onto vehicle 1 is PACKAGE_NOT_IN_LOAD naming vehicle 2; a missing kit part blocks the handover; "
            + "an order on hold blocks vehicle 1 though nothing is handed over there; vehicle 1 signed: the shipment "
            + "still waits; vehicle 2 signed: HANDED_OVER, order SHIPPED, plan DISPATCHED")
    void oneShipmentAcrossTwoVehicles() throws Exception {
        OrderSummary order = ftlOrder(2);
        UUID shipment = packed(order, true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        assertThat(plan.path("vehicles")).hasSize(2);
        String v1 = loadOf(plan, 1);
        String v2 = loadOf(plan, 2);

        // Confirmation opened both vehicles' manifests with the shipment, each listing its own two parcels (a kit).
        JsonNode first = load(v1);
        JsonNode second = load(v2);
        for (JsonNode l : List.of(first, second)) {
            assertThat(l.path("mode").asText()).isEqualTo("FULL_TRUCKLOAD");
            assertThat(l.path("status").asText()).isEqualTo("PLANNED");
            assertThat(l.path("loadPlanCode").asText()).isEqualTo(plan.path("code").asText());
            assertThat(l.path("vehicleTypeCode").asText()).isEqualTo("TRUCK-1T25");
            assertThat(l.path("shipments")).singleElement().satisfies(s -> assertThat(s.path("shipmentId").asText())
                    .isEqualTo(shipment.toString()));
            assertThat(l.path("expectedPackageCount").asInt()).isEqualTo(2);
            assertThat(l.path("codTotal").decimalValue()).isEqualByComparingTo("0");
        }
        assertThat(first.path("vehicleSeq").asInt()).isEqualTo(1);
        assertThat(labels(first)).doesNotContainAnyElementsOf(labels(second));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.dispatch_load_shipment WHERE shipment_id = ? "
                + "AND active AND mode = 'FULL_TRUCKLOAD'", Integer.class, shipment)).isEqualTo(2);

        // Booking (09 §4.2 step 5); loading only once booked.
        dispatch(post(LOADS + "/" + v1 + "/loading-start")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
        JsonNode booked = book(v1, ghn, "51C-111.11");
        assertThat(booked.path("status").asText()).isEqualTo("VEHICLE_BOOKED");
        assertThat(booked.path("carrierCode").asText()).isEqualTo("GHN");
        assertThat(booked.path("vehiclePlate").asText()).isEqualTo("51C-111.11");
        assertThat(booked.hasNonNull("bookedAt")).isTrue();
        book(v2, ghn, null);
        JsonNode unbooked = data(dispatch(body(post(LOADS + "/" + v2 + "/booking-cancellation"),
                "{\"reason\":\"GHN has no second truck\"}")).andExpect(status().isOk()));
        assertThat(unbooked.path("status").asText()).isEqualTo("PLANNED");
        assertThat(unbooked.path("bookingCancelReason").asText()).isEqualTo("GHN has no second truck");
        assertThat(unbooked.hasNonNull("carrierId")).isFalse();
        book(v2, ghtk, "51C-222.22");

        // Vehicle 1 arrives: loading - the plan is locked (BR-14), its booking no longer cancelled.
        assertThat(data(dispatch(post(LOADS + "/" + v1 + "/loading-start")).andExpect(status().isOk()))
                .path("status").asText()).isEqualTo("LOADING");
        JsonNode now = data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()));
        dispatch(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + "/reopening"), "{\"expectedVersion\":"
                + now.path("version").asLong() + ",\"reason\":\"x\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_LOADING_STARTED"));
        dispatch(body(post(LOADS + "/" + v1 + "/booking-cancellation"), "{\"reason\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));

        // 09 edge "quét nhầm kiện của xe khác": refused, naming the vehicle and load it belongs on.
        String vehicle2Parcel = labels(second).getFirst();
        JsonNode wrong = body(scanOnto(v1, vehicle2Parcel).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_IN_LOAD")));
        assertThat(wrong.path("message").asText()).contains("vehicle 2").contains(second.path("code").asText());
        Map<String, String> fields = new HashMap<>();
        wrong.path("fieldErrors").forEach(f -> fields.put(f.path("field").asText(), f.path("message").asText()));
        assertThat(fields).containsEntry("vehicleSeq", "2").containsEntry("loadCode", second.path("code").asText());

        // Kit part 1 only: the kit is not whole - LOAD_NOT_FULLY_LOADED names part 2.
        List<String> mine = labels(first);
        Map<String, Integer> partOf = new HashMap<>();
        first.path("shipments").get(0).path("packages").forEach(p -> partOf.put(p.path("labelCode").asText(),
                p.path("kit").path("partNo").asInt()));
        String part1 = mine.stream().filter(l -> partOf.get(l) == 1).findFirst().orElseThrow();
        String part2 = mine.stream().filter(l -> partOf.get(l) == 2).findFirst().orElseThrow();
        scanOnto(v1, part1).andExpect(status().isOk());
        JsonNode notWhole = body(handOver(v1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_NOT_FULLY_LOADED")));
        assertThat(notWhole.path("message").asText()).contains(part2).contains("kit part 2/2");
        scanOnto(v1, part2).andExpect(status().isOk());

        // The per-vehicle manifest: only this vehicle's parcels, COD 0.
        JsonNode manifest = data(dispatch(get(LOADS + "/" + v1 + "/manifest")).andExpect(status().isOk()));
        assertThat(manifest.path("mode").asText()).isEqualTo("FULL_TRUCKLOAD");
        assertThat(manifest.path("vehicleSeq").asInt()).isEqualTo(1);
        assertThat(manifest.path("loadPlanCode").asText()).isEqualTo(plan.path("code").asText());
        assertThat(manifest.path("packageCount").asInt()).isEqualTo(2);
        assertThat(manifest.path("codTotal").decimalValue()).isEqualByComparingTo("0");
        assertThat(manifest.path("shipments").get(0).path("codAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(manifest.path("shipments").get(0).path("orderNumber").asText()).isEqualTo(order.orderNumber());
        assertThat(manifest.path("shipments").get(0).path("packages").findValuesAsText("labelCode"))
                .containsExactlyInAnyOrderElementsOf(mine);

        // The order's gate runs at every vehicle (LP-3): on hold, vehicle 1 is refused though it completes nothing.
        orders.putOnHold(order.orderId(), HoldReason.PAYMENT, "check the transfer", coordinator);
        handOver(v1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_INVALID_TRANSITION"));
        orders.resolveHold(order.orderId(), "transfer found", coordinator);

        // Vehicle 1 signed: it left, the shipment still waits for vehicle 2, the order is not shipped.
        JsonNode left = data(handOver(v1).andExpect(status().isOk()));
        assertThat(left.path("status").asText()).isEqualTo("DISPATCHED");
        assertThat(shipmentStatus(shipment)).isEqualTo("READY_TO_DISPATCH");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        assertThat(planStatus(plan)).isEqualTo("CONFIRMED");
        handOver(v1).andExpect(status().isOk());    // again: answered as it is

        // Vehicle 2: loaded and signed - the shipment is handed over with it, the order shipped, the plan dispatched.
        dispatch(post(LOADS + "/" + v2 + "/loading-start")).andExpect(status().isOk());
        for (String label : labels(second)) {
            scanOnto(v2, label).andExpect(status().isOk());
        }
        assertThat(data(handOver(v2).andExpect(status().isOk())).path("status").asText()).isEqualTo("DISPATCHED");
        Map<String, Object> handed = jdbc.queryForMap("SELECT status, load_id, carrier_id, cod_amount FROM "
                + "fulfillment.shipment WHERE id = ?", shipment);
        assertThat(handed).containsEntry("status", "HANDED_OVER").containsEntry("load_id", UUID.fromString(v2))
                .containsEntry("carrier_id", ghtk);
        assertThat((BigDecimal) handed.get("cod_amount")).isEqualByComparingTo("0");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(planStatus(plan)).isEqualTo("DISPATCHED");
        assertThat(data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()))
                .hasNonNull("dispatchedAt")).isTrue();

        // The list shows each vehicle with its plan.
        JsonNode rows = data(dispatch(get(LOADS + "?warehouseId=" + HCM + "&search=" + first.path("code").asText()))
                .andExpect(status().isOk()));
        assertThat(rows.path("items")).singleElement().satisfies(r -> {
            assertThat(r.path("loadPlanCode").asText()).isEqualTo(plan.path("code").asText());
            assertThat(r.path("vehicleSeq").asInt()).isEqualTo(1);
        });
    }

    // ---- LP-11 -------------------------------------------------------------------------------

    @Test
    @DisplayName("LP-11: reopening a plan cancels its booked vehicle with the reason; confirmed again, a new PLANNED "
            + "load carries the shipment and is booked again; a plan's manifest is not edited by hand "
            + "(LOAD_MANAGED_BY_PLAN); a parcel load is not booked")
    void reopeningCancelsBookings() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        book(loadId, ghn, "51C-333.33");
        dispatch(delete(LOADS + "/" + loadId + "/shipments/" + shipment)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_MANAGED_BY_PLAN"));

        JsonNode now = data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()));
        plan = step(now, "/reopening", "{\"expectedVersion\":" + now.path("version").asLong()
                + ",\"reason\":\"the truck is a 2.5 t, not a 5 t\"}");
        assertThat(plan.path("status").asText()).isEqualTo("PLANNED");
        Map<String, Object> cancelled = jdbc.queryForMap("SELECT status, cancel_reason, carrier_id FROM "
                + "fulfillment.dispatch_load WHERE id = ?::uuid", loadId);
        assertThat(cancelled).containsEntry("status", "CANCELLED").containsEntry("carrier_id", ghn);
        assertThat((String) cancelled.get("cancel_reason")).contains("the truck is a 2.5 t, not a 5 t");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.dispatch_load_shipment WHERE load_id = "
                + "?::uuid AND active", Integer.class, loadId)).isZero();
        // The cancelled load stays cancelled: the dispatcher books the new one.
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));

        plan = step(plan, "/confirmation", version(plan));
        String again = loadOf(plan, 1);
        assertThat(again).isNotEqualTo(loadId);
        assertThat(load(again).path("shipments")).singleElement().satisfies(s -> assertThat(s.path("shipmentId")
                .asText()).isEqualTo(shipment.toString()));
        assertThat(book(again, ghn, null).path("status").asText()).isEqualTo("VEHICLE_BOOKED");

        // A parcel load opened for GHN is not a plan's vehicle.
        String parcelLoad = data(dispatch(body(post(LOADS), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"carrierId\":\"" + ghn + "\",\"shipmentIds\":[]}"))
                .andExpect(status().isCreated())).path("loadId").asText();
        dispatch(body(post(LOADS + "/" + parcelLoad + "/booking"), "{\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
        dispatch(post(LOADS + "/" + parcelLoad + "/loading-start")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
    }

    // ---- an order cancelled while its vehicle loads -------------------------------------------

    @Test
    @DisplayName("an order cancelled while its parcel is on a loading vehicle: off the manifest, the parcel to unload "
            + "- the handover is 409 LOAD_UNLOAD_PENDING; another order's parcel is not to unload (PACKAGE_NOT_IN_LOAD); "
            + "unloaded, the vehicle leaves with the rest and the plan is DISPATCHED; the goods wait for put-back")
    void orderCancelledWhileLoading() throws Exception {
        OrderSummary doomedOrder = ftlOrder(1);
        UUID[] doomed = packed(doomedOrder, false);
        UUID kept = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(doomed[0], kept), truck5t);
        assertThat(plan.path("vehicles")).hasSize(1);
        String loadId = loadOf(plan, 1);
        book(loadId, ghn, "51C-444.44");
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        String doomedLabel = jdbc.queryForObject("SELECT label_code FROM fulfillment.package WHERE shipment_id = ?",
                String.class, doomed[0]);
        String keptLabel = jdbc.queryForObject("SELECT label_code FROM fulfillment.package WHERE shipment_id = ?",
                String.class, kept);
        scanOnto(loadId, doomedLabel).andExpect(status().isOk());
        scanOnto(loadId, keptLabel).andExpect(status().isOk());

        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderCancelled(doomedOrder.orderId(),
                doomedOrder.orderNumber(), DemoData.CUSTOMER_ID, "PACKED", "OTHER", "test", BigDecimal.ZERO,
                BigDecimal.ZERO, "VND", coordinator, Instant.now())));
        Await.until("the cancelled order's shipment is cancelled", ASYNC,
                () -> "CANCELLED".equals(shipmentStatus(doomed[0])));

        JsonNode loading = load(loadId);
        assertThat(loading.path("status").asText()).isEqualTo("LOADING");
        assertThat(loading.path("shipments")).singleElement().satisfies(s -> assertThat(s.path("shipmentId").asText())
                .isEqualTo(kept.toString()));
        assertThat(loading.path("pendingUnloads")).singleElement().satisfies(u -> {
            assertThat(u.path("labelCode").asText()).isEqualTo(doomedLabel);
            assertThat(u.path("shipmentId").asText()).isEqualTo(doomed[0].toString());
        });
        assertThat(planStatus(plan)).as("a vehicle is loading: the plan stays confirmed (BR-14)").isEqualTo("CONFIRMED");
        handOver(loadId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_UNLOAD_PENDING"));
        dispatch(body(post(LOADS + "/" + loadId + "/unloads"), "{\"packageCode\":\"" + keptLabel + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_IN_LOAD"));

        JsonNode unloaded = data(dispatch(body(post(LOADS + "/" + loadId + "/unloads"), "{\"packageCode\":\""
                + doomedLabel + "\"}")).andExpect(status().isOk()));
        assertThat(unloaded.path("pendingUnloads")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT unloaded_by FROM fulfillment.dispatch_load_unload WHERE load_id = "
                + "?::uuid", UUID.class, loadId)).isEqualTo(dispatcher);
        assertThat(data(handOver(loadId).andExpect(status().isOk())).path("status").asText()).isEqualTo("DISPATCHED");
        assertThat(shipmentStatus(kept)).isEqualTo("HANDED_OVER");
        assertThat(planStatus(plan)).isEqualTo("DISPATCHED");
        assertThat(jdbc.queryForObject("SELECT needs_put_back FROM fulfillment.pick WHERE id = ?", Boolean.class,
                doomed[1])).as("the goods go back through the put-back queue").isTrue();
    }

    // ---- a shipment split when its plan starts loading (decision 2, review of #162) ----------

    @Test
    @DisplayName("a shipment with one carton placed and one left behind (partial shipments allowed): confirmation and a "
            + "reopening leave it whole; while its plan holds part of it, it is no candidate and no other plan takes it "
            + "(LP-5); the first vehicle starting to load splits the left-behind carton into a new shipment whose label "
            + "is outdated (409 LABEL_OUTDATED until reprinted); the piece check balances; the vehicle hands over the "
            + "rest - the order PARTIALLY_FULFILLED")
    void leftBehindSplitsOffAtLoadingStart() throws Exception {
        OrderSummary order = ftlOrder(2);
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
        UUID[] packedShipment = packed(order, false);
        UUID shipment = packedShipment[0];
        JsonNode plan = plan(List.of(shipment), truck5t);
        plan = step(plan, "/calculation", version(plan));
        List<String> parcels = plan.path("packages").findValuesAsText("packageId");
        assertThat(parcels).hasSize(2);
        String goes = parcels.get(0);
        String stays = parcels.get(1);
        plan = leaveOnTray(plan, goes, stays);
        plan = step(plan, "/confirmation", version(plan));
        String id = plan.path("loadPlanId").asText();
        assertThat(jdbc.queryForObject("SELECT shipment_id FROM fulfillment.package WHERE id = ?::uuid", UUID.class,
                stays)).as("confirmed: untouched").isEqualTo(shipment);

        // Reopened and confirmed again: still untouched (a plan that never loads changes no shipment).
        plan = step(plan, "/reopening", "{\"expectedVersion\":" + plan.path("version").asLong()
                + ",\"reason\":\"another truck\"}");
        plan = step(plan, "/confirmation", version(plan));
        assertThat(jdbc.queryForObject("SELECT package_count FROM fulfillment.shipment WHERE id = ?", Integer.class,
                shipment)).isEqualTo(2);
        // LP-5: the plan holds part of the shipment - no candidate, no other plan takes the rest.
        JsonNode candidates = data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM))
                .andExpect(status().isOk()));
        assertThat(candidates.findValuesAsText("shipmentId")).doesNotContain(shipment.toString());
        dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + HCM
                + "\",\"shipmentIds\":[\"" + shipment + "\"],\"vehicleTypeIds\":[\"" + truck5t + "\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_PACKAGE_TAKEN"));

        String loadId = loadOf(plan, 1);
        book(loadId, ghn, "51C-555.55");
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        UUID rest = jdbc.queryForObject("SELECT shipment_id FROM fulfillment.package WHERE id = ?::uuid", UUID.class,
                stays);
        assertThat(rest).isNotEqualTo(shipment);
        assertThat(jdbc.queryForMap("SELECT status, delivery_mode, package_count, partial, order_id FROM "
                + "fulfillment.shipment WHERE id = ?", rest)).containsEntry("status", "READY_TO_DISPATCH")
                .containsEntry("delivery_mode", "FULL_TRUCKLOAD").containsEntry("package_count", 1)
                .containsEntry("partial", true).containsEntry("order_id", order.orderId());
        assertThat(jdbc.queryForObject("SELECT package_count FROM fulfillment.shipment WHERE id = ?", Integer.class,
                shipment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT label_outdated_at IS NOT NULL FROM fulfillment.package WHERE id = "
                + "?::uuid", Boolean.class, stays)).isTrue();
        assertThat(data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()))
                .findValuesAsText("shipmentId")).contains(rest.toString()).doesNotContain(shipment.toString());
        JsonNode check = data(dispatch(get(PLANS + "/" + id + "/reconciliation")).andExpect(status().isOk()));
        assertThat(check.path("pieceCheck").path("balanced").asBoolean()).isTrue();
        assertThat(check.path("shipments")).allSatisfy(s -> assertThat(s.path("notInPlan").asInt()).isZero());

        // Its label names the old shipment: reprinted before it is loaded anywhere.
        String restLabel = jdbc.queryForObject("SELECT label_code FROM fulfillment.package WHERE id = ?::uuid",
                String.class, stays);
        scanOnto(loadId, restLabel).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LABEL_OUTDATED"));
        packer(get(BASE + "/tasks/" + packedShipment[1] + "/packages/" + stays + "/label")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipmentId").value(rest.toString()));
        assertThat(jdbc.queryForObject("SELECT label_outdated_at FROM fulfillment.package WHERE id = ?::uuid",
                java.sql.Timestamp.class, stays)).isNull();
        scanOnto(loadId, restLabel).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_IN_LOAD"));

        // The parcel that stays is 1/1 now, no longer 1/2: its label is reprinted before it is loaded too.
        assertThat(labels(load(loadId))).hasSize(1);
        scanOnto(loadId, labels(load(loadId)).getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LABEL_OUTDATED"));
        packer(get(BASE + "/tasks/" + packedShipment[1] + "/packages/" + goes + "/label")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.packageCount").value(1));
        scanOnto(loadId, labels(load(loadId)).getFirst()).andExpect(status().isOk());
        handOver(loadId).andExpect(status().isOk());
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");
        assertThat(shipmentStatus(rest)).isEqualTo("READY_TO_DISPATCH");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(planStatus(plan)).isEqualTo("DISPATCHED");
    }

    /** One carton on the floor of vehicle 1, the other on the tray, acknowledged (BR-13). */
    private JsonNode leaveOnTray(JsonNode plan, String goes, String stays) throws Exception {
        plan = data(dispatch(body(put(PLANS + "/" + plan.path("loadPlanId").asText() + "/placements"),
                "{\"expectedVersion\":" + plan.path("version").asLong() + ",\"vehicles\":[{\"seq\":1,\"boxes\":[{\"ref\":\""
                        + goes + "\",\"xMm\":0,\"yMm\":0,\"zMm\":0,\"wMm\":950,\"hMm\":900,\"dMm\":2150,"
                        + "\"orientation\":\"LWH\"}]}],\"tray\":[\"" + stays + "\"]}")).andExpect(status().isOk()));
        return step(plan, "/unplaced-acknowledgement", "{\"expectedVersion\":" + plan.path("version").asLong()
                + ",\"note\":\"next truck\"}");
    }

    // ---- review of #162: no-partial orders, the gate at every vehicle, concurrency, auto-cancel ------------------

    /** An order of 2 sofas packed in two shipments (Sales allowed partial shipments): as kits or cartons. */
    private UUID[] packedInTwo(OrderSummary order, boolean kits) throws Exception {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        packer(post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, sku, allocated_qty FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/confirmation"),
                    "{\"locationCode\":\"" + line.get("location_code") + "\",\"scannedCode\":\"" + line.get("sku")
                            + "\",\"quantity\":" + line.get("allocated_qty") + "}")).andExpect(status().isOk());
        }
        packer(post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk());
        packer(post(BASE + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        UUID line = order.lines().getFirst().lineId();
        UUID[] shipments = new UUID[2];
        for (int i = 0; i < 2; i++) {
            if (kits) {
                JsonNode kit = data(packer(body(post(BASE + "/tasks/" + task + "/package-kits"), "{\"orderLineId\":\""
                        + line + "\"}")).andExpect(status().isCreated()));
                String part1 = kit.path("packages").get(0).path("packageId").asText();
                String part2 = kit.path("packages").get(1).path("packageId").asText();
                scan(task, part1);
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + part1 + "/seal"), SOFA_MEASURES))
                        .andExpect(status().isOk());
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + part2 + "/seal"), PART2_MEASURES))
                        .andExpect(status().isOk());
            } else {
                String parcel = data(packer(body(post(BASE + "/tasks/" + task + "/packages"), ONE_CARTON))
                        .andExpect(status().isCreated())).path("packageId").asText();
                scan(task, parcel);
                packer(body(post(BASE + "/tasks/" + task + "/packages/" + parcel + "/seal"), SOFA_MEASURES))
                        .andExpect(status().isOk());
            }
            shipments[i] = UUID.fromString(data(packer(body(post(BASE + "/tasks/" + task + "/packing-completion"),
                    i == 0 ? "{\"partial\":true,\"note\":\"one sofa now\"}" : "{}")).andExpect(status().isOk()))
                    .path("shipmentId").asText());
        }
        return shipments;
    }

    private void allowPartial(OrderSummary order, boolean allowed) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET allow_partial_shipment = ? "
                + "WHERE id = ?", allowed, order.orderId()));
    }

    /** The load of the vehicle carrying {@code shipment}'s parcels (one vehicle each here). */
    private String loadCarrying(JsonNode plan, UUID shipment) throws Exception {
        for (int seq = 1; seq <= plan.path("vehicles").size(); seq++) {
            String loadId = loadOf(plan, seq);
            for (JsonNode s : load(loadId).path("shipments")) {
                if (s.path("shipmentId").asText().equals(shipment.toString())) {
                    return loadId;
                }
            }
        }
        throw new AssertionError("no vehicle carries " + shipment);
    }

    private void loadAll(String loadId, UUID carrier) throws Exception {
        book(loadId, carrier, "51C-" + loadId.substring(0, 6));
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        for (String label : labels(load(loadId))) {
            scanOnto(loadId, label).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("review of #162, blocking 1-3: an order that may NOT ship in parts with S1 on truck 1 and S2 on truck 2 "
            + "(Sales turned the flag off after confirmation): truck 1 signs - nothing handed over, goods_left_at set, "
            + "the order no longer cancelled (ORDER_NOT_CANCELLABLE); truck 2 signs - S1 (on truck 1's load) and S2 "
            + "recorded together, the order SHIPPED")
    void noPartialOrderAcrossTrucks() throws Exception {
        OrderSummary order = ftlOrder(2);
        UUID[] shipments = packedInTwo(order, true);
        JsonNode plan = confirmed(List.of(shipments[0], shipments[1]), truck1t25);
        assertThat(plan.path("vehicles")).hasSize(2);
        String truck1 = loadCarrying(plan, shipments[0]);
        String truck2 = loadCarrying(plan, shipments[1]);
        assertThat(truck1).isNotEqualTo(truck2);
        allowPartial(order, false);

        loadAll(truck1, ghn);
        assertThat(data(handOver(truck1).andExpect(status().isOk())).path("status").asText()).isEqualTo("DISPATCHED");
        assertThat(shipmentStatus(shipments[0])).isEqualTo("READY_TO_DISPATCH");
        assertThat(jdbc.queryForObject("SELECT goods_left_at IS NOT NULL FROM ordering.customer_order WHERE id = ?",
                Boolean.class, order.orderId())).isTrue();
        oc(body(post(ORDERS + "/" + order.orderId() + "/admin-cancellation"),
                "{\"reasonCode\":\"CUSTOMER_REQUEST\",\"note\":\"customer changed its mind\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ORDER_NOT_CANCELLABLE"));

        loadAll(truck2, ghtk);
        handOver(truck2).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("SELECT status, load_id, carrier_id FROM fulfillment.shipment WHERE id = ?",
                shipments[0])).containsEntry("status", "HANDED_OVER").containsEntry("load_id", UUID.fromString(truck1))
                .containsEntry("carrier_id", ghn);
        assertThat(jdbc.queryForMap("SELECT status, load_id FROM fulfillment.shipment WHERE id = ?", shipments[1]))
                .containsEntry("status", "HANDED_OVER").containsEntry("load_id", UUID.fromString(truck2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_shipment WHERE order_id = ?", Long.class,
                order.orderId())).isEqualTo(2L);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(planStatus(plan)).isEqualTo("DISPATCHED");
    }

    @Test
    @DisplayName("review of #162, blocking 2: an order that may not ship in parts with units outside the plan is "
            + "refused when its first vehicle starts loading (409 ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED) - nothing on "
            + "board, the plan can still be reopened")
    void partialRefusedAtLoadingStart() throws Exception {
        OrderSummary order = ftlOrder(2);
        UUID[] shipments = packedInTwo(order, false);
        JsonNode plan = confirmed(List.of(shipments[0]), truck5t);
        allowPartial(order, false);
        String loadId = loadOf(plan, 1);
        book(loadId, ghn, "51C-777.77");
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED"));
        assertThat(load(loadId).path("status").asText()).isEqualTo("VEHICLE_BOOKED");
        JsonNode now = data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()));
        assertThat(step(now, "/reopening", "{\"expectedVersion\":" + now.path("version").asLong()
                + ",\"reason\":\"take the whole order\"}").path("status").asText()).isEqualTo("PLANNED");
    }

    @Test
    @DisplayName("review of #162: an unpaid BEFORE_DELIVERY deposit order is refused at a vehicle that completes no "
            + "shipment (409 ORDER_BALANCE_NOT_PAID); paid, it leaves")
    void unpaidAtANonLastTruck() throws Exception {
        OrderSummary order = ftlOrder(2);
        UUID shipment = packed(order, true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        assertThat(plan.path("vehicles")).hasSize(2);
        String truck1 = loadOf(plan, 1);
        BigDecimal total = order.total().amount();
        BigDecimal deposit = total.multiply(new BigDecimal("0.3"));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE ordering.customer_order SET payment_term = 'DEPOSIT', deposit_required = ?,
                           balance_policy = 'BEFORE_DELIVERY', paid_amount = ?, paid_in_full_at = NULL WHERE id = ?""",
                    deposit, deposit, order.orderId());
            jdbc.update("UPDATE payment.payment SET amount = ? WHERE order_id = ?", deposit, order.orderId());
        });
        loadAll(truck1, ghn);
        handOver(truck1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_BALANCE_NOT_PAID"));
        assertThat(load(truck1).path("status").asText()).isEqualTo("LOADING");
        assertThat(jdbc.queryForObject("SELECT goods_left_at FROM ordering.customer_order WHERE id = ?",
                java.sql.Timestamp.class, order.orderId())).isNull();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE ordering.customer_order SET paid_amount = total_amount WHERE id = ?", order.orderId());
            jdbc.update("UPDATE payment.payment SET amount = ? WHERE order_id = ?", total, order.orderId());
        });
        handOver(truck1).andExpect(status().isOk());
        assertThat(shipmentStatus(shipment)).isEqualTo("READY_TO_DISPATCH");
    }

    @Test
    @DisplayName("review of #162: the two trucks of one shipment signed at the same moment - both succeed, the shipment "
            + "is handed over once, the plan DISPATCHED once")
    void twoTrucksSignAtOnce() throws Exception {
        OrderSummary order = ftlOrder(2);
        UUID shipment = packed(order, true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        String truck1 = loadOf(plan, 1);
        String truck2 = loadOf(plan, 2);
        loadAll(truck1, ghn);
        loadAll(truck2, ghtk);
        List<String> bodies = new ArrayList<>();
        for (String loadId : List.of(truck1, truck2)) {
            bodies.add("{\"version\":" + load(loadId).path("version").asLong()
                    + ",\"carrierRepresentative\":\"Tai\",\"signed\":true}");
        }
        List<String> tokens = List.of(token(dispatcher, "WAREHOUSE_STAFF"), token(dispatcher, "WAREHOUSE_STAFF"));
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
        List<String> trucks = List.of(truck1, truck2);
        for (int i = 0; i < 2; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post(LOADS + "/" + trucks.get(n) + "/handover").header("Authorization",
                        tokens.get(n)).contentType("application/json").content(bodies.get(n)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (var f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses).containsOnly(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id = ? AND "
                + "status = 'HANDED_OVER'", Long.class, shipment)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_shipment WHERE id = ?", Long.class,
                shipment)).isEqualTo(1L);
        assertThat(planStatus(plan)).isEqualTo("DISPATCHED");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("decision 7 (review of #162): truck A left; the order on booked truck B is cancelled - B is "
            + "auto-cancelled ('auto-cancelled: nothing to carry'), flagged carrierBookingToCancel, and the plan turns "
            + "DISPATCHED")
    void autoCancelDispatchesThePlan() throws Exception {
        OrderSummary leaving = ftlOrder(1);
        OrderSummary doomed = ftlOrder(1);
        UUID kept = packed(leaving, true)[0];
        UUID[] cancelled = packed(doomed, true);
        JsonNode plan = confirmed(List.of(kept, cancelled[0]), truck1t25);
        assertThat(plan.path("vehicles")).hasSize(2);
        String truckA = loadCarrying(plan, kept);
        String truckB = loadCarrying(plan, cancelled[0]);
        assertThat(truckA).isNotEqualTo(truckB);
        book(truckB, ghtk, "51C-888.88");
        loadAll(truckA, ghn);
        handOver(truckA).andExpect(status().isOk());
        assertThat(planStatus(plan)).isEqualTo("CONFIRMED");

        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderCancelled(doomed.orderId(), doomed.orderNumber(),
                DemoData.CUSTOMER_ID, "PACKED", "OTHER", "test", BigDecimal.ZERO, BigDecimal.ZERO, "VND", coordinator,
                Instant.now())));
        Await.until("truck B called off", ASYNC, () -> "CANCELLED".equals(jdbc.queryForObject(
                "SELECT status FROM fulfillment.dispatch_load WHERE id = ?::uuid", String.class, truckB)));
        JsonNode b = load(truckB);
        assertThat(b.path("cancelReason").asText()).startsWith("auto-cancelled: nothing to carry");
        assertThat(b.path("carrierBookingToCancel").asBoolean()).isTrue();
        assertThat(load(truckA).path("carrierBookingToCancel").asBoolean()).isFalse();
        assertThat(planStatus(plan)).isEqualTo("DISPATCHED");
        assertThat(shipmentStatus(kept)).isEqualTo("HANDED_OVER");
    }

    // ---- who may -----------------------------------------------------------------------------

    @Test
    @DisplayName("permissions and scope: the coordinator reads a vehicle's load but does not book, load or unload "
            + "(403); warehouse staff of another warehouse get OUT_OF_DATA_SCOPE")
    void whoMay() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        oc(get(LOADS + "/" + loadId)).andExpect(status().isOk());
        oc(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isForbidden());
        oc(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isForbidden());
        oc(body(post(LOADS + "/" + loadId + "/booking-cancellation"), "{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        oc(body(post(LOADS + "/" + loadId + "/unloads"), "{\"packageCode\":\"PKG-X\"}"))
                .andExpect(status().isForbidden());
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", body(post(LOADS + "/" + loadId + "/booking"),
                "{\"carrierId\":\"" + ghn + "\"}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", post(LOADS + "/" + loadId + "/loading-start"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{}")).andExpect(status().isBadRequest());
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CARRIER_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.dispatch_load WHERE id = ?::uuid", String.class,
                loadId)).isEqualTo("PLANNED");
    }

    // ---- the database, a second time (V20261022001400) ---------------------------------------

    @Test
    @DisplayName("V20261022001400: a manifest row takes its load's mode (another is refused); a plan's vehicle is "
            + "booked before it loads; a vehicle with a parcel left to unload does not turn DISPATCHED")
    void theDatabaseNets() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        assertThat(jdbc.queryForObject("SELECT mode FROM fulfillment.dispatch_load_shipment WHERE load_id = ?::uuid",
                String.class, loadId)).isEqualTo("FULL_TRUCKLOAD");
        Throwable otherMode = catchThrowable(() -> jdbc.update("""
                INSERT INTO fulfillment.dispatch_load_shipment (id, load_id, shipment_id, mode, added_at, added_by)
                VALUES (gen_random_uuid(), ?::uuid, ?, 'PARCEL', NOW(), ?)""", loadId, shipment, dispatcher));
        assertThat(otherMode).hasMessageContaining("tg_dispatch_load_shipment_mode");
        Throwable unbooked = catchThrowable(() -> jdbc.update("UPDATE fulfillment.dispatch_load SET status = 'LOADING', "
                + "carrier_id = ?, loading_started_at = NOW() WHERE id = ?::uuid", ghn, loadId));
        assertThat(unbooked).hasMessageContaining("ck_dispatch_load_ftl_booked");

        book(loadId, ghn, "51C-666.66");
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        UUID parcel = jdbc.queryForObject("SELECT id FROM fulfillment.package WHERE shipment_id = ?", UUID.class,
                shipment);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO fulfillment.dispatch_load_unload (id, load_id, package_id, shipment_id, reason, requested_at)
                VALUES (gen_random_uuid(), ?::uuid, ?, ?, 'test', NOW())""", loadId, parcel, shipment));
        Throwable leaving = catchThrowable(() -> jdbc.update(
                "UPDATE fulfillment.dispatch_load SET status = 'DISPATCHED' WHERE id = ?::uuid", loadId));
        assertThat(leaving).hasMessageContaining("tg_dispatch_load_unload_pending");
    }
}
