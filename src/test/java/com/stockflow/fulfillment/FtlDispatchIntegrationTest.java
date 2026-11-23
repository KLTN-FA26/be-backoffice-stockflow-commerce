package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveAddressCommand;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.support.Await;
import com.stockflow.order.internal.domain.WindowChangeReason;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.ChatRows;
import com.stockflow.support.WithCurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        // WF-05 item 6: the callbacks here carry Ahamove's documented sample times, fixed years apart from the day the
        // suite runs, so the time window is wide; a callback dated 2001 is still outside it. The allow-list holds the
        // address MockMvc calls from, and a block.
        "stockflow.fulfillment.ahamove.webhook.max-age=P3650D",
        "stockflow.fulfillment.ahamove.webhook.max-future=P3650D",
        "stockflow.fulfillment.ahamove.webhook.allowed-ips=127.0.0.1,10.20.0.0/16",
        // 3-2: waybill files are scanned as every upload is; no ClamAV runs in tests.
        "stockflow.upload-inspection.enabled=false",
        // bookingThroughTheCarriersApi calls the booking sweeper by hand; a scheduled run firing around it would settle
        // the booking first or hold the sweeper's lock, and the call under test would see nothing to do.
        "stockflow.fulfillment.carrier-booking-sweep-interval=PT24H"})
class FtlDispatchIntegrationTest {

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
    @Autowired CustomerService customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;

    private UUID coordinator;
    private UUID sales;
    private UUID picker;
    private UUID dispatcher;
    private UUID elsewhereWarehouse;
    private UUID dispatcherElsewhere;
    private UUID truck5t;
    private UUID truck1t25;
    private UUID ghn;
    private UUID ghtk;

    @BeforeEach
    void setUp() {
        parts = new CartonStationSupport.Parts(this::packer, tx, jdbc, json);
        coordinator = staff("WAREHOUSE_MANAGER", HCM);
        sales = staff("SALES_STAFF");
        picker = staff("WAREHOUSE_STAFF", HCM);
        dispatcher = staff("WAREHOUSE_STAFF", HCM);
        elsewhereWarehouse = tx.execute(s -> otherWarehouse());
        dispatcherElsewhere = staff("WAREHOUSE_STAFF", elsewhereWarehouse);
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
        return as(coordinator, "WAREHOUSE_MANAGER", request);
    }

    /** What the retired order coordinator did for the customer - cancelling an order, say - Sales does now. */
    private ResultActions sales(MockHttpServletRequestBuilder request) throws Exception {
        return as(sales, "SALES_STAFF", request);
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
     * The order's goods, as whole sealed cartons of its SKU's carton unit: an order is given whole cartons only (team
     * plan 4C-2), so loose units would not be allocated and the order would draw on the shared demo cartons.
     * Received long ago, so the allocator hands them out first (FIFO).
     */
    /**
     * A paid order of {@code cartons} cartons for the customer, released at HCM (every order is a full truckload): a
     * contract's order, one carton of two pieces each (14 BR-08), its sealed cartons held at HCM and picked one by
     * one by their LPN (4C).
     */
    private OrderSummary ftlOrder(int cartons, UUID customer) throws Exception {
        return ftlOrder(cartons, customer, ConfirmedOrders.paidOnSigning());
    }

    /** The same, its contract paying as {@code plan} says; the installments on signing are paid. */
    private OrderSummary ftlOrder(int cartons, UUID customer, List<SalesContracts.Installment> plan) throws Exception {
        CustomerSkuRows.Issued issued = cupOf(customer);
        CustomerSkuRows.stock(jdbc, tx, issued.sku(), 2 * cartons);
        ConfirmedOrders.Signed signed = confirmedOrders().signed(customer, plan,
                ConfirmedOrders.Line.cartons(issued, cartons, 6_000_000));
        return confirmedOrders().release(confirmedOrders().pay(signed).orderId(), HCM, coordinator);
    }

    private ConfirmedOrders confirmedOrders;

    private ConfirmedOrders confirmedOrders() {
        if (confirmedOrders == null) {
            confirmedOrders = new ConfirmedOrders(context);
        }
        return confirmedOrders;
    }

    private final java.util.Map<UUID, CustomerSkuRows.Issued> cupSkus = new java.util.HashMap<>();

    /**
     * The customer's own SKU, handling like the demo cup, sold in cartons of two (a contract's lines are only ever the
     * customer's, 14 BR-04), its carton the big one the truck maths below were measured with (215 x 95 x 90 cm,
     * 52.5 kg).
     */
    private CustomerSkuRows.Issued cupOf(UUID customer) {
        return cupSkus.computeIfAbsent(customer, c -> {
            CustomerSkuRows.Issued issued = CustomerSkuRows.like(jdbc, tx, entityManager, c, DemoData.SKU_STOCKED, 2);
            CartonStationSupport.giveCartonsASize(tx, jdbc, issued.sku());
            return issued;
        });
    }

    private OrderSummary ftlOrder(int cartons) throws Exception {
        return ftlOrder(cartons, DemoData.CUSTOMER_ID);
    }
    /** The order picked and packed at the check station, one shipment of all its cartons. Returns the shipment and task. */
    private UUID[] packed(OrderSummary order, boolean kits) throws Exception {
        UUID task = picked(order);
        // The check station (4C-6): start, scan the LPN of every picked carton, label, complete: one shipment.
        UUID shipment = CartonStationSupport.ship(this::packer, jdbc, json, task, null);
        return new UUID[] {shipment, task};
    }

    /** The order's task created, every carton picked to dispatch: the task PICKED, at the check station. */
    private UUID picked(OrderSummary order) throws Exception {
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
        return task;
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
        // 4B-8 (09 BR-18): the vehicle is refused whole, the held order named with why.
        orders.putOnHold(order.orderId(), HoldReason.PAYMENT, "check the transfer", coordinator);
        handOver(v1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_HANDOVER_BLOCKED"))
                .andExpect(jsonPath("$.details.orders[0].orderId").value(order.orderId().toString()))
                .andExpect(jsonPath("$.details.orders[0].reasonCode").value("ORDER_INVALID_TRANSITION"));
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
        // The number is there; the driver's name is not: refused (WF4-DRIVER) until typed with the number.
        handOver(loadId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_DRIVER_MISSING"));
        as(dispatcher, "WAREHOUSE_STAFF", body(put(LOADS + "/" + loadId + "/waybill"), "{\"waybillNo\":\"" + number
                + "\",\"driverName\":\"Tai\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driverName").value("Tai"));
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

        // Reopened, the plan cancels the load: its files are history, it takes no new one (RTM-WAYBILLFILE).
        JsonNode now = data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()));
        step(now, "/reopening", "{\"expectedVersion\":" + now.path("version").asLong() + ",\"reason\":\"đổi xe\"}");
        as(dispatcher, "WAREHOUSE_STAFF", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(LOADS + "/" + loadId + "/waybill-files").file(file)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_NOT_BOOKED"));
    }

    private ResultActions importing(UUID user, String role, String path, String csv) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "van-don.csv", "application/vnd.ms-excel",
                ("\uFEFFMã chuyến,Mã vận đơn,Biển số,Tên tài xế,Điện thoại tài xế\r\n" + csv)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return as(user, role, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/waybill-imports" + forCaller(user, path)).file(file));
    }

    /** The import is made for a warehouse (RTM-WBIMPORT-WH): the caller's own. */
    private String forCaller(UUID user, String path) {
        return withWarehouse(user.equals(dispatcherElsewhere) ? elsewhereWarehouse : HCM, path);
    }

    private static String withWarehouse(UUID warehouse, String path) {
        return path + (path.contains("?") ? "&" : "?") + "warehouseId=" + warehouse;
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
        // The warehouse is chosen when importing (RTM-WBIMPORT-WH): required, known, one the caller is assigned to.
        var csvFile = new org.springframework.mock.web.MockMultipartFile("file", "van-don.csv", "text/csv",
                ("a,b\r\n" + one + "," + number + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        dispatch(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/waybill-imports/preview").file(csvFile)).andExpect(status().isBadRequest());
        dispatch(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/waybill-imports/preview" + withWarehouse(UUID.randomUUID(), "")).file(csvFile))
                .andExpect(status().isNotFound());
        dispatch(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/waybill-imports/preview" + withWarehouse(elsewhereWarehouse, "")).file(csvFile))
                .andExpect(status().isForbidden());
        // A load of another warehouse than the chosen one: the same reason in the preview and after the import.
        JsonNode otherPreview = data(importing(dispatcherElsewhere, "WAREHOUSE_STAFF", "/preview", csv)
                .andExpect(status().isOk()));
        JsonNode otherApplied = data(importing(dispatcherElsewhere, "WAREHOUSE_STAFF", "", csv)
                .andExpect(status().isCreated()));
        assertThat(otherPreview.path("lines").get(0).path("problem").asText()).isEqualTo("LOAD_OUT_OF_SCOPE");
        assertThat(otherApplied.path("lines").get(0).path("problem").asText())
                .isEqualTo(otherPreview.path("lines").get(0).path("problem").asText());
        // The picker offers only the caller's own warehouses (RTM-WBIMPORT-WH).
        assertThat(data(dispatch(get(BASE + "/waybill-imports/warehouses")).andExpect(status().isOk()))
                .findValuesAsText("warehouseId")).containsExactly(HCM.toString());
        assertThat(data(as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(BASE + "/waybill-imports/warehouses"))
                .andExpect(status().isOk())).findValuesAsText("warehouseId"))
                .containsExactly(elsewhereWarehouse.toString());
        // Another warehouse's dispatcher sees the same lines refused as out of scope, not the loads.
        JsonNode elsewhere = data(importing(dispatcherElsewhere, "WAREHOUSE_STAFF", "/preview", csv)
                .andExpect(status().isOk()));
        assertThat(elsewhere.path("lines").get(0).path("problem").asText()).isEqualTo("LOAD_OUT_OF_SCOPE");
        assertThat(elsewhere.path("lines").get(0).hasNonNull("loadId")).isFalse();

        JsonNode imported = data(importing(dispatcher, "WAREHOUSE_STAFF", "", csv).andExpect(status().isCreated()));
        String importId = imported.path("importId").asText();
        assertThat(imported.path("rowsApplied").asInt()).isEqualTo(1);
        assertThat(imported.path("rowsRejected").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT warehouse_id FROM fulfillment.waybill_import WHERE id = ?::uuid",
                UUID.class, importId)).isEqualTo(HCM);
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
        sales(get(BASE + "/waybill-imports/template")).andExpect(status().isForbidden());
        byte[] template = dispatch(get(BASE + "/waybill-imports/template")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(template[0]).isEqualTo((byte) 0xEF);
        assertThat(new String(template, java.nio.charset.StandardCharsets.UTF_8).split(",")).hasSize(5);
    }

    private ResultActions importingFile(UUID user, String role, String path, String name, String type, byte[] bytes)
            throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", name, type, bytes);
        return as(user, role, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/waybill-imports" + forCaller(user, path)).file(file));
    }

    /** The template's table as a workbook: the heading row, then each line as text cells. */
    private static byte[] workbookOf(String[]... lines) throws java.io.IOException {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Nhập");
            String[] all = {"Mã chuyến", "Mã vận đơn", "Biển số", "Tên tài xế", "Điện thoại tài xế"};
            var heading = sheet.createRow(0);
            for (int c = 0; c < all.length; c++) {
                heading.createCell(c).setCellValue(all[c]);
            }
            for (int r = 0; r < lines.length; r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < lines[r].length; c++) {
                    if (lines[r][c] != null) {
                        row.createCell(c).setCellValue(lines[r][c]);
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("an .xlsx workbook of the template's table gives the same preview and result as its CSV, is kept as "
            + "the load's waybill file as it is (a workbook), and a file that is neither is refused whole")
    void waybillNumbersFromAWorkbook() throws Exception {
        JsonNode first = bookedWithoutNumber(ghn);
        JsonNode second = bookedWithoutNumber(ghn);
        String one = first.path("code").asText();
        String two = second.path("code").asText();
        String other = historyLoad("51C-HELD-X");
        String held = jdbc.queryForObject("SELECT waybill_no FROM fulfillment.dispatch_load WHERE id = ?::uuid",
                String.class, other);
        String number = "WB-XLS-" + first.path("loadId").asText();
        String[][] lines = {{one, number, "51C-222.22", "Tài", "0900"}, {two, held}, {"LD-NOPE", number + "-X"},
                {"", number + "-Y"}};
        String csv = one + "," + number + ",51C-222.22,Tài,0900\r\n" + two + "," + held + "\r\n"
                + "LD-NOPE," + number + "-X\r\n" + "," + number + "-Y\r\n";
        byte[] xlsx = workbookOf(lines);
        String xlsxType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

        // The same lines, outcomes and problems whichever the format - even when the browser declares another type or
        // the name says another.
        JsonNode fromCsv = data(importing(dispatcher, "WAREHOUSE_STAFF", "/preview", csv).andExpect(status().isOk()));
        JsonNode fromXlsx = data(importingFile(dispatcher, "WAREHOUSE_STAFF", "/preview", "van-don.xlsx", xlsxType,
                xlsx).andExpect(status().isOk()));
        JsonNode renamed = data(importingFile(dispatcher, "WAREHOUSE_STAFF", "/preview", "van-don.csv",
                "application/vnd.ms-excel", xlsx).andExpect(status().isOk()));
        assertThat(fromXlsx.path("lines")).isEqualTo(fromCsv.path("lines"));
        assertThat(renamed.path("lines")).isEqualTo(fromCsv.path("lines"));
        assertThat(fromXlsx.path("rowsTotal").asInt()).isEqualTo(4);
        assertThat(fromXlsx.path("rowsApplied").asInt()).isEqualTo(1);
        assertThat(fromXlsx.path("lines").findValuesAsText("outcome")).containsExactly("NEW", "ERROR", "ERROR",
                "ERROR");
        assertThat(fromXlsx.path("lines").get(1).path("problem").asText()).isEqualTo("DUPLICATE_IN_CARRIER");
        assertThat(fromXlsx.path("lines").get(3).path("problem").asText()).isEqualTo("MISSING_FIELDS");

        JsonNode imported = data(importingFile(dispatcher, "WAREHOUSE_STAFF", "", "van-don.xlsx", xlsxType, xlsx)
                .andExpect(status().isCreated()));
        assertThat(imported.path("rowsApplied").asInt()).isEqualTo(1);
        assertThat(imported.path("rowsRejected").asInt()).isEqualTo(3);
        JsonNode now = load(first.path("loadId").asText());
        assertThat(now.path("waybillNo").asText()).isEqualTo(number);
        assertThat(now.path("vehiclePlate").asText()).isEqualTo("51C-222.22");
        assertThat(now.path("driverName").asText()).isEqualTo("Tài");
        // The booking slip names the load's warehouse (kltn-docs 09 §3 Output: kho xuất): by name, not by id alone.
        assertThat(now.path("warehouseName").asText()).isEqualTo(jdbc.queryForObject(
                "SELECT w.name FROM fulfillment.dispatch_load l JOIN warehouse.warehouse w ON w.id = l.warehouse_id "
                        + "WHERE l.id = ?::uuid", String.class, first.path("loadId").asText()));
        // The workbook itself is the load's waybill file, a workbook still.
        assertThat(data(dispatch(get(LOADS + "/" + first.path("loadId").asText() + "/waybill-files"))
                .andExpect(status().isOk()))).singleElement().satisfies(f -> {
                    assertThat(f.path("contentType").asText()).isEqualTo(xlsxType);
                    assertThat(f.path("fileName").asText()).isEqualTo("van-don.xlsx");
                });
        assertThat(jdbc.queryForObject("SELECT storage_key FROM fulfillment.waybill_import WHERE id = ?::uuid",
                String.class, imported.path("importId").asText())).endsWith(".xlsx");

        // Neither a workbook nor the template's text: refused whole, in either format's name.
        importingFile(dispatcher, "WAREHOUSE_STAFF", "/preview", "van-don.xlsx", xlsxType, onePagePdf())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WAYBILL_IMPORT_INVALID_FILE"));
        importingFile(dispatcher, "WAREHOUSE_STAFF", "", "van-don.csv", "text/csv",
                new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0}).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WAYBILL_IMPORT_INVALID_FILE"));
        importingFile(dispatcher, "WAREHOUSE_STAFF", "/preview", "van-don.xlsx", xlsxType, workbookOf())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("WAYBILL_IMPORT_INVALID_FILE"));
        // Without the permission to import, neither format is read.
        sales(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(BASE + "/waybill-imports/preview" + withWarehouse(HCM, "")).file(new org.springframework.mock.web.MockMultipartFile(
                        "file", "van-don.xlsx", xlsxType, xlsx))).andExpect(status().isForbidden());
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

    /**
     * A carrier's time {@code millis} after the latest handover of {@code shipments}, as ISO-8601: a delivery is held to
     * what a person could enter (WF-05 BE-38) - not before the handover, not in the future - so a report's time is
     * taken just after the vehicle left, and the reports keep their order by the offsets.
     */
    private String afterHandover(long millis, UUID... shipments) {
        Instant latest = null;
        for (UUID shipment : shipments) {
            Instant at = jdbc.queryForObject("SELECT handed_over_at FROM fulfillment.shipment WHERE id = ?",
                    java.sql.Timestamp.class, shipment).toInstant();
            latest = latest == null || at.isAfter(latest) ? at : latest;
        }
        return latest.plusMillis(millis).toString();
    }

    /**
     * The first whole second after the shipment's handover, in epoch seconds - Ahamove gives whole seconds - once the
     * clock has passed it, so the time is neither before the handover nor in the future (WF-05 BE-38).
     */
    private long secondAfterHandover(UUID shipment) throws InterruptedException {
        long second = jdbc.queryForObject("SELECT handed_over_at FROM fulfillment.shipment WHERE id = ?",
                java.sql.Timestamp.class, shipment).toInstant().getEpochSecond() + 1;
        while (Instant.now().getEpochSecond() <= second) {
            Thread.sleep(50);
        }
        return second;
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
        loadAndScan(loadId);
        // WF4-DRIVER: the driver's name is on the load before the signature. A test that is not about the driver has the
        // dispatcher type one when the booking or the carrier's webhook left the load without.
        if (!load(loadId).hasNonNull("driverName")) {
            dispatch(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\"Tai\"}"))
                    .andExpect(status().isOk());
        }
        sign(loadId, plate).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"));
    }

    private void loadAndScan(String loadId) throws Exception {
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        for (String label : labels(load(loadId))) {
            scanOnto(loadId, label).andExpect(status().isOk());
        }
    }

    private ResultActions sign(String loadId, String plate) throws Exception {
        long version = load(loadId).path("version").asLong();
        return dispatch(body(post(LOADS + "/" + loadId + "/handover"), "{\"version\":" + version
                + ",\"carrierRepresentative\":\"Nguyen Van Tai\",\"vehiclePlate\":\"" + plate + "\",\"signed\":true}"));
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
        // WF4-DRIVER: the driver the carrier sent is the driver on the signed load - not the one who signed for it.
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Tài xế B");
        assertThat(load(loadId).path("carrierRepresentative").asText()).isEqualTo("Nguyen Van Tai");
        aha(code, ahamove("AHA-W1", "IN PROCESS", "", "\"pickup_time\":1800000700", ""))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(jdbc.queryForObject("SELECT milestone FROM fulfillment.tracking_milestone WHERE load_id = ?::uuid",
                String.class, loadId)).isEqualTo("PICKED_UP");

        // Drop-off 1 completed: its shipment delivered at the carrier's time (E25), recorded by nobody. A delivery time
        // is held to what a person could enter (WF-05 BE-38): after the handover, not in the future.
        long completed = secondAfterHandover(shipment);
        ahamoveSays("AHA-W1", "IN PROCESS", "COMPLETED");
        aha(code, ahamove("AHA-W1-1", "IN PROCESS", "", "", "\"status\":\"COMPLETED\",\"complete_time\":" + completed
                + ".5")).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT outcome_at FROM fulfillment.shipment WHERE id = ?", java.sql.Timestamp.class,
                shipment).toInstant()).isEqualTo(Instant.ofEpochSecond(completed, 500_000_000L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id = ? AND "
                + "status = 'DELIVERED' AND source = 'CARRIER_WEBHOOK' AND recorded_by IS NULL", Long.class, shipment))
                .isOne();
        assertThat(lastEvent(aha).path("shipment_id").asText()).isEqualTo(shipment.toString());
        // A failure the carrier reports for an earlier time: outdated, ignored, still logged.
        aha(code, ahamove("AHA-W1-1", "IN PROCESS", "", "", "\"status\":\"FAILED\",\"fail_time\":" + (completed - 600)
                + ",\"fail_comment\":\"Khách vắng\"")).andExpect(jsonPath("$.data.result").value("STALE"));
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
        ahamoveSays("AHA-C1", "CANCELLED");
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
    @DisplayName("RTM-LOADCANCEL / RTM-LOADCANCEL2 (09 §4.5): Ahamove cancels the order while the vehicle is loading - "
            + "the load is PLANNED again with its booking cleared and the same layout; parcels already on board are "
            + "taken off the count and must be scanned off back to the dispatch area before the vehicle is booked again")
    void ahamoveCancelsTheOrderWhileLoading() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        String code = carrierCode(aha);
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        deliverTo(shipment, "79");
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-L1", null), new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-L2", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());

        ahamoveSays("AHA-L1", "CANCELLED");
        aha(code, ahamove("AHA-L1", "CANCELLED", "", "\"cancel_time\":1800000900,\"cancel_by_user\":false", ""))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        JsonNode planned = load(loadId);
        assertThat(planned.path("status").asText()).isEqualTo("PLANNED");
        assertThat(planned.hasNonNull("carrierId")).isFalse();
        assertThat(planned.path("carrierBooking").path("status").asText()).isEqualTo("CANCELLED");

        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());
        loadAndScan(loadId);
        List<String> onBoard = labels(load(loadId));
        assertThat(load(loadId).path("loadedPackageCount").asInt()).isEqualTo(onBoard.size());
        ahamoveSays("AHA-L2", "CANCELLED");
        aha(code, ahamove("AHA-L2", "CANCELLED", "", "\"cancel_time\":1800001900,\"cancel_by_user\":false", ""))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        JsonNode back = load(loadId);
        assertThat(back.path("status").asText()).isEqualTo("PLANNED");
        assertThat(back.path("loadedPackageCount").asInt()).isZero();
        List<String> pending = new ArrayList<>();
        back.path("pendingUnloads").forEach(u -> pending.add(u.path("labelCode").asText()));
        assertThat(pending).containsExactlyInAnyOrderElementsOf(onBoard);
        // Booked again only once every parcel is scanned off the old truck.
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LOAD_UNLOAD_PENDING"));
        for (String label : onBoard) {
            dispatch(body(post(LOADS + "/" + loadId + "/unloads"), "{\"packageCode\":\"" + label + "\"}"))
                    .andExpect(status().isOk());
        }
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-L3", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());
        loadAndScan(loadId);
        assertThat(load(loadId).path("loadedPackageCount").asInt()).isEqualTo(onBoard.size());
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
        // The carrier never sent a driver: the load cannot be signed until the dispatcher types one (WF4-DRIVER).
        loadAndScan(loadId);
        sign(loadId, "51C-AHA.02").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_DRIVER_MISSING"));
        dispatch(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\"Tài xế gõ tay\"}"))
                .andExpect(status().isOk());
        sign(loadId, "51C-AHA.02").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"));
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Tài xế gõ tay");
        assertThat(load(loadId).path("carrierRepresentative").asText()).isEqualTo("Nguyen Van Tai");
        assertThat(load(loadId).path("waybillNo").asText()).isEqualTo("AHA-K1");

        String wording = "Khách không nghe máy ".repeat(40);
        ahamoveSays("AHA-K1", "COMPLETED", "FAILED");
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
        // WF-05 Q-7 (BE-14): a report for the whole load does not force back a shipment with no report of its own - it
        // may be at its customer, its delivery report not in yet, and RETURNED is final. For review instead.
        String loadBack = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"RETURNED\",\"reportedAt\":"
                + "\"2026-11-21T10:00:00Z\"}";
        hook(code, loadBack, "X-Signature", signed.apply(loadBack))
                .andExpect(jsonPath("$.data.result").value("NEEDS_REVIEW"));
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");
        String shipmentCode = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                shipment);
        String back = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"RETURNED\",\"reportedAt\":"
                + "\"2026-11-21T10:00:00Z\",\"shipmentCode\":\"" + shipmentCode + "\"}";
        hook(code, back, "X-Signature", signed.apply(back)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(shipment)).isEqualTo("RETURNED");
        assertThat(jdbc.queryForList("SELECT status FROM fulfillment.shipment_event WHERE shipment_id = ? AND "
                + "source = 'CARRIER_WEBHOOK' ORDER BY occurred_at, status", String.class, shipment))
                .containsExactly("DELIVERY_FAILED", "RETURNED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM fulfillment.shipment WHERE id = ?", String.class,
                shipment)).isEqualTo("OTHER");
        // G18: the note is the docs' own words (09 §4.5 step 7, BR-26); G14: the timeline says the carrier reported it.
        assertThat(jdbc.queryForObject("SELECT failure_note FROM fulfillment.shipment WHERE id = ?", String.class,
                shipment)).isEqualTo("hãng báo hoàn hàng");
        JsonNode steps = data(dispatch(get(BASE + "/shipments/" + shipment)).andExpect(status().isOk())).path("timeline");
        assertThat(steps).filteredOn(s -> "RETURNED".equals(s.path("status").asText())).singleElement()
                .satisfies(s -> {
                    assertThat(s.path("source").asText()).isEqualTo("CARRIER_WEBHOOK");
                    assertThat(s.path("carrierEventId").asText()).isNotBlank();
                    assertThat(s.hasNonNull("recordedBy")).isFalse();
                });
        assertThat(steps).filteredOn(s -> "READY_TO_DISPATCH".equals(s.path("status").asText())).singleElement()
                .satisfies(s -> assertThat(s.path("source").asText()).isEqualTo("MANUAL"));

        // G12 (09 edge "hoàn hàng khi còn Handed Over"): the manager learnt the real reason - a dispatcher may not, a
        // correction says why, and the order's shipment follows; the status stays RETURNED.
        String reasonFix = BASE + "/shipments/" + shipment + "/failure-reason-correction";
        dispatch(body(post(reasonFix), "{\"failureReason\":\"REFUSED\",\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        as(manager, "WAREHOUSE_MANAGER", body(post(reasonFix), "{\"failureReason\":\"REFUSED\"}"))
                .andExpect(status().isBadRequest());
        as(manager, "WAREHOUSE_MANAGER", body(post(reasonFix), "{\"failureReason\":\"OTHER\",\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
        JsonNode fixed = data(as(manager, "WAREHOUSE_MANAGER", body(post(reasonFix), "{\"failureReason\":\"REFUSED\","
                + "\"reason\":\"the carrier called: the customer refused the goods\"}")).andExpect(status().isOk()));
        assertThat(fixed.path("shipment").path("status").asText()).isEqualTo("RETURNED");
        assertThat(fixed.path("shipment").path("failureReason").asText()).isEqualTo("REFUSED");
        assertThat(fixed.path("reasonCorrections")).singleElement().satisfies(c -> {
            assertThat(c.path("fromReason").asText()).isEqualTo("OTHER");
            assertThat(c.path("fromNote").asText()).isEqualTo("hãng báo hoàn hàng");
            assertThat(c.path("toReason").asText()).isEqualTo("REFUSED");
            assertThat(c.path("reason").asText()).isEqualTo("the carrier called: the customer refused the goods");
            assertThat(c.path("correctedBy").asText()).isEqualTo(manager.toString());
        });
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM ordering.order_shipment WHERE id = ?", String.class,
                shipment)).isEqualTo("REFUSED");
        // The same again changes nothing.
        as(manager, "WAREHOUSE_MANAGER", body(post(reasonFix), "{\"failureReason\":\"REFUSED\",\"reason\":\"again\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reasonCorrections.length()").value(1));

        // The error list: the manager resolves the early outcome once; an applied event is not the list's. The
        // load-wide RETURNED is on it too (Q-7).
        String reviewId = review.path("eventId").asText();
        as(manager, "WAREHOUSE_MANAGER", get(EVENTS).param("result", "ERRORS").param("open", "true")
                .param("carrierId", carrier.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4));
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

    /**
     * What Ahamove's API gives for the order when a final outcome its callback reported is asked back (WF-05 item 6):
     * the order's status and each drop-off's.
     */
    private void ahamoveSays(String orderId, String status, String... stops) {
        org.mockito.Mockito.doReturn(java.util.Optional.of(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.OrderState(status, null, List.of(stops)))).when(carrierGateway)
                .orderState(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(orderId));
    }

    /** An Ahamove carrier with a load booked on order {@code orderId} for one shipment, on the road: {shipment, load}. */
    private Object[] ahamoveLoadOnTheRoad(UUID aha, String orderId, String plate) throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        deliverTo(shipment, "79");
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed(orderId, null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());
        loadAndSign(loadId, plate);
        return new Object[] {shipment, loadId};
    }

    @Test
    @DisplayName("WF-05 item 6: Ahamove does not sign its callback, so a final outcome is asked back of its API - not "
            + "borne out, it is UNVERIFIED, not applied and on the error list; an API that does not answer leaves it "
            + "to be sent again (503); borne out, the same callback applies; a repeat is told without asking")
    void ahamoveOutcomeIsAskedBack() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        String code = carrierCode(aha);
        Object[] road = ahamoveLoadOnTheRoad(aha, "AHA-V1", "51C-AHA.07");
        UUID shipment = (UUID) road[0];
        // After the handover, not in the future (WF-05 BE-38).
        String delivered = ahamove("AHA-V1-1", "IN PROCESS", "", "", "\"status\":\"COMPLETED\","
                + "\"complete_time\":" + secondAfterHandover(shipment));

        // Ahamove's API gives the drop-off as still under way: whoever sent this, it is not applied.
        ahamoveSays("AHA-V1", "IN PROCESS", "");
        String unverified = data(aha(code, delivered).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("UNVERIFIED"))).path("eventId").asText();
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");
        JsonNode kept = lastEvent(aha);
        assertThat(kept.path("result_note").asText()).contains("drop-off 1").contains("AHA-V1").contains("COMPLETED");
        assertThat(kept.path("shipment_id").asText()).isEqualTo(shipment.toString());
        as(manager, "WAREHOUSE_MANAGER", get(EVENTS).param("result", "ERRORS").param("open", "true")
                .param("carrierId", aha.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].result").value("UNVERIFIED"))
                .andExpect(jsonPath("$.data.items[0].source").value("WEBHOOK"));
        as(manager, "WAREHOUSE_MANAGER", get(EVENTS).param("result", "UNVERIFIED").param("carrierId", aha.toString()))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        // An order Ahamove does not know at all: the same.
        org.mockito.Mockito.doReturn(java.util.Optional.empty()).when(carrierGateway)
                .orderState(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("AHA-V1"));
        aha(code, delivered).andExpect(jsonPath("$.data.result").value("UNVERIFIED"));
        assertThat(lastEvent(aha).path("result_note").asText()).contains("knows no order AHA-V1");

        // The API does not answer: no verdict. The callback is kept undecided and asked for again.
        org.mockito.Mockito.doThrow(new com.stockflow.fulfillment.internal.service.CarrierBookingGateway
                .CarrierCallFailed(false, null, "Ahamove did not answer")).when(carrierGateway)
                .orderState(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("AHA-V1"));
        aha(code, delivered).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_UNAVAILABLE"));
        assertThat(lastEvent(aha).path("result").asText()).isEqualTo("RECEIVED");
        assertThat(lastEvent(aha).path("result_note").asText()).contains("did not answer");
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");

        // Sent again once the API bears it out: applied - the copies not verified before do not make it a repeat.
        ahamoveSays("AHA-V1", "IN PROCESS", "COMPLETED");
        aha(code, delivered).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        // Review F6: the copy answered 503 is closed with it, so no retry stays open on the error list.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.carrier_event WHERE carrier_id = ? AND "
                + "result = 'RECEIVED'", Long.class, aha)).isZero();
        assertThat(jdbc.queryForObject("SELECT result_note FROM fulfillment.carrier_event WHERE carrier_id = ? AND "
                + "result = 'DUPLICATE'", String.class, aha)).contains("sent again was handled");
        as(manager, "WAREHOUSE_MANAGER", get(EVENTS).param("result", "ERRORS").param("open", "true")
                .param("carrierId", aha.toString())).andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].result").value("UNVERIFIED"))
                .andExpect(jsonPath("$.data.items[1].result").value("UNVERIFIED"));
        // The same callback once more is a repeat, told without asking Ahamove again.
        org.mockito.Mockito.clearInvocations(carrierGateway);
        aha(code, delivered).andExpect(jsonPath("$.data.result").value("DUPLICATE"));
        org.mockito.Mockito.verify(carrierGateway, org.mockito.Mockito.never())
                .orderState(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // The manager deals with the unverified call by hand, once.
        as(manager, "WAREHOUSE_MANAGER", body(post(EVENTS + "/" + unverified + "/resolution"),
                "{\"note\":\"asked Ahamove by phone: the driver had not arrived yet\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.result").value("UNVERIFIED"));
        as(manager, "WAREHOUSE_MANAGER", body(post(EVENTS + "/" + unverified + "/resolution"), "{\"note\":\"again\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("WF-05 item 6: a forged cancel of an order Ahamove still has is UNVERIFIED and leaves the load booked; "
            + "a callback dated outside the time window is not applied; a call from an address off the allow-list is "
            + "refused whatever credential it carries")
    void ahamoveForgedCancelWindowAndAllowList() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        String code = carrierCode(aha);
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        deliverTo(shipment, "79");
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-F1", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());

        ahamoveSays("AHA-F1", "ASSIGNING");
        String cancel = ahamove("AHA-F1", "CANCELLED", "", "\"cancel_time\":1800000900,\"cancel_by_user\":false", "");
        aha(code, cancel).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("UNVERIFIED"));
        JsonNode booked = load(loadId);
        assertThat(booked.path("status").asText()).isEqualTo("VEHICLE_BOOKED");
        assertThat(booked.path("carrierBooking").path("status").asText()).isEqualTo("BOOKED");
        assertThat(lastEvent(aha).path("result_note").asText()).contains("CANCELLED").contains("ASSIGNING");

        // A driver's acceptance dated 2001: recorded, outdated, not on the load.
        aha(code, ahamove("AHA-F1", "ACCEPTED", "", "\"accept_time\":1000000000,\"supplier_name\":\"Tài xế cũ\","
                + "\"supplier_id\":\"84903333333\"", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("STALE"));
        assertThat(lastEvent(aha).path("result_note").asText()).contains("time window");
        assertThat(load(loadId).path("driverName").asText("")).isNotEqualTo("Tài xế cũ");

        // The right credential from an address that is not on the list: refused, kept as a summary.
        String accepted = ahamove("AHA-F1", "ACCEPTED", "", "\"accept_time\":1800000000,\"supplier_name\":"
                + "\"Tài xế A\",\"supplier_id\":\"84901111111\"", "");
        mvc.perform(post(HOOKS + code).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(accepted).header("apikey", AHA_CREDENTIAL).with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_SIGNATURE_INVALID"));
        JsonNode refused = lastEvent(aha);
        assertThat(refused.path("result").asText()).isEqualTo("INVALID_SIGNATURE");
        assertThat(refused.path("result_note").asText()).contains("allow-list").doesNotContain("203.0.113.9");
        assertThat(refused.path("payload").asText()).startsWith("[refused: ");
        // From inside a block of the list: taken.
        mvc.perform(post(HOOKS + code).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(accepted).header("apikey", AHA_CREDENTIAL).with(request -> {
                            request.setRemoteAddr("10.20.3.4");
                            return request;
                        })).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Tài xế A");
    }

    @Test
    @DisplayName("WF-05 item 6: a webhook credential is replaced without a gap - after START both the old and the new "
            + "one are taken, after PROMOTE only the new one; the carrier's API never shows either")
    void ahamoveCredentialRotation() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        String code = carrierCode(aha);
        String next = "webhook-credential-next-0123456789";
        String call = ahamove("AHA-NONE", "ACCEPTED", "", "\"accept_time\":1800000000", "");
        String rotation = BASE + "/carriers/" + aha + "/webhook-credential-rotation";
        long version = data(as(manager, "WAREHOUSE_MANAGER", get(BASE + "/carriers/" + aha))).path("version").asLong();

        hook(code, call, "apikey", next).andExpect(status().isUnauthorized());
        dispatch(body(post(rotation), "{\"step\":\"START\",\"value\":\"" + next + "\",\"version\":" + version + "}"))
                .andExpect(status().isForbidden());
        as(manager, "WAREHOUSE_MANAGER", body(post(rotation), "{\"step\":\"PROMOTE\",\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARRIER_CREDENTIAL_ROTATION_NOT_STARTED"));
        as(manager, "WAREHOUSE_MANAGER", body(post(rotation), "{\"step\":\"START\",\"value\":\"" + AHA_CREDENTIAL
                + "\",\"version\":" + version + "}")).andExpect(status().isBadRequest());
        as(manager, "WAREHOUSE_MANAGER", body(post(rotation), "{\"step\":\"SWAP\",\"value\":\"" + next
                + "\",\"version\":" + version + "}")).andExpect(status().isBadRequest());

        ResultActions start = as(manager, "WAREHOUSE_MANAGER", body(post(rotation), "{\"step\":\"START\",\"value\":\""
                + next + "\",\"version\":" + version + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection.webhookAuthValueConfigured").value(true))
                .andExpect(jsonPath("$.data.connection.webhookAuthValueNextConfigured").value(true));
        assertThat(start.andReturn().getResponse().getContentAsString()).doesNotContain(next)
                .doesNotContain(AHA_CREDENTIAL);
        long started = data(start).path("version").asLong();
        // Both hold: Ahamove can be told the new one while calls with the old one still arrive.
        hook(code, call, "apikey", AHA_CREDENTIAL).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("UNMATCHED"));
        hook(code, call, "apikey", next).andExpect(status().isOk());
        hook(code, call, "apikey", "neither-of-the-two-0123456789").andExpect(status().isUnauthorized());
        as(manager, "WAREHOUSE_MANAGER", body(post(rotation), "{\"step\":\"PROMOTE\",\"version\":" + version + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));

        as(manager, "WAREHOUSE_MANAGER", body(post(rotation), "{\"step\":\"PROMOTE\",\"version\":" + started + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection.webhookAuthValueNextConfigured").value(false));
        hook(code, call, "apikey", AHA_CREDENTIAL).andExpect(status().isUnauthorized());
        hook(code, call, "apikey", next).andExpect(status().isOk());

        // A carrier with no header credential has nothing to rotate.
        long manual = data(as(manager, "WAREHOUSE_MANAGER", get(BASE + "/carriers/" + ghn))).path("version").asLong();
        as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers/" + ghn + "/webhook-credential-rotation"),
                "{\"step\":\"START\",\"value\":\"" + next + "\",\"version\":" + manual + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("CARRIER_NOT_API_BOOKING"));
    }

    // ---- delivery outcomes from a file (WF-05 item 5; 09 §4.3, BR-26, BR-27) ----------------------

    private static final String OUTCOME_IMPORTS = BASE + "/delivery-outcome-imports";

    private ResultActions outcomes(UUID user, String role, String path, String csv) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "ket-qua-giao.csv",
                "application/vnd.ms-excel", ("\uFEFFMã vận đơn,Kết quả,Lý do,Thời điểm,Người nhận,Ghi chú,Mã shipment,Mã hãng\r\n"
                        + csv).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return as(user, role, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(OUTCOME_IMPORTS + path).file(file));
    }

    /** When the vehicle left: no outcome of it is earlier (review F3). */
    private Instant leftAt(String loadId) {
        return jdbc.queryForObject("SELECT dispatched_at FROM fulfillment.dispatch_load WHERE id = ?::uuid",
                java.sql.Timestamp.class, loadId).toInstant();
    }

    private long fileEvents(String loadId) {
        return jdbc.queryForObject("SELECT count(*) FROM fulfillment.carrier_event WHERE load_id = ?::uuid AND "
                + "source = 'FILE'", Long.class, loadId);
    }

    @Test
    @DisplayName("WF-05 item 5: delivery outcomes from a file - the preview checks each line and writes nothing; "
            + "applying it makes one carrier event (source FILE) per accepted line, the failure reason mapped by the "
            + "carrier's table; the same file again changes nothing (DUPLICATE); an older line is STALE; the warehouse "
            + "manager's, within their warehouses")
    void deliveryOutcomesFromAFile() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        UUID managerElsewhere = staff("WAREHOUSE_MANAGER", elsewhere);
        String code = "MF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Xe tải ngoài\",\"connection\":{\"connectionType\":\"MANUAL\","
                        + "\"failureReasonMap\":{\"Khách không nhận\":\"REFUSED\"}}}")).andExpect(status().isCreated()))
                .path("carrierId").asText());
        UUID first = packed(ftlOrder(1), false)[0];
        UUID second = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(first, second), truck5t);
        assertThat(plan.path("vehicles")).hasSize(1);
        String loadId = loadOf(plan, 1);
        String firstCode = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                first);
        String secondCode = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                second);
        String waybill = "WF-" + loadId;
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-WF.01\",\"waybillNo\":\"" + waybill + "\"}")).andExpect(status().isOk());

        // The vehicle has not left: its outcome is not a file's to give yet.
        outcomes(manager, "WAREHOUSE_MANAGER", "/preview", waybill + ",DELIVERED,," + Instant.now() + ",,,\r\n")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[0].problem").value("LOAD_NOT_DISPATCHED"));
        loadAndSign(loadId, "51C-WF.01");
        // The carrier's times, a moment after the vehicle left. The first line names the carrier too (review F4).
        Instant left = leftAt(loadId);
        Instant at = left.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(2);
        String delivered = waybill + ",DELIVERED,," + at + ",Chị Lan,giao tận tay," + firstCode + ","
                + code.toLowerCase() + "\r\n";
        String failed = waybill + ",DELIVERY_FAILED,khách  KHÔNG nhận," + at + ",,," + secondCode + "\r\n";

        // Step 1, the preview: each line checked, nothing written.
        String file = delivered + failed
                + "WF-NO-SUCH-NUMBER,DELIVERED,," + at + ",,,\r\n"
                + waybill + ",SHIPPED,," + at + ",,,\r\n"
                + waybill + ",DELIVERED,," + Instant.now().plus(Duration.ofDays(1)) + ",,," + firstCode + "\r\n"
                + waybill + ",DELIVERY_FAILED,," + at + ",,," + secondCode + "\r\n"
                // Review F3: last year's date for this year's - readable, not in the future, before the vehicle left.
                + waybill + ",DELIVERED,," + left.minus(Duration.ofDays(365)) + ",,," + firstCode + "\r\n"
                // Review F4: the number as another carrier's.
                + waybill + ",DELIVERED,," + at + ",,," + firstCode + ",NO-SUCH-CARRIER\r\n";
        outcomes(manager, "WAREHOUSE_MANAGER", "/preview", file).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowsTotal").value(8)).andExpect(jsonPath("$.data.rowsAccepted").value(2))
                .andExpect(jsonPath("$.data.rowsRejected").value(6))
                .andExpect(jsonPath("$.data.lines[6].problem").value("TIME_BEFORE_DISPATCH"))
                .andExpect(jsonPath("$.data.lines[7].problem").value("WAYBILL_NOT_FOUND"))
                .andExpect(jsonPath("$.data.lines[0].verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.lines[0].changes").value(true))
                .andExpect(jsonPath("$.data.lines[0].loadCode").value(load(loadId).path("code").asText()))
                .andExpect(jsonPath("$.data.lines[1].verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.lines[2].problem").value("WAYBILL_NOT_FOUND"))
                .andExpect(jsonPath("$.data.lines[3].problem").value("INVALID_OUTCOME"))
                .andExpect(jsonPath("$.data.lines[4].problem").value("TIME_IN_FUTURE"))
                .andExpect(jsonPath("$.data.lines[5].problem").value("REASON_REQUIRED"));
        assertThat(fileEvents(loadId)).isZero();
        assertThat(shipmentStatus(first)).isEqualTo("HANDED_OVER");
        // Dispatch staff do not apply outcomes; a manager of another warehouse does not see this vehicle's number.
        outcomes(dispatcher, "WAREHOUSE_STAFF", "/preview", file).andExpect(status().isForbidden());
        outcomes(dispatcher, "WAREHOUSE_STAFF", "", file).andExpect(status().isForbidden());
        outcomes(managerElsewhere, "WAREHOUSE_MANAGER", "/preview", delivered).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].problem").value("WAYBILL_NOT_FOUND"));
        var notATable = new org.springframework.mock.web.MockMultipartFile("file", "x.csv", "text/csv",
                new byte[] {(byte) 0xC3, (byte) 0x28, 0x0A, 0x41});
        as(manager, "WAREHOUSE_MANAGER", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(OUTCOME_IMPORTS).file(notATable)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_OUTCOME_IMPORT_INVALID_FILE"));

        // An apply that stopped half-way (the process died after the file and its lines were recorded): its accepted
        // line has no event, and reads so. Applying the file again is what finishes it.
        UUID interrupted = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO fulfillment.delivery_outcome_import (id, file_name, file_sha256, uploaded_by, "
                    + "uploaded_at, rows_total, rows_accepted, rows_rejected) VALUES (?, 'ket-qua-giao.csv', "
                    + "repeat('a', 64), ?, NOW() - INTERVAL '1 hour', 1, 1, 0)", interrupted, manager);
            jdbc.update("INSERT INTO fulfillment.delivery_outcome_import_row (id, import_id, line_no, waybill_no, "
                    + "outcome, reported_at, shipment_code, load_id, verdict) VALUES (gen_random_uuid(), ?, 2, ?, "
                    + "'DELIVERED', ?, ?, ?::uuid, 'ACCEPTED')", interrupted, waybill, at.toString(), firstCode, loadId);
        });
        as(manager, "WAREHOUSE_MANAGER", get(OUTCOME_IMPORTS + "/" + interrupted)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowsAccepted").value(1)).andExpect(jsonPath("$.data.rowsApplied").value(0))
                .andExpect(jsonPath("$.data.lines[0].verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.lines[0].result").doesNotExist())
                .andExpect(jsonPath("$.data.lines[0].eventId").doesNotExist());
        assertThat(shipmentStatus(first)).isEqualTo("HANDED_OVER");

        // Step 2, applying it: one event per accepted line, through the webhooks' pipeline.
        JsonNode applied = data(outcomes(manager, "WAREHOUSE_MANAGER", "", file).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rowsAccepted").value(2)).andExpect(jsonPath("$.data.rowsApplied").value(2))
                .andExpect(jsonPath("$.data.lines[0].result").value("APPLIED"))
                .andExpect(jsonPath("$.data.lines[1].result").value("APPLIED"))
                .andExpect(jsonPath("$.data.lines[2].problem").value("WAYBILL_NOT_FOUND")));
        String importId = applied.path("id").asText();
        assertThat(shipmentStatus(first)).isEqualTo("DELIVERED");
        assertThat(shipmentStatus(second)).isEqualTo("DELIVERY_FAILED");
        Map<String, Object> failure = jdbc.queryForMap("SELECT failure_reason, failure_note, outcome_at FROM "
                + "fulfillment.shipment WHERE id = ?", second);
        assertThat(failure.get("failure_reason")).isEqualTo("REFUSED");
        assertThat(failure.get("failure_note")).isEqualTo("khách  KHÔNG nhận");
        assertThat(((java.sql.Timestamp) failure.get("outcome_at")).toInstant()).isEqualTo(at);
        assertThat(fileEvents(loadId)).isEqualTo(2);
        Map<String, Object> event = jdbc.queryForMap("SELECT source, signature_valid, outcome_import_id::text AS "
                + "import_id, outcome_import_line, carrier_reference, payload, result FROM fulfillment.carrier_event "
                + "WHERE shipment_id = ?", first);
        assertThat(event).containsEntry("source", "FILE").containsEntry("signature_valid", true)
                .containsEntry("import_id", importId).containsEntry("outcome_import_line", 2)
                .containsEntry("carrier_reference", waybill).containsEntry("result", "APPLIED");
        assertThat((String) event.get("payload")).contains("Chị Lan").contains("giao tận tay");
        as(manager, "WAREHOUSE_MANAGER", get(EVENTS).param("loadId", loadId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].source").value("FILE"));

        // The same file again: every accepted line is a repeat. Nothing changes.
        long steps = jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id IN (?, ?)",
                Long.class, first, second);
        outcomes(manager, "WAREHOUSE_MANAGER", "", file).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rowsAccepted").value(2)).andExpect(jsonPath("$.data.rowsApplied").value(0))
                .andExpect(jsonPath("$.data.lines[0].changes").value(false))
                .andExpect(jsonPath("$.data.lines[0].result").value("DUPLICATE"))
                .andExpect(jsonPath("$.data.lines[1].result").value("DUPLICATE"));
        assertThat(shipmentStatus(first)).isEqualTo("DELIVERED");
        assertThat(shipmentStatus(second)).isEqualTo("DELIVERY_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id IN (?, ?)",
                Long.class, first, second)).isEqualTo(steps);

        // A line older than what was applied to the shipment is outdated, as a webhook's report would be (BR-27).
        outcomes(manager, "WAREHOUSE_MANAGER", "", waybill + ",DELIVERED,," + at.minusSeconds(1) + ",,,"
                + secondCode + "\r\n").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.lines[0].result").value("STALE"));
        assertThat(shipmentStatus(second)).isEqualTo("DELIVERY_FAILED");

        // The import read back: its lines as written, and what came of each one's event.
        as(manager, "WAREHOUSE_MANAGER", get(OUTCOME_IMPORTS + "/" + importId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("ket-qua-giao.csv"))
                .andExpect(jsonPath("$.data.rowsTotal").value(8)).andExpect(jsonPath("$.data.rowsApplied").value(2))
                .andExpect(jsonPath("$.data.lines[0].recipient").value("Chị Lan"))
                .andExpect(jsonPath("$.data.lines[0].carrierCode").value(code.toLowerCase()))
                .andExpect(jsonPath("$.data.lines[6].problem").value("TIME_BEFORE_DISPATCH"))
                .andExpect(jsonPath("$.data.lines[0].result").value("APPLIED"))
                .andExpect(jsonPath("$.data.lines[3].verdict").value("ERROR"))
                .andExpect(jsonPath("$.data.lines[3].problem").value("INVALID_OUTCOME"));
        as(manager, "WAREHOUSE_MANAGER", get(OUTCOME_IMPORTS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[2].id").value(importId))
                .andExpect(jsonPath("$.data.items[3].id").value(interrupted.toString()));
        as(managerElsewhere, "WAREHOUSE_MANAGER", get(OUTCOME_IMPORTS + "/" + importId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_OUTCOME_IMPORT_NOT_FOUND"));
        dispatch(get(OUTCOME_IMPORTS)).andExpect(status().isForbidden());
        byte[] template = as(manager, "WAREHOUSE_MANAGER", get(OUTCOME_IMPORTS + "/template").header("Accept-Language",
                "vi")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(template, java.nio.charset.StandardCharsets.UTF_8)).startsWith("\uFEFFMã vận đơn,")
                .contains("Người nhận").contains("Mã hãng");
    }

    @Test
    @DisplayName("WF-05 item 5: a line with no shipment code is about every shipment on the vehicle (BR-26); a webhook "
            + "and a file reporting the same outcome of a WEBHOOK_LOAD carrier's vehicle are one report")
    void aFileLineForTheWholeVehicle() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        String secret = "load-webhook-secret-file-0123456789";
        String code = "WF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Xe tải webhook\",\"connection\":{\"connectionType\":"
                        + "\"WEBHOOK_LOAD\",\"webhookSecret\":\"" + secret + "\"}}")).andExpect(status().isCreated()))
                .path("carrierId").asText());
        UUID first = packed(ftlOrder(1), false)[0];
        UUID second = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(first, second), truck5t), 1);
        String loadCode = load(loadId).path("code").asText();
        String waybill = "WG-" + loadId;
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-WG.01\",\"waybillNo\":\"" + waybill + "\"}")).andExpect(status().isOk());
        loadAndSign(loadId, "51C-WG.01");
        Instant at = leftAt(loadId).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(2);

        // The carrier's webhook reports the whole vehicle delivered...
        String delivered = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":\"" + at + "\"}";
        hook(code, delivered, "X-Signature", com.stockflow.fulfillment.internal.service.CarrierWebhookTestSigner
                .sign(secret, delivered)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(first)).isEqualTo("DELIVERED");
        assertThat(shipmentStatus(second)).isEqualTo("DELIVERED");
        // ...and its list, applied afterwards, says the same of the same time: the report was handled already.
        outcomes(manager, "WAREHOUSE_MANAGER", "", waybill + ",DELIVERED,," + at + ",,,\r\n")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.lines[0].changes").value(false))
                .andExpect(jsonPath("$.data.lines[0].result").value("DUPLICATE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment_event WHERE shipment_id = ? AND "
                + "status = 'DELIVERED'", Long.class, first)).isOne();
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
    @DisplayName("09B G7 / G13: a delivery the carrier's webhook reports with a proof of delivery keeps it with the "
            + "shipment as an uploaded one is (uploaded by nobody, naming the event; once per event); Sales read the "
            + "order's POD and waybill files and download them, the customer and the warehouse staff may not")
    void aProofOfDeliveryFromTheWebhookAndSalesReadTheDocuments() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        String secret = "load-webhook-secret-pod-0123456789";
        String code = "WP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Xe tải webhook\",\"connection\":{\"connectionType\":"
                        + "\"WEBHOOK_LOAD\",\"webhookSecret\":\"" + secret + "\"}}")).andExpect(status().isCreated()))
                .path("carrierId").asText());
        UUID[] mine = portalCustomer();
        OrderSummary order = ftlOrder(1, mine[0]);
        UUID shipment = packed(order, false)[0];
        String shipmentCode = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                shipment);
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        String loadCode = load(loadId).path("code").asText();
        var waybillFile = new org.springframework.mock.web.MockMultipartFile("file", "van-don.pdf", "application/pdf",
                onePagePdf());
        // RTM-WAYBILLFILE: no waybill file before a carrier is booked for the vehicle.
        dispatch(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(LOADS + "/" + loadId + "/waybill-files").file(waybillFile)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_NOT_BOOKED"));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-WP.01\",\"driverName\":\"Tai\",\"waybillNo\":\"WP-" + loadId
                + "\"}")).andExpect(status().isOk());
        dispatch(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(LOADS + "/" + loadId + "/waybill-files").file(waybillFile)).andExpect(status().isCreated());
        loadAndSign(loadId, "51C-WP.01");
        byte[] proof = onePagePdf();
        com.stockflow.fulfillment.internal.service.CarrierPodTestDownloads.answer(context, proof, "application/pdf",
                "pod-signed.pdf");

        // Not an https address: the event is unreadable and goes to the error list, nothing is applied.
        String plain = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":\""
                + afterHandover(1, shipment) + "\",\"shipmentCode\":\"" + shipmentCode + "\",\"podUrl\":"
                + "\"http://files.example.com/pod.pdf\"}";
        hook(code, plain, "X-Signature", com.stockflow.fulfillment.internal.service.CarrierWebhookTestSigner
                .sign(secret, plain)).andExpect(jsonPath("$.data.result").value("NEEDS_REVIEW"));
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");

        String delivered = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":\""
                + afterHandover(1, shipment) + "\",\"shipmentCode\":\"" + shipmentCode + "\",\"podUrl\":"
                + "\"https://files.example.com/pod/123/pod-signed.pdf\"}";
        String eventId = data(hook(code, delivered, "X-Signature", com.stockflow.fulfillment.internal.service
                .CarrierWebhookTestSigner.sign(secret, delivered)).andExpect(jsonPath("$.data.result").value("APPLIED")))
                .path("eventId").asText();
        assertThat(shipmentStatus(shipment)).isEqualTo("DELIVERED");
        Await.until("the carrier's proof of delivery kept", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "fulfillment.shipment_pod_file WHERE shipment_id = ?", Long.class, shipment) == 1);
        Map<String, Object> pod = jdbc.queryForMap("SELECT file_name, content_type, uploaded_by, carrier_event_id::text "
                + "AS event FROM fulfillment.shipment_pod_file WHERE shipment_id = ?", shipment);
        assertThat(pod.get("file_name")).isEqualTo("pod-signed.pdf");
        assertThat(pod.get("content_type")).isEqualTo("application/pdf");
        assertThat(pod.get("uploaded_by")).isNull();
        assertThat(pod.get("event")).isEqualTo(eventId);

        // Sales read the order's documents and download them; the customer and the warehouse staff are refused.
        String documents = BASE + "/orders/" + order.orderId() + "/delivery-documents";
        JsonNode listed = data(sales(get(documents)).andExpect(status().isOk()));
        assertThat(listed).singleElement().satisfies(s -> {
            assertThat(s.path("shipmentCode").asText()).isEqualTo(shipmentCode);
            assertThat(s.path("podFiles")).singleElement()
                    .satisfies(f -> assertThat(f.path("fileName").asText()).isEqualTo("pod-signed.pdf"));
            assertThat(s.path("vehicles")).singleElement().satisfies(v -> {
                assertThat(v.path("loadCode").asText()).isEqualTo(loadCode);
                assertThat(v.path("waybillNo").asText()).isEqualTo("WP-" + loadId);
                assertThat(v.path("waybillFiles")).singleElement()
                        .satisfies(f -> assertThat(f.path("fileName").asText()).isEqualTo("van-don.pdf"));
            });
        });
        String podFile = listed.get(0).path("podFiles").get(0).path("fileId").asText();
        String waybillFileId = listed.get(0).path("vehicles").get(0).path("waybillFiles").get(0).path("fileId").asText();
        // The link itself needs the S3 provider (local storage answers 503 STORAGE_ERROR here): the order's own files
        // pass the permission and the ownership check - neither 403 nor 404.
        for (String link : List.of(documents + "/pod-files/" + podFile + "/download-url",
                documents + "/waybill-files/" + waybillFileId + "/download-url")) {
            int answered = sales(get(link)).andReturn().getResponse().getStatus();
            assertThat(answered).as(link).isNotIn(403, 404);
        }
        // A file of another order is not this order's.
        OrderSummary other = ftlOrder(1);
        sales(get(BASE + "/orders/" + other.orderId() + "/delivery-documents/pod-files/" + podFile + "/download-url"))
                .andExpect(status().isNotFound());
        assertThat(data(sales(get(BASE + "/orders/" + other.orderId() + "/delivery-documents"))
                .andExpect(status().isOk()))).isEmpty();
        as(mine[1], "CUSTOMER", get(documents)).andExpect(status().isForbidden());
        dispatch(get(documents)).andExpect(status().isForbidden());
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
    @DisplayName("09 §4.3 step 2: the warehouse manager enters the delivery time the carrier reported - not in the future, "
            + "not before the handover - and it is the shipment's, the order's and the draft "
            + "invoice's date; a correction back to delivered takes one too; none given = now")
    void theDeliveryTimeIsEnteredByHand() throws Exception {
        OrderSummary order = ftlOrder(1);
        UUID orderId = order.orderId();
        UUID shipment = packed(order, false)[0];
        String load = loadOf(confirmed(List.of(shipment), truck5t), 1);
        loadAll(load, ghn);
        handOver(load).andExpect(status().isOk());
        Instant handedOver = jdbc.queryForObject("SELECT handed_over_at FROM fulfillment.shipment WHERE id = ?",
                java.sql.Timestamp.class, shipment).toInstant();
        String outcome = BASE + "/shipments/" + shipment + "/delivery-outcome";

        oc(body(post(outcome), "{\"outcome\":\"DELIVERED\",\"deliveredAt\":\"" + Instant.now().plusSeconds(3600)
                + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_DELIVERY_TIME_IN_FUTURE"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("deliveredAt"));
        oc(body(post(outcome), "{\"outcome\":\"DELIVERED\",\"deliveredAt\":\"" + handedOver.minusSeconds(3600)
                + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("deliveredAt"));
        oc(body(post(outcome), "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"LOST\",\"deliveredAt\":\""
                + Instant.now().minusSeconds(60) + "\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("deliveredAt"));
        assertThat(shipmentStatus(shipment)).as("nothing was recorded").isEqualTo("HANDED_OVER");

        // A moment between the handover and now: the whole span a delivery time may take.
        Thread.sleep(2_500);
        Instant deliveredAt = handedOver.plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        assertThat(deliveredAt).isAfter(handedOver).isBefore(Instant.now());
        oc(body(post(outcome), "{\"outcome\":\"DELIVERED\",\"deliveredAt\":\"" + deliveredAt + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shipment.status").value("DELIVERED"))
                .andExpect(jsonPath("$.data.shipment.outcomeAt").value(org.hamcrest.Matchers
                        .startsWith(deliveredAt.toString().replace("Z", ""))));
        assertThat(jdbc.queryForObject("SELECT outcome_at FROM fulfillment.shipment WHERE id = ?",
                java.sql.Timestamp.class, shipment).toInstant()).isEqualTo(deliveredAt);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM fulfillment.shipment_event WHERE shipment_id = ? "
                + "AND status = 'DELIVERED'", java.sql.Timestamp.class, shipment).toInstant()).isEqualTo(deliveredAt);
        assertThat(orders.findById(orderId).orElseThrow().lifecycle().deliveredAt()).isEqualTo(deliveredAt);
        Await.until("the draft invoice drawn up", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ?", Long.class, shipment) == 1);
        assertThat(jdbc.queryForObject("SELECT issue_date FROM invoicing.einvoice WHERE shipment_id = ?",
                java.sql.Date.class, shipment).toLocalDate()).as("the invoice is dated the delivery day")
                .isEqualTo(com.stockflow.common.domain.BusinessCalendar.date(deliveredAt));

        // The manager corrects it to a failure and, once the customer found the goods, back to a delivery of an earlier
        // time. A correction to a failure takes no delivery time.
        String correction = BASE + "/shipments/" + shipment + "/delivery-outcome-correction";
        oc(body(post(correction), "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"CUSTOMER_ABSENT\","
                + "\"reason\":\"nothing arrived\",\"deliveredAt\":\"" + deliveredAt + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("deliveredAt"));
        oc(body(post(correction), "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"CUSTOMER_ABSENT\","
                + "\"reason\":\"nothing arrived\"}")).andExpect(status().isOk());
        Instant foundAt = handedOver.plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        oc(body(post(correction), "{\"outcome\":\"DELIVERED\",\"reason\":\"found\",\"deliveredAt\":\""
                + Instant.now().plusSeconds(3600) + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_DELIVERY_TIME_IN_FUTURE"));
        oc(body(post(correction), "{\"outcome\":\"DELIVERED\",\"reason\":\"found\",\"deliveredAt\":\""
                + handedOver.minusSeconds(60) + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_DELIVERY_TIME_BEFORE_HANDOVER"));
        oc(body(post(correction), "{\"outcome\":\"DELIVERED\",\"reason\":\"found\",\"deliveredAt\":\""
                + foundAt + "\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT outcome_at FROM fulfillment.shipment WHERE id = ?",
                java.sql.Timestamp.class, shipment).toInstant()).isEqualTo(foundAt);
        assertThat(orders.findById(orderId).orElseThrow().lifecycle().deliveredAt()).isEqualTo(foundAt);
        assertThat(jdbc.queryForObject("SELECT corrected_at FROM fulfillment.delivery_outcome_correction WHERE "
                + "shipment_id = ? AND to_status = 'DELIVERED'", java.sql.Timestamp.class, shipment).toInstant())
                .as("the correction itself is dated when it was made, not by the delivery time").isAfter(foundAt);
    }

    @Test
    @DisplayName("09 §4.3 step 2: a delivery recorded without a time is dated now, as before")
    void aDeliveryWithoutATimeIsDatedNow() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        String load = loadOf(confirmed(List.of(shipment), truck5t), 1);
        loadAll(load, ghn);
        handOver(load).andExpect(status().isOk());
        Instant before = Instant.now();

        oc(body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"), "{\"outcome\":\"DELIVERED\"}"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT outcome_at FROM fulfillment.shipment WHERE id = ?",
                java.sql.Timestamp.class, shipment).toInstant()).isBetween(before.minusSeconds(1),
                Instant.now().plusSeconds(1));
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
                + "\",\"vehiclePlate\":\"51C-WC.01\",\"driverName\":\"Tai\",\"waybillNo\":\"WC-" + loadId
                + "\"}")).andExpect(status().isOk());
        loadAndSign(loadId, "51C-WC.01");
        String delivered = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":\""
                + afterHandover(1, shipment) + "\",\"shipmentCode\":\"" + shipmentCode + "\"}";
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
        String again = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":\""
                + afterHandover(3, shipment) + "\",\"shipmentCode\":\"" + shipmentCode + "\"}";
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
    @DisplayName("09 edge 'hãng có webhook nhưng sự kiện không về' (RTM-FAILEDDELIV, 09B-226): the warehouse manager "
            + "recorded a failure by hand - the carrier's later 'delivered' is not applied, it goes to the error list; "
            + "a failure the carrier itself reported is delivered again by its next report")
    void aDeliveryReportedAfterAFailureRecordedByHand() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        String secret = "load-webhook-secret-fd-0123456789";
        String code = "WF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Xe tải webhook\",\"connection\":{\"connectionType\":"
                        + "\"WEBHOOK_LOAD\",\"webhookSecret\":\"" + secret + "\"}}")).andExpect(status().isCreated()))
                .path("carrierId").asText());
        UUID byHand = packed(ftlOrder(1), false)[0];
        UUID byCarrier = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(byHand, byCarrier), truck5t);
        String loadId = loadOf(plan, 1);
        String loadCode = load(loadId).path("code").asText();
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-WF.01\",\"driverName\":\"Tai\",\"waybillNo\":\"WF-" + loadId
                + "\"}")).andExpect(status().isOk());
        loadAndSign(loadId, "51C-WF.01");
        java.util.function.BiFunction<UUID, String, String> report = (shipment, type) -> "{\"loadCode\":\""
                + loadCode + "\",\"type\":\"" + type + "\",\"reportedAt\":\"" + afterHandover(type.equals("DELIVERED")
                ? 2 : 1, shipment) + "\",\"shipmentCode\":\"" + jdbc.queryForObject("SELECT code FROM "
                + "fulfillment.shipment WHERE id = ?", String.class, shipment) + "\",\"failureReason\":\"x\"}";
        java.util.function.Function<String, String> signed = content -> com.stockflow.fulfillment.internal.service
                .CarrierWebhookTestSigner.sign(secret, content);

        oc(body(post(BASE + "/shipments/" + byHand + "/delivery-outcome"), "{\"outcome\":\"DELIVERY_FAILED\","
                + "\"failureReason\":\"CUSTOMER_ABSENT\"}")).andExpect(status().isOk());
        String delivered = report.apply(byHand, "DELIVERED");
        hook(code, delivered, "X-Signature", signed.apply(delivered))
                .andExpect(jsonPath("$.data.result").value("NEEDS_REVIEW"));
        assertThat(shipmentStatus(byHand)).isEqualTo("DELIVERY_FAILED");

        String failed = report.apply(byCarrier, "DELIVERY_FAILED");
        hook(code, failed, "X-Signature", signed.apply(failed)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        String deliveredAgain = report.apply(byCarrier, "DELIVERED");
        hook(code, deliveredAgain, "X-Signature", signed.apply(deliveredAgain))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(byCarrier)).isEqualTo("DELIVERED");
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
                + "\",\"vehiclePlate\":\"51C-WE.01\",\"driverName\":\"Tai\",\"waybillNo\":\"WE-" + loadId
                + "\"}")).andExpect(status().isOk());
        loadAndSign(loadId, "51C-WE.01");
        java.util.function.Function<String, String> signed = content -> com.stockflow.fulfillment.internal.service
                .CarrierWebhookTestSigner.sign(secret, content);
        String one = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":\""
                + afterHandover(1, first, second) + "\",\"shipmentCode\":\"" + firstCode + "\"}";
        hook(code, one, "X-Signature", signed.apply(one)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/shipments/" + first + "/delivery-outcome-correction"),
                "{\"outcome\":\"DELIVERY_FAILED\",\"failureReason\":\"REFUSED\",\"reason\":\"the customer refused\"}"))
                .andExpect(status().isOk());

        String all = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"DELIVERED\",\"reportedAt\":\""
                + afterHandover(2, first, second) + "\"}";
        hook(code, all, "X-Signature", signed.apply(all)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(first)).isEqualTo("DELIVERY_FAILED");
        assertThat(shipmentStatus(second)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT result_note FROM fulfillment.carrier_event WHERE carrier_id = ? "
                + "ORDER BY received_at DESC LIMIT 1", String.class, carrier)).contains("corrected by hand")
                .contains(firstCode);
    }

    // ---- the driver before the signature (WF4-DRIVER; 09 §4.2 steps 6 and 8) ---------------------

    @Test
    @DisplayName("WF4-DRIVER: a load with no driver name is not handed over (409 LOAD_DRIVER_MISSING) - who signed for "
            + "the carrier is not taken for the driver; the dispatcher types the driver (permission checked, name "
            + "required, audited, the waybill number untouched); it cannot change once the vehicle left")
    void noDriverNoHandover() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        // Booked with its number and plate but no driver, loaded and every parcel scanned.
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + ghn
                + "\",\"vehiclePlate\":\"51C-DR.01\",\"waybillNo\":\"WB-" + loadId + "\"}")).andExpect(status().isOk());
        loadAndScan(loadId);
        sign(loadId, "51C-DR.01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_DRIVER_MISSING"));
        assertThat(load(loadId).path("status").asText()).isEqualTo("LOADING");
        assertThat(load(loadId).hasNonNull("driverName")).isFalse();

        // Sales does not work vehicles; a blank name is no name; the dispatcher's entry is on the load and audited.
        sales(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\"Tai\"}")).andExpect(status().isForbidden());
        dispatch(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\" \"}"))
                .andExpect(status().isBadRequest());
        dispatch(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverPhone\":\"0900\"}"))
                .andExpect(status().isBadRequest());
        dispatch(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\" Hoang Van Tai \","
                + "\"driverPhone\":\"0901234567\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driverName").value("Hoang Van Tai"))
                .andExpect(jsonPath("$.data.driverPhone").value("0901234567"))
                .andExpect(jsonPath("$.data.waybillNo").value("WB-" + loadId));
        // The same again changes nothing; a name alone keeps the phone.
        dispatch(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\"Hoang Van Tai\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.driverPhone").value("0901234567"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.audit_log WHERE resource_type = 'dispatch-load' "
                + "AND resource_id = ? AND outcome = 'SUCCESS' AND action = 'UPDATE' AND actor_id = ? "
                + "AND details LIKE '%Hoang Van Tai%'", Long.class, loadId, dispatcher)).isPositive();
        assertThat(data(dispatch(get(LOADS + "/" + loadId + "/waybill-history")).andExpect(status().isOk())))
                .as("the driver is not a waybill change").hasSize(1);

        sign(loadId, "51C-DR.01").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.driverName").value("Hoang Van Tai"))
                .andExpect(jsonPath("$.data.carrierRepresentative").value("Nguyen Van Tai"));
        // The driver is on the manifest both sides signed.
        dispatch(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\"Someone else\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Hoang Van Tai");
        // And not on a load with no carrier booked.
        UUID other = packed(ftlOrder(1), false)[0];
        String planned = loadOf(confirmed(List.of(other), truck5t), 1);
        dispatch(body(put(LOADS + "/" + planned + "/driver"), "{\"driverName\":\"Tai\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
    }

    @Test
    @DisplayName("WF4-DRIVER: a load booked through a carrier's API takes no typed waybill number but its driver is "
            + "typed by the dispatcher or the warehouse manager when the carrier's webhook never sends one - the number "
            + "stays the carrier's order id; the carrier's driver replaces the typed one, and when the driver gives "
            + "the order back the load needs another before it is signed")
    void aDriverByHandOnAnApiBookedLoad() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        UUID aha = apiCarrier(manager);
        String code = carrierCode(aha);
        UUID shipment = packed(ftlOrder(1), false)[0];
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        deliverTo(shipment, "79");
        org.mockito.Mockito.when(carrierGateway.place(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(new com.stockflow.fulfillment.internal.service
                .CarrierBookingGateway.Placed("AHA-D1", null));
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + aha + "\"}"))
                .andExpect(status().isOk());

        // The waybill route is closed to a typed number, driver or not - the driver has its own.
        dispatch(body(put(LOADS + "/" + loadId + "/waybill"), "{\"waybillNo\":\"AHA-TYPED\",\"driverName\":\"Tai\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("WAYBILL_FROM_CARRIER_API"));
        loadAndScan(loadId);
        sign(loadId, "51C-AHA.03").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_DRIVER_MISSING"));

        // The warehouse manager types the driver (the dispatcher's permission is the manager's too).
        as(manager, "WAREHOUSE_MANAGER", body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\"Tài xế A\","
                + "\"driverPhone\":\"0907000001\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driverName").value("Tài xế A"))
                .andExpect(jsonPath("$.data.waybillNo").value("AHA-D1"));
        assertThat(load(loadId).path("waybillNo").asText()).isEqualTo("AHA-D1");
        assertThat(data(dispatch(get(LOADS + "/" + loadId + "/waybill-history")).andExpect(status().isOk())))
                .hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM platform.audit_log WHERE resource_type = 'dispatch-load' "
                + "AND resource_id = ? AND outcome = 'SUCCESS' AND action = 'UPDATE' AND actor_id = ?", Long.class,
                loadId, manager)).isPositive();

        // An event with the driver's phone only: the phone is updated, the typed name stays.
        aha(code, ahamove("AHA-D1", "ACCEPTED", "", "\"accept_time\":1800000000,\"supplier_id\":\"84903333333\"", ""))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Tài xế A");
        assertThat(load(loadId).path("driverPhone").asText()).isEqualTo("84903333333");

        // The carrier's driver takes the order: it replaces the typed one. The driver gives it back: none is left.
        aha(code, ahamove("AHA-D1", "ACCEPTED", "", "\"accept_time\":1800000100,\"supplier_name\":\"Tài xế B\","
                + "\"supplier_id\":\"84902222222\"", "")).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(load(loadId).path("driverName").asText()).isEqualTo("Tài xế B");
        aha(code, ahamove("AHA-D1", "ASSIGNING", "", "\"accept_time\":1800000200", ""))
                .andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(load(loadId).hasNonNull("driverName")).isFalse();
        sign(loadId, "51C-AHA.03").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_DRIVER_MISSING"));

        dispatch(body(put(LOADS + "/" + loadId + "/driver"), "{\"driverName\":\"Tài xế C\"}"))
                .andExpect(status().isOk());
        sign(loadId, "51C-AHA.03").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.driverName").value("Tài xế C"))
                .andExpect(jsonPath("$.data.waybillNo").value("AHA-D1"));
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
        // A shipping label names neither the shipment nor n/N (08 §4.3, 4C-6): a split changes no carton's label.
        assertThat(jdbc.queryForObject("SELECT label_version FROM fulfillment.package WHERE id = ?::uuid", Integer.class,
                stays)).isEqualTo(1);
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

    /** An order of 2 cartons packed in two shipments (Sales allowed partial shipments), a carton each. */
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
        // Two shipments of one carton each: the check station runs once and hands the cartons out (see Parts).
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
        sales(body(post(ORDERS + "/" + order.orderId() + "/admin-cancellation"),
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
    @DisplayName("review of #162, 4B-8 (09 BR-18, 08 BR-11): an order owing its BEFORE_DELIVERY installment is named when "
            + "loading starts (handoverWarnings, loading goes ahead) and refuses the vehicle whole - even one that completes "
            + "no shipment (409 LOAD_HANDOVER_BLOCKED, the order, ORDER_BALANCE_NOT_PAID, what it owes, its batch); paid, "
            + "it leaves")
    void unpaidAtANonLastTruck() throws Exception {
        // 30% on signing (paid, so the order is confirmed), the rest before delivery (15 BR-07, BR-08).
        OrderSummary order = ftlOrder(2, DemoData.CUSTOMER_ID, List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("30"), 0, null),
                new SalesContracts.Installment("Before delivery", "BEFORE_DELIVERY", null, new BigDecimal("70"), null,
                        null)));
        UUID shipment = packed(order, true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        assertThat(plan.path("vehicles")).hasSize(2);
        String truck1 = loadOf(plan, 1);
        BigDecimal owed = jdbc.queryForObject("SELECT amount FROM payment.installment WHERE order_id = ? "
                + "AND milestone = 'BEFORE_DELIVERY'", BigDecimal.class, order.orderId());
        book(truck1, ghn, "51C-" + truck1.substring(0, 6));
        // R6: loading starts, and the warehouse is told now which order will hold the vehicle back.
        JsonNode started = data(dispatch(post(LOADS + "/" + truck1 + "/loading-start")).andExpect(status().isOk()));
        assertThat(started.path("status").asText()).isEqualTo("LOADING");
        assertThat(started.path("handoverWarnings")).singleElement().satisfies(w -> {
            assertThat(w.path("orderId").asText()).isEqualTo(order.orderId().toString());
            assertThat(w.path("reasonCode").asText()).isEqualTo("ORDER_BALANCE_NOT_PAID");
            assertThat(w.path("reasonCodes")).extracting(JsonNode::asText).containsExactly("ORDER_BALANCE_NOT_PAID");
            assertThat(w.path("amountDue").decimalValue()).isEqualByComparingTo(owed);
            assertThat(w.path("deliveryBatchSeq").asInt()).isEqualTo(1);
        });
        // WF4-A7-WARN: the load page shows the warnings that still hold, computed on every read (reloading keeps them).
        assertThat(load(truck1).path("handoverWarnings")).singleElement().satisfies(w -> {
            assertThat(w.path("orderId").asText()).isEqualTo(order.orderId().toString());
            assertThat(w.path("reasonCode").asText()).isEqualTo("ORDER_BALANCE_NOT_PAID");
            assertThat(w.path("amountDue").decimalValue()).isEqualByComparingTo(owed);
        });
        for (String label : labels(load(truck1))) {
            scanOnto(truck1, label).andExpect(status().isOk());
        }
        handOver(truck1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_HANDOVER_BLOCKED"))
                .andExpect(jsonPath("$.details.orders.length()").value(1))
                .andExpect(jsonPath("$.details.orders[0].orderId").value(order.orderId().toString()))
                .andExpect(jsonPath("$.details.orders[0].orderNumber").value(order.orderNumber()))
                .andExpect(jsonPath("$.details.orders[0].reasonCode").value("ORDER_BALANCE_NOT_PAID"))
                .andExpect(jsonPath("$.details.orders[0].reasonCodes.length()").value(1))
                .andExpect(jsonPath("$.details.orders[0].reasonCodes[0]").value("ORDER_BALANCE_NOT_PAID"))
                .andExpect(jsonPath("$.details.orders[0].amountDue").value(owed.stripTrailingZeros().toPlainString()))
                .andExpect(jsonPath("$.details.orders[0].currency").value("VND"))
                .andExpect(jsonPath("$.details.orders[0].deliveryBatchSeq").value(1));
        assertThat(load(truck1).path("status").asText()).isEqualTo("LOADING");
        assertThat(jdbc.queryForObject("SELECT goods_left_at FROM ordering.customer_order WHERE id = ?",
                java.sql.Timestamp.class, order.orderId())).isNull();
        Map<String, Object> balance = jdbc.queryForMap("SELECT id, amount FROM payment.installment WHERE order_id = ? "
                + "AND milestone = 'BEFORE_DELIVERY'", order.orderId());
        confirmedOrders().pay(DemoData.CUSTOMER_ID, (UUID) balance.get("id"), (BigDecimal) balance.get("amount"));
        assertThat(load(truck1).has("handoverWarnings")).as("paid: the warning no longer holds, so it is gone").isFalse();
        handOver(truck1).andExpect(status().isOk());
        assertThat(shipmentStatus(shipment)).isEqualTo("READY_TO_DISPATCH");
    }

    @Test
    @DisplayName("09 §4.2 step 7, BR-18: an order owing its BEFORE_DELIVERY installment whose delivery window also opens "
            + "tomorrow is warned for BOTH reasons - when loading starts and on every read of the loading vehicle "
            + "(reasonCodes, reasonCode the first) - and the handover's refusal lists the same two; paying leaves the window")
    void bothReasonsWarnedAtLoadingStart() throws Exception {
        OrderSummary order = ftlOrder(2, DemoData.CUSTOMER_ID, List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("30"), 0, null),
                new SalesContracts.Installment("Before delivery", "BEFORE_DELIVERY", null, new BigDecimal("70"), null,
                        null)));
        UUID shipment = packed(order, true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        String truck1 = loadOf(plan, 1);
        LocalDate tomorrow = LocalDate.now(ConfirmedOrders.VIETNAM).plusDays(1);
        // The schedule of a signed contract is fixed (14 BR-14): the window is moved as time passing would, triggers off.
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.delivery_batch SET window_start = ?, window_end = ? WHERE contract_id IN "
                    + "(SELECT id FROM ordering.sales_contract WHERE order_id = ?)", tomorrow, tomorrow.plusDays(6),
                    order.orderId());
        });
        book(truck1, ghn, "51C-" + truck1.substring(0, 6));
        List<String> both = List.of("ORDER_BALANCE_NOT_PAID", "DELIVERY_BATCH_WINDOW_NOT_OPEN");

        JsonNode started = data(dispatch(post(LOADS + "/" + truck1 + "/loading-start")).andExpect(status().isOk()));
        assertThat(started.path("handoverWarnings")).singleElement().satisfies(w -> {
            assertThat(w.path("orderId").asText()).isEqualTo(order.orderId().toString());
            assertThat(w.path("reasonCode").asText()).isEqualTo("ORDER_BALANCE_NOT_PAID");
            assertThat(w.path("reasonCodes")).extracting(JsonNode::asText).containsExactlyElementsOf(both);
            assertThat(w.path("windowStart").asText()).isEqualTo(tomorrow.toString());
        });
        assertThat(load(truck1).path("handoverWarnings")).singleElement().satisfies(w ->
                assertThat(w.path("reasonCodes")).extracting(JsonNode::asText).containsExactlyElementsOf(both));
        for (String label : labels(load(truck1))) {
            scanOnto(truck1, label).andExpect(status().isOk());
        }
        handOver(truck1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_HANDOVER_BLOCKED"))
                .andExpect(jsonPath("$.details.orders[0].reasonCodes.length()").value(2))
                .andExpect(jsonPath("$.details.orders[0].reasonCodes[0]").value(both.get(0)))
                .andExpect(jsonPath("$.details.orders[0].reasonCodes[1]").value(both.get(1)));

        Map<String, Object> balance = jdbc.queryForMap("SELECT id, amount FROM payment.installment WHERE order_id = ? "
                + "AND milestone = 'BEFORE_DELIVERY'", order.orderId());
        confirmedOrders().pay(DemoData.CUSTOMER_ID, (UUID) balance.get("id"), (BigDecimal) balance.get("amount"));
        assertThat(load(truck1).path("handoverWarnings")).as("paid: only the window is left to warn about")
                .singleElement().satisfies(w -> {
                    assertThat(w.path("reasonCode").asText()).isEqualTo("DELIVERY_BATCH_WINDOW_NOT_OPEN");
                    assertThat(w.path("reasonCodes")).extracting(JsonNode::asText)
                            .containsExactly("DELIVERY_BATCH_WINDOW_NOT_OPEN");
                });
    }

    @Test
    @DisplayName("4B-8 (17 §4.5, BR-28, BR-29; 09 BR-04, BR-18): an order of two delivery batches leaves batch by batch - "
            + "batch 1 released, picked on a task of its own, checked and handed over: PARTIALLY_FULFILLED; batch 2 "
            + "released only then, on a second task; its vehicle refused whole before batch 2's window opens "
            + "(LOAD_HANDOVER_BLOCKED, DELIVERY_BATCH_WINDOW_NOT_OPEN); the window moved to today with the customer's "
            + "consent, it leaves: SHIPPED, each shipment recording its batch")
    void twoDeliveryBatches() throws Exception {
        CustomerSkuRows.Issued cup = cupOf(DemoData.CUSTOMER_ID);
        CustomerSkuRows.stock(jdbc, tx, cup.sku(), 2 * 4);
        ConfirmedOrders.Confirmed confirmed = confirmedOrders().pay(confirmedOrders().signedInBatches(
                DemoData.CUSTOMER_ID, 2, ConfirmedOrders.Line.cartons(cup, 4, 6_000_000)));
        UUID orderId = confirmed.orderId();
        OrderSummary order = confirmedOrders().release(orderId, HCM, coordinator);
        assertThat(order.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        List<UUID> batchIds = jdbc.queryForList("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? ORDER BY b.seq""", UUID.class, orderId);

        // Batch 1: its own task, two cartons checked, one vehicle, handed over.
        UUID[] first = packed(order, false);
        assertThat(jdbc.queryForObject("SELECT delivery_batch_id FROM fulfillment.pick WHERE id = ?", UUID.class,
                first[1])).isEqualTo(batchIds.get(0));
        assertThat(jdbc.queryForObject("SELECT package_count FROM fulfillment.shipment WHERE id = ?", Integer.class,
                first[0])).as("batch 1's two cartons").isEqualTo(2);
        String load1 = loadOf(confirmed(List.of(first[0]), truck5t), 1);
        loadAll(load1, ghn);
        handOver(load1).andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().status()).as("batch 2 still to come")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(jdbc.queryForObject("SELECT delivery_batch_id FROM ordering.order_shipment WHERE id = ?", UUID.class,
                first[0])).isEqualTo(batchIds.get(0));

        // Batch 2: released now that batch 1 left; the order keeps PARTIALLY_FULFILLED.
        OrderSummary second = WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL,
                () -> context.getBean(OrderReleases.class).release(orderId, HCM, coordinator));
        assertThat(second.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        UUID[] next = packed(second, false);
        assertThat(orders.findById(orderId).orElseThrow().status()).as("batch 2 checked in full: a part left already, "
                + "so still PARTIALLY_FULFILLED (17 §4.5, WF4-PF-NEXT)").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(next[1]).as("a task of its own").isNotEqualTo(first[1]);
        assertThat(jdbc.queryForObject("SELECT delivery_batch_id FROM fulfillment.pick WHERE id = ?", UUID.class,
                next[1])).isEqualTo(batchIds.get(1));
        String load2 = loadOf(confirmed(List.of(next[0]), truck5t), 1);
        loadAll(load2, ghtk);
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        handOver(load2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_HANDOVER_BLOCKED"))
                .andExpect(jsonPath("$.details.orders[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.details.orders[0].reasonCode").value("DELIVERY_BATCH_WINDOW_NOT_OPEN"))
                .andExpect(jsonPath("$.details.orders[0].reasonCodes.length()").value(1))
                .andExpect(jsonPath("$.details.orders[0].reasonCodes[0]").value("DELIVERY_BATCH_WINDOW_NOT_OPEN"))
                .andExpect(jsonPath("$.details.orders[0].deliveryBatchSeq").value(2))
                .andExpect(jsonPath("$.details.orders[0].windowStart").value(today.plusDays(14).toString()));
        assertThat(shipmentStatus(next[0])).isEqualTo("READY_TO_DISPATCH");

        // The customer takes it early: Sales moves the window to today, the consent a message of theirs (BR-28).
        UUID consent = ChatRows.message(jdbc, tx, DemoData.CUSTOMER_ID, orderId, "CUSTOMER");
        WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL, () -> context.getBean(DeliveryBatchService.class)
                .changeWindow(confirmedOrders().sales(), orderId, batchIds.get(1), new DeliveryBatchService.WindowChange(
                        today, today.plusDays(2), WindowChangeReason.CUSTOMER_REQUEST, "The customer takes it now",
                        null, consent)));
        handOver(load2).andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(jdbc.queryForObject("SELECT delivery_batch_id FROM ordering.order_shipment WHERE id = ?", UUID.class,
                next[0])).isEqualTo(batchIds.get(1));
        DeliveryBatchService.Schedule schedule = WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL,
                () -> context.getBean(DeliveryBatchService.class).schedule(orderId));
        assertThat(schedule.batches()).extracting(DeliveryBatchService.BatchView::state)
                .containsExactly(DeliveryBatchService.BatchState.IN_DELIVERY, DeliveryBatchService.BatchState.IN_DELIVERY);
    }

    @Test
    @DisplayName("WF4-X02 (08 §4.2 step 4, §4.4 step 8; WF4-PF-STATUS 2026-11-12): a carton damaged at the check station "
            + "is removed; completing in full is refused (PACKING_INCOMPLETE); Sales allows partial delivery; completed in "
            + "part with a note - a shipment of the labelled carton READY_TO_DISPATCH, the session still IN_PROGRESS, the "
            + "order PARTIALLY_FULFILLED at once; that shipment planned, loaded and handed over - the order still "
            + "PARTIALLY_FULFILLED, the rest to come")
    void completedInPartAtTheCheckStation() throws Exception {
        OrderSummary order = ftlOrder(2);
        allowPartial(order, false);
        UUID task = picked(order);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PICKING);
        CartonStationSupport.startAndScanAll(this::packer, jdbc, task);
        List<String> lpns = CartonStationSupport.pickedLpns(jdbc, task);
        assertThat(lpns).hasSize(2);
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/cartons/" + lpns.get(1) + "/removal",
                "{\"note\":\"móp góc\"}").andExpect(status().isOk());
        // WF4-BE3-HOLD: the order is held at the removal and the station refuses its cartons until Sales decides.
        assertHeldForTheRemovedCarton(order);
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/carton-labels", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACK_STATE_CONFLICT"));
        salesResolvesTheInventoryHold(order);
        CartonStationSupport.labelAll(this::packer, task);

        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/packing-completion", "{}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKING_INCOMPLETE"));
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/packing-completion",
                "{\"partial\":true,\"note\":\"1 thùng móp, giao sau\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACK_PARTIAL_NOT_ALLOWED"));
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).as("refused: nothing changed")
                .isEqualTo(OrderStatus.PICKING);

        allowPartial(order, true);
        JsonNode done = CartonStationSupport.completeOnly(this::packer, json, task,
                "{\"partial\":true,\"note\":\"1 thùng móp, giao sau\"}");
        UUID shipment = UUID.fromString(done.path("shipmentId").asText());
        assertThat(jdbc.queryForMap("SELECT status, package_count, partial, partial_note FROM fulfillment.shipment "
                + "WHERE id = ?", shipment)).containsEntry("status", "READY_TO_DISPATCH")
                .containsEntry("package_count", 1).containsEntry("partial", true)
                .containsEntry("partial_note", "1 thùng móp, giao sau");
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task))
                .isEqualTo("IN_PROGRESS");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).as("at once, nothing handed over yet")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        String load = loadOf(confirmed(List.of(shipment), truck5t), 1);
        loadAll(load, ghn);
        handOver(load).andExpect(status().isOk());
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");
        OrderSummary after = orders.findById(order.orderId()).orElseThrow();
        assertThat(after.status()).as("one carton of two still to come").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(after.lifecycle().shipments()).singleElement()
                .satisfies(s -> assertThat(s.shipmentId()).isEqualTo(shipment));
    }

    // ---- BE-3: a carton removed at the check station is a shortage, replaced as a short pick is reallocated -------

    /**
     * WF4-BE3-HOLD (owner, 2026-11-15): the removal of a carton at the check station puts the order on INVENTORY hold at
     * once, as a short pick report does; the check station refuses the order's cartons while it is held.
     */
    private void assertHeldForTheRemovedCarton(OrderSummary order) throws Exception {
        OrderSummary held = orders.findById(order.orderId()).orElseThrow();
        assertThat(held.status()).as("held at the removal").isEqualTo(OrderStatus.ON_HOLD);
        assertThat(held.lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
    }

    /** Sales decides with the customer to go on with what is checked: the INVENTORY hold is resolved (17 §4.9 step 36). */
    private void salesResolvesTheInventoryHold(OrderSummary order) throws Exception {
        assertHeldForTheRemovedCarton(order);
        sales(body(post(ORDERS + "/" + order.orderId() + "/hold/resolution"),
                "{\"note\":\"Khách đồng ý nhận phần đã kiểm trước\"}")).andExpect(status().isOk());
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).as("back where it was before the hold")
                .isNotEqualTo(OrderStatus.ON_HOLD);
    }

    /** The per-line view of the order's cover (GET stock-coverage): the cartons the line holds and lacks. */
    private void assertLineCover(OrderSummary order, int held, int lacking) throws Exception {
        JsonNode coverage = data(sales(get(ORDERS + "/" + order.orderId() + "/stock-coverage"))
                .andExpect(status().isOk()));
        assertThat(coverage.path("lines")).singleElement().satisfies(line -> {
            assertThat(line.path("heldCartons").asInt()).as("line: held cartons").isEqualTo(held);
            assertThat(line.path("shortCartons").asInt()).as("line: short cartons").isEqualTo(lacking);
        });
        assertThat(coverage.path("batches")).singleElement().satisfies(batch -> assertThat(batch.path("lines"))
                .singleElement().satisfies(line -> assertThat(line.path("shortCartons").asInt())
                        .as("batch: short cartons").isEqualTo(lacking)));
    }

    /** The pick line of a carton the task picked, by its LPN. */
    private UUID lineOf(UUID task, String lpn) {
        return jdbc.queryForObject("SELECT id FROM fulfillment.pick_line WHERE pick_id = ? AND lpn = ?", UUID.class,
                task, lpn);
    }

    /** The order's cartons its delivery batches count: holds of a batch on a carton, picked or not. */
    private int batchHolds(OrderSummary order) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM ordering.delivery_batch_hold h
                  JOIN inventory.stock_reservation r ON r.id = h.reservation_id
                 WHERE h.order_id = ? AND r.status IN ('HELD', 'CONSUMED')""", Integer.class, order.orderId());
    }

    /** One line of the task's pick list, as the list read answers it. */
    private JsonNode pickLine(UUID task, UUID line) throws Exception {
        for (JsonNode row : data(oc(get(BASE + "/tasks/" + task + "/pick-lines")).andExpect(status().isOk()))
                .path("items")) {
            if (row.path("lineId").asText().equals(line.toString())) {
                return row;
            }
        }
        throw new AssertionError("line " + line + " is not on the pick list of " + task);
    }

    private ResultActions reallocate(UUID task, UUID line) throws Exception {
        return oc(post(BASE + "/tasks/" + task + "/pick-lines/" + line + "/reallocation"));
    }

    /** The task's OPEN lines picked to dispatch and the picking completed: the task PICKED again. */
    private void pickOpenLines(UUID task) throws Exception {
        for (Map<String, Object> line : jdbc.queryForList("SELECT id, location_code, lpn FROM fulfillment.pick_line "
                + "WHERE pick_id = ? AND status = 'OPEN'", task)) {
            packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/confirmation"),
                    "{\"locationCode\":\"" + line.get("location_code") + "\",\"lpn\":\"" + line.get("lpn") + "\"}"))
                    .andExpect(status().isOk());
        }
        packer(post(BASE + "/tasks/" + task + "/picking-completion")).andExpect(status().isOk());
    }

    /**
     * X02 steps 1-3 over HTTP: two cartons picked, one removed at the check station as damaged, the other labelled and
     * completed in part (Sales allows partial delivery), that shipment handed over, the damaged carton put back into the
     * quarantine area. Returns the task, the removed carton's line and LPN.
     */
    private Object[] removedAndPartShipped(OrderSummary order) throws Exception {
        allowPartial(order, true);
        UUID task = picked(order);
        CartonStationSupport.startAndScanAll(this::packer, jdbc, task);
        String damaged = CartonStationSupport.pickedLpns(jdbc, task).get(1);
        UUID removed = lineOf(task, damaged);
        assertThat(batchHolds(order)).isEqualTo(2);
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/cartons/" + damaged + "/removal",
                "{\"note\":\"móp góc\"}").andExpect(status().isOk());
        assertThat(batchHolds(order)).as("17 §5.5: the batch no longer counts the removed carton - it lacks it again")
                .isEqualTo(1);
        assertLineCover(order, 1, 1);
        salesResolvesTheInventoryHold(order);
        CartonStationSupport.labelAll(this::packer, task);
        UUID part = UUID.fromString(CartonStationSupport.completeOnly(this::packer, json, task,
                "{\"partial\":true,\"note\":\"1 thùng móp, giao sau\"}").path("shipmentId").asText());
        String load = loadOf(confirmed(List.of(part), truck5t), 1);
        loadAll(load, ghn);
        handOver(load).andExpect(status().isOk());
        assertThat(shipmentStatus(part)).isEqualTo("HANDED_OVER");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        // 08 §4.2 step 4: the damaged carton goes whole into the quarantine area, through the put-back queue.
        packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + removed + "/put-back"),
                "{\"locationCode\":\"HCM-QC01\",\"lpn\":\"" + damaged + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUT_BACK"));
        return new Object[] {task, removed, damaged};
    }

    /** X02 step 4 after the replacement's lines exist: picked, checked in the same session, handed over: SHIPPED. */
    private void replacementShipped(OrderSummary order, UUID task, String damaged) throws Exception {
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task))
                .as("07 §4.3 step 7: the PICKED task picks again").isEqualTo("PICKING");
        pickOpenLines(task);
        CartonStationSupport.startAndScanAll(this::packer, jdbc, task);
        CartonStationSupport.labelAll(this::packer, task);
        UUID rest = UUID.fromString(CartonStationSupport.completeOnly(this::packer, json, task, "{}")
                .path("shipmentId").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task))
                .as("the same session, completed in full now").isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("SELECT p.lpn FROM fulfillment.package p WHERE p.shipment_id = ?", String.class,
                rest)).singleElement().isNotEqualTo(damaged);
        assertThat(orders.findById(order.orderId()).orElseThrow().status())
                .as("WF4-PF-NEXT: a part was handed over already, so the rest packed keeps it PARTIALLY_FULFILLED")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        String load = loadOf(confirmed(List.of(rest), truck5t), 1);
        loadAll(load, ghn);
        handOver(load).andExpect(status().isOk());
        OrderSummary after = orders.findById(order.orderId()).orElseThrow();
        assertThat(after.status()).as("every carton of the order handed over").isEqualTo(OrderStatus.SHIPPED);
        assertThat(after.lifecycle().shipments()).hasSize(2);
        assertThat(jdbc.queryForMap("SELECT location_code, status FROM inventory.stock_package WHERE lpn = ?", damaged))
                .as("the damaged carton stays in quarantine").containsEntry("location_code", "HCM-QC01")
                .containsEntry("status", "IN_STOCK");
    }

    @Test
    @DisplayName("BE-3 / WF4-X02 step 4 (08 §4.2 step 4, 07 §4.3-§4.4): a carton removed at the check station is a "
            + "shortage of its batch; the part ships first; the warehouse manager reallocates the removed line - a "
            + "replacement carton (never the damaged one) joins the task as a top-up, is picked, checked in the same "
            + "session, completed in full and handed over on a later load: the order SHIPPED")
    void removedCartonIsReplacedAndShipsOnALaterLoad() throws Exception {
        OrderSummary order = ftlOrder(2);
        CustomerSkuRows.stock(jdbc, tx, cupOf(DemoData.CUSTOMER_ID).sku(), 2);
        Object[] x = removedAndPartShipped(order);
        UUID task = (UUID) x[0];
        UUID removed = (UUID) x[1];
        String damaged = (String) x[2];

        // F5: the line list says what the check station did to the carton - the UI shows "replace" from it alone.
        JsonNode removedLine = pickLine(task, removed);
        assertThat(removedLine.path("status").asText()).as("PUT_BACK: the damaged carton went to quarantine already")
                .isEqualTo("PUT_BACK");
        assertThat(removedLine.path("removalReason").asText()).isEqualTo("DAMAGED");
        assertThat(removedLine.path("replacementPending").asBoolean()).isTrue();
        // A picker does not reallocate: the warehouse manager does (fulfillment-task-assignment:UPDATE).
        packer(post(BASE + "/tasks/" + task + "/pick-lines/" + removed + "/reallocation"))
                .andExpect(status().isForbidden());
        JsonNode lines = data(reallocate(task, removed).andExpect(status().isOk()));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).path("status").asText()).isEqualTo("OPEN");
        assertThat(lines.get(0).path("lpn").asText()).isNotEqualTo(damaged);
        assertThat(batchHolds(order)).as("the replacement stands for the batch").isEqualTo(2);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        JsonNode replacedLine = pickLine(task, removed);
        assertThat(replacedLine.path("removalReason").asText()).isEqualTo("DAMAGED");
        assertThat(replacedLine.path("replacementPending").asBoolean()).as("replaced: nothing more to replace")
                .isFalse();
        assertThat(pickLine(task, UUID.fromString(lines.get(0).path("lineId").asText())).has("removalReason"))
                .as("the replacement line was not removed").isFalse();
        reallocate(task, removed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_LINE_NOT_OPEN"));
        // The top-up of the task finds nothing more to add.
        assertThat(data(oc(post(BASE + "/tasks/" + task + "/top-up")).andExpect(status().isOk()))).isEmpty();

        replacementShipped(order, task, damaged);
    }

    @Test
    @DisplayName("BE-3 (07 §4.4 step 11): no whole carton to replace a removed one - 409 INSUFFICIENT_STOCK, the order "
            + "held INVENTORY for Sales to decide, the batch short of it; stock arrives, the reallocation succeeds, the "
            + "order resumes PARTIALLY_FULFILLED and the replacement ships: SHIPPED")
    void removedCartonWithoutStockHoldsTheOrder() throws Exception {
        OrderSummary order = ftlOrder(2);
        Object[] x = removedAndPartShipped(order);
        UUID task = (UUID) x[0];
        UUID removed = (UUID) x[1];
        String damaged = (String) x[2];

        reallocate(task, removed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"));
        OrderSummary held = orders.findById(order.orderId()).orElseThrow();
        assertThat(held.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(held.lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        assertThat(batchHolds(order)).as("nothing allocated; the batch still lacks the carton").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick_line WHERE pick_id = ?", Integer.class,
                task)).isEqualTo(2);
        // Refused again: still held, once.
        reallocate(task, removed).andExpect(status().isConflict());
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);

        // The goods arrive (a production order's receipt, say): the reallocation now replaces it, and the hold resolves.
        CustomerSkuRows.stock(jdbc, tx, cupOf(DemoData.CUSTOMER_ID).sku(), 2);
        assertThat(data(reallocate(task, removed).andExpect(status().isOk()))).hasSize(1);
        assertThat(orders.findById(order.orderId()).orElseThrow().status())
                .as("back where it was before the hold").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        replacementShipped(order, task, damaged);
    }

    @Test
    @DisplayName("BE-3 (08 §4.2 step 4): partial delivery not allowed - the removed carton is replaced before the check "
            + "completes: the station waits while the replacement is picked, then the same session completes in full, "
            + "the order PACKED")
    void removedCartonIsReplacedBeforeTheCheckCompletes() throws Exception {
        OrderSummary order = ftlOrder(2);
        CustomerSkuRows.stock(jdbc, tx, cupOf(DemoData.CUSTOMER_ID).sku(), 2);
        UUID task = picked(order);
        CartonStationSupport.startAndScanAll(this::packer, jdbc, task);
        String damaged = CartonStationSupport.pickedLpns(jdbc, task).get(0);
        UUID removed = lineOf(task, damaged);
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/cartons/" + damaged + "/removal",
                "{\"note\":\"ướt\"}").andExpect(status().isOk());
        assertHeldForTheRemovedCarton(order);
        assertLineCover(order, 1, 1);
        assertThat(data(reallocate(task, removed).andExpect(status().isOk()))).hasSize(1);
        assertThat(orders.findById(order.orderId()).orElseThrow().status())
                .as("a reallocation that finds stock lifts the hold").isNotEqualTo(OrderStatus.ON_HOLD);
        assertLineCover(order, 2, 0);
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/carton-labels", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("PACKING_NOT_READY"));
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PICKING);

        pickOpenLines(task);
        for (String lpn : CartonStationSupport.pickedLpns(jdbc, task)) {
            if (!lpn.equals(damaged)) {
                CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/carton-checks",
                        "{\"lpn\":\"" + lpn + "\"}").andExpect(status().isOk());
            }
        }
        CartonStationSupport.labelAll(this::packer, task);
        UUID shipment = UUID.fromString(CartonStationSupport.completeOnly(this::packer, json, task, "{}")
                .path("shipmentId").asText());
        assertThat(jdbc.queryForObject("SELECT package_count FROM fulfillment.shipment WHERE id = ?", Integer.class,
                shipment)).isEqualTo(2);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task))
                .isEqualTo("COMPLETED");
    }

    // ---- RTM-RTDCANCEL (owner 2026-11-19; 09 §5.1 "Cancelled", 08 §5.1, 07 §4.3 step 7): a shipment cancelled ----------
    // ---- before dispatch: the order held for stock, its cartons replaced and checked again -----------------------------

    private ResultActions cancelShipment(UUID shipment, String content) throws Exception {
        return oc(body(post(BASE + "/shipments/" + shipment + "/cancellation"), content));
    }

    @Test
    @DisplayName("RTM-RTDCANCEL: an order packed whole into one shipment, its check COMPLETED; the warehouse manager "
            + "cancels the shipment with a reason, one carton marked damaged: the shipment CANCELLED, both cartons out "
            + "of it and REMOVED (DAMAGED / SHIPMENT_CANCELLED), the damaged one into quarantine and the good one back "
            + "onto a bin through the put-back queue, the order ON_HOLD (INVENTORY), the check IN_PROGRESS again; both "
            + "lines reallocated - the task PICKING with two top-up lines, the hold resolved back to PACKED; the "
            + "replacements picked, checked and completed into a new shipment READY_TO_DISPATCH: the order PACKED")
    void aShipmentCancelledBeforeDispatchIsReplaced() throws Exception {
        OrderSummary order = ftlOrder(2);
        CustomerSkuRows.stock(jdbc, tx, cupOf(DemoData.CUSTOMER_ID).sku(), 4);
        UUID[] made = packed(order, false);
        UUID shipment = made[0];
        UUID task = made[1];
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PACKED);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task))
                .isEqualTo("COMPLETED");
        List<Map<String, Object>> cartons = jdbc.queryForList("SELECT id, lpn, pick_line_id, label_code FROM "
                + "fulfillment.package WHERE shipment_id = ? ORDER BY package_no", shipment);
        assertThat(cartons).hasSize(2);
        UUID damagedId = (UUID) cartons.get(0).get("id");
        String damagedLpn = (String) cartons.get(0).get("lpn");
        UUID damagedLine = (UUID) cartons.get(0).get("pick_line_id");
        UUID goodId = (UUID) cartons.get(1).get("id");
        String goodLpn = (String) cartons.get(1).get("lpn");
        UUID goodLine = (UUID) cartons.get(1).get("pick_line_id");

        JsonNode cancelled = data(cancelShipment(shipment, "{\"reason\":\"Thùng móp khi chờ xe, xếp lại chuyến\","
                + "\"damagedPackageIds\":[\"" + damagedId + "\"]}").andExpect(status().isOk()));
        assertThat(cancelled.path("shipment").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("cancelledAt").isNull()).isFalse();
        assertThat(cancelled.path("packages")).as("its cartons left it").isEmpty();
        JsonNode last = cancelled.path("timeline").get(cancelled.path("timeline").size() - 1);
        assertThat(last.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(last.path("note").asText()).isEqualTo("Thùng móp khi chờ xe, xếp lại chuyến");
        assertThat(last.path("recordedBy").asText()).isEqualTo(coordinator.toString());
        assertThat(jdbc.queryForMap("SELECT status, removal_reason, removed_by, shipment_id FROM fulfillment.package "
                + "WHERE id = ?", damagedId)).containsEntry("status", "REMOVED").containsEntry("removal_reason", "DAMAGED")
                .containsEntry("removed_by", coordinator).containsEntry("shipment_id", null);
        assertThat(jdbc.queryForMap("SELECT status, removal_reason, removed_by, shipment_id FROM fulfillment.package "
                + "WHERE id = ?", goodId)).containsEntry("status", "REMOVED")
                .containsEntry("removal_reason", "SHIPMENT_CANCELLED").containsEntry("shipment_id", null);
        // Its labels are void: a removed carton has no shipping label to print (08 §5.2 "Removed").
        packer(body(post(BASE + "/tasks/" + task + "/shipping-labels"), "{\"lpns\":[\"" + goodLpn + "\"]}")
                .accept(MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARTON_NOT_CHECKED"));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task))
                .as("08 §5.1: Completed -> In Progress for the replacement cartons").isEqualTo("IN_PROGRESS");
        OrderSummary held = orders.findById(order.orderId()).orElseThrow();
        assertThat(held.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(held.lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        assertThat(batchHolds(order)).as("17 §5.5: the batch no longer counts either carton - it lacks both again")
                .isZero();
        assertLineCover(order, 0, 2);

        // The put-back queue: the damaged carton for quarantine, the good one for a bin.
        JsonNode queue = data(oc(get(BASE + "/put-backs").param("warehouseId", HCM.toString()).param("size", "200"))
                .andExpect(status().isOk())).path("items");
        Map<String, Boolean> queued = new HashMap<>();
        queue.forEach(row -> queued.put(row.path("lpn").asText(), row.path("quarantine").asBoolean()));
        assertThat(queued).containsEntry(damagedLpn, true).containsEntry(goodLpn, false);
        packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + damagedLine + "/put-back"),
                "{\"locationCode\":\"HCM-QC01\",\"lpn\":\"" + damagedLpn + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUT_BACK"));
        assertThat(jdbc.queryForMap("SELECT location_code, status FROM inventory.stock_package WHERE lpn = ?",
                damagedLpn)).as("in quarantine").containsEntry("location_code", "HCM-QC01")
                .containsEntry("status", "IN_STOCK");
        String bin = jdbc.queryForObject("SELECT location_code FROM fulfillment.pick_line WHERE id = ?", String.class,
                goodLine);
        packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + goodLine + "/put-back"),
                "{\"locationCode\":\"HCM-QC01\",\"lpn\":\"" + goodLpn + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PUT_BACK_LOCATION_INVALID"));
        packer(body(post(BASE + "/tasks/" + task + "/pick-lines/" + goodLine + "/put-back"),
                "{\"locationCode\":\"" + bin + "\",\"lpn\":\"" + goodLpn + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUT_BACK"));
        assertThat(jdbc.queryForMap("SELECT location_code, status FROM inventory.stock_package WHERE lpn = ?",
                goodLpn)).as("back onto its bin").containsEntry("location_code", bin).containsEntry("status", "IN_STOCK");

        // 07 §4.4 step 10: the warehouse manager reallocates each line; the hold resolves with the last.
        assertThat(pickLine(task, goodLine).path("removalReason").asText()).isEqualTo("SHIPMENT_CANCELLED");
        assertThat(pickLine(task, goodLine).path("replacementPending").asBoolean()).isTrue();
        JsonNode first = data(reallocate(task, damagedLine).andExpect(status().isOk()));
        assertThat(first).hasSize(1);
        // 07 §4.4 step 10: never the carton the line lacks; any other whole carton of the warehouse, FIFO - the good one
        // already back on its bin included.
        assertThat(first.get(0).path("lpn").asText()).isNotEqualTo(damagedLpn);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).as("one carton still lacks a replacement")
                .isEqualTo(OrderStatus.ON_HOLD);
        JsonNode second = data(reallocate(task, goodLine).andExpect(status().isOk()));
        assertThat(second).hasSize(1);
        assertThat(second.get(0).path("lpn").asText()).isNotIn(damagedLpn, goodLpn,
                first.get(0).path("lpn").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task))
                .as("07 §5: Picked -> Picking with the top-up lines").isEqualTo("PICKING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.pick_line WHERE pick_id = ? AND status = "
                + "'OPEN'", Integer.class, task)).isEqualTo(2);
        assertThat(orders.findById(order.orderId()).orElseThrow().status())
                .as("09 §5.1 steps 4-5: the hold waits for the replacements' check").isEqualTo(OrderStatus.ON_HOLD);
        assertThat(batchHolds(order)).as("the replacements stand for the batch").isEqualTo(2);
        assertLineCover(order, 2, 0);

        // 08 §4.1-§4.4 for the replacements, the order still held: picked, checked in the reopened session, completed:
        // a new shipment, and the hold resolved back to PACKED with no hand on it.
        pickOpenLines(task);
        CartonStationSupport.startAndScanAll(this::packer, jdbc, task);
        CartonStationSupport.labelAll(this::packer, task);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);
        UUID replacement = UUID.fromString(CartonStationSupport.completeOnly(this::packer, json, task, "{}")
                .path("shipmentId").asText());
        assertThat(replacement).isNotEqualTo(shipment);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_hold WHERE order_id = ? AND reason = "
                + "'INVENTORY' AND resolved_at IS NULL", Integer.class, order.orderId())).isZero();
        assertThat(jdbc.queryForMap("SELECT status, package_count, code FROM fulfillment.shipment WHERE id = ?",
                replacement)).containsEntry("status", "READY_TO_DISPATCH").containsEntry("package_count", 2)
                .containsEntry("code", order.orderNumber() + "-S2");
        assertThat(jdbc.queryForList("SELECT lpn FROM fulfillment.package WHERE shipment_id = ?", String.class,
                replacement)).doesNotContain(damagedLpn).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task))
                .isEqualTo("COMPLETED");
        OrderSummary after = orders.findById(order.orderId()).orElseThrow();
        assertThat(after.status()).isEqualTo(OrderStatus.PACKED);
        assertThat(after.lifecycle().holdReason()).isNull();
    }

    @Test
    @DisplayName("RTM-RTDCANCEL, video QA case 13 (F3): a shipment of four cartons, one marked damaged and three good, "
            + "cancelled before dispatch; the warehouse manager replaces every line ('Bù thùng', one call per line) - "
            + "the order stays ON_HOLD (INVENTORY); while it is held the station starts, scans, labels and completes "
            + "the four replacements with nobody resolving the hold: a new shipment of four cartons READY_TO_DISPATCH, "
            + "listed for the order, and the hold resolved back to PACKED")
    void fourCartonsCancelledOneDamagedAreReplacedWhileHeld() throws Exception {
        OrderSummary order = ftlOrder(4);
        CustomerSkuRows.stock(jdbc, tx, cupOf(DemoData.CUSTOMER_ID).sku(), 8);
        UUID[] made = packed(order, false);
        UUID shipment = made[0];
        UUID task = made[1];
        List<Map<String, Object>> cartons = jdbc.queryForList("SELECT id, lpn, pick_line_id FROM fulfillment.package "
                + "WHERE shipment_id = ? ORDER BY package_no", shipment);
        assertThat(cartons).hasSize(4);
        cancelShipment(shipment, "{\"reason\":\"Xe đến trễ, xếp lại lô\",\"damagedPackageIds\":[\""
                + cartons.get(0).get("id") + "\"]}").andExpect(status().isOk());
        assertHeldForTheRemovedCarton(order);
        List<String> old = cartons.stream().map(c -> (String) c.get("lpn")).toList();

        // Every removed line - the damaged one and the three good ones - offers and takes a replacement.
        for (Map<String, Object> carton : cartons) {
            UUID line = (UUID) carton.get("pick_line_id");
            assertThat(pickLine(task, line).path("replacementPending").asBoolean()).as("line " + line).isTrue();
            assertThat(data(reallocate(task, line).andExpect(status().isOk()))).hasSize(1);
            assertThat(pickLine(task, line).path("replacementPending").asBoolean()).isFalse();
        }
        assertThat(orders.findById(order.orderId()).orElseThrow().status())
                .as("09 §5.1: still held - the replacements are not checked yet").isEqualTo(OrderStatus.ON_HOLD);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task))
                .isEqualTo("PICKING");

        pickOpenLines(task);
        JsonNode queue = data(packer(get(BASE + "/packing-queue").param("warehouseId", HCM.toString())
                .param("size", "200")).andExpect(status().isOk())).path("items");
        List<String> queued = new ArrayList<>();
        queue.forEach(row -> queued.add(row.path("taskId").asText()));
        assertThat(queued).as("the held order's reopened check is in the station's queue").contains(task.toString());
        // The removed cartons wait in the put-back queue (their lines still PICKED); only the replacements are scanned.
        List<String> replacements = jdbc.queryForList("SELECT lpn FROM fulfillment.pick_line WHERE pick_id = ? AND "
                + "replaces_line_id IS NOT NULL AND status = 'PICKED'", String.class, task);
        assertThat(replacements).hasSize(4);
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/packing-start", null)
                .andExpect(status().isOk());
        for (String lpn : replacements) {
            CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/carton-checks",
                    "{\"lpn\":\"" + lpn + "\"}").andExpect(status().isOk());
        }
        CartonStationSupport.labelAll(this::packer, task);
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);
        UUID replacement = UUID.fromString(CartonStationSupport.completeOnly(this::packer, json, task, "{}")
                .path("shipmentId").asText());

        assertThat(jdbc.queryForMap("SELECT status, package_count FROM fulfillment.shipment WHERE id = ?",
                replacement)).containsEntry("status", "READY_TO_DISPATCH").containsEntry("package_count", 4);
        assertThat(jdbc.queryForList("SELECT lpn FROM fulfillment.package WHERE shipment_id = ?", String.class,
                replacement)).hasSize(4).doesNotContainAnyElementsOf(old);
        JsonNode listed = data(dispatch(get(BASE + "/shipments").param("orderId", order.orderId().toString()))
                .andExpect(status().isOk())).path("items");
        List<String> statuses = new ArrayList<>();
        listed.forEach(row -> statuses.add(row.path("status").asText()));
        assertThat(statuses).containsExactlyInAnyOrder("CANCELLED", "READY_TO_DISPATCH");
        OrderSummary after = orders.findById(order.orderId()).orElseThrow();
        assertThat(after.status()).as("the hold resolved at the completion").isEqualTo(OrderStatus.PACKED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_hold WHERE order_id = ? AND resolved_at IS "
                + "NULL", Integer.class, order.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("RTM-RTDCANCEL refusals: warehouse staff without fulfillment-shipment-cancellations get 403; no reason "
            + "is 400, a damaged carton of another shipment 400; a shipment in a load plan 409 PACKAGE_IN_LOAD_PLAN; "
            + "one cancelled already (not READY_TO_DISPATCH) 409 SHIPMENT_STATE_CONFLICT - nothing changes")
    void aShipmentIsCancelledBeforeDispatchOnly() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        UUID planned = packed(ftlOrder(1), false)[0];
        dispatch(body(post(BASE + "/shipments/" + shipment + "/cancellation"), "{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        cancelShipment(shipment, "{\"damagedPackageIds\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        cancelShipment(shipment, "{\"reason\":\"   \"}").andExpect(status().isBadRequest());
        UUID elsewhere = jdbc.queryForObject("SELECT id FROM fulfillment.package WHERE shipment_id = ? LIMIT 1",
                UUID.class, planned);
        cancelShipment(shipment, "{\"reason\":\"móp\",\"damagedPackageIds\":[\"" + elsewhere + "\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(shipmentStatus(shipment)).isEqualTo("READY_TO_DISPATCH");

        plan(List.of(planned), truck5t);
        cancelShipment(planned, "{\"reason\":\"xếp lại\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_IN_LOAD_PLAN"));
        assertThat(shipmentStatus(planned)).isEqualTo("READY_TO_DISPATCH");

        cancelShipment(shipment, "{\"reason\":\"xếp lại\"}").andExpect(status().isOk());
        cancelShipment(shipment, "{\"reason\":\"xếp lại\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("WF4-CLOSE-REST (17 §4.8 step 33, owner decision 2026-11-13): completed in part, the checked part waiting "
            + "at dispatch - Sales closes the rest: 200, only the carton neither handed over nor labelled is closed and "
            + "taken off the installments, the order stays PARTIALLY_FULFILLED, the check is COMPLETED; the waiting "
            + "shipment planned, loaded and handed over - SHIPPED")
    void restClosedWhileTheCheckedPartWaits() throws Exception {
        OrderSummary order = ftlOrder(2);
        allowPartial(order, true);
        UUID task = picked(order);
        CartonStationSupport.startAndScanAll(this::packer, jdbc, task);
        List<String> lpns = CartonStationSupport.pickedLpns(jdbc, task);
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/cartons/" + lpns.get(1) + "/removal",
                "{\"note\":\"móp góc\"}").andExpect(status().isOk());
        salesResolvesTheInventoryHold(order);
        CartonStationSupport.labelAll(this::packer, task);
        UUID shipment = UUID.fromString(CartonStationSupport.completeOnly(this::packer, json, task,
                "{\"partial\":true,\"note\":\"1 thùng móp, giao sau\"}").path("shipmentId").asText());
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        int perCarton = jdbc.queryForObject("SELECT units_per_carton FROM ordering.order_line WHERE order_id = ?",
                Integer.class, order.orderId());

        JsonNode closed = data(sales(body(post(ORDERS + "/" + order.orderId() + "/remainder-closure"),
                "{\"reasonCode\":\"OUT_OF_STOCK\",\"note\":\"khách không chờ thùng thay\"}"))
                .andExpect(status().isOk()));
        assertThat(closed.path("status").asText()).as("the labelled part still to go").isEqualTo("PARTIALLY_FULFILLED");
        assertThat(jdbc.queryForObject("SELECT cancelled_quantity FROM ordering.order_line WHERE order_id = ?",
                Integer.class, order.orderId())).as("the removed carton only").isEqualTo(perCarton);
        OrderSummary after = orders.findById(order.orderId()).orElseThrow();
        BigDecimal settled = jdbc.queryForObject("SELECT closed_amount FROM payment.order_settlement WHERE order_id = ? "
                + "AND kind = 'REMAINDER_CLOSED'", BigDecimal.class, order.orderId());
        assertThat(settled).as("15 §4.6: the closed carton's worth, not the waiting one's")
                .isEqualByComparingTo(after.total().amount().subtract(after.lifecycle().effectiveTotal()))
                .isPositive().isLessThan(after.total().amount());
        Await.until("the check completed with nothing left to ship", ASYNC, () -> "COMPLETED".equals(
                jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task)));
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick WHERE id = ?", String.class, task))
                .as("the task goes on").isEqualTo("PICKED");
        assertThat(shipmentStatus(shipment)).isEqualTo("READY_TO_DISPATCH");

        String load = loadOf(confirmed(List.of(shipment), truck5t), 1);
        loadAll(load, ghn);
        handOver(load).andExpect(status().isOk());
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");
        assertThat(orders.findById(order.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("WF4-PF-NEXT + WF4-CLOSE-REST with a delivery schedule: batch 1 handed over - PARTIALLY_FULFILLED; batch "
            + "2 released and picked, one carton labelled and one only checked - Sales closes the rest: the checked one "
            + "is REMOVED into the put-back queue, the labelled one kept, the order stays PARTIALLY_FULFILLED; batch 2 "
            + "completed in full - still PARTIALLY_FULFILLED (a part left already); handed over in its window - SHIPPED")
    void laterBatchStaysPartialAndItsRestIsClosed() throws Exception {
        CustomerSkuRows.Issued cup = cupOf(DemoData.CUSTOMER_ID);
        CustomerSkuRows.stock(jdbc, tx, cup.sku(), 2 * 4);
        ConfirmedOrders.Confirmed confirmed = confirmedOrders().pay(confirmedOrders().signedInBatches(
                DemoData.CUSTOMER_ID, 2, ConfirmedOrders.Line.cartons(cup, 4, 6_000_000)));
        UUID orderId = confirmed.orderId();
        OrderSummary order = confirmedOrders().release(orderId, HCM, coordinator);
        List<UUID> batchIds = jdbc.queryForList("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? ORDER BY b.seq""", UUID.class, orderId);
        UUID[] first = packed(order, false);
        assertThat(orders.findById(orderId).orElseThrow().status()).as("batch 1 checked in full, nothing left yet")
                .isEqualTo(OrderStatus.PACKED);
        String load1 = loadOf(confirmed(List.of(first[0]), truck5t), 1);
        loadAll(load1, ghn);
        handOver(load1).andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        OrderSummary second = WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL,
                () -> context.getBean(OrderReleases.class).release(orderId, HCM, coordinator));
        UUID task = picked(second);
        CartonStationSupport.startAndScanAll(this::packer, jdbc, task);
        List<String> lpns = CartonStationSupport.pickedLpns(jdbc, task);
        assertThat(lpns).hasSize(2);
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/carton-labels",
                "{\"lpns\":[\"" + lpns.get(0) + "\"]}").andExpect(status().isOk());

        assertThat(data(sales(body(post(ORDERS + "/" + orderId + "/remainder-closure"),
                "{\"reasonCode\":\"OUT_OF_STOCK\"}")).andExpect(status().isOk())).path("status").asText())
                .isEqualTo("PARTIALLY_FULFILLED");
        // A label after the closure is refused: by the order (the closed part is no longer wanted), or because the
        // closure's listener removed the carton already.
        CartonStationSupport.post(this::packer, BASE + "/tasks/" + task + "/carton-labels",
                "{\"lpns\":[\"" + lpns.get(1) + "\"]}").andExpect(status().isConflict());
        Await.until("the checked carton removed", ASYNC, () -> "REMOVED".equals(jdbc.queryForObject(
                "SELECT k.status FROM fulfillment.package k JOIN fulfillment.pack p ON p.id = k.pack_id "
                        + "WHERE k.lpn = ? AND p.pick_id = ?", String.class, lpns.get(1), task)));
        assertThat(jdbc.queryForMap("SELECT k.removal_reason, k.removed_by FROM fulfillment.package k JOIN "
                + "fulfillment.pack p ON p.id = k.pack_id WHERE k.lpn = ? AND p.pick_id = ?", lpns.get(1), task)).containsEntry("removal_reason", "REMAINDER_CLOSED")
                .containsEntry("removed_by", sales);
        assertThat(jdbc.queryForObject("SELECT k.status FROM fulfillment.package k JOIN fulfillment.pack p ON "
                + "p.id = k.pack_id WHERE k.lpn = ? AND p.pick_id = ?", String.class, lpns.get(0), task))
                .isEqualTo("LABELED");
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pack WHERE pick_id = ?", String.class, task))
                .isEqualTo("IN_PROGRESS");
        JsonNode queue = data(oc(get(BASE + "/put-backs").param("warehouseId", HCM.toString()).param("size", "200"))
                .andExpect(status().isOk())).path("items");
        List<String> queued = new ArrayList<>();
        queue.forEach(row -> queued.add(row.path("lpn").asText()));
        assertThat(queued).as("back onto a bin").contains(lpns.get(1)).doesNotContain(lpns.get(0));

        UUID rest = UUID.fromString(CartonStationSupport.completeOnly(this::packer, json, task, "{}")
                .path("shipmentId").asText());
        assertThat(orders.findById(orderId).orElseThrow().status()).as("a part left already (WF4-PF-NEXT)")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        String load2 = loadOf(confirmed(List.of(rest), truck5t), 1);
        loadAll(load2, ghtk);
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        UUID consent = ChatRows.message(jdbc, tx, DemoData.CUSTOMER_ID, orderId, "CUSTOMER");
        WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL, () -> context.getBean(DeliveryBatchService.class)
                .changeWindow(confirmedOrders().sales(), orderId, batchIds.get(1), new DeliveryBatchService.WindowChange(
                        today, today.plusDays(2), WindowChangeReason.CUSTOMER_REQUEST, "The customer takes it now",
                        null, consent)));
        handOver(load2).andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(orders.history(orderId)).extracting(com.stockflow.order.api.OrderStatusChange::to)
                .endsWith(OrderStatus.PICKING, OrderStatus.PACKED, OrderStatus.PARTIALLY_FULFILLED, OrderStatus.SHIPPED);
    }

    @Test
    @DisplayName("4B-8 (17 BR-15): a plan of delivery batch 1 is confirmed without partial shipments being allowed - the "
            + "later batch's units are not left behind")
    void aBatchIsPlannedWithoutAllowingPartialShipments() throws Exception {
        CustomerSkuRows.Issued cup = cupOf(DemoData.CUSTOMER_ID);
        CustomerSkuRows.stock(jdbc, tx, cup.sku(), 2 * 4);
        UUID orderId = confirmedOrders().pay(confirmedOrders().signedInBatches(DemoData.CUSTOMER_ID, 2,
                ConfirmedOrders.Line.cartons(cup, 4, 6_000_000))).orderId();
        OrderSummary order = confirmedOrders().release(orderId, HCM, coordinator);
        allowPartial(order, false);
        UUID shipment = packed(order, false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        assertThat(plan.path("status").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("R3 (15 §4.1, 17 §4.5): batch 1 delivered in full makes its AFTER_DELIVERY installment due, dated from "
            + "that delivery, while batch 2 has not left; the installment tied to no batch waits for the whole order")
    void afterDeliveryInstallmentOfABatch() throws Exception {
        // A customer of the test's own with credit approved by accounting: a contract paying after delivery fits it.
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID accountant = staff("ACCOUNTANT");
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                                                     approved_at)
                VALUES (?, ?, 10000000000, 30, ?, NOW())""", Identifiers.newId(), customer, accountant));
        CustomerSkuRows.Issued cup = cupOf(customer);
        CustomerSkuRows.stock(jdbc, tx, cup.sku(), 2 * 4);
        List<SalesContracts.Installment> plan = List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("40"), 0, null),
                new SalesContracts.Installment("After batch 1", "AFTER_DELIVERY", null, new BigDecimal("30"), 10, 1),
                new SalesContracts.Installment("After all", "AFTER_DELIVERY", null, new BigDecimal("30"), 20, null));
        UUID orderId = confirmedOrders().pay(confirmedOrders().sign(confirmedOrders().draft(customer,
                List.of(ConfirmedOrders.Line.cartons(cup, 4, 6_000_000))), plan, 2)).orderId();
        OrderSummary order = confirmedOrders().release(orderId, HCM, coordinator);
        UUID shipment = packed(order, false)[0];
        String load = loadOf(confirmed(List.of(shipment), truck5t), 1);
        loadAll(load, ghn);
        handOver(load).andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        oc(body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"), "{\"outcome\":\"DELIVERED\"}"))
                .andExpect(status().isOk());
        Await.until("batch 1's installment after delivery due", ASYNC, () -> "DUE".equals(jdbc.queryForObject(
                "SELECT status FROM payment.installment WHERE order_id = ? AND seq = 2", String.class, orderId)));
        java.sql.Date outcome = jdbc.queryForObject("SELECT CAST(outcome_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date) "
                + "FROM fulfillment.shipment WHERE id = ?", java.sql.Date.class, shipment);
        assertThat(jdbc.queryForObject("SELECT due_date FROM payment.installment WHERE order_id = ? AND seq = 2",
                java.sql.Date.class, orderId).toLocalDate()).as("10 days from batch 1's delivery")
                .isEqualTo(outcome.toLocalDate().plusDays(10));
        assertThat(jdbc.queryForObject("SELECT status FROM payment.installment WHERE order_id = ? AND seq = 3",
                String.class, orderId)).as("tied to no batch: waits for the whole order").isEqualTo("NOT_DUE");
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
    @DisplayName("permissions and scope: Sales has no part in a vehicle's load: it neither reads, books, loads nor unloads it "
            + "(403); warehouse staff of another warehouse get OUT_OF_DATA_SCOPE")
    void whoMay() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        sales(get(LOADS + "/" + loadId)).andExpect(status().isForbidden());
        sales(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + ghn + "\"}"))
                .andExpect(status().isForbidden());
        sales(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isForbidden());
        sales(body(post(LOADS + "/" + loadId + "/booking-cancellation"), "{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        sales(body(post(LOADS + "/" + loadId + "/unloads"), "{\"packageCode\":\"PKG-X\"}"))
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

    // ---- WF-05 DS-05b, RTM-RETURNED: a shipment the carrier brought back -----------------------------------------

    /** The LPNs of the cartons a shipment carried. */
    private List<String> lpnsOf(UUID shipment) {
        return jdbc.queryForList("SELECT lpn FROM fulfillment.package WHERE shipment_id = ? ORDER BY package_no",
                String.class, shipment);
    }

    /** The order's one shipment of everything released, handed over to the carrier. */
    private UUID[] handedOver(OrderSummary order, UUID carrier) throws Exception {
        UUID[] made = packed(order, false);
        String loadId = loadOf(confirmed(List.of(made[0]), truck5t), 1);
        loadAll(loadId, carrier);
        handOver(loadId).andExpect(status().isOk());
        return made;
    }

    @Test
    @DisplayName("RTM-RETURNED with a delivery schedule: batch 1 is damaged on the way and comes back - its units "
            + "closed, nothing of it owed, so batch 2 is the next released; handed over and delivered, the order stays "
            + "PARTIALLY_FULFILLED (part of it came back) and the refund of batch 1 moves it to PARTIALLY_REFUNDED")
    void aReturnedBatchDoesNotHoldBackTheNext() throws Exception {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        CustomerSkuRows.Issued cup = cupOf(customer);
        CustomerSkuRows.stock(jdbc, tx, cup.sku(), 2 * 4);
        UUID orderId = confirmedOrders().pay(confirmedOrders().signedInBatches(customer, 2,
                ConfirmedOrders.Line.cartons(cup, 4, 6_000_000))).orderId();
        OrderSummary order = confirmedOrders().release(orderId, HCM, coordinator);
        List<UUID> batchIds = jdbc.queryForList("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? ORDER BY b.seq""", UUID.class, orderId);
        UUID[] first = handedOver(order, ghn);
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        failedAndCameBack(first[0], "DAMAGED");
        assertThat(orders.findById(orderId).orElseThrow().status()).as("batch 2 still to come")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(openHolds(orderId)).isEmpty();
        UUID caseId = receivedBack(first[0], null);
        assertThat(caseRow(caseId)).containsEntry("status", "CANCELLED")
                .containsEntry("delivery_batch_id", batchIds.get(0));

        OrderSummary second = WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL,
                () -> context.getBean(OrderReleases.class).release(orderId, HCM, coordinator));
        assertThat(second.status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(jdbc.queryForList("SELECT delivery_batch_id FROM ordering.delivery_batch_release WHERE order_id = ? "
                + "ORDER BY seq", UUID.class, orderId)).containsExactlyElementsOf(batchIds);
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        UUID consent = ChatRows.message(jdbc, tx, customer, orderId, "CUSTOMER");
        WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL, () -> context.getBean(
                DeliveryBatchService.class).changeWindow(confirmedOrders().sales(), orderId, batchIds.get(1),
                new DeliveryBatchService.WindowChange(today, today.plusDays(2), WindowChangeReason.CUSTOMER_REQUEST,
                        "The customer takes it now", null, consent)));
        UUID[] next = handedOver(second, ghtk);
        assertThat(jdbc.queryForObject("SELECT delivery_batch_id FROM fulfillment.pick WHERE id = ?", UUID.class,
                next[1])).isEqualTo(batchIds.get(1));
        oc(body(post(BASE + "/shipments/" + next[0] + "/delivery-outcome"), "{\"outcome\":\"DELIVERED\"}"))
                .andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().status()).as("part of it came back: never DELIVERED")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        recordRefund(orderId, new BigDecimal("24000000"));
        Await.until("the order follows its refund", ASYNC,
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.PARTIALLY_REFUNDED);
        assertThat(statusHistory(orderId)).noneMatch(change -> change.endsWith(">RETURNED"));
    }

    /** 09 §4.3 steps 2-3: the carrier could not deliver, then brought the shipment back. */
    private void failedAndCameBack(UUID shipment, String reason) throws Exception {
        oc(body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"), "{\"outcome\":\"DELIVERY_FAILED\","
                + "\"failureReason\":\"" + reason + "\"}")).andExpect(status().isOk());
        oc(body(post(BASE + "/shipments/" + shipment + "/delivery-outcome"), "{\"outcome\":\"RETURNED\"}"))
                .andExpect(status().isOk());
    }

    /**
     * 03 §4.2 steps 9-12: the return receipt of a returned shipment - every carton scanned by LPN, the first with
     * {@code condition} noted - confirmed. Returns the delivery failure case the receipt opened (after commit).
     */
    private UUID receivedBack(UUID shipment, String condition) {
        returnReceipt(shipment, condition);
        Await.until("the delivery failure case opened by the return receipt", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.delivery_failure_case WHERE shipment_id = ?", Long.class, shipment) == 1);
        return jdbc.queryForObject("SELECT id FROM ordering.delivery_failure_case WHERE shipment_id = ?", UUID.class,
                shipment);
    }

    /** The return receipt of {@link #receivedBack}, confirmed, without waiting for the case it opens. */
    private com.stockflow.procurement.internal.service.ReturnReceiptService.ReturnReceiptView returnReceipt(
            UUID shipment, String condition) {
        var receipts = context.getBean(com.stockflow.procurement.internal.service.ReturnReceiptService.class);
        var clerk = new com.stockflow.common.security.CurrentUser(picker, "clerk",
                java.util.Set.of(com.stockflow.common.security.Role.WAREHOUSE_STAFF), java.util.Set.of(),
                DataScope.WAREHOUSE, java.util.Set.of("HCM"), java.util.Set.of(HCM));
        return WithCurrentUser.call(clerk, DataScope.WAREHOUSE, () -> {
            var draft = receipts.create(new com.stockflow.procurement.internal.service.ReturnReceiptService.Create(
                    shipment, null, "HCM-QC01", "back from the carrier"), picker);
            String note = condition;
            for (String lpn : lpnsOf(shipment)) {
                receipts.scan(draft.id(), lpn, note, picker);
                note = null;
            }
            return receipts.confirm(draft.id(), picker);
        });
    }

    /** 20 §4.8, 05 BR-14: the cartons that came back are released from quarantine and put away on a bin. */
    private void releasedFromQuarantine(UUID shipment) {
        var quarantine = context.getBean(com.stockflow.inventory.internal.service.CartonQuarantineService.class);
        var putaway = context.getBean(com.stockflow.inventory.internal.service.PutawayService.class);
        WithCurrentUser.run(confirmedOrders().sales(), DataScope.ALL, () -> {
            for (String lpn : lpnsOf(shipment)) {
                UUID task = quarantine.release(lpn, coordinator);
                String bin = putaway.get(task).suggestions().stream().map(s -> s.locationCode()).findFirst()
                        .orElse("HCM-A01-1-A");
                putaway.confirm(task, bin, lpn, coordinator);
            }
        });
    }

    private long heldForCase(UUID caseId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(r.quantity), 0) FROM ordering.delivery_failure_case_hold h "
                + "JOIN inventory.stock_reservation r ON r.id = h.reservation_id WHERE h.case_id = ? "
                + "AND r.status = 'HELD'", Long.class, caseId);
    }

    private Map<String, Object> caseRow(UUID caseId) {
        return jdbc.queryForMap("SELECT status, failure_reason, failed_trip_fee, fee_installment_id, carrier_claim, "
                + "released_at, redelivery_shipment_id, delivery_batch_id, closed_at FROM ordering.delivery_failure_case "
                + "WHERE id = ?", caseId);
    }

    private String failures(UUID orderId) {
        return ORDERS + "/" + orderId + "/delivery-failures";
    }

    /**
     * The case's goods as if they came back to {@code warehouse}. What a case is about never changes, so its trigger is
     * off for this one update, in the same transaction.
     */
    private void caseAt(UUID caseId, UUID warehouse) {
        tx.executeWithoutResult(s -> {
            jdbc.execute("ALTER TABLE ordering.delivery_failure_case DISABLE TRIGGER tg_delivery_failure_case");
            jdbc.update("UPDATE ordering.delivery_failure_case SET warehouse_id = ? WHERE id = ?", warehouse, caseId);
            jdbc.execute("ALTER TABLE ordering.delivery_failure_case ENABLE TRIGGER tg_delivery_failure_case");
        });
    }

    /** The units held for the order (its holds, those of its delivery failure cases included). */
    private long heldForOrder(UUID orderId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM inventory.stock_reservation "
                + "WHERE order_id = ? AND status = 'HELD'", Long.class, orderId);
    }

    /** The order's holds still open, by reason. */
    private List<String> openHolds(UUID orderId) {
        return jdbc.queryForList("SELECT reason FROM ordering.order_hold WHERE order_id = ? AND resolved_at IS NULL",
                String.class, orderId);
    }

    /** The order's status changes as {@code FROM>TO}, in the order they happened. */
    private List<String> statusHistory(UUID orderId) {
        return jdbc.queryForList("SELECT COALESCE(from_status, '') || '>' || to_status FROM ordering.order_status_history "
                + "WHERE order_id = ? ORDER BY occurred_at, created_at", String.class, orderId);
    }

    @Test
    @DisplayName("RTM-RETURNED (09 §4.3 step 4, 17 §5.1; WF-05 Q-7): the carrier brings the order's one shipment back - "
            + "the order RETURNED with no hold, nothing closed or settled on the carrier's word; the return receipt "
            + "takes the cartons into quarantine, closes the units and settles them with no fee (SHIPMENT_RETURNED), "
            + "and opens a case CANCELLED at once, a record of what came back: nothing to deliver again "
            + "(no redelivery endpoint) and nothing to cancel (409); the nightly settlement leaves the order while the "
            + "refund is not made, and the refund recorded moves it to REFUNDED")
    void aReturnedShipmentReturnsTheOrderUntilItIsRefunded() throws Exception {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        OrderSummary order = ftlOrder(2, customer);
        UUID orderId = order.orderId();
        Sku sku = new Sku(cupOf(customer).sku());
        var inventory = context.getBean(com.stockflow.inventory.api.InventoryService.class);
        UUID[] first = handedOver(order, ghn);
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(lpnsOf(first[0])).hasSize(2);

        failedAndCameBack(first[0], "CUSTOMER_ABSENT");
        OrderSummary back = orders.findById(orderId).orElseThrow();
        assertThat(back.status()).as("nothing of it was delivered").isEqualTo(OrderStatus.RETURNED);
        assertThat(back.lifecycle().holdReason()).as("no longer held for Sales to deliver again").isNull();
        assertThat(openHolds(orderId)).isEmpty();
        assertThat(statusHistory(orderId)).endsWith("DELIVERY_FAILED>RETURNED");
        // WF-05 Q-7 (L-I8): the carrier's word closes nothing and settles nothing.
        assertThat(jdbc.queryForObject("SELECT SUM(cancelled_quantity) FROM ordering.order_line WHERE order_id = ?",
                Integer.class, orderId)).as("nothing closed before the receipt").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                orderId)).as("nothing settled before the receipt").isZero();
        assertThat(data(sales(get(failures(orderId))).andExpect(status().isOk()))).as("no goods back yet: no case")
                .isEmpty();
        // F-5: reported back, its receipt not confirmed - Sales may close it as lost (goods lost on the way back),
        // with the carrier's confirmation only.
        assertThat(data(sales(get(ORDERS + "/" + orderId)).andExpect(status().isOk())).path("shipments").get(0)
                .path("lostClosable").asBoolean()).as("awaiting its receipt").isTrue();
        sales(body(post(ORDERS + "/" + orderId + "/shipments/" + first[0] + "/lost-closure"),
                "{\"note\":\"Hãng báo thất lạc trên đường hoàn\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ORDER_SHIPMENT_LOSS_EVIDENCE_REQUIRED"));

        // The return receipt: the cartons into quarantine, the units closed and settled, the case on record and
        // closed at once.
        UUID caseId = receivedBack(first[0], "dented corner");
        JsonNode closedOrder = data(sales(get(ORDERS + "/" + orderId)).andExpect(status().isOk()));
        assertThat(closedOrder.path("shipments").get(0).path("lostClosable").asBoolean())
                .as("its units were closed at the receipt").isFalse();
        assertThat(closedOrder.path("settlementReadiness").isMissingNode())
                .as("F-1: nothing delivered, nothing to show (nulls are left out)").isTrue();
        assertThat(jdbc.queryForObject("SELECT SUM(cancelled_quantity) FROM ordering.order_line WHERE order_id = ?",
                Integer.class, orderId)).as("the shipment's units closed at its receipt").isEqualTo(4);
        // F7: by hand - 2 cartons x 2 pieces x 6,000,000, no VAT, all paid on signing: everything closed, no fee, all
        // of it refundable; nothing was owed, so nothing is reduced.
        assertThat(order.total().amount()).isEqualByComparingTo("24000000");
        assertThat(settlement(orderId)).containsEntry("kind", "SHIPMENT_RETURNED");
        amounts(settlement(orderId), 24_000_000, 24_000_000, 0, 24_000_000, 0, 0, 24_000_000, 0);
        assertThat(settlement(orderId).get("fee_installment_id")).isNull();
        assertThat(installments(orderId)).containsExactly("1 ON_SIGNING PAID 24000000/24000000/0");
        JsonNode record = data(sales(get(failures(orderId))).andExpect(status().isOk())).get(0);
        assertThat(record.path("caseId").asText()).isEqualTo(caseId.toString());
        assertThat(record.path("status").asText()).as("nothing of the shipment is owed").isEqualTo("CANCELLED");
        // F-4 (BE-42): closed by the system, said in a field - not the English note the back office showed as it was.
        assertThat(record.path("closure").asText()).isEqualTo("RECORDED_ON_RETURN");
        assertThat(record.path("decisionNote").isMissingNode()).as("no note for a system closure").isTrue();
        assertThat(record.path("failureReason").asText()).isEqualTo("CUSTOMER_ABSENT");
        assertThat(record.path("orderStatus").asText()).isEqualTo("RETURNED");
        assertThat(record.path("cartons")).hasSize(2);
        assertThat(record.path("cartons").findValuesAsText("condition")).contains("dented corner");
        assertThat(record.path("lines").get(0).path("units").asInt()).isEqualTo(4);
        assertThat(caseRow(caseId).get("closed_at")).isNotNull();
        assertThat(heldForCase(caseId)).isZero();
        assertThat(inventory.availableToPromise(sku, "HCM")).as("in quarantine: promised to no one").isZero();

        // F5: a warehouse manager reads only the cases of goods that came back to their own warehouses - none of a
        // warehouse nothing came back to, and another warehouse's order is not found - while HCM's sees this one.
        UUID elsewhere = tx.execute(s -> otherWarehouse());
        UUID managerElsewhere = staff("WAREHOUSE_MANAGER", elsewhere);
        assertThat(data(as(managerElsewhere, "WAREHOUSE_MANAGER", get(ORDERS + "/delivery-failures"))
                .andExpect(status().isOk()))).isEmpty();
        as(managerElsewhere, "WAREHOUSE_MANAGER", get(failures(orderId))).andExpect(status().isNotFound());
        assertThat(data(oc(get(ORDERS + "/delivery-failures").param("status", "CANCELLED").param("limit", "200"))
                .andExpect(status().isOk())).findValuesAsText("caseId")).contains(caseId.toString());
        assertThat(data(oc(get(failures(orderId))).andExpect(status().isOk())).findValuesAsText("caseId"))
                .containsExactly(caseId.toString());
        // WF-05 review N5: the order's cases are read by the list's rule - the warehouse the goods came back to, not
        // the one the order ships from. With the case's goods back elsewhere, HCM's manager reads the order and none
        // of its cases, and the other warehouse's manager reads the case of an order not of theirs.
        caseAt(caseId, elsewhere);
        try {
            assertThat(data(oc(get(failures(orderId))).andExpect(status().isOk()))).isEmpty();
            assertThat(data(as(managerElsewhere, "WAREHOUSE_MANAGER", get(failures(orderId)))
                    .andExpect(status().isOk())).findValuesAsText("caseId")).containsExactly(caseId.toString());
            assertThat(data(as(managerElsewhere, "WAREHOUSE_MANAGER", get(ORDERS + "/delivery-failures"))
                    .andExpect(status().isOk())).findValuesAsText("caseId")).as("the same case in the list")
                    .containsExactly(caseId.toString());
        } finally {
            caseAt(caseId, HCM);
        }

        // Nothing is left to decide: sending the goods again is a new order.
        int redelivery = sales(body(post(failures(orderId) + "/" + caseId + "/redelivery"), "{\"note\":\"giao lại\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(redelivery).as("the decision to deliver again is gone").isIn(404, 405);
        sales(body(post(failures(orderId) + "/" + caseId + "/cancellation"), cancelKeeping(4_000_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_FAILURE_INVALID_TRANSITION"));
        sales(body(post(failures(orderId) + "/cancellation"), "{\"note\":\"Khách không nhận lại hàng\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_FAILURE_INVALID_TRANSITION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                orderId)).as("settled once, when its receipt was confirmed").isEqualTo(1L);

        // The nightly settlement: a candidate, left as it is while the refund is not made.
        var settlements = context.getBean(com.stockflow.order.internal.service.OrderSettlementService.class);
        assertThat(settlements.failedDeliveryCandidates(0, 1000)).contains(orderId);
        assertThat(settlements.settleFailedDelivery(orderId)).isFalse();
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.RETURNED);

        assertThat(refundRefused(orderId, new BigDecimal("24000001"))).isEqualTo(REFUND_EXCEEDS_REFUNDABLE);
        // The accountant records the refund (15 §4.5 step 16): everything paid went back, the order follows it.
        recordRefund(orderId, new BigDecimal("24000000"));
        Await.until("the order follows its refund", ASYNC,
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.REFUNDED);
        assertThat(statusHistory(orderId)).endsWith("RETURNED>REFUNDED");
        assertThat(settlements.failedDeliveryCandidates(0, 1000)).doesNotContain(orderId);
        assertThat(jdbc.queryForObject("SELECT refunded_at IS NOT NULL FROM ordering.customer_order WHERE id = ?",
                Boolean.class, orderId)).as("what the closing run counts its delay from").isTrue();
    }

    // ---- F7: the money of a cancellation, every amount worked out by hand ---------------------------------------

    private static final com.stockflow.common.error.ErrorCode REFUND_EXCEEDS_REFUNDABLE =
            com.stockflow.common.error.ErrorCode.REFUND_EXCEEDS_REFUNDABLE;

    /** The order's one settlement row. */
    private Map<String, Object> settlement(UUID orderId) {
        return jdbc.queryForMap("SELECT id, kind, collected_amount, closed_amount, reduced_amount, surplus_amount, "
                + "fee_amount, fee_covered_amount, refundable_amount, fee_owed_amount, fee_installment_id "
                + "FROM payment.order_settlement WHERE order_id = ?", orderId);
    }

    /**
     * Every amount of a settlement against constants, in whole dong. Constants on purpose: compared with a sum read
     * back from the same tables, the settlement would agree with any arithmetic.
     */
    private static void amounts(Map<String, Object> settlement, long collected, long closed, long reduced,
                                long surplus, long fee, long covered, long refundable, long owed) {
        Map<String, Long> wanted = new java.util.LinkedHashMap<>();
        wanted.put("collected_amount", collected);
        wanted.put("closed_amount", closed);
        wanted.put("reduced_amount", reduced);
        wanted.put("surplus_amount", surplus);
        wanted.put("fee_amount", fee);
        wanted.put("fee_covered_amount", covered);
        wanted.put("refundable_amount", refundable);
        wanted.put("fee_owed_amount", owed);
        wanted.forEach((column, amount) -> assertThat((BigDecimal) settlement.get(column)).as(column)
                .isEqualByComparingTo(BigDecimal.valueOf(amount)));
    }

    /** Each installment of the order as {@code seq milestone status amount/paid/reduced}, in whole dong, by seq. */
    private List<String> installments(UUID orderId) {
        return jdbc.queryForList("SELECT seq || ' ' || milestone || ' ' || status || ' ' || amount::bigint || '/' "
                + "|| paid_amount::bigint || '/' || reduced_amount::bigint FROM payment.installment "
                + "WHERE order_id = ? ORDER BY seq", String.class, orderId);
    }

    private void recordRefund(UUID orderId, BigDecimal amount) {
        UUID accountant = staff("ACCOUNTANT");
        context.getBean(com.stockflow.payment.internal.service.RefundService.class).record(
                new com.stockflow.payment.internal.service.RefundService.RecordRefundCommand(orderId, amount,
                        LocalDate.now(ConfirmedOrders.VIETNAM), "FT" + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 16).toUpperCase(java.util.Locale.ROOT), null, accountant));
    }

    /** The error a refund of {@code amount} is refused with, or null when it is recorded. */
    private com.stockflow.common.error.ErrorCode refundRefused(UUID orderId, BigDecimal amount) {
        Throwable refused = catchThrowable(() -> recordRefund(orderId, amount));
        return refused instanceof com.stockflow.common.error.BusinessException e ? e.errorCode() : null;
    }

    private static String cancelKeeping(long fee) {
        return "{\"note\":\"Khách không nhận lại hàng\",\"agreedFee\":{\"amount\":" + fee + ",\"reason\":"
                + "\"Hàng đã in theo thiết kế\",\"detail\":\"Theo điều khoản hợp đồng, thống nhất với khách\"}}";
    }

    @Test
    @DisplayName("F7 money, paid in part, RTM-RETURNED: 30% on signing (7,200,000, paid) and 70% after delivery "
            + "(16,800,000, not due); the shipment comes back - closed 24,000,000, the installment after delivery "
            + "reduced whole and CANCELLED, no fee, the 7,200,000 paid refundable; no fee is kept on it afterwards (its "
            + "case is closed, 409); a refund above it is refused, and the refund of all of it makes the order REFUNDED")
    void aReturnedShipmentOfAnOrderPaidInPartIsSettled() throws Exception {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID accountant = staff("ACCOUNTANT");
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                                                     approved_at)
                VALUES (?, ?, 10000000000, 30, ?, NOW())""", Identifiers.newId(), customer, accountant));
        OrderSummary order = ftlOrder(2, customer, List.of(
                new SalesContracts.Installment("Deposit", "ON_SIGNING", null, new BigDecimal("30"), 0, null),
                new SalesContracts.Installment("After delivery", "AFTER_DELIVERY", null, new BigDecimal("70"), 10,
                        null)));
        UUID orderId = order.orderId();
        assertThat(installments(orderId)).containsExactly("1 ON_SIGNING PAID 7200000/7200000/0",
                "2 AFTER_DELIVERY NOT_DUE 16800000/0/0");
        UUID shipment = handedOver(order, ghn)[0];
        failedAndCameBack(shipment, "CUSTOMER_ABSENT");
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.RETURNED);
        assertThat(installments(orderId)).as("nothing moves on the carrier's word (Q-7)")
                .containsExactly("1 ON_SIGNING PAID 7200000/7200000/0", "2 AFTER_DELIVERY NOT_DUE 16800000/0/0");

        UUID caseId = receivedBack(shipment, null);
        Map<String, Object> settled = settlement(orderId);
        amounts(settled, 7_200_000, 24_000_000, 16_800_000, 7_200_000, 0, 0, 7_200_000, 0);
        assertThat(settled.get("fee_installment_id")).isNull();
        assertThat(installments(orderId)).containsExactly("1 ON_SIGNING PAID 7200000/7200000/0",
                "2 AFTER_DELIVERY CANCELLED 16800000/0/16800000");
        assertThat(jdbc.queryForObject("SELECT amount::bigint FROM payment.installment_reduction "
                + "WHERE settlement_id = ?", Long.class, settled.get("id"))).isEqualTo(16_800_000L);
        assertThat(caseRow(caseId)).containsEntry("status", "CANCELLED");
        sales(body(post(failures(orderId) + "/" + caseId + "/cancellation"), cancelKeeping(10_000_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_FAILURE_INVALID_TRANSITION"));
        amounts(settlement(orderId), 7_200_000, 24_000_000, 16_800_000, 7_200_000, 0, 0, 7_200_000, 0);

        assertThat(refundRefused(orderId, new BigDecimal("7200001"))).isEqualTo(REFUND_EXCEEDS_REFUNDABLE);
        recordRefund(orderId, new BigDecimal("7200000"));
        Await.until("the order follows its refund", ASYNC,
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("RTM-RETURNED-PARTIAL: two batches of 2 cartons (48,000,000, paid on signing); batch 1 delivered, "
            + "batch 2 fails and comes back - its 4 pieces closed, 24,000,000 refundable (not 48,000,000), the order "
            + "PARTIALLY_FULFILLED with no hold, never DELIVERED; the return receipt's case is CANCELLED at once; the "
            + "refund recorded moves the order straight to PARTIALLY_REFUNDED, never through RETURNED")
    void aReturnedBatchAfterADeliveredOneIsRefundedInPart() throws Exception {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        CustomerSkuRows.Issued cup = cupOf(customer);
        CustomerSkuRows.stock(jdbc, tx, cup.sku(), 2 * 4);
        UUID orderId = confirmedOrders().pay(confirmedOrders().signedInBatches(customer, 2,
                ConfirmedOrders.Line.cartons(cup, 4, 6_000_000))).orderId();
        assertThat(installments(orderId)).containsExactly("1 ON_SIGNING PAID 48000000/48000000/0");
        List<UUID> batchIds = jdbc.queryForList("""
                SELECT b.id FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
                 WHERE c.order_id = ? ORDER BY b.seq""", UUID.class, orderId);
        UUID[] first = handedOver(confirmedOrders().release(orderId, HCM, coordinator), ghn);
        oc(body(post(BASE + "/shipments/" + first[0] + "/delivery-outcome"), "{\"outcome\":\"DELIVERED\"}"))
                .andExpect(status().isOk());

        OrderSummary second = WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL,
                () -> context.getBean(OrderReleases.class).release(orderId, HCM, coordinator));
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        UUID consent = ChatRows.message(jdbc, tx, customer, orderId, "CUSTOMER");
        WithCurrentUser.call(confirmedOrders().sales(), DataScope.ALL, () -> context.getBean(
                DeliveryBatchService.class).changeWindow(confirmedOrders().sales(), orderId, batchIds.get(1),
                new DeliveryBatchService.WindowChange(today, today.plusDays(2), WindowChangeReason.CUSTOMER_REQUEST,
                        "The customer takes it now", null, consent)));
        UUID[] next = handedOver(second, ghtk);
        failedAndCameBack(next[0], "CUSTOMER_ABSENT");

        OrderSummary back = orders.findById(orderId).orElseThrow();
        assertThat(back.status()).as("batch 1 arrived, batch 2 came back").isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(back.lifecycle().holdReason()).isNull();
        assertThat(openHolds(orderId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                orderId)).as("nothing settled on the carrier's word (Q-7)").isZero();
        UUID caseId = receivedBack(next[0], null);
        assertThat(jdbc.queryForObject("SELECT SUM(cancelled_quantity) FROM ordering.order_line WHERE order_id = ?",
                Integer.class, orderId)).as("batch 2's four pieces only").isEqualTo(4);
        amounts(settlement(orderId), 48_000_000, 24_000_000, 0, 24_000_000, 0, 0, 24_000_000, 0);
        assertThat(settlement(orderId)).containsEntry("kind", "SHIPMENT_RETURNED");
        assertThat(installments(orderId)).containsExactly("1 ON_SIGNING PAID 48000000/48000000/0");
        assertThat(caseRow(caseId)).containsEntry("status", "CANCELLED")
                .containsEntry("delivery_batch_id", batchIds.get(1));

        var settlements = context.getBean(com.stockflow.order.internal.service.OrderSettlementService.class);
        assertThat(settlements.failedDeliveryCandidates(0, 1000)).contains(orderId);
        assertThat(settlements.settleFailedDelivery(orderId)).as("the refund is not made yet").isFalse();
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);

        assertThat(refundRefused(orderId, new BigDecimal("24000001"))).isEqualTo(REFUND_EXCEEDS_REFUNDABLE);
        recordRefund(orderId, new BigDecimal("24000000"));
        Await.until("the order follows its refund", ASYNC,
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.PARTIALLY_REFUNDED);
        List<String> history = statusHistory(orderId);
        assertThat(history).endsWith("PARTIALLY_FULFILLED>PARTIALLY_REFUNDED");
        assertThat(history).as("never through RETURNED, never DELIVERED as a whole")
                .noneMatch(change -> change.endsWith(">RETURNED") || change.endsWith(">DELIVERED"));
        assertThat(settlements.failedDeliveryCandidates(0, 1000)).doesNotContain(orderId);
    }

    @Test
    @DisplayName("RTM-RETURNED, one order on two shipments: the first comes back - its 2 pieces closed and settled, the "
            + "order SHIPPED while the second is out; the second comes back too - closed and settled on its own, the "
            + "order RETURNED; each return receipt opens a case CANCELLED at once that holds nothing, there is nothing "
            + "to cancel without a case (409), and the refund of both closures makes the order REFUNDED")
    void twoShipmentsComeBackAndEachIsClosedOnItsOwn() throws Exception {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        OrderSummary order = ftlOrder(2, customer);
        UUID orderId = order.orderId();
        UUID[] both = packedInTwo(order, false);
        JsonNode plan = confirmed(List.of(both[0], both[1]), truck5t);
        for (int seq = 1; seq <= plan.path("vehicles").size(); seq++) {
            String loadId = loadOf(plan, seq);
            loadAll(loadId, ghn);
            handOver(loadId).andExpect(status().isOk());
        }
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);

        failedAndCameBack(both[0], "DAMAGED");
        assertThat(orders.findById(orderId).orElseThrow().status()).as("the second shipment is still out")
                .isEqualTo(OrderStatus.SHIPPED);
        assertThat(jdbc.queryForObject("SELECT SUM(cancelled_quantity) FROM ordering.order_line WHERE order_id = ?",
                Integer.class, orderId)).as("nothing closed before its receipt (Q-7)").isZero();
        failedAndCameBack(both[1], "DAMAGED");
        assertThat(orders.findById(orderId).orElseThrow().status()).as("nothing of it was delivered")
                .isEqualTo(OrderStatus.RETURNED);
        assertThat(openHolds(orderId)).isEmpty();

        UUID first = receivedBack(both[0], null);
        UUID second = receivedBack(both[1], null);
        assertThat(jdbc.queryForObject("SELECT SUM(cancelled_quantity) FROM ordering.order_line WHERE order_id = ?",
                Integer.class, orderId)).as("both shipments' units").isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT closed_amount::bigint || '/' || refundable_amount::bigint FROM "
                + "payment.order_settlement WHERE order_id = ? AND kind = 'SHIPMENT_RETURNED' ORDER BY created_at",
                String.class, orderId)).as("one closure per shipment, at its receipt")
                .containsExactly("12000000/12000000", "12000000/12000000");
        assertThat(caseRow(first)).containsEntry("status", "CANCELLED");
        assertThat(caseRow(second)).containsEntry("status", "CANCELLED");
        releasedFromQuarantine(both[1]);
        assertThat(heldForCase(second)).as("held for no one").isZero();
        sales(body(post(failures(orderId) + "/cancellation"), "{\"note\":\"Khách không chờ\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DELIVERY_FAILURE_INVALID_TRANSITION"));

        recordRefund(orderId, new BigDecimal("24000000"));
        Await.until("the order follows its refund", ASYNC,
                () -> orders.findById(orderId).orElseThrow().status() == OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("F8, RTM-RETURNED: nothing paid (everything after delivery, on credit) and the shipment comes back - "
            + "nothing collected, the installment reduced whole and CANCELLED, nothing refundable and no fee; no refund "
            + "will come, so the receipt (Q-7) moves the order from RETURNED to PARTIALLY_REFUNDED, not REFUNDED")
    void anUnpaidReturnedShipmentIsSettledWithoutARefund() throws Exception {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID accountant = staff("ACCOUNTANT");
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, approved_by,
                                                     approved_at)
                VALUES (?, ?, 10000000000, 30, ?, NOW())""", Identifiers.newId(), customer, accountant));
        OrderSummary order = ftlOrder(2, customer, List.of(new SalesContracts.Installment("After delivery",
                "AFTER_DELIVERY", null, new BigDecimal("100"), 10, null)));
        UUID orderId = order.orderId();
        assertThat(installments(orderId)).containsExactly("1 AFTER_DELIVERY NOT_DUE 24000000/0/0");
        UUID shipment = handedOver(order, ghn)[0];
        failedAndCameBack(shipment, "CUSTOMER_ABSENT");
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.RETURNED);
        assertThat(installments(orderId)).as("nothing moves on the carrier's word (Q-7)")
                .containsExactly("1 AFTER_DELIVERY NOT_DUE 24000000/0/0");

        assertThat(caseRow(receivedBack(shipment, null))).containsEntry("status", "CANCELLED");
        amounts(settlement(orderId), 0, 24_000_000, 24_000_000, 0, 0, 0, 0, 0);
        assertThat(installments(orderId)).containsExactly("1 AFTER_DELIVERY CANCELLED 24000000/0/24000000");
        assertThat(refundRefused(orderId, BigDecimal.ONE)).as("nothing is refundable")
                .isEqualTo(REFUND_EXCEEDS_REFUNDABLE);

        // No refund will come: the receipt itself moved the order on (DeliveryFailureService.followRefunds).
        var settlements = context.getBean(com.stockflow.order.internal.service.OrderSettlementService.class);
        assertThat(orders.findById(orderId).orElseThrow().status()).as("nothing paid, so nothing went back")
                .isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
        assertThat(settlements.failedDeliveryCandidates(0, 1000)).doesNotContain(orderId);
    }

    @Test
    @DisplayName("RTM-RETURNED: goods that came back are owed to nobody - their case is CANCELLED and wants nothing "
            + "held; released from quarantine they are free stock, and another order of the same SKU choosing its "
            + "warehouse holds them (sending the goods again is a new order)")
    void goodsThatCameBackAreFreeStockOnceReleased() throws Exception {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        CustomerSkuRows.Issued cup = cupOf(customer);
        Sku sku = new Sku(cup.sku());
        var inventory = context.getBean(com.stockflow.inventory.api.InventoryService.class);
        OrderSummary order = ftlOrder(2, customer);
        UUID[] first = handedOver(order, ghn);
        failedAndCameBack(first[0], "DAMAGED");
        UUID caseId = receivedBack(first[0], null);
        var cases = context.getBean(com.stockflow.order.internal.domain.DeliveryFailureCases.class);
        assertThat(cases.ordersWantingGoodsHeld(List.of(cup.sku()))).doesNotContain(order.orderId());
        assertThat(cases.ordersWantingGoodsHeld()).doesNotContain(order.orderId());
        assertThat(inventory.availableToPromise(sku, "HCM")).as("in quarantine").isZero();

        releasedFromQuarantine(first[0]);
        assertThat(heldForCase(caseId)).isZero();
        assertThat(heldForOrder(order.orderId())).as("its units are closed: held for no one").isZero();
        assertThat(inventory.availableToPromise(sku, "HCM")).isEqualTo(4);

        UUID other = confirmedOrders().pay(confirmedOrders().signed(customer, ConfirmedOrders.paidOnSigning(),
                ConfirmedOrders.Line.cartons(cup, 2, 6_000_000))).orderId();
        assertThat(confirmedOrders().ship(other, HCM).fullyHeld()).as("the goods that came back serve the new order")
                .isTrue();
        assertThat(heldForOrder(other)).isEqualTo(4);
    }

    @Test
    @DisplayName("RTM-RETURNED while held: a partly shipped order is held for the customer; the carrier's webhook brings "
            + "the shipment back - its units closed and settled, the order stays ON_HOLD for the customer with that "
            + "one hold open and none raised for the failed delivery; resolved, it is PARTIALLY_FULFILLED again, its "
            + "other shipment still to leave")
    void aShipmentBackWhileTheOrderIsHeldLeavesTheHoldAsItIs() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", HCM);
        String secret = "load-webhook-secret-f12-0123456789";
        String code = "WF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID carrier = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", body(post(BASE + "/carriers"),
                "{\"code\":\"" + code + "\",\"name\":\"Xe tải webhook\",\"connection\":{\"connectionType\":"
                        + "\"WEBHOOK_LOAD\",\"webhookSecret\":\"" + secret + "\"}}")).andExpect(status().isCreated()))
                .path("carrierId").asText());
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        OrderSummary order = ftlOrder(2, customer);
        UUID orderId = order.orderId();
        UUID[] both = packedInTwo(order, false);
        String loadId = loadOf(confirmed(List.of(both[0]), truck5t), 1);
        String loadCode = load(loadId).path("code").asText();
        dispatch(body(post(LOADS + "/" + loadId + "/booking"), "{\"carrierId\":\"" + carrier
                + "\",\"vehiclePlate\":\"51C-WF.12\",\"waybillNo\":\"WF-" + loadId + "\"}")).andExpect(status().isOk());
        loadAndSign(loadId, "51C-WF.12");
        assertThat(orders.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        sales(body(post(ORDERS + "/" + orderId + "/hold"), "{\"reasonCode\":\"CUSTOMER\","
                + "\"note\":\"Khách hẹn lại lịch nhận\"}")).andExpect(status().isOk());

        String shipmentCode = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class,
                both[0]);
        String back = "{\"loadCode\":\"" + loadCode + "\",\"type\":\"RETURNED\",\"reportedAt\":\"" + Instant.now()
                + "\",\"shipmentCode\":\"" + shipmentCode + "\"}";
        hook(code, back, "X-Signature", com.stockflow.fulfillment.internal.service.CarrierWebhookTestSigner
                .sign(secret, back)).andExpect(jsonPath("$.data.result").value("APPLIED"));
        assertThat(shipmentStatus(both[0])).isEqualTo("RETURNED");
        OrderSummary held = orders.findById(orderId).orElseThrow();
        assertThat(held.status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(held.lifecycle().holdReason()).isEqualTo(HoldReason.CUSTOMER);
        assertThat(openHolds(orderId)).containsExactly("CUSTOMER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_hold WHERE order_id = ? AND reason = "
                + "'DELIVERY_FAILED'", Long.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(cancelled_quantity) FROM ordering.order_line WHERE order_id = ?",
                Integer.class, orderId)).as("nothing closed on the carrier's word (Q-7)").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE order_id = ?", Long.class,
                orderId)).isZero();

        sales(body(post(ORDERS + "/" + orderId + "/hold/resolution"), "{\"note\":\"Khách nhận phần còn lại\"}"))
                .andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().status()).as("the other shipment still to leave")
                .isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat(openHolds(orderId)).isEmpty();
    }

    // ---- BE3-A: the delivery note -------------------------------------------------------------

    @Test
    @DisplayName("09 §4.2: a shipment's delivery note prints at dispatch before a vehicle is booked (lines to fill in) "
            + "and after the handover with the vehicle, the waybill, every LPN; another warehouse 403; a cancelled "
            + "shipment 409; none 404")
    void theDeliveryNote() throws Exception {
        OrderSummary order = ftlOrder(1);
        UUID shipment = packed(order, false)[0];
        String note = BASE + "/shipments/" + shipment + "/delivery-note";
        String code = jdbc.queryForObject("SELECT code FROM fulfillment.shipment WHERE id = ?", String.class, shipment);
        List<String> lpns = jdbc.queryForList("""
                SELECT l.lpn FROM fulfillment.package p JOIN fulfillment.pick_line l ON l.id = p.pick_line_id
                 WHERE p.shipment_id = ? ORDER BY l.lpn""", String.class, shipment);
        String contract = jdbc.queryForObject("SELECT contract_number FROM ordering.sales_contract WHERE order_id = ? "
                + "AND status <> 'CANCELLED'", String.class, order.orderId());
        assertThat(lpns).isNotEmpty();
        // Each SKU carried is printed with its catalogue name (the variant's), looked up through product :: api.
        Map<String, Object> skuNamed = jdbc.queryForMap("""
                SELECT DISTINCT l.sku, v.name FROM fulfillment.package p
                  JOIN fulfillment.pick_line l ON l.id = p.pick_line_id
                  JOIN product.variants v ON v.sku = l.sku
                 WHERE p.shipment_id = ?""", shipment);

        // At dispatch, no vehicle yet: the same form, the vehicle and the handover dotted lines.
        String before = pdfText(dispatch(get(note)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(before).contains("BIÊN BẢN GIAO NHẬN HÀNG HÓA", "Số: " + code,
                "Căn cứ Đơn đặt hàng số " + order.orderNumber(),
                "Căn cứ Hợp đồng mua bán hàng hóa số " + contract, "(Giao hàng đợt 1)",
                "Xe: biển số ......", "Thời điểm bàn giao: ......", lpns.getFirst(),
                (String) skuNamed.get("name"), "Tổng cộng");
        // A SKU longer than its column is broken over two lines of the cell, so it is looked for without the break.
        assertThat(before.replace(" ", "")).contains((String) skuNamed.get("sku"));

        // Booked, loaded and signed: the vehicle, its waybill and the moment of the handover are on it.
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        book(loadId, ghn, "51C-246.80");
        loadAndSign(loadId, "51C-246.80");
        String loadCode = load(loadId).path("code").asText();
        String after = pdfText(dispatch(get(note)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(after).contains("Chuyến " + loadCode + " - Xe tải Nhanh: biển số 51C-246.80; tài xế Tai",
                "mã vận đơn WB-" + loadId, lpns.getLast()).doesNotContain("Thời điểm bàn giao: ......");

        // Limited to the caller's warehouses, as every shipment read is.
        as(dispatcherElsewhere, "WAREHOUSE_STAFF", get(note)).andExpect(status().isForbidden());
        dispatch(get(BASE + "/shipments/" + UUID.randomUUID() + "/delivery-note")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_NOT_FOUND"));

        // A cancelled shipment hands nothing over: no delivery note.
        UUID cancelled = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, package_count, ready_at,
                                                  cancelled_at, cancel_reason, created_at, created_by)
                VALUES (?, ?, 'CANCELLED', ?, ?, 1, NOW(), NOW(), 'order cancelled', NOW(), 'test')""",
                cancelled, order.orderId(), code + "-X", HCM));
        dispatch(get(BASE + "/shipments/" + cancelled + "/delivery-note")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SHIPMENT_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("WF04 G2 (17 §4.7 steps 23-24, BR-16; 08 BR-04, §6 'Địa chỉ giao không hợp lệ'): Sales hold the order "
            + "ADDRESS, change its delivery address to another of the customer's and release it; the change is in the "
            + "order's history; once a carton is labelled the address no longer changes (409 ORDER_ADDRESS_LOCKED)")
    void salesChangeTheDeliveryAddressUntilTheFirstLabel() throws Exception {
        OrderSummary order = ftlOrder(1);
        UUID firstAddress = jdbc.queryForObject("SELECT id FROM customer.address WHERE customer_id = ? AND type = "
                + "'SHIPPING' ORDER BY is_default DESC, created_at LIMIT 1", UUID.class, order.customerId());
        UUID corrected = customers.addAddress(order.customerId(), sales, true, new SaveAddressCommand("SHIPPING",
                "Kho Đa Kao", "0907654321", "9 Hai Bà Trưng", null, "26737", "Phường Đa Kao", "79", "TP. Hồ Chí Minh",
                "VN", null, false, null)).addressId();
        String path = ORDERS + "/" + order.orderId() + "/shipping-address";
        String toCorrected = "{\"shippingAddressId\":\"" + corrected + "\"}";

        // Sales' to change: not the warehouse's; an address not the customer's is not found.
        packer(body(put(path), toCorrected)).andExpect(status().isForbidden());
        sales(body(put(path), "{\"shippingAddressId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));

        // 08 §6: caught before the first label - held ADDRESS, corrected, released.
        sales(body(post(ORDERS + "/" + order.orderId() + "/hold"), "{\"reasonCode\":\"ADDRESS\","
                + "\"note\":\"Sai phường\"}")).andExpect(status().isOk());
        sales(body(put(path), toCorrected)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.data.shippingAddress.line1").value("9 Hai Bà Trưng"))
                .andExpect(jsonPath("$.data.shippingAddress.recipientName").value("Kho Đa Kao"));
        sales(body(post(ORDERS + "/" + order.orderId() + "/hold/resolution"),
                "{\"note\":\"Khách xác nhận địa chỉ mới\"}")).andExpect(status().isOk());
        // The same address again changes nothing and records nothing.
        sales(body(put(path), toCorrected)).andExpect(status().isOk());

        JsonNode history = data(sales(get(ORDERS + "/" + order.orderId() + "/history")).andExpect(status().isOk()));
        List<JsonNode> changes = new ArrayList<>();
        history.forEach(entry -> {
            if ("ADDRESS_CHANGE".equals(entry.path("type").asText())) {
                changes.add(entry);
            }
        });
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.path("newAddress").path("line1").asText()).isEqualTo("9 Hai Bà Trưng");
            assertThat(change.path("previousAddress").path("line1").asText()).isNotEqualTo("9 Hai Bà Trưng");
            assertThat(change.path("actorId").asText()).isEqualTo(sales.toString());
        });

        // 08 BR-04: the first carton labelled locks it; the label carries the corrected address.
        UUID task = picked(order);
        CartonStationSupport.startAndScanAll(this::packer, jdbc, task);
        CartonStationSupport.labelAll(this::packer, task);
        sales(body(put(path), "{\"shippingAddressId\":\"" + firstAddress + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_ADDRESS_LOCKED"));
        assertThat(jdbc.queryForObject("SELECT shipping_line1 FROM ordering.customer_order WHERE id = ?", String.class,
                order.orderId())).isEqualTo("9 Hai Bà Trưng");
    }

    private ResultActions reprintLpnLabel(UUID user, String role, String lpn) throws Exception {
        return as(user, role, body(post("/api/v1/inventory/stock-packages/labels"), "{\"format\":\"THERMAL_ROLL\","
                + "\"lpns\":[\"" + lpn + "\"]}").accept(MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("WF04 G1 (03 §4.1 step 4, 08 §6 'Nhãn LPN rách'): a carton picked for the order (CONSUMED at dispatch) "
            + "has its LPN label printed again by its LPN - before its check and while labelled in a shipment waiting "
            + "for its load - and no longer once that shipment is handed over")
    void aPickedCartonsLpnLabelIsPrintedAgainUntilHandover() throws Exception {
        OrderSummary order = ftlOrder(1);
        UUID task = picked(order);
        String lpn = CartonStationSupport.pickedLpns(jdbc, task).getFirst();
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class, lpn))
                .isEqualTo("CONSUMED");

        // At the dispatch area, not checked yet: the warehouse staff reprint it, then scan it at the station.
        reprintLpnLabel(picker, "WAREHOUSE_STAFF", lpn).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        // Checked, labelled and completed: its shipment waits at dispatch for its load - still the order's, still here.
        UUID shipment = CartonStationSupport.ship(this::packer, jdbc, json, task, null);
        assertThat(shipmentStatus(shipment)).isEqualTo("READY_TO_DISPATCH");
        reprintLpnLabel(coordinator, "WAREHOUSE_MANAGER", lpn).andExpect(status().isOk());
        // Another warehouse's clerk is refused before anything is said about the carton.
        reprintLpnLabel(dispatcherElsewhere, "WAREHOUSE_STAFF", lpn).andExpect(status().isForbidden());

        // Handed over: the carton left with the carrier, there is no LPN label to print any more.
        String loadId = loadOf(confirmed(List.of(shipment), truck5t), 1);
        book(loadId, ghn, "51C-135.79");
        loadAndSign(loadId, "51C-135.79");
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");
        reprintLpnLabel(picker, "WAREHOUSE_STAFF", lpn).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STOCK_PACKAGE_TRANSITION"));
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }
}
