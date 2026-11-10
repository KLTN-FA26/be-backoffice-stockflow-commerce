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
import com.stockflow.support.CartonRows;
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
        "stockflow.fulfillment.load-planning.solver-wall-limit=PT2S",
        "stockflow.test.context=ftl-dispatch",
        // 3-2: waybill files are scanned as every upload is; no ClamAV runs in tests.
        "stockflow.upload-inspection.enabled=false"})
class FtlDispatchIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(15);
    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final String ORDERS = "/api/v1/orders";
    private static final String BASE = "/api/v1/fulfillment";
    private static final String PLANS = BASE + "/load-plans";
    private static final String LOADS = BASE + "/loads";

    @Autowired MockMvc mvc;
    private CartonStationSupport.Parts parts;
    @MockitoBean JwtDecoder decoder;
    /** 3-4: the carrier's API, faked - no test calls Ahamove. */
    @MockitoBean com.stockflow.fulfillment.internal.service.CarrierBookingGateway carrierGateway;
    @Autowired com.stockflow.fulfillment.internal.service.CarrierBookingSweeper bookingSweeper;
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
        CartonStationSupport.giveCartonsASize(tx, jdbc);
        parts = new CartonStationSupport.Parts(this::packer, tx, jdbc, json);
        coordinator = staff("ORDER_COORDINATOR");
        picker = staff("WAREHOUSE_STAFF", HCM);
        dispatcher = staff("WAREHOUSE_STAFF", HCM);
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        dispatcherElsewhere = staff("WAREHOUSE_STAFF", elsewhere);
        truck5t = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-5T'", UUID.class);
        truck1t25 = jdbc.queryForObject("SELECT id FROM fulfillment.vehicle_type WHERE code = 'TRUCK-1T25'",
                UUID.class);
        // Truck hirers of the test's own: the parcel carriers are retired (V20261108001300).
        ghn = tx.execute(s -> ReferenceRows.truckCarrier(entityManager, "Xe tải Nhanh"));
        ghtk = tx.execute(s -> ReferenceRows.truckCarrier(entityManager, "Xe tải Tiết Kiệm"));
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

    /**
     * The order's sofas, as whole sealed cartons of one (the demo sofa's carton unit): an order is given whole cartons
     * only (team plan 4C-2), so loose sofas would not be allocated and the order would draw on the shared demo cartons.
     * Received long ago, so the allocator hands them out first (FIFO).
     */
    private void stock(int sofas) {
        tx.executeWithoutResult(s -> CartonRows.stock(jdbc, SOFA.code(), "HCM-A02-1-A", sofas, 1, null,
                Instant.now().minus(Duration.ofDays(400))));
    }

    /** A paid order of the demo customer for {@code sofas} sofas, released at HCM (every order is a full truckload). */
    private OrderSummary ftlOrder(int sofas) throws Exception {
        return ftlOrder(sofas, DemoData.CUSTOMER_ID);
    }

    private OrderSummary ftlOrder(int sofas, UUID customer) throws Exception {
        stock(sofas);
        OrderSummary order = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), customer,
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
        oc(body(post(ORDERS + "/" + order.orderId() + "/release"), "{\"warehouseId\":\"" + HCM + "\"}"))
                .andExpect(status().isOk());
        return order;
    }

    /** The order picked and packed at the check station, one shipment of all its cartons. Returns the shipment and task. */
    private UUID[] packed(OrderSummary order, boolean kits) throws Exception {
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        packer(post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, lpn AS scan "
                        + "FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/confirmation"),
                    "{\"locationCode\":\"" + line.get("location_code") + "\",\"lpn\":\"" + line.get("scan")
                            + "\"}")).andExpect(status().isOk());
        }
        packer(post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk());
        // The check station (4C-6): start, scan the LPN of every picked carton, label, complete: one shipment.
        UUID shipment = CartonStationSupport.ship(this::packer, jdbc, json, task, null);
        return new UUID[] {shipment, task};
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

    /** Booked with the carrier's waybill number (3-2: required before the handover), one per load. */
    private JsonNode book(String loadId, UUID carrier, String plate) throws Exception {
        return data(dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier + "\""
                + (plate == null ? "" : ",\"vehiclePlate\":\"" + plate + "\"") + ",\"driverName\":\"Tai\""
                + ",\"waybillNo\":\"WB-" + loadId + "\"}"))
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

        // Confirmation opened both vehicles' manifests with the shipment, each listing its own carton.
        JsonNode first = load(v1);
        JsonNode second = load(v2);
        for (JsonNode l : List.of(first, second)) {
            assertThat(l.path("status").asText()).isEqualTo("PLANNED");
            assertThat(l.path("loadPlanCode").asText()).isEqualTo(plan.path("code").asText());
            assertThat(l.path("vehicleTypeCode").asText()).isEqualTo("TRUCK-1T25");
            assertThat(l.path("shipments")).singleElement().satisfies(s -> assertThat(s.path("shipmentId").asText())
                    .isEqualTo(shipment.toString()));
            assertThat(l.path("expectedPackageCount").asInt()).isEqualTo(1);
        }
        assertThat(first.path("vehicleSeq").asInt()).isEqualTo(1);
        assertThat(labels(first)).doesNotContainAnyElementsOf(labels(second));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.dispatch_load_shipment WHERE shipment_id = ? "
                + "AND active", Integer.class, shipment)).isEqualTo(2);

        // Booking (09 §4.2 step 5); loading only once booked.
        dispatch(post(LOADS + "/" + v1 + "/loading-start")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
        JsonNode booked = book(v1, ghn, "51C-111.11");
        assertThat(booked.path("status").asText()).isEqualTo("VEHICLE_BOOKED");
        assertThat(booked.path("carrierCode").asText())
                .isEqualTo(jdbc.queryForObject("SELECT code FROM fulfillment.carrier WHERE id = ?", String.class, ghn));
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

        // Nothing scanned: the vehicle's carton is missing - LOAD_NOT_FULLY_LOADED names it.
        List<String> mine = labels(first);
        JsonNode notWhole = body(handOver(v1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_NOT_FULLY_LOADED")));
        assertThat(notWhole.path("message").asText()).contains(mine.getFirst());
        scanOnto(v1, mine.getFirst()).andExpect(status().isOk());

        // The per-vehicle manifest: only this vehicle's parcels.
        JsonNode manifest = data(dispatch(get(LOADS + "/" + v1 + "/manifest")).andExpect(status().isOk()));
        assertThat(manifest.path("vehicleSeq").asInt()).isEqualTo(1);
        assertThat(manifest.path("loadPlanCode").asText()).isEqualTo(plan.path("code").asText());
        assertThat(manifest.path("packageCount").asInt()).isEqualTo(1);
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
        Map<String, Object> handed = jdbc.queryForMap("SELECT status, load_id, carrier_id FROM "
                + "fulfillment.shipment WHERE id = ?", shipment);
        assertThat(handed).containsEntry("status", "HANDED_OVER").containsEntry("load_id", UUID.fromString(v2))
                .containsEntry("carrier_id", ghtk);
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
            + "load carries the shipment and is booked again; the cancelled load is not booked again")
    void reopeningCancelsBookings() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        book(loadId, ghn, "51C-333.33");

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
    }

    // ---- the waybill number (3-2; 09 §4.4, BR-23, BR-24, BR-28) --------------------------------

    private ResultActions waybill(UUID user, String role, String loadId, String number, String reason)
            throws Exception {
        return as(user, role, body(put(LOADS + "/" + loadId + "/waybill"), "{\"waybillNo\":\"" + number + "\""
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}"));
    }

    private static byte[] onePagePdf() throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument();
             var out = new java.io.ByteArrayOutputStream()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("3-2: a load leaves only with its carrier's waybill number; the number is unique within its carrier among "
            + "live loads; after the vehicle left only the warehouse manager corrects it, with a reason; every change "
            + "is in the history, and the number is on the load, the list and the manifest")
    void theWaybillNumber() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        // No carrier booked yet: there is no carrier's number to record.
        waybill(dispatcher, "WAREHOUSE_STAFF", loadId, "WB-EARLY", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));

        // Booked without a number, loaded, signed: refused until the number is recorded (BR-23).
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + ghn
                + "\",\"vehiclePlate\":\"51C-777.77\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waybillNo").doesNotExist());
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        for (String label : labels(load(loadId))) {
            scanOnto(loadId, label).andExpect(status().isOk());
        }
        handOver(loadId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_WAYBILL_MISSING"));

        // Another live load of the same carrier has the number: refused, naming it.
        String other = historyLoad("51C-OTHER");
        String taken = jdbc.queryForObject("SELECT waybill_no FROM fulfillment.dispatch_load WHERE id = ?::uuid",
                String.class, other);
        waybill(dispatcher, "WAREHOUSE_STAFF", loadId, taken, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WAYBILL_DUPLICATE"));
        String number = "WB-" + loadId;
        waybill(dispatcher, "WAREHOUSE_STAFF", loadId, number, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waybillNo").value(number))
                .andExpect(jsonPath("$.data.carrierConnectionType").value("MANUAL"));
        handOver(loadId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"));
        dispatch(get(LOADS + "/" + loadId + "/manifest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waybillNo").value(number));
        assertThat(loadsPage("&search=" + number).path("items")).singleElement()
                .satisfies(row -> assertThat(row.path("waybillNo").asText()).isEqualTo(number));

        // After the vehicle left: a correction - the warehouse manager's, with a reason (BR-24).
        waybill(dispatcher, "WAREHOUSE_STAFF", loadId, number + "-FIX", "the carrier sent another")
                .andExpect(status().isForbidden());
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        waybill(manager, "WAREHOUSE_MANAGER", loadId, number + "-FIX", null).andExpect(status().isBadRequest());
        // The plate and driver are on the signed manifest: a correction changes the number only.
        as(manager, "WAREHOUSE_MANAGER", body(put(LOADS + "/" + loadId + "/waybill"), "{\"waybillNo\":\"" + number
                + "-FIX\",\"driverName\":\"Someone else\",\"reason\":\"the carrier sent another\"}"))
                .andExpect(status().isBadRequest());
        waybill(manager, "WAREHOUSE_MANAGER", loadId, number + "-FIX", "the carrier sent another")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.waybillNo").value(number + "-FIX"));

        JsonNode history = data(dispatch(get(LOADS + "/" + loadId + "/waybill-history")).andExpect(status().isOk()));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).hasNonNull("oldWaybillNo")).isFalse();
        assertThat(history.get(0).path("newWaybillNo").asText()).isEqualTo(number);
        assertThat(history.get(0).path("source").asText()).isEqualTo("MANUAL");
        assertThat(history.get(0).path("changedBy").asText()).isEqualTo(dispatcher.toString());
        assertThat(history.get(1).path("newWaybillNo").asText()).isEqualTo(number + "-FIX");
        assertThat(history.get(1).path("changedBy").asText()).isEqualTo(manager.toString());
        assertThat(history.get(1).path("reason").asText()).isEqualTo("the carrier sent another");
    }

    @Test
    @DisplayName("3-2: a number given at booking is recorded; a cancelled booking takes it with it (the history keeps "
            + "it) and frees it; an API_BOOKING carrier's number is never typed; waybill files are kept with a live "
            + "load and not with a cancelled one")
    void waybillWithTheBooking() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        String number = "WB-" + loadId;
        assertThat(book(loadId, ghn, "51C-888.88").path("waybillNo").asText()).isEqualTo(number);
        dispatch(body(post(LOADS + "/" + loadId + "/booking-cancellation"), "{\"reason\":\"xe không tới\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.waybillNo").doesNotExist());
        JsonNode history = data(dispatch(get(LOADS + "/" + loadId + "/waybill-history")).andExpect(status().isOk()));
        assertThat(history).hasSize(2);
        assertThat(history.get(1).path("oldWaybillNo").asText()).isEqualTo(number);
        assertThat(history.get(1).hasNonNull("newWaybillNo")).isFalse();
        assertThat(history.get(1).path("reason").asText()).contains("xe không tới");
        // Booked again, a blank number is no number; then given again.
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + ghn
                + "\",\"waybillNo\":\" \"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waybillNo").doesNotExist());
        assertThat(book(loadId, ghn, "51C-888.88").path("waybillNo").asText()).isEqualTo(number);

        // A carrier booked through its API gives the number itself (09 BR-29, 3-4): nobody types it.
        UUID aha = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO fulfillment.carrier (id, code, name, status, connection_type, webhook_enabled,
                    api_environment, api_account, api_key_sealed, webhook_auth_header, webhook_auth_value_sealed)
                VALUES (?, ?, 'Ahamove', 'ACTIVE', 'API_BOOKING', TRUE, 'STAGING', '84908000000',
                        'v1:AAAAAAAAAAAAAAAAAAAAAAAA', 'apikey', 'v1:BBBBBBBBBBBBBBBBBBBBBBBB')""",
                aha, "AHA" + aha.toString().substring(28).toUpperCase()));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha
                + "\",\"waybillNo\":\"AHA-TYPED\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        // A load the carrier's API booked (3-4) - set here by hand - takes no typed number either.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE fulfillment.dispatch_load SET carrier_id = ? WHERE id = "
                + "?::uuid", aha, loadId));
        waybill(dispatcher, "WAREHOUSE_STAFF", loadId, "AHA-TYPED", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WAYBILL_FROM_CARRIER_API"));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE fulfillment.dispatch_load SET carrier_id = ? WHERE id = "
                + "?::uuid", ghn, loadId));

        // The carrier's waybill file, kept with the load and fetched by a short-lived link.
        var file = new org.springframework.mock.web.MockMultipartFile("file", "van-don.pdf", "application/pdf",
                onePagePdf());
        JsonNode kept = data(as(dispatcher, "WAREHOUSE_STAFF", org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.multipart(LOADS + "/" + loadId + "/waybill-files").file(file))
                .andExpect(status().isCreated()));
        assertThat(kept.path("fileName").asText()).isEqualTo("van-don.pdf");
        assertThat(data(dispatch(get(LOADS + "/" + loadId + "/waybill-files")).andExpect(status().isOk())))
                .singleElement().satisfies(f -> assertThat(f.path("fileId").asText())
                        .isEqualTo(kept.path("fileId").asText()));
        // The file is found and the link asked of the store; the local store of the tests signs none (MinIO's
        // integration tests cover signing), so it answers STORAGE_ERROR rather than a link.
        dispatch(get(LOADS + "/" + loadId + "/waybill-files/" + kept.path("fileId").asText() + "/download-url"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.errorCode").value("STORAGE_ERROR"));
        dispatch(get(LOADS + "/" + loadId + "/waybill-files/" + UUID.randomUUID() + "/download-url"))
                .andExpect(status().isNotFound());
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(LOADS + "/" + loadId + "/waybill-files"))
                .andExpect(status().isForbidden());

        // Reopened, the plan cancels the load: its files are history, it takes no new one.
        JsonNode now = data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()));
        step(now, "/reopening", "{\"expectedVersion\":" + now.path("version").asLong() + ",\"reason\":\"đổi xe\"}");
        as(dispatcher, "WAREHOUSE_STAFF", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(LOADS + "/" + loadId + "/waybill-files").file(file)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
    }

    private ResultActions importing(UUID user, String role, String path, String csv) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "van-don.csv", "application/vnd.ms-excel",
                ("\uFEFFMã chuyến,Mã vận đơn,Biển số,Tên tài xế,Điện thoại tài xế\r\n" + csv)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return as(user, role, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/waybill-imports" + path).file(file));
    }

    /** A vehicle of its own plan, booked with {@code carrier} and no waybill number: its load. */
    private JsonNode bookedWithoutNumber(UUID carrier) throws Exception {
        String loadId = loadOf(confirmed(List.of(packed(ftlOrder(1), false)[0]), truck5t), 1);
        return data(dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-100.00\"}")).andExpect(status().isOk()));
    }

    @Test
    @DisplayName("3-3: a file of waybill numbers is previewed without writing, then imported - its valid lines "
            + "applied, the others kept with their reason, the file kept with the loads it applied to - and a "
            + "second file overwrites; a load of another warehouse is a refused line, not a refused file")
    void waybillNumbersFromAFile() throws Exception {
        JsonNode first = bookedWithoutNumber(ghn);
        JsonNode second = bookedWithoutNumber(ghn);
        String one = first.path("code").asText();
        String two = second.path("code").asText();
        String other = historyLoad("51C-HELD");
        String held = jdbc.queryForObject("SELECT waybill_no FROM fulfillment.dispatch_load WHERE id = ?::uuid",
                String.class, other);
        String number = "WB-IMP-" + first.path("loadId").asText();
        String csv = one + "," + number + ",51C-111.11,Tài,0900\r\n"
                + two + "," + held + "\r\n"
                + "LD-NOPE," + number + "-X\r\n"
                + "," + number + "-Y\r\n";

        JsonNode preview = data(importing(dispatcher, "WAREHOUSE_STAFF", "/preview", csv).andExpect(status().isOk()));
        assertThat(preview.path("importId").isMissingNode() || preview.path("importId").isNull()).isTrue();
        assertThat(preview.path("rowsTotal").asInt()).isEqualTo(4);
        assertThat(preview.path("rowsApplied").asInt()).isEqualTo(1);
        assertThat(preview.path("lines").findValuesAsText("outcome")).containsExactly("NEW", "ERROR", "ERROR",
                "ERROR");
        assertThat(preview.path("lines").get(1).path("problem").asText()).isEqualTo("DUPLICATE_IN_CARRIER");
        assertThat(preview.path("lines").get(1).path("holderCode").asText()).isNotBlank();
        assertThat(preview.path("lines").get(2).path("problem").asText()).isEqualTo("LOAD_NOT_FOUND");
        assertThat(preview.path("lines").get(3).path("problem").asText()).isEqualTo("MISSING_FIELDS");
        assertThat(load(first.path("loadId").asText()).has("waybillNo")
                && !load(first.path("loadId").asText()).path("waybillNo").isNull()).isFalse();
        // Another warehouse's dispatcher sees the same lines refused as out of scope, not the loads.
        JsonNode elsewhere = data(importing(dispatcherElsewhere, "WAREHOUSE_STAFF", "/preview", csv)
                .andExpect(status().isOk()));
        assertThat(elsewhere.path("lines").get(0).path("problem").asText()).isEqualTo("LOAD_OUT_OF_SCOPE");
        assertThat(elsewhere.path("lines").get(0).hasNonNull("loadId")).isFalse();

        JsonNode imported = data(importing(dispatcher, "WAREHOUSE_STAFF", "", csv).andExpect(status().isCreated()));
        String importId = imported.path("importId").asText();
        assertThat(imported.path("rowsApplied").asInt()).isEqualTo(1);
        assertThat(imported.path("rowsRejected").asInt()).isEqualTo(3);
        JsonNode now = load(first.path("loadId").asText());
        assertThat(now.path("waybillNo").asText()).isEqualTo(number);
        assertThat(now.path("vehiclePlate").asText()).isEqualTo("51C-111.11");
        assertThat(now.path("driverName").asText()).isEqualTo("Tài");
        JsonNode history = data(dispatch(get(LOADS + "/" + first.path("loadId").asText() + "/waybill-history"))
                .andExpect(status().isOk()));
        assertThat(history).singleElement().satisfies(h -> {
            assertThat(h.path("source").asText()).isEqualTo("FILE");
            assertThat(h.path("reason").asText()).contains("van-don.csv").contains("line 2");
        });
        assertThat(data(dispatch(get(LOADS + "/" + first.path("loadId").asText() + "/waybill-files"))
                .andExpect(status().isOk()))).singleElement()
                .satisfies(f -> assertThat(f.path("contentType").asText()).isEqualTo("text/csv"));
        assertThat(data(dispatch(get(LOADS + "/" + second.path("loadId").asText() + "/waybill-files"))
                .andExpect(status().isOk()))).isEmpty();
        assertThat(data(dispatch(get(BASE + "/waybill-imports/" + importId)).andExpect(status().isOk()))
                .path("lines")).hasSize(4);
        assertThat(data(dispatch(get(BASE + "/waybill-imports")).andExpect(status().isOk())).path("items")
                .findValuesAsText("importId")).contains(importId);
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(BASE + "/waybill-imports/" + importId))
                .andExpect(status().isNotFound());

        // A second file: the first load would get another number ("sẽ ghi đè"), the second its first one. The
        // overwrite applies only once the dispatcher confirms the line the preview showed (BR-25).
        String csv2 = one + "," + number + "-2\r\n" + two + ",WB-IMP2-" + second.path("loadId").asText() + "\r\n";
        assertThat(data(importing(dispatcher, "WAREHOUSE_STAFF", "/preview", csv2).andExpect(status().isOk()))
                .path("lines").findValuesAsText("outcome")).containsExactly("OVERWRITE", "NEW");
        JsonNode unconfirmed = data(importing(dispatcher, "WAREHOUSE_STAFF", "", csv2)
                .andExpect(status().isCreated()));
        assertThat(unconfirmed.path("lines").findValuesAsText("outcome")).containsExactly("ERROR", "NEW");
        assertThat(unconfirmed.path("lines").get(0).path("problem").asText()).isEqualTo("OVERWRITE_NOT_CONFIRMED");
        assertThat(load(first.path("loadId").asText()).path("waybillNo").asText()).isEqualTo(number);
        JsonNode again = data(importing(dispatcher, "WAREHOUSE_STAFF", "?overwriteLines=2", csv2)
                .andExpect(status().isCreated()));
        assertThat(again.path("lines").findValuesAsText("outcome")).containsExactly("OVERWRITE", "SAME");
        assertThat(load(first.path("loadId").asText()).path("waybillNo").asText()).isEqualTo(number + "-2");
        // The second load's line changed nothing the second time: no second copy of the file is kept with it.
        assertThat(data(dispatch(get(LOADS + "/" + second.path("loadId").asText() + "/waybill-files"))
                .andExpect(status().isOk()))).hasSize(1);

        // Not the template's table: refused whole.
        importing(dispatcher, "WAREHOUSE_STAFF", "/preview", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WAYBILL_IMPORT_INVALID_FILE"));
        oc(get(BASE + "/waybill-imports/template")).andExpect(status().isForbidden());
        byte[] template = dispatch(get(BASE + "/waybill-imports/template")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(template[0]).isEqualTo((byte) 0xEF);
        assertThat(new String(template, java.nio.charset.StandardCharsets.UTF_8).split(",")).hasSize(5);
    }

    // ---- booking through the carrier's API (3-4; 09 §4.2 step 6, BR-29, E24) -------------------

    /** An API_BOOKING carrier with a service code for 5 t trucks and a pickup point at HCM, both in province 79. */
    private UUID apiCarrier(UUID manager) throws Exception {
        String code = "AHA" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Ahamove\",\"connection\":{\"connectionType\":\"API_BOOKING\","
                        + "\"webhookAuthHeader\":\"apikey\",\"webhookAuthValue\":\"webhook-credential-0123456789\","
                        + "\"apiEnvironment\":\"STAGING\",\"apiAccount\":\"84908000000\",\"apiKey\":\"aha-key-0123456789\"}}"))
                .andExpect(status().isCreated())).path("carrierId").asText());
        as(manager, "WAREHOUSE_MANAGER", body(put(BASE + "/carriers/" + carrier + "/service-codes"),
                "{\"codes\":[{\"vehicleTypeId\":\"" + truck5t + "\",\"provinceCode\":\"79\","
                        + "\"carrierCityCode\":\"SGN\",\"serviceCode\":\"SGN-TRUCK-2000\"}]}"))
                .andExpect(status().isOk());
        as(manager, "WAREHOUSE_MANAGER", body(put(BASE + "/carriers/" + carrier + "/pickup-points"),
                "{\"pickupPoints\":[{\"warehouseId\":\"" + HCM + "\",\"provinceCode\":\"79\","
                        + "\"address\":\"1 Lê Lợi, Bến Nghé, Quận 1, Hồ Chí Minh\",\"contactName\":\"Kho HCM\","
                        + "\"contactPhone\":\"0909000111\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].provinceCode").value("79"));
        return carrier;
    }

    /** The shipment's order delivered to {@code province}. */
    private void deliverTo(UUID shipment, String province) {
        // A labelled order's delivery address is locked (17 BR-16, tg_order_label_lock). This test is about carriers by
        // province, so the fixture writes the address with the row triggers off for its own transaction.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                UPDATE ordering.customer_order SET shipping_recipient_name = 'Lan', shipping_phone = '0912345678',
                    shipping_line1 = '2 Hai Bà Trưng', shipping_ward_code = '26740', shipping_ward_name = 'Bến Nghé',
                    shipping_province_code = ?,
                    shipping_province_name = 'Hồ Chí Minh', shipping_country_code = 'VN'
                 WHERE id = (SELECT order_id FROM fulfillment.shipment WHERE id = ?)""", province, shipment);
        });
    }

    @Test
    @DisplayName("3-4: an API_BOOKING carrier is booked through its API with no transaction open - the warehouse the "
            + "pickup, each shipment a drop-off - and its order id becomes the waybill number; a refusal leaves the "
            + "load PLANNED; a cancel cancels the order there first; a load called off has its order cancelled by "
            + "the sweep")
    void bookingThroughTheCarriersApi() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        String loadCode = load(loadId).path("code").asText();
        String shipmentCode = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                shipment);
        String book = "{\"carrierId\":\"" + aha + "\",\"pickupAt\":\"2026-11-20T01:00:00Z\",\"stopShipmentIds\":[\""
                + shipment + "\"]}";

        // A drop-off outside the province the carrier picks up in (E24): refused before any call.
        deliverTo(shipment, "01");
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), book)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CARRIER_STOP_OUT_OF_AREA"));
        deliverTo(shipment, "79");
        // The plate, driver and number are the carrier's to give.
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha
                + "\",\"waybillNo\":\"AHA-TYPED\"}")).andExpect(status().isBadRequest());

        // The carrier refuses: the load stays PLANNED, the booking says why.
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.CarrierCallFailed(true, "SERVICE_NOT_VALID_AT_PICKUP",
                "406 SERVICE_NOT_VALID_AT_PICKUP: not served at the pickup"));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), book)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CARRIER_BOOKING_FAILED"));
        assertThat(load(loadId).path("status").asText()).isEqualTo("PLANNED");
        assertThat(load(loadId).path("carrierBooking").path("status").asText()).isEqualTo("FAILED");
        assertThat(load(loadId).path("carrierBooking").path("lastError").asText())
                .contains("SERVICE_NOT_VALID_AT_PICKUP");

        // The carrier takes it: asked with nothing open, the order id is the waybill number.
        java.util.concurrent.atomic.AtomicReference<Boolean> inTransaction = new java.util.concurrent.atomic
                .AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<com.stockflow.fulfillment.internal.service.CarrierBookingGateway
                .Order> sent = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.reset(carrierGateway);
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                    inTransaction.set(org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    sent.set(call.getArgument(1));
                    return new com.stockflow.fulfillment.internal.service.CarrierBookingGateway.Placed("AHA-24ABCD",
                            "https://expressstg.ahamove.com/s/24ABCD");
                });
        JsonNode booked = data(dispatch(body(post(LOADS + "/" + loadId + "/booking"), book))
                .andExpect(status().isOk()));
        assertThat(inTransaction.get()).isFalse();
        assertThat(booked.path("status").asText()).isEqualTo("VEHICLE_BOOKED");
        assertThat(booked.path("waybillNo").asText()).isEqualTo("AHA-24ABCD");
        assertThat(booked.path("carrierBooking").path("status").asText()).isEqualTo("BOOKED");
        assertThat(booked.path("carrierBooking").path("trackingLink").asText()).contains("24ABCD");
        assertThat(sent.get().serviceCode()).isEqualTo("SGN-TRUCK-2000");
        assertThat(sent.get().remarks()).isEqualTo(loadCode);
        assertThat(sent.get().pickupAt()).isEqualTo(Instant.parse("2026-11-20T01:00:00Z"));
        assertThat(sent.get().route()).hasSize(2);
        assertThat(sent.get().route().get(0).address()).startsWith("1 Lê Lợi");
        assertThat(sent.get().route().get(1).trackingNumber()).isEqualTo(shipmentCode);
        assertThat(sent.get().route().get(1).address()).contains("2 Hai Bà Trưng").contains("Bến Nghé");
        JsonNode history = data(dispatch(get(LOADS + "/" + loadId + "/waybill-history")).andExpect(status().isOk()));
        assertThat(history.get(history.size() - 1).path("source").asText()).isEqualTo("API");
        // Booked through the API, it is not booked again, nor given a typed number.
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), book)).andExpect(status().isConflict());
        waybill(dispatcher, "WAREHOUSE_STAFF", loadId, "AHA-TYPED", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("WAYBILL_FROM_CARRIER_API"));
        // Nor rebooked by hand with another carrier: the order there would be held for no load.
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + ghn
                + "\",\"vehiclePlate\":\"51C-999.99\",\"waybillNo\":\"WB-OVER\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARRIER_BOOKING_IN_PROGRESS"));
        assertThat(load(loadId).path("waybillNo").asText()).isEqualTo("AHA-24ABCD");

        // Cancelled: the carrier first. Refused (the driver picked up, the order is under way): nothing changes here.
        org.mockito.Mockito.when(carrierGateway.orderStatus(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("AHA-24ABCD"))).thenReturn(java.util.Optional.of("IN PROCESS"));
        org.mockito.Mockito.doThrow(new com.stockflow.fulfillment.internal.service.CarrierBookingGateway
                .CarrierCallFailed(true, "INVALID_ORDER_STATUS", "406 INVALID_ORDER_STATUS"))
                .when(carrierGateway).cancel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(
                        "AHA-24ABCD"));
        dispatch(body(post(LOADS + "/" + loadId + "/booking-cancellation"), "{\"reason\":\"đổi giờ\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARRIER_CANCEL_REFUSED"));
        assertThat(load(loadId).path("status").asText()).isEqualTo("VEHICLE_BOOKED");
        org.mockito.Mockito.doNothing().when(carrierGateway).cancel(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        JsonNode cancelled = data(dispatch(body(post(LOADS + "/" + loadId + "/booking-cancellation"),
                "{\"reason\":\"đổi giờ\"}")).andExpect(status().isOk()));
        assertThat(cancelled.path("status").asText()).isEqualTo("PLANNED");
        assertThat(cancelled.has("waybillNo") && !cancelled.path("waybillNo").isNull()).isFalse();
        assertThat(cancelled.path("carrierBooking").path("status").asText()).isEqualTo("CANCELLED");

        // Booked again, then the plan reopened: the load is called off and the sweep cancels the order there.
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-24ABCE", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), book)).andExpect(status().isOk());
        JsonNode now = data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()));
        step(now, "/reopening", "{\"expectedVersion\":" + now.path("version").asLong() + ",\"reason\":\"đổi xe\"}");
        org.mockito.Mockito.clearInvocations(carrierGateway);
        bookingSweeper.settle();
        org.mockito.Mockito.verify(carrierGateway).cancel(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("AHA-24ABCE"));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.carrier_booking WHERE carrier_order_id = "
                + "'AHA-24ABCE'", String.class)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("3-4: a call that does not come back may have made an order - the claim holds the load (UNCONFIRMED), "
            + "no second order is made until a dispatcher releases it; a cancel refused for an order the carrier "
            + "dropped already still cancels")
    void aCallThatDoesNotComeBack() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        String loadCode = load(loadId).path("code").asText();
        deliverTo(shipment, "79");
        String book = "{\"carrierId\":\"" + aha + "\"}";

        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.CarrierCallFailed(false, null, "Ahamove did not answer"));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), book)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("EXTERNAL_SERVICE_ERROR"));
        JsonNode held = load(loadId);
        assertThat(held.path("status").asText()).isEqualTo("PLANNED");
        assertThat(held.path("carrierBooking").path("status").asText()).isEqualTo("UNCONFIRMED");
        assertThat(held.path("carrierBooking").path("lastError").asText()).contains(loadCode);

        org.mockito.Mockito.reset(carrierGateway);
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-2", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), book)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARRIER_BOOKING_IN_PROGRESS"));
        org.mockito.Mockito.verify(carrierGateway, org.mockito.Mockito.never()).place(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        // The dispatcher checked the carrier's portal: released, then booked.
        dispatch(body(post(LOADS + "/" + loadId + "/booking-cancellation"), "{\"reason\":\"no order on the portal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PLANNED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.carrier_booking WHERE load_id = ?::uuid "
                + "AND status = 'FAILED' AND cancel_reason = 'no order on the portal'", Long.class, loadId)).isOne();
        assertThat(data(dispatch(body(post(LOADS + "/" + loadId + "/booking"), book)).andExpect(status().isOk()))
                .path("waybillNo").asText()).isEqualTo("AHA-2");

        // Refused, but the carrier no longer holds the order (nobody took it): cancelled here as well.
        org.mockito.Mockito.doThrow(new com.stockflow.fulfillment.internal.service.CarrierBookingGateway
                .CarrierCallFailed(true, "INVALID_ORDER_STATUS", "406 INVALID_ORDER_STATUS"))
                .when(carrierGateway).cancel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(
                        "AHA-2"));
        org.mockito.Mockito.when(carrierGateway.orderStatus(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("AHA-2"))).thenReturn(java.util.Optional.of("CANCELLED"));
        JsonNode cancelled = data(dispatch(body(post(LOADS + "/" + loadId + "/booking-cancellation"),
                "{\"reason\":\"đổi giờ\"}")).andExpect(status().isOk()));
        assertThat(cancelled.path("status").asText()).isEqualTo("PLANNED");
        assertThat(cancelled.path("carrierBooking").path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("3-4: while the carrier takes the order, a cancel is refused (the claim is in flight); the load called "
            + "off meanwhile does not take the order - it is cancelled at the carrier again")
    void aLoadThatMovesWhileTheCarrierBooks() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        deliverTo(shipment, "79");
        java.util.concurrent.atomic.AtomicInteger cancelDuringCall = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> cancelError = new java.util.concurrent.atomic
                .AtomicReference<>();
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
                    var during = dispatch(body(post(LOADS + "/" + loadId + "/booking-cancellation"),
                            "{\"reason\":\"đổi ý\"}")).andReturn().getResponse();
                    cancelDuringCall.set(during.getStatus());
                    cancelError.set(json.readTree(during.getContentAsString()).path("errorCode").asText());
                    JsonNode now = data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText()))
                            .andExpect(status().isOk()));
                    step(now, "/reopening", "{\"expectedVersion\":" + now.path("version").asLong()
                            + ",\"reason\":\"đổi xe\"}");
                    return new com.stockflow.fulfillment.internal.service.CarrierBookingGateway.Placed("AHA-9", null);
                });

        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));

        assertThat(cancelDuringCall.get()).isEqualTo(409);
        assertThat(cancelError.get()).isEqualTo("CARRIER_BOOKING_IN_PROGRESS");
        org.mockito.Mockito.verify(carrierGateway).cancel(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("AHA-9"));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.carrier_booking WHERE carrier_order_id = "
                + "'AHA-9'", String.class)).isEqualTo("CANCELLED");
        assertThat(load(loadId).path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("3-4: a manager limited to a warehouse sees and replaces that warehouse's pickup point only; "
            + "another warehouse's is kept, and naming it is OUT_OF_DATA_SCOPE")
    void pickupPointsPerWarehouse() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        UUID managerElsewhere = staff("WAREHOUSE_MANAGER", elsewhere);
        String point = "{\"pickupPoints\":[{\"warehouseId\":\"%s\",\"provinceCode\":\"%s\",\"address\":\"%s\"}]}";

        as(managerElsewhere, "WAREHOUSE_MANAGER", body(put(BASE + "/carriers/" + aha + "/pickup-points"),
                point.formatted(elsewhere, "01", "5 Tràng Tiền, Hoàn Kiếm, Hà Nội"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].warehouseId").value(elsewhere.toString()));
        as(manager, "WAREHOUSE_MANAGER", get(BASE + "/carriers/" + aha + "/pickup-points")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].warehouseId").value(HCM.toString()));
        as(manager, "WAREHOUSE_MANAGER", body(put(BASE + "/carriers/" + aha + "/pickup-points"),
                point.formatted(HCM, "79", "9 Lê Lợi, Bến Nghé, Quận 1, Hồ Chí Minh"))).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT province_code FROM fulfillment.carrier_pickup_point WHERE carrier_id = ? "
                + "ORDER BY province_code", String.class, aha)).containsExactly("01", "79");
        as(manager, "WAREHOUSE_MANAGER", body(put(BASE + "/carriers/" + aha + "/pickup-points"),
                point.formatted(elsewhere, "01", "6 Tràng Tiền"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
    }

    @Test
    @DisplayName("3-4: the carrier's truck services are asked of its API; a carrier that does not book through one has "
            + "none")
    void carrierServicesFromItsApi() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        org.mockito.Mockito.when(carrierGateway.services(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("SGN"))).thenReturn(List.of(new com.stockflow.fulfillment.internal
                .service.CarrierBookingGateway.ServiceOption("SGN-TRUCK-1000", "Xe tải 1 tấn", "SGN")));

        as(manager, "WAREHOUSE_MANAGER", get(BASE + "/carriers/" + aha + "/services").param("cityId", "SGN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].serviceCode").value("SGN-TRUCK-1000"));
        as(manager, "WAREHOUSE_MANAGER", get(BASE + "/carriers/" + ghn + "/services").param("cityId", "SGN"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARRIER_NOT_API_BOOKING"));
        dispatch(get(BASE + "/carriers/" + aha + "/services").param("cityId", "SGN")).andExpect(status().isForbidden());
    }

    // ---- carriers' webhooks (3-5; 09 §4.5, BR-22, BR-26, BR-27) ----------------------------------

    private static final String HOOKS = BASE + "/carrier-webhooks/";
    private static final String EVENTS = BASE + "/carrier-events";
    private static final String AHA_CREDENTIAL = "webhook-credential-0123456789";

    /** A call to a carrier's webhook, anonymous as carriers' are. */
    private ResultActions hook(String carrierCode, String content, String header, String value) throws Exception {
        MockHttpServletRequestBuilder request = post(HOOKS + carrierCode)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(content);
        return mvc.perform(header == null ? request : request.header(header, value));
    }

    private ResultActions aha(String carrierCode, String content) throws Exception {
        return hook(carrierCode, content, "apikey", AHA_CREDENTIAL);
    }

    /**
     * Ahamove's callback as its docs show it: the whole order, the partner's own api_key echoed among its fields, the
     * pickup as path[0] and each shipment a drop-off.
     */
    private static String ahamove(String id, String status, String sub, String fields, String stop1) {
        return ("{\"_id\":\"%s\",\"status\":\"%s\",\"sub_status\":\"%s\",\"api_key\":\"aha-key-0123456789\","
                + "\"city_id\":\"SGN\",\"service_id\":\"SGN-TRUCK-2000\",%s\"path\":[{\"address\":\"1 Lê Lợi\","
                + "\"status\":\"\"},{\"address\":\"2 Hai Bà Trưng\",%s}]}").formatted(id, status, sub,
                fields.isEmpty() ? "" : fields + ",", stop1.isEmpty() ? "\"status\":\"\"" : stop1);
    }

    private String carrierCode(UUID carrier) {
        return jdbc.queryForObject("SELECT code FROM fulfillment.carrier WHERE id = ?", String.class, carrier);
    }

    private JsonNode lastEvent(UUID carrier) {
        return json.valueToTree(jdbc.queryForMap("SELECT result, result_note, load_id, shipment_id, payload "
                + "FROM fulfillment.carrier_event WHERE carrier_id = ? ORDER BY received_at DESC, id DESC LIMIT 1",
                carrier));
    }

    private void loadAndSign(String loadId, String plate) throws Exception {
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        for (String label : labels(load(loadId))) {
            scanOnto(loadId, label).andExpect(status().isOk());
        }
        long version = load(loadId).path("version").asLong();
        dispatch(body(post(LOADS + "/" + loadId + "/handover"), "{\"version\":" + version
                + ",\"carrierRepresentative\":\"Nguyen Van Tai\",\"vehiclePlate\":\"" + plate + "\",\"signed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"));
    }

    @Test
    @DisplayName("3-5: Ahamove's webhook - its header credential, the api_key it echoes masked in the log; a driver "
            + "taking the order, giving it back, another taking it; a repeat recorded once; the pickup reported before "
            + "the handover is for review; after it, the milestone, the drop-off delivered at the carrier's time, an "
            + "older failure outdated")
    void ahamoveWebhook() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        String code = carrierCode(aha);
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        deliverTo(shipment, "79");
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-W1", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());

        // The wrong credential, or none: refused, and kept on the error list all the same.
        hook(code, ahamove("AHA-W1", "ACCEPTED", "", "\"accept_time\":1800000000", ""), "apikey", "guess")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errorCode").value("WEBHOOK_SIGNATURE_INVALID"));
        hook(code, "{}", null, null).andExpect(status().isUnauthorized());
        hook("NO-SUCH-CARRIER", "{}", "apikey", AHA_CREDENTIAL).andExpect(status().isUnauthorized());
        assertThat(lastEvent(aha).path("result").asText()).isEqualTo("INVALID_SIGNATURE");
        // A refused call is kept as a summary - size, digest, first characters, the echoed key masked; a call naming no
        // carrier the system knows is not kept at all.
        String refused = jdbc.queryForObject("SELECT payload FROM fulfillment.carrier_event WHERE carrier_id = ? "
                + "AND result = 'INVALID_SIGNATURE' ORDER BY received_at LIMIT 1", String.class, aha);
        assertThat(refused).startsWith("[refused: ").contains("sha256").doesNotContain("aha-key-0123456789");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.carrier_event WHERE carrier_code = "
                + "'NO-SUCH-CARRIER'", Long.class)).isZero();

        // A driver takes the order: on the load; the api_key Ahamove echoes is not kept.
        String accepted = ahamove("AHA-W1", "ACCEPTED", "", "\"accept_time\":1800000000,\"supplier_name\":\"Tài xế A\","
                + "\"supplier_id\":\"84901111111\"", "");
        aha(code, accepted).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Tài xế A");
        assertThat(load(loadId).path("driverPhone").asText()).isEqualTo("84901111111");
        assertThat(lastEvent(aha).path("payload").asText()).doesNotContain("aha-key-0123456789").contains("***");
        // The same callback again: recorded once.
        aha(code, accepted).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("DUPLICATE"));
        // The driver gives it back, another takes it.
        aha(code, ahamove("AHA-W1", "ASSIGNING", "", "\"accept_time\":1800000000", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(load(loadId).has("driverName") && !load(loadId).path("driverName").isNull()).isFalse();
        aha(code, ahamove("AHA-W1", "ACCEPTED", "", "\"accept_time\":1800000300,\"supplier_name\":\"Tài xế B\","
                + "\"supplier_id\":\"84902222222\"", "")).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Tài xế B");

        // Picked up before the dispatcher signed: not applied, on the error list.
        aha(code, ahamove("AHA-W1", "IN PROCESS", "", "\"pickup_time\":1800000600", ""))
                .andExpect(jsonPath("$.data.result").value("NEEDS_REVIEW"));
        loadAndSign(loadId, "51C-AHA.01");
        aha(code, ahamove("AHA-W1", "IN PROCESS", "", "\"pickup_time\":1800000700", ""))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(jdbc.queryForObject("SELECT milestone FROM fulfillment.tracking_milestone WHERE load_id = ?::uuid",
                String.class, loadId)).isEqualTo("PICKED_UP");

        // Drop-off 1 completed: its shipment delivered at the carrier's time (E25), recorded by nobody.
        aha(code, ahamove("AHA-W1-1", "IN PROCESS", "", "", "\"status\":\"COMPLETED\",\"complete_time\":1800003600.5"))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT outcome_at FROM fulfillment.shipment WHERE id = ?", java.sql.Timestamp.class,
                shipment).toInstant()).isEqualTo(Instant.ofEpochSecond(1_800_003_600L, 500_000_000L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id = ? AND "
                + "status = 'DELIVERED' AND source = 'CARRIER_WEBHOOK' AND recorded_by IS NULL", Long.class, shipment))
                .isOne();
        assertThat(lastEvent(aha).path("shipment_id").asText()).isEqualTo(shipment.toString());
        // A failure the carrier reports for an earlier time: outdated, ignored, still logged.
        aha(code, ahamove("AHA-W1-1", "IN PROCESS", "", "", "\"status\":\"FAILED\",\"fail_time\":1800003000,"
                + "\"fail_comment\":\"Khách vắng\"")).andExpect(jsonPath("$.data.result").value("STALE"));
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        // A drop-off the order never had: for review.
        aha(code, ahamove("AHA-W1-2", "IN PROCESS", "", "", "\"status\":\"COMPLETED\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("NEEDS_REVIEW"));

        // The log of the load, newest first; the error list is the manager's.
        JsonNode logged = data(as(manager, "WAREHOUSE_MANAGER", get(EVENTS).param("loadId", loadId))
                .andExpect(status().isOk()));
        assertThat(logged.path("items")).hasSizeGreaterThanOrEqualTo(8);
        assertThat(logged.path("items").get(0).path("loadCode").asText()).isEqualTo(load(loadId).path("code").asText());
        dispatch(get(EVENTS)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("3-5: Ahamove cancels the order before the pickup - the load is PLANNED again, its booking CANCELLED, "
            + "the warehouse's dispatchers told; an order the load no longer holds is outdated")
    void ahamoveCancelsTheOrder() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        String code = carrierCode(aha);
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        deliverTo(shipment, "79");
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-C1", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());

        // Our own cancel echoed back (cancel_by_user): not the carrier cancelling - nothing follows from it.
        aha(code, ahamove("AHA-C1", "CANCELLED", "", "\"cancel_time\":1800000800,\"cancel_by_user\":true", ""))
                .andExpect(jsonPath("$.data.result").value("IGNORED"));
        assertThat(load(loadId).path("status").asText()).isEqualTo("VEHICLE_BOOKED");
        aha(code, ahamove("AHA-C1", "CANCELLED", "", "\"cancel_time\":1800000900,\"cancel_by_user\":false,"
                + "\"cancel_comment\":\"Auto cancel, no driver accepted\"", ""))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        JsonNode planned = load(loadId);
        assertThat(planned.path("status").asText()).isEqualTo("PLANNED");
        assertThat(planned.path("carrierBooking").path("status").asText()).isEqualTo("CANCELLED");
        JsonNode history = data(dispatch(get(LOADS + "/" + loadId + "/waybill-history")).andExpect(status().isOk()));
        assertThat(history.get(history.size() - 1).path("source").asText()).isEqualTo("WEBHOOK");
        Await.until("the dispatchers told", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "notification.user_notification WHERE recipient_id = ? AND category = 'CARRIER_BOOKING' "
                + "AND source_id = ?", Long.class, dispatcher, loadId) == 1);
        // Echoed again after the booking is gone: about an order the load no longer holds.
        aha(code, ahamove("AHA-C1", "CANCELLED", "", "\"cancel_time\":1800000901", ""))
                .andExpect(jsonPath("$.data.result").value("STALE"));
    }

    @Test
    @DisplayName("3-5: a completed Ahamove order applies the drop-off outcomes it carries that were not applied yet - "
            + "the carrier's long wording kept within the note's length (E22)")
    void ahamoveCompletedOrder() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        String code = carrierCode(aha);
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        deliverTo(shipment, "79");
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-K1", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());
        loadAndSign(loadId, "51C-AHA.02");

        String wording = "Khách không nghe máy ".repeat(40);
        aha(code, ahamove("AHA-K1", "COMPLETED", "", "\"complete_time\":1800009000", "\"status\":\"FAILED\","
                + "\"fail_time\":1800008000,\"fail_comment\":\"" + wording + "\""))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERY_FAILED");
        Map<String, Object> failed = jdbc.queryForMap("SELECT failure_reason, failure_note, outcome_at FROM "
                + "fulfillment.shipment WHERE id = ?", shipment);
        assertThat(failed.get("failure_reason")).isEqualTo("OTHER");
        assertThat((String) failed.get("failure_note")).hasSize(500).endsWith("...");
        assertThat(((java.sql.Timestamp) failed.get("outcome_at")).toInstant())
                .isEqualTo(Instant.ofEpochSecond(1_800_008_000L));
        // The drop-off's own callback for it, later: already so.
        aha(code, ahamove("AHA-K1-1", "IN PROCESS", "", "", "\"status\":\"FAILED\",\"fail_time\":1800008000"))
                .andExpect(jsonPath("$.data.result").value("STALE"));
    }

    @Test
    @DisplayName("3-5: a carrier set up with 'Authorization: Bearer' is not read as a sign-in token; a callback for an "
            + "order not saved yet while a booking with the carrier is under way is asked for again (503)")
    void ahamoveBearerAndEarlyCallbacks() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        String code = "AHB" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Ahamove\",\"connection\":{\"connectionType\":\"API_BOOKING\","
                        + "\"webhookAuthHeader\":\"Authorization\",\"webhookAuthValue\":\"Bearer aha-bearer-0123456789\","
                        + "\"apiEnvironment\":\"STAGING\",\"apiAccount\":\"84908000001\",\"apiKey\":\"aha-key-9876543210\"}}"))
                .andExpect(status().isCreated())).path("carrierId").asText());

        hook(code, ahamove("AHA-UNKNOWN", "ACCEPTED", "", "\"accept_time\":1800000000", ""), "Authorization",
                "Bearer aha-bearer-0123456789").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("UNMATCHED"));
        hook(code, "{}", "Authorization", "Bearer something-else").andExpect(status().isUnauthorized());

        // A booking with this carrier is being made: a callback no load matches yet is to be sent again.
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO fulfillment.carrier_booking (id, load_id, carrier_id, service_code, stops, status,
                                                         requested_by, requested_at)
                VALUES (gen_random_uuid(), ?::uuid, ?, 'SGN-TRUCK-2000', '[{"seq":1}]'::jsonb, 'REQUESTED', ?, NOW())""",
                loadId, carrier, manager));
        hook(code, ahamove("AHA-EARLY", "ACCEPTED", "", "\"accept_time\":1800000000", ""), "Authorization",
                "Bearer aha-bearer-0123456789").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_UNAVAILABLE"));
        JsonNode pending = lastEvent(carrier);
        assertThat(pending.path("result").asText()).isEqualTo("RECEIVED");
        assertThat(pending.path("result_note").asText()).contains("AHA-EARLY").contains("send it again");
    }

    @Test
    @DisplayName("3-5: a WEBHOOK_LOAD carrier signs its body (QD-11): unsigned is refused; the waybill number from its "
            + "event (history source WEBHOOK); an unknown load is unmatched; an outcome before the vehicle left is for "
            + "review; returned with no failure first is failed (OTHER) then returned (E21); the manager resolves the "
            + "error list once")
    void webhookPerLoad() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        String secret = "load-webhook-secret-0123456789";
        String code = "WL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Xe tải webhook\",\"connection\":{\"connectionType\":"
                        + "\"WEBHOOK_LOAD\",\"webhookSecret\":\"" + secret + "\",\"failureReasonMap\":"
                        + "{\"Khách không nhận\":\"REFUSED\"}}}")).andExpect(status().isCreated()))
                .path("carrierId").asText());
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        String loadCode = load(loadId).path("code").asText();
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-WL.01\"}")).andExpect(status().isOk());
        java.util.function.Function<String, String> signed = content -> com.stockflow.fulfillment.internal.service
                .CarrierWebhookTestSigner.sign(secret, content);

        String waybill = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"WAYBILL\",\"reportedAt\":"
                + "\"2026-11-20T08:00:00+07:00\",\"waybillNo\":\"WL-0001\",\"driverName\":\"Tài\"}";
        hook(code, waybill, "X-Signature", "sha256=00").andExpect(status().isUnauthorized());
        hook(code, waybill, "X-Signature", signed.apply(waybill)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(load(loadId).path("waybillNo").asText()).isEqualTo("WL-0001");
        JsonNode history = data(dispatch(get(LOADS + "/" + loadId + "/waybill-history")).andExpect(status().isOk()));
        assertThat(history.get(history.size() - 1).path("source").asText()).isEqualTo("WEBHOOK");

        String unknown = "{\"loadCode\":\"LD-NOPE\",\"type\":\"PICKED_UP\",\"reportedAt\":\"2026-11-20T08:05:00Z\"}";
        hook(code, unknown, "X-Signature", signed.apply(unknown))
                .andExpect(jsonPath("$.data.result").value("UNMATCHED"));
        String early = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":"
                + "\"2026-11-20T09:00:00Z\"}";
        JsonNode review = data(hook(code, early, "X-Signature", signed.apply(early))
                .andExpect(jsonPath("$.data.result").value("NEEDS_REVIEW")));

        loadAndSign(loadId, "51C-WL.01");
        String back = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"RETURNED\",\"reportedAt\":"
                + "\"2026-11-21T10:00:00Z\"}";
        hook(code, back, "X-Signature", signed.apply(back)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(shipment)).isEqualTo("RETURNED");
        assertThat(jdbc.queryForList("SELECT status FROM fulfillment.shipment_event WHERE shipment_id = ? AND "
                + "source = 'CARRIER_WEBHOOK' ORDER BY occurred_at, status", String.class, shipment))
                .containsExactly("DELIVERY_FAILED", "RETURNED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM fulfillment.shipment WHERE id = ?", String.class,
                shipment)).isEqualTo("OTHER");

        // The error list: the manager resolves the early outcome once; an applied event is not the list's.
        String reviewId = review.path("eventId").asText();
        as(manager, "WAREHOUSE_MANAGER", get(EVENTS).param("result", "ERRORS").param("open", "true")
                .param("carrierId", carrier.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3));
        as(manager, "WAREHOUSE_MANAGER", body(post(EVENTS + "/" + reviewId + "/resolution"), "{\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
        as(manager, "WAREHOUSE_MANAGER", body(post(EVENTS + "/" + reviewId + "/resolution"),
                "{\"note\":\"recorded by hand after the call\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.resolutionNote").value("recorded by hand after the call"));
        as(manager, "WAREHOUSE_MANAGER", body(post(EVENTS + "/" + reviewId + "/resolution"), "{\"note\":\"again\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARRIER_EVENT_NOT_RESOLVABLE"));
        String appliedId = jdbc.queryForObject("SELECT id::text FROM fulfillment.carrier_event WHERE carrier_id = ? "
                + "AND result = 'APPLIED' ORDER BY received_at LIMIT 1", String.class, carrier);
        as(manager, "WAREHOUSE_MANAGER", body(post(EVENTS + "/" + appliedId + "/resolution"), "{\"note\":\"x\"}"))
                .andExpect(status().isConflict());
        as(manager, "WAREHOUSE_MANAGER", get(EVENTS + "/" + appliedId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payload").value(waybill));
    }

    // ---- following a delivery, correcting one (3-6; 09 §4.3, §4.5 step 9; 17 BR-27; E16, E17, E23) ----

    /** A customer with a portal account: {customer id, account id}. */
    private UUID[] portalCustomer() {
        UUID account = staff("CUSTOMER");
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        return new UUID[] {customer, account};
    }

    @Test
    @DisplayName("3-6: the customer hears of each vehicle in the portal's inbox, not by e-mail, and follows the order's "
            + "vehicles - carrier, waybill number, plate, its shipments - nobody else sees them; a proof of delivery is "
            + "kept with a shipment that left")
    void theCustomerFollowsTheVehicles() throws Exception {
        UUID[] mine = portalCustomer();
        UUID[] someoneElse = portalCustomer();
        OrderSummary order = ftlOrder(1, mine[0]);
        UUID shipment = packed(order, false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        book(loadId, ghn, "51C-555.55");
        String vehicles = BASE + "/orders/me/" + order.orderId() + "/vehicles";
        assertThat(data(as(mine[1], "CUSTOMER", get(vehicles)).andExpect(status().isOk()))).isEmpty();
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        var pod = new org.springframework.mock.web.MockMultipartFile("file", "bien-ban.pdf", "application/pdf",
                onePagePdf());
        as(manager, "WAREHOUSE_MANAGER", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/shipments/" + shipment + "/pod-files").file(pod))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("SHIPMENT_STATE_CONFLICT"));

        loadAndSign(loadId, "51C-555.55");
        Await.until("the customer told in the portal", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "notification.user_notification WHERE recipient_id = ? AND category = 'ORDER_SHIPPING' "
                + "AND source_id = ?", Long.class, mine[1], order.orderId().toString()) == 1);
        Map<String, Object> notice = jdbc.queryForMap("SELECT title, body, link FROM notification.user_notification "
                + "WHERE recipient_id = ? AND category = 'ORDER_SHIPPING'", mine[1]);
        assertThat((String) notice.get("title")).contains(order.orderNumber());
        assertThat((String) notice.get("body")).contains("Xe tải Nhanh").contains("WB-" + loadId).contains("51C-555.55");
        assertThat(notice.get("link")).isEqualTo("/orders/" + order.orderId());

        JsonNode seen = data(as(mine[1], "CUSTOMER", get(vehicles)).andExpect(status().isOk()));
        assertThat(seen).singleElement().satisfies(v -> {
            assertThat(v.path("carrierName").asText()).isEqualTo("Xe tải Nhanh");
            assertThat(v.path("waybillNo").asText()).isEqualTo("WB-" + loadId);
            assertThat(v.path("vehiclePlate").asText()).isEqualTo("51C-555.55");
            assertThat(v.has("driverName") || v.has("driverPhone")).isFalse();
            assertThat(v.path("shipments")).singleElement()
                    .satisfies(s -> assertThat(s.path("status").asText()).isEqualTo("HANDED_OVER"));
        });
        as(someoneElse[1], "CUSTOMER", get(vehicles)).andExpect(status().isNotFound());

        // The proof of delivery is a delivered shipment's: refused while it is on its way, kept once delivered - a PDF
        // or a photo, not a spreadsheet.
        as(manager, "WAREHOUSE_MANAGER", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/shipments/" + shipment + "/pod-files").file(pod))
                .andExpect(status().isConflict());
        oc(body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"), "{\"outcome\":\"DELIVERED\"}"))
                .andExpect(status().isOk());
        as(manager, "WAREHOUSE_MANAGER", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/shipments/" + shipment + "/pod-files").file(new org.springframework.mock.web
                        .MockMultipartFile("file", "bien-ban.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", onePagePdf())))
                .andExpect(status().is4xxClientError());
        JsonNode kept = data(as(manager, "WAREHOUSE_MANAGER", org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.multipart(BASE + "/shipments/" + shipment + "/pod-files").file(pod))
                .andExpect(status().isCreated()));
        assertThat(kept.path("shipmentId").asText()).isEqualTo(shipment.toString());
        assertThat(data(dispatch(get(BASE + "/shipments/" + shipment + "/pod-files")).andExpect(status().isOk())))
                .singleElement().satisfies(f -> assertThat(f.path("fileName").asText()).isEqualTo("bien-ban.pdf"));
    }

    @Test
    @DisplayName("3-6: a shipment across two vehicles (LP-3): each vehicle is shown to the customer as it leaves and "
            + "told in the portal's inbox once - not only the last one")
    void theCustomerFollowsEachVehicle() throws Exception {
        UUID[] mine = portalCustomer();
        OrderSummary order = ftlOrder(2, mine[0]);
        UUID shipment = packed(order, true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        assertThat(plan.path("vehicles")).hasSize(2);
        String v1 = loadOf(plan, 1);
        String v2 = loadOf(plan, 2);
        book(v1, ghn, "51C-201.01");
        book(v2, ghtk, "51C-202.02");
        String vehicles = BASE + "/orders/me/" + order.orderId() + "/vehicles";

        loadAndSign(v1, "51C-201.01");
        JsonNode one = data(as(mine[1], "CUSTOMER", get(vehicles)).andExpect(status().isOk()));
        assertThat(one).singleElement().satisfies(v -> {
            assertThat(v.path("vehiclePlate").asText()).isEqualTo("51C-201.01");
            assertThat(v.path("shipments")).singleElement()
                    .satisfies(s -> assertThat(s.path("status").asText()).isEqualTo("READY_TO_DISPATCH"));
        });

        loadAndSign(v2, "51C-202.02");
        JsonNode both = data(as(mine[1], "CUSTOMER", get(vehicles)).andExpect(status().isOk()));
        assertThat(both).extracting(v -> v.path("vehiclePlate").asText()).containsExactly("51C-201.01", "51C-202.02");
        assertThat(both.get(1).path("carrierName").asText()).isEqualTo("Xe tải Tiết Kiệm");
        Await.until("each vehicle told", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "notification.user_notification WHERE recipient_id = ? AND category = 'ORDER_SHIPPING'", Long.class,
                mine[1]) == 2);
    }

    @Test
    @DisplayName("3-6: the warehouse manager corrects an outcome the carrier reported wrongly, with a reason, and the "
            + "order follows; the carrier's later report about that shipment goes to the error list (E17); a dispatcher "
            + "may not correct")
    void correctingAnOutcome() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        String secret = "load-webhook-secret-6-0123456789";
        String code = "WC" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Xe tải webhook\",\"connection\":{\"connectionType\":"
                        + "\"WEBHOOK_LOAD\",\"webhookSecret\":\"" + secret + "\"}}")).andExpect(status().isCreated()))
                .path("carrierId").asText());
        OrderSummary order = ftlOrder(1);
        UUID shipment = packed(order, false)[0];
        String shipmentCode = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                shipment);
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        String loadCode = load(loadId).path("code").asText();
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-WC.01\",\"waybillNo\":\"WC-" + loadId + "\"}")).andExpect(status().isOk());
        loadAndSign(loadId, "51C-WC.01");
        String delivered = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":"
                + "\"2026-11-21T09:00:00Z\",\"shipmentCode\":\"" + shipmentCode + "\"}";
        hook(code, delivered, "X-Signature", com.stockflow.fulfillment.internal.service.CarrierWebhookTestSigner
                .sign(secret, delivered)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        String correction = BASE + "/shipments/" + shipment + "/delivery-outcome-correction";

        dispatch(body(post(correction), "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"CUSTOMER_ABSENT\","
                + "\"reason\":\"x\"}")).andExpect(status().isForbidden());
        as(manager, "WAREHOUSE_MANAGER", body(post(correction), "{\"outcome\":\"DELIVERY_FAILED\","
                + "\"failureReason\":\"CUSTOMER_ABSENT\"}")).andExpect(status().isBadRequest());
        as(manager, "WAREHOUSE_MANAGER", body(post(correction), "{\"outcome\":\"DELIVERY_FAILED\","
                + "\"failureReason\":\"CUSTOMER_ABSENT\",\"reason\":\"the customer says nothing arrived\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shipment.status").value("DELIVERY_FAILED"));
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.DELIVERY_FAILED);
        assertThat(jdbc.queryForObject("SELECT from_status || '>' || to_status || ':' || reason FROM "
                + "fulfillment.delivery_outcome_correction WHERE shipment_id = ?", String.class, shipment))
                .isEqualTo("DELIVERED>DELIVERY_FAILED:the customer says nothing arrived");

        // E17: the carrier reports it again - not applied, the manager looks at it.
        String again = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":"
                + "\"2026-11-21T11:00:00Z\",\"shipmentCode\":\"" + shipmentCode + "\"}";
        hook(code, again, "X-Signature", com.stockflow.fulfillment.internal.service.CarrierWebhookTestSigner
                .sign(secret, again)).andExpect(jsonPath("$.data.result").value("NEEDS_REVIEW"));
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERY_FAILED");

        // Corrected back once the customer found it: delivered, and the order with it.
        as(manager, "WAREHOUSE_MANAGER", body(post(correction), "{\"outcome\":\"DELIVERED\","
                + "\"reason\":\"found at the reception\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.status").value("DELIVERED"));
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("3-6 / E17: a report for the whole load leaves the shipment corrected by hand alone and applies to the "
            + "others")
    void aLoadWideReportSkipsTheCorrectedShipment() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        String secret = "load-webhook-secret-e17-0123456789";
        String code = "WE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Xe tải webhook\",\"connection\":{\"connectionType\":"
                        + "\"WEBHOOK_LOAD\",\"webhookSecret\":\"" + secret + "\"}}")).andExpect(status().isCreated()))
                .path("carrierId").asText());
        UUID first = packed(ftlOrder(1), false)[0];
        UUID second = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(first, second), truck5t);
        assertThat(plan.path("vehicles")).hasSize(1);
        String loadId = loadOf(plan, 1);
        String loadCode = load(loadId).path("code").asText();
        String firstCode = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                first);
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-WE.01\",\"waybillNo\":\"WE-" + loadId + "\"}")).andExpect(status().isOk());
        loadAndSign(loadId, "51C-WE.01");
        java.util.function.Function<String, String> signed = content -> com.stockflow.fulfillment.internal.service
                .CarrierWebhookTestSigner.sign(secret, content);
        String one = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":"
                + "\"2026-11-21T09:00:00Z\",\"shipmentCode\":\"" + firstCode + "\"}";
        hook(code, one, "X-Signature", signed.apply(one)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/shipments/" + first + "/delivery-outcome-correction"),
                "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"REFUSED\",\"reason\":\"the customer refused\"}"))
                .andExpect(status().isOk());

        String all = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":"
                + "\"2026-11-21T10:00:00Z\"}";
        hook(code, all, "X-Signature", signed.apply(all)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(first)).isEqualTo("DELIVERY_FAILED");
        assertThat(shipmentStatus(second)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT result_note FROM fulfillment.carrier_event WHERE carrier_id = ? "
                + "ORDER BY received_at DESC LIMIT 1", String.class, carrier)).contains("corrected by hand")
                .contains(firstCode);
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
        assertThat(jdbc.queryForMap("SELECT status, package_count, partial, order_id FROM "
                + "fulfillment.shipment WHERE id = ?", rest)).containsEntry("status", "READY_TO_DISPATCH")
                .containsEntry("package_count", 1).containsEntry("partial", true)
                .containsEntry("order_id", order.orderId());
        assertThat(jdbc.queryForObject("SELECT package_count FROM fulfillment.shipment WHERE id = ?", Integer.class,
                shipment)).isEqualTo(1);
        // A shipping label names neither the shipment nor n/N (08 §4.3, 4C-6): a split outdates no carton's label.
        assertThat(jdbc.queryForObject("SELECT label_outdated_at IS NOT NULL FROM fulfillment.package WHERE id = "
                + "?::uuid", Boolean.class, stays)).isFalse();
        assertThat(data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()))
                .findValuesAsText("shipmentId")).contains(rest.toString()).doesNotContain(shipment.toString());
        JsonNode check = data(dispatch(get(PLANS + "/" + id + "/reconciliation")).andExpect(status().isOk()));
        assertThat(check.path("pieceCheck").path("balanced").asBoolean()).isTrue();
        assertThat(check.path("shipments")).allSatisfy(s -> assertThat(s.path("notInPlan").asInt()).isZero());

        // The carton that moved to the new shipment is not on this vehicle's manifest: its scan is refused as such.
        String restLabel = jdbc.queryForObject("SELECT label_code FROM fulfillment.package WHERE id = ?::uuid",
                String.class, stays);
        scanOnto(loadId, restLabel).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_IN_LOAD"));

        // The carton that stays is loadable at once: its label did not change with the split.
        assertThat(labels(load(loadId))).hasSize(1);
        scanOnto(loadId, labels(load(loadId)).getFirst()).andExpect(status().isOk());
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

    /** An order of 2 sofas packed in two shipments (Sales allowed partial shipments), a carton each. */
    private UUID[] packedInTwo(OrderSummary order, boolean kits) throws Exception {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
        UUID task = UUID.fromString(data(oc(body(post(BASE + "/tasks"), "{\"orderId\":\"" + order.orderId()
                + "\",\"assignedUserId\":\"" + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        packer(post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        for (Map<String, Object> line : jdbc.queryForList(
                "SELECT id, location_code, lpn AS scan "
                        + "FROM fulfillment.pick_line WHERE pick_id = ?", task)) {
            packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/confirmation"),
                    "{\"locationCode\":\"" + line.get("location_code") + "\",\"lpn\":\"" + line.get("scan")
                            + "\"}")).andExpect(status().isOk());
        }
        packer(post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk());
        // Two shipments of one sofa each: the check station runs once and hands the cartons out (see Parts).
        UUID[] shipments = new UUID[2];
        for (int i = 0; i < 2; i++) {
            shipments[i] = parts.ship(task, 1);
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

    // ---- loads list: status filter, plan code search; label flags (the FE gaps of the dispatch screens) --------

    /**
     * A load of the parcel shipping that was removed (V20261108000400), straight into the table: it has no plan's vehicle,
     * which only a closed load may lack, so it is history - signed and gone.
     */
    private String historyLoad(String plate) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, carrier_id, vehicle_plate,
                    status, loading_started_at, dispatched_at, dispatched_by, carrier_representative, shipment_count,
                    package_count, waybill_no)
                VALUES (?, ?, gen_random_uuid(), ?, ?, ?, 'DISPATCHED', NOW(), NOW(), ?, 'Tai', 1, 1,
                        'WB-' || gen_random_uuid())""",
                id, "LD-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), HCM, ghn, plate,
                dispatcher));
        return id.toString();
    }

    private JsonNode loadsPage(String query) throws Exception {
        return data(dispatch(get(LOADS + "?warehouseId=" + HCM + query)).andExpect(status().isOk()));
    }

    private int count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM fulfillment.dispatch_load WHERE warehouse_id = ? AND "
                + where, Integer.class, HCM);
    }

    @Test
    @DisplayName("GET loads?status: PLANNED / DISPATCHED (any case) filter in the query - totalElements, totalPages and "
            + "the pages of a size 1 walk are those of the filtered set; no status: every load; an unknown status is "
            + "400 UNSUPPORTED_PARAMETER on field status")
    void listFiltersByStatus() throws Exception {
        UUID shipment = packed(ftlOrder(2), true)[0];
        confirmed(List.of(shipment), truck1t25);                       // two vehicles, PLANNED
        historyLoad("51D-001.01");
        historyLoad("51D-002.02");
        int planned = count("status = 'PLANNED'");
        int dispatched = count("status = 'DISPATCHED'");
        int all = count("TRUE");
        assertThat(planned).isGreaterThanOrEqualTo(2);
        assertThat(dispatched).isGreaterThanOrEqualTo(2);

        JsonNode onlyPlanned = loadsPage("&status=PLANNED&size=1");
        assertThat(onlyPlanned.path("totalElements").asInt()).isEqualTo(planned);
        assertThat(onlyPlanned.path("totalPages").asInt()).isEqualTo(planned);
        assertThat(onlyPlanned.path("items")).hasSize(1);
        assertThat(onlyPlanned.path("hasNext").asBoolean()).isTrue();
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < planned; page++) {
            JsonNode one = loadsPage("&status=PLANNED&size=1&page=" + page);
            assertThat(one.path("items")).hasSize(1);
            assertThat(one.path("items").get(0).path("status").asText()).isEqualTo("PLANNED");
            seen.add(one.path("items").get(0).path("loadId").asText());
        }
        assertThat(seen).doesNotHaveDuplicates().hasSize(planned);
        assertThat(loadsPage("&status=PLANNED&size=1&page=" + planned).path("items")).isEmpty();

        JsonNode onlyDispatched = loadsPage("&status=dispatched&size=2");
        assertThat(onlyDispatched.path("totalElements").asInt()).isEqualTo(dispatched);
        assertThat(onlyDispatched.path("items")).allSatisfy(l -> assertThat(l.path("status").asText())
                .isEqualTo("DISPATCHED"));

        assertThat(loadsPage("&size=1").path("totalElements").asInt()).isEqualTo(all);
        assertThat(loadsPage("&status=").path("totalElements").asInt()).isEqualTo(all);

        JsonNode bad = body(dispatch(get(LOADS + "?status=TRAIN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER")));
        assertThat(bad.path("fieldErrors")).singleElement().satisfies(f -> assertThat(f.path("field").asText())
                .isEqualTo("status"));
    }

    @Test
    @DisplayName("GET loads?search also matches the load plan's code (case-insensitive, partial) through the vehicle: "
            + "both vehicles of the plan and no other load; load code and plate matches unchanged; one query, so the "
            + "count and the pages stay right with status")
    void listSearchesByPlanCode() throws Exception {
        UUID shipment = packed(ftlOrder(2), true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        String planCode = plan.path("code").asText();
        String v1 = loadOf(plan, 1);
        String v2 = loadOf(plan, 2);
        String other = historyLoad("51E-777.77");

        JsonNode byPlan = loadsPage("&search=" + planCode);
        assertThat(byPlan.path("totalElements").asInt()).isEqualTo(2);
        assertThat(byPlan.path("items").findValuesAsText("loadId")).containsExactlyInAnyOrder(v1, v2);
        assertThat(byPlan.path("items")).allSatisfy(l -> assertThat(l.path("loadPlanCode").asText()).isEqualTo(planCode));
        assertThat(loadsPage("&search=" + planCode.toLowerCase()).path("totalElements").asInt()).isEqualTo(2);
        assertThat(loadsPage("&search=" + planCode.substring(planCode.length() - 4)).path("items")
                .findValuesAsText("loadId")).contains(v1, v2);
        JsonNode paged = loadsPage("&search=" + planCode + "&size=1&page=1");
        assertThat(paged.path("totalElements").asInt()).isEqualTo(2);
        assertThat(paged.path("totalPages").asInt()).isEqualTo(2);
        assertThat(paged.path("items")).hasSize(1);
        assertThat(loadsPage("&search=" + planCode + "&status=DISPATCHED").path("totalElements").asInt()).isZero();
        assertThat(loadsPage("&search=" + planCode + "&status=PLANNED").path("totalElements").asInt())
                .isEqualTo(2);

        // The existing matches: the load's own code and the plate (a load of the removed parcel shipping has no plan).
        assertThat(loadsPage("&search=" + load(v1).path("code").asText()).path("items").findValuesAsText("loadId"))
                .containsExactly(v1);
        book(v2, ghn, "51F-123.45");
        assertThat(loadsPage("&search=51F-123").path("items").findValuesAsText("loadId")).containsExactly(v2);
        JsonNode byPlate = loadsPage("&search=51E-777");
        assertThat(byPlate.path("items").findValuesAsText("loadId")).containsExactly(other);
        assertThat(byPlate.path("items").get(0).hasNonNull("loadPlanCode")).isFalse();
        assertThat(loadsPage("&search=LP-NO-SUCH-PLAN").path("totalElements").asInt()).isZero();
        // The % of a search term is literal, not a wildcard.
        assertThat(loadsPage("&search=%25").path("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("V20261022001400 / V20261108000400: a load still open is a plan's vehicle (one of no plan is refused); "
            + "a plan's vehicle is booked before it loads; a vehicle with a parcel left to unload does not turn "
            + "DISPATCHED")
    void theDatabaseNets() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        Throwable noPlan = catchThrowable(() -> jdbc.update("""
                INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, status)
                VALUES (gen_random_uuid(), ?, gen_random_uuid(), ?, 'PLANNED')""",
                "LD-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), HCM));
        assertThat(noPlan).hasMessageContaining("ck_dispatch_load_vehicle");
        Throwable unbooked = catchThrowable(() -> jdbc.update("UPDATE fulfillment.dispatch_load SET status = 'LOADING', "
                + "carrier_id = ?, loading_started_at = NOW() WHERE id = ?::uuid", ghn, loadId));
        assertThat(unbooked).hasMessageContaining("ck_dispatch_load_vehicle_booked");

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
