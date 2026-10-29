package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controlled cancel after loading started (SCRUM-474 UNLOAD-DESIGN, owner decision 29/10/2026; kltn-docs 09 BR-14) through
 * the real filter chain, permission aspect, solver and database - one test per point of the decision: cancel only; one
 * vehicle back to PLANNED with the same layout while the others carry on; the whole plan refused once a vehicle left;
 * parcels locked until scanned off; scan-off and the warehouse manager's override; the permission; left-behind splits
 * untouched; the order-cancelled-while-loading path in both orders. The transfer picking cancel is in
 * {@link TransferLoadPlanningIntegrationTest}.
 *
 * <p>Shares its context, hence its database, with {@link FtlDispatchIntegrationTest}.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.fulfillment.packing.dim-divisor=6000", "stockflow.fulfillment.load-planning.solver-budget=PT2S",
        "stockflow.test.context=ftl-dispatch"})
class ControlledUnloadIntegrationTest {

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
    private UUID manager;
    private UUID managerElsewhere;
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
        manager = staff("WAREHOUSE_MANAGER", HCM);
        managerElsewhere = staff("WAREHOUSE_MANAGER", elsewhere);
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

    /** The warehouse manager: the only role that calls off after loading started (fulfillment-controlled-cancellations). */
    private ResultActions wm(MockHttpServletRequestBuilder request) throws Exception {
        return as(manager, "WAREHOUSE_MANAGER", request);
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

    // ---- helpers of this class ---------------------------------------------------------------

    private static final String XE_HONG = "{\"reasonCode\":\"XE_HONG\",\"note\":\"the rear axle broke\"}";

    private ResultActions cancelVehicle(String loadId, String content) throws Exception {
        return wm(body(post(LOADS + "/" + loadId + "/controlled-cancellation"), content));
    }

    private ResultActions cancelPlan(JsonNode plan, String reasonCode, String note) throws Exception {
        JsonNode now = data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()));
        return wm(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + "/controlled-cancellation"),
                "{\"expectedVersion\":" + now.path("version").asLong() + ",\"reasonCode\":\"" + reasonCode
                        + "\",\"note\":\"" + note + "\"}"));
    }

    private JsonNode planOf(JsonNode plan) throws Exception {
        return data(dispatch(get(PLANS + "/" + plan.path("loadPlanId").asText())).andExpect(status().isOk()));
    }

    private ResultActions scanOff(String loadId, String label) throws Exception {
        return dispatch(body(post(LOADS + "/" + loadId + "/unloads"), "{\"packageCode\":\"" + label + "\"}"));
    }

    private ResultActions override(String loadId, String packageId, String note) throws Exception {
        return wm(body(post(LOADS + "/" + loadId + "/unload-overrides"), "{\"packageId\":\"" + packageId + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}"));
    }

    private static List<String> pendingLabels(JsonNode loadOrPlan) {
        List<String> out = new ArrayList<>();
        loadOrPlan.path("pendingUnloads").forEach(u -> out.add(u.path("labelCode").asText()));
        return out;
    }

    private String packageIdOf(String label) {
        return jdbc.queryForObject("SELECT id FROM fulfillment.package WHERE label_code = ?", String.class, label);
    }

    private void cancelOrder(OrderSummary order, UUID shipment) {
        tx.executeWithoutResult(s -> publisher.publishEvent(new OrderCancelled(order.orderId(), order.orderNumber(),
                DemoData.CUSTOMER_ID, "PACKED", "OTHER", "test", BigDecimal.ZERO, BigDecimal.ZERO, "VND", coordinator,
                Instant.now())));
        Await.until("the cancelled order's shipment is cancelled", ASYNC, () -> "CANCELLED".equals(shipmentStatus(shipment)));
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

    // ---- points 1, 2, 4, 5, 7: one vehicle back to PLANNED with the same layout ---------------

    @Test
    @DisplayName("points 2, 4, 5, 7: one truck of two being loaded is called off (XE_HONG + note) - its load is CANCELLED "
            + "at once with the code, actor and time, a PLANNED load with the same plan vehicle, layout and manifest "
            + "takes its place, the other truck carries on; the scanned parcels are locked (not scanned onto the "
            + "replacement, PACKAGE_UNLOAD_PENDING) until one is scanned off by the dispatcher and one confirmed off "
            + "by the manager without a scan (overridden, who, when, note); then the replacement is loaded and both "
            + "sign - the plan is DISPATCHED. Never a reopening")
    void oneVehicleBackToPlannedWithTheSameLayout() throws Exception {
        OrderSummary order = ftlOrder(2);
        UUID shipment = packed(order, true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        assertThat(plan.path("vehicles")).hasSize(2);
        String planId = plan.path("loadPlanId").asText();
        String v1 = loadOf(plan, 1);
        String v2 = loadOf(plan, 2);
        JsonNode layoutBefore = data(dispatch(get(PLANS + "/" + planId + "/vehicles/1/layout")).andExpect(status().isOk()));
        List<String> parcels = labels(load(v1));
        assertThat(parcels).hasSize(2);
        book(v2, ghn, "51C-222.22");
        loadAll(v1, ghn);
        assertThat(load(v1).path("loadedPackageCount").asInt()).isEqualTo(2);

        // The dispatcher may not: this is the warehouse manager's permission.
        dispatch(body(post(LOADS + "/" + v1 + "/controlled-cancellation"), XE_HONG)).andExpect(status().isForbidden());
        assertThat(load(v1).path("status").asText()).isEqualTo("LOADING");

        JsonNode result = data(cancelVehicle(v1, XE_HONG).andExpect(status().isOk()));
        JsonNode cancelled = result.path("cancelled");
        JsonNode replacement = result.path("replacement");
        assertThat(cancelled.path("loadId").asText()).isEqualTo(v1);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("cancelReasonCode").asText()).isEqualTo("XE_HONG");
        assertThat(cancelled.path("cancelReason").asText()).isEqualTo("the rear axle broke");
        assertThat(cancelled.hasNonNull("cancelledAt")).isTrue();
        assertThat(cancelled.path("carrierBookingToCancel").asBoolean()).isTrue();
        assertThat(pendingLabels(cancelled)).containsExactlyInAnyOrderElementsOf(parcels);
        cancelled.path("pendingUnloads").forEach(u -> {
            assertThat(u.path("reasonCode").asText()).isEqualTo("XE_HONG");
            assertThat(u.path("reason").asText()).isEqualTo("the rear axle broke");
        });
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM fulfillment.dispatch_load WHERE id = ?::uuid",
                UUID.class, v1)).isEqualTo(manager);

        // Back to PLANNED: same plan vehicle, same layout, same manifest, nothing loaded, another truck to be booked.
        assertThat(replacement.path("loadId").asText()).isNotEqualTo(v1);
        assertThat(cancelled.path("replacementLoadId").asText()).isEqualTo(replacement.path("loadId").asText());
        assertThat(load(v1).path("replacementLoadCode").asText()).isEqualTo(replacement.path("code").asText());
        assertThat(replacement.path("status").asText()).isEqualTo("PLANNED");
        assertThat(replacement.path("loadPlanId").asText()).isEqualTo(planId);
        assertThat(replacement.path("vehicleSeq").asInt()).isEqualTo(1);
        assertThat(labels(replacement)).containsExactlyInAnyOrderElementsOf(parcels);
        assertThat(replacement.path("shipments")).singleElement().satisfies(s -> assertThat(s.path("shipmentId")
                .asText()).isEqualTo(shipment.toString()));
        assertThat(replacement.path("loadedPackageCount").asInt()).isZero();
        assertThat(data(dispatch(get(PLANS + "/" + planId + "/vehicles/1/layout")).andExpect(status().isOk())))
                .isEqualTo(layoutBefore);
        JsonNode afterPlan = planOf(plan);
        assertThat(afterPlan.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(afterPlan.path("vehicles")).anySatisfy(v -> {
            assertThat(v.path("seq").asInt()).isEqualTo(1);
            assertThat(v.path("load").path("loadId").asText()).isEqualTo(replacement.path("loadId").asText());
            assertThat(v.path("load").path("status").asText()).isEqualTo("PLANNED");
        });
        assertThat(pendingLabels(afterPlan)).containsExactlyInAnyOrderElementsOf(parcels);
        assertThat(load(v2).path("status").asText()).as("the other vehicle carries on").isEqualTo("VEHICLE_BOOKED");
        // Called off again: answered as it is, nothing more is made.
        JsonNode again = data(cancelVehicle(v1, XE_HONG).andExpect(status().isOk()));
        assertThat(again.path("replacement").path("loadId").asText()).isEqualTo(replacement.path("loadId").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.dispatch_load WHERE load_plan_vehicle_id = "
                + "(SELECT load_plan_vehicle_id FROM fulfillment.dispatch_load WHERE id = ?::uuid)", Integer.class, v1))
                .isEqualTo(2);

        // The lock, a second time: the database refuses to put a still-to-unload parcel back on a vehicle.
        Throwable net = catchThrowable(() -> jdbc.update("UPDATE fulfillment.dispatch_load_package SET active = TRUE, "
                + "removed_at = NULL, removed_by = NULL WHERE load_id = ?::uuid AND package_id = ?::uuid", v1,
                packageIdOf(parcels.getFirst())));
        assertThat(net).hasMessageContaining("tg_package_awaiting_unload");

        // Locked: the replacement is booked and loading, but the parcels are still on the broken truck.
        String vehicle = replacement.path("loadId").asText();
        book(vehicle, ghtk, "51C-333.33");
        dispatch(post(LOADS + "/" + vehicle + "/loading-start")).andExpect(status().isOk());
        JsonNode refused = body(scanOnto(vehicle, parcels.getFirst()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_UNLOAD_PENDING")));
        assertThat(refused.path("message").asText()).contains(cancelled.path("code").asText());

        // Scan-off (the dispatcher); the second parcel's label is damaged: the manager confirms it off without a scan.
        JsonNode half = data(scanOff(v1, parcels.getFirst()).andExpect(status().isOk()));
        assertThat(pendingLabels(half)).containsExactly(parcels.get(1));
        assertThat(jdbc.queryForObject("SELECT unloaded_by FROM fulfillment.dispatch_load_unload WHERE load_id = "
                + "?::uuid AND package_id = ?::uuid", UUID.class, v1, packageIdOf(parcels.getFirst())))
                .isEqualTo(dispatcher);
        scanOnto(vehicle, parcels.get(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_UNLOAD_PENDING"));
        as(dispatcher, "WAREHOUSE_STAFF", body(post(LOADS + "/" + v1 + "/unload-overrides"), "{\"packageId\":\""
                + packageIdOf(parcels.get(1)) + "\"}")).andExpect(status().isForbidden());
        JsonNode done = data(override(v1, packageIdOf(parcels.get(1)), "label torn in the rain")
                .andExpect(status().isOk()));
        assertThat(done.path("pendingUnloads")).isEmpty();
        Map<String, Object> row = jdbc.queryForMap("SELECT overridden, override_note, unloaded_by, unloaded_at, "
                + "reason_code FROM fulfillment.dispatch_load_unload WHERE load_id = ?::uuid AND package_id = "
                + "?::uuid", v1, packageIdOf(parcels.get(1)));
        assertThat(row).containsEntry("overridden", true).containsEntry("override_note", "label torn in the rain")
                .containsEntry("unloaded_by", manager).containsEntry("reason_code", "XE_HONG");
        assertThat(row.get("unloaded_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT overridden FROM fulfillment.dispatch_load_unload WHERE load_id = "
                + "?::uuid AND package_id = ?::uuid", Boolean.class, v1, packageIdOf(parcels.getFirst()))).isFalse();
        override(v1, packageIdOf(parcels.get(1)), null).andExpect(status().isOk());    // again: nothing changes
        // A parcel that is not to be unloaded from this load.
        override(v2, packageIdOf(parcels.getFirst()), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_IN_LOAD"));
        assertThat(pendingLabels(planOf(plan))).isEmpty();

        // Free again: the replacement is loaded and signed, so is the other truck - the plan is DISPATCHED.
        for (String label : parcels) {
            scanOnto(vehicle, label).andExpect(status().isOk());
        }
        assertThat(data(handOver(vehicle).andExpect(status().isOk())).path("status").asText()).isEqualTo("DISPATCHED");
        dispatch(post(LOADS + "/" + v2 + "/loading-start")).andExpect(status().isOk());
        for (String label : labels(load(v2))) {
            scanOnto(v2, label).andExpect(status().isOk());
        }
        handOver(v2).andExpect(status().isOk());
        assertThat(planStatus(plan)).isEqualTo("DISPATCHED");
        assertThat(shipmentStatus(shipment)).isEqualTo("HANDED_OVER");
    }

    // ---- point 3 -----------------------------------------------------------------------------

    @Test
    @DisplayName("point 3: once a vehicle has left (DISPATCHED) the whole plan cannot be cancelled "
            + "(LOAD_PLAN_VEHICLE_DISPATCHED) - the other vehicle is cancelled on its own and the plan stays CONFIRMED; "
            + "a left vehicle, a vehicle not loading yet and a parcel load are not cancelled under control "
            + "(INVALID_LOAD_TRANSITION); the plain cancel is still refused (BR-14)")
    void wholePlanRefusedOnceAVehicleLeft() throws Exception {
        UUID shipment = packed(ftlOrder(2), true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        String v1 = loadOf(plan, 1);
        String v2 = loadOf(plan, 2);
        loadAll(v1, ghn);
        handOver(v1).andExpect(status().isOk());
        loadAll(v2, ghtk);

        cancelPlan(plan, "SAI_XE", "the wrong truck came").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_VEHICLE_DISPATCHED"));
        assertThat(planStatus(plan)).isEqualTo("CONFIRMED");
        assertThat(load(v2).path("status").asText()).isEqualTo("LOADING");
        JsonNode now = planOf(plan);
        dispatch(body(post(PLANS + "/" + plan.path("loadPlanId").asText() + "/cancellation"), "{\"expectedVersion\":"
                + now.path("version").asLong() + ",\"reason\":\"x\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LOAD_PLAN_LOADING_STARTED"));

        cancelVehicle(v1, XE_HONG).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
        JsonNode result = data(cancelVehicle(v2, "{\"reasonCode\":\"SAI_XE\",\"note\":\"the wrong truck came\"}")
                .andExpect(status().isOk()));
        assertThat(result.path("cancelled").path("cancelReasonCode").asText()).isEqualTo("SAI_XE");
        assertThat(result.path("replacement").path("status").asText()).isEqualTo("PLANNED");
        assertThat(planStatus(plan)).isEqualTo("CONFIRMED");
        assertThat(load(v1).path("status").asText()).as("the vehicle that left is untouched").isEqualTo("DISPATCHED");
        // Not loading yet: its booking is cancelled the old way, not under control.
        cancelVehicle(result.path("replacement").path("loadId").asText(), XE_HONG).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
        cancelVehicle(parcelLoad("51C-999.99"), XE_HONG).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
    }

    /** A parcel load straight into the table (a parcel load needs parcel shipments, which this context has none of). */
    private String parcelLoad(String plate) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO fulfillment.dispatch_load (id, code, request_id, warehouse_id, mode, carrier_id,
                    vehicle_plate, status, loading_started_at)
                VALUES (?, ?, gen_random_uuid(), ?, 'PARCEL', ?, ?, 'LOADING', NOW())""",
                id, "LD-UL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), HCM, ghn, plate));
        return id.toString();
    }

    // ---- review of #228, B1 ---------------------------------------------------------------------

    @Test
    @DisplayName("B1: a vehicle whose only order was cancelled while it loads (its parcels still to scan off) is called off "
            + "under control after the other vehicle left: no empty replacement is opened, the plan no longer waits for "
            + "it and is DISPATCHED; the cancelled order's parcels are still scanned off. Calling off a load that was "
            + "cancelled another way is 409 INVALID_LOAD_TRANSITION, not a silent 200")
    void emptiedVehicleLeavesNoReplacementAndDoesNotHoldThePlan() throws Exception {
        OrderSummary orderA = ftlOrder(1);
        UUID shipmentA = packed(orderA, true)[0];
        UUID shipmentB = packed(ftlOrder(1), true)[0];
        JsonNode plan = confirmed(List.of(shipmentA, shipmentB), truck1t25);
        assertThat(plan.path("vehicles")).hasSize(2);
        String vA = loadCarrying(plan, shipmentA);
        String vB = loadCarrying(plan, shipmentB);
        assertThat(vA).isNotEqualTo(vB);
        loadAll(vA, ghn);
        loadAll(vB, ghtk);
        List<String> parcelsOfA = labels(load(vA));
        handOver(vB).andExpect(status().isOk());
        assertThat(planStatus(plan)).isEqualTo("CONFIRMED");

        cancelOrder(orderA, shipmentA);
        JsonNode emptied = load(vA);
        assertThat(emptied.path("shipments")).isEmpty();
        assertThat(pendingLabels(emptied)).containsExactlyInAnyOrderElementsOf(parcelsOfA);
        assertThat(planStatus(plan)).isEqualTo("CONFIRMED");

        JsonNode result = data(cancelVehicle(vA, XE_HONG).andExpect(status().isOk()));
        assertThat(result.path("cancelled").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(result.path("cancelled").path("cancelReasonCode").asText()).isEqualTo("XE_HONG");
        assertThat(result.hasNonNull("replacement")).as("nothing to carry: no empty replacement").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.dispatch_load WHERE load_plan_vehicle_id = "
                + "(SELECT load_plan_vehicle_id FROM fulfillment.dispatch_load WHERE id = ?::uuid) AND status <> "
                + "'CANCELLED'", Integer.class, vA)).isZero();
        assertThat(planStatus(plan)).as("BR-15: the plan does not wait for the emptied vehicle").isEqualTo("DISPATCHED");
        assertThat(pendingLabels(load(vA))).containsExactlyInAnyOrderElementsOf(parcelsOfA);
        assertThat(load(vA).hasNonNull("replacementLoadId")).isFalse();
        // Again: answered as it is, still no replacement
        assertThat(data(cancelVehicle(vA, XE_HONG).andExpect(status().isOk())).hasNonNull("replacement")).isFalse();
        for (String label : parcelsOfA) {
            scanOff(vA, label).andExpect(status().isOk());
        }
        assertThat(pendingLabels(load(vA))).isEmpty();
    }

    @Test
    @DisplayName("review of #228: a load cancelled another way (the plan was reopened: plain cancel of its PLANNED load) is "
            + "not 'called off again' - 409 INVALID_LOAD_TRANSITION, not a 200 that would read as a replacement")
    void loadCancelledAnotherWayIsRefused() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(shipment), truck5t);
        String loadId = loadOf(plan, 1);
        JsonNode now = planOf(plan);
        step(now, "/reopening", "{\"expectedVersion\":" + now.path("version").asLong() + ",\"reason\":\"x\"}");
        assertThat(load(loadId).path("status").asText()).isEqualTo("CANCELLED");
        cancelVehicle(loadId, XE_HONG).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
    }

    // ---- point 4: the whole plan -----------------------------------------------------------------

    @Test
    @DisplayName("point 4: the whole plan is cancelled at once (KHAC + note) while a truck is loading - the plan, the "
            + "loading truck (parcels locked) and the booked truck (plain) are CANCELLED together; the plan lists the "
            + "parcels still to scan off; the shipment is no candidate and no new plan takes it (PACKAGE_UNLOAD_PENDING) "
            + "until every parcel is off, then it can be planned again; calling it off again changes nothing")
    void wholePlanCancelledAtOnce() throws Exception {
        UUID shipment = packed(ftlOrder(2), true)[0];
        JsonNode plan = confirmed(List.of(shipment), truck1t25);
        String v1 = loadOf(plan, 1);
        String v2 = loadOf(plan, 2);
        List<String> onTruck = labels(load(v1));
        loadAll(v1, ghn);
        book(v2, ghtk, "51C-777.77");

        JsonNode cancelled = data(cancelPlan(plan, "KHAC", "the customer moved the pickup").andExpect(status().isOk()));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("cancelReasonCode").asText()).isEqualTo("KHAC");
        assertThat(cancelled.path("cancelReason").asText()).isEqualTo("the customer moved the pickup");
        assertThat(cancelled.hasNonNull("cancelledAt")).isTrue();
        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM fulfillment.load_plan WHERE id = ?::uuid", UUID.class,
                plan.path("loadPlanId").asText())).isEqualTo(manager);
        JsonNode loading = load(v1);
        assertThat(loading.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(loading.path("cancelReasonCode").asText()).isEqualTo("KHAC");
        assertThat(loading.hasNonNull("replacementLoadId")).as("a whole-plan cancel has no replacement").isFalse();
        assertThat(pendingLabels(loading)).containsExactlyInAnyOrderElementsOf(onTruck);
        JsonNode booked = load(v2);
        assertThat(booked.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(booked.hasNonNull("cancelReasonCode")).as("a truck not loading is cancelled the plain way").isFalse();
        assertThat(booked.path("carrierBookingToCancel").asBoolean()).isTrue();
        assertThat(booked.path("pendingUnloads")).isEmpty();
        assertThat(pendingLabels(cancelled)).containsExactlyInAnyOrderElementsOf(onTruck);
        cancelled.path("pendingUnloads").forEach(u -> assertThat(u.path("loadId").asText()).isEqualTo(v1));
        assertThat(cancelled.path("pendingUnloads").get(0).path("vehicleSeq").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.load_plan_package WHERE load_plan_id = "
                + "?::uuid AND active", Integer.class, plan.path("loadPlanId").asText())).isZero();
        // Again: answered as it is.
        long version = cancelled.path("version").asLong();
        assertThat(data(cancelPlan(plan, "KHAC", "again").andExpect(status().isOk())).path("version").asLong())
                .isEqualTo(version);

        // Locked: no candidate, no new plan.
        assertThat(data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()))
                .findValuesAsText("shipmentId")).doesNotContain(shipment.toString());
        JsonNode taken = body(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"shipmentIds\":[\"" + shipment + "\"],\"vehicleTypeIds\":[\""
                + truck1t25 + "\"]}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_UNLOAD_PENDING")));
        assertThat(taken.path("message").asText()).contains(onTruck.getFirst());
        scanOff(v1, onTruck.getFirst()).andExpect(status().isOk());
        assertThat(data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()))
                .findValuesAsText("shipmentId")).as("one parcel is still on the truck").doesNotContain(shipment.toString());

        // Every parcel off: free, and planned again.
        scanOff(v1, onTruck.get(1)).andExpect(status().isOk());
        assertThat(pendingLabels(planOf(plan))).isEmpty();
        assertThat(data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()))
                .findValuesAsText("shipmentId")).contains(shipment.toString());
        JsonNode again = data(dispatch(body(post(PLANS), "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"warehouseId\":\"" + HCM + "\",\"shipmentIds\":[\"" + shipment + "\"],\"vehicleTypeIds\":[\""
                + truck1t25 + "\"]}")).andExpect(status().isCreated()));
        assertThat(again.path("status").asText()).isEqualTo("DRAFT");
    }

    // ---- points 6, 7: the permission and the body ----------------------------------------------

    @Test
    @DisplayName("points 6, 7: the controlled cancel and the override need fulfillment-controlled-cancellations:UPDATE - "
            + "the dispatcher and the coordinator get 403, a manager of another warehouse OUT_OF_DATA_SCOPE; the reason "
            + "code is one of the four (400 otherwise) and the note is required; a plan that is not CONFIRMED is "
            + "cancelled the plain way (INVALID_LOAD_PLAN_TRANSITION), a CONFIRMED one with nothing loading goes at once")
    void permissionAndBody() throws Exception {
        UUID shipment = packed(ftlOrder(1), false)[0];
        JsonNode draft = plan(List.of(shipment), truck5t);
        cancelPlan(draft, "KHAC", "x").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_PLAN_TRANSITION"));
        JsonNode plan = step(draft, "/calculation", version(draft));
        plan = step(plan, "/confirmation", version(plan));
        String planId = plan.path("loadPlanId").asText();
        String loadId = loadOf(plan, 1);
        String version = "{\"expectedVersion\":" + plan.path("version").asLong() + ",\"reasonCode\":\"XE_HONG\","
                + "\"note\":\"x\"}";
        String override = "{\"packageId\":\"" + UUID.randomUUID() + "\"}";
        for (String[] role : new String[][] {{"WAREHOUSE_STAFF", "d"}, {"ORDER_COORDINATOR", "c"}}) {
            UUID user = role[1].equals("d") ? dispatcher : coordinator;
            as(user, role[0], body(post(PLANS + "/" + planId + "/controlled-cancellation"), version))
                    .andExpect(status().isForbidden());
            as(user, role[0], body(post(LOADS + "/" + loadId + "/controlled-cancellation"), XE_HONG))
                    .andExpect(status().isForbidden());
            as(user, role[0], body(post(LOADS + "/" + loadId + "/unload-overrides"), override))
                    .andExpect(status().isForbidden());
        }
        as(managerElsewhere, "WAREHOUSE_MANAGER", body(post(PLANS + "/" + planId + "/controlled-cancellation"), version))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        as(managerElsewhere, "WAREHOUSE_MANAGER", body(post(LOADS + "/" + loadId + "/controlled-cancellation"), XE_HONG))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        assertThat(planStatus(plan)).isEqualTo("CONFIRMED");

        // The body: the four codes, a note.
        for (String bad : List.of("{\"reasonCode\":\"BROKEN\",\"note\":\"x\"}", "{\"note\":\"x\"}",
                "{\"reasonCode\":\"KHAC\"}", "{\"reasonCode\":\"KHAC\",\"note\":\"  \"}")) {
            cancelVehicle(loadId, bad).andExpect(status().isBadRequest());
        }
        wm(body(post(PLANS + "/" + planId + "/controlled-cancellation"), "{\"expectedVersion\":1,\"reasonCode\":\"NOPE\","
                + "\"note\":\"x\"}")).andExpect(status().isBadRequest());
        // The vehicle is not loading: the plain ways (booking, plan) apply.
        cancelVehicle(loadId, XE_HONG).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_LOAD_TRANSITION"));
        // A stale version is refused like every plan write.
        wm(body(post(PLANS + "/" + planId + "/controlled-cancellation"), "{\"expectedVersion\":999,\"reasonCode\":"
                + "\"KIEN_KHONG_VUA\",\"note\":\"x\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));

        // CONFIRMED with nothing loading: it goes at once; the PLANNED vehicle is cancelled the plain way.
        JsonNode gone = data(cancelPlan(plan, "KIEN_KHONG_VUA", "the carton does not fit").andExpect(status().isOk()));
        assertThat(gone.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(gone.path("cancelReasonCode").asText()).isEqualTo("KIEN_KHONG_VUA");
        assertThat(gone.path("pendingUnloads")).isEmpty();
        assertThat(load(loadId).path("status").asText()).isEqualTo("CANCELLED");
        // Nothing is to unload: the shipment is a candidate again at once.
        assertThat(data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()))
                .findValuesAsText("shipmentId")).contains(shipment.toString());
        // The plain (BR-14) cancel of a cancelled plan is untouched.
        assertThat(planStatus(gone)).isEqualTo("CANCELLED");
    }

    // ---- point 8 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("point 8: parcels already split off as 'left behind' stay as they are - a vehicle called off after the "
            + "split leaves the new shipment, its labels and the shipment it was split from untouched, and the "
            + "replacement starting to load splits nothing again")
    void leftBehindSplitsAreUntouched() throws Exception {
        OrderSummary order = ftlOrder(2);
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.customer_order SET allow_partial_shipment = TRUE WHERE id = ?", order.orderId()));
        UUID shipment = packed(order, false)[0];
        JsonNode plan = plan(List.of(shipment), truck5t);
        plan = step(plan, "/calculation", version(plan));
        List<String> parcels = plan.path("packages").findValuesAsText("packageId");
        String goes = parcels.get(0);
        String stays = parcels.get(1);
        plan = data(dispatch(body(put(PLANS + "/" + plan.path("loadPlanId").asText() + "/placements"),
                "{\"expectedVersion\":" + plan.path("version").asLong() + ",\"vehicles\":[{\"seq\":1,\"boxes\":[{\"ref\":\""
                        + goes + "\",\"xMm\":0,\"yMm\":0,\"zMm\":0,\"wMm\":950,\"hMm\":900,\"dMm\":2150,"
                        + "\"orientation\":\"LWH\"}]}],\"tray\":[\"" + stays + "\"]}")).andExpect(status().isOk()));
        plan = step(plan, "/unplaced-acknowledgement", "{\"expectedVersion\":" + plan.path("version").asLong()
                + ",\"note\":\"next truck\"}");
        plan = step(plan, "/confirmation", version(plan));
        String loadId = loadOf(plan, 1);
        book(loadId, ghn, "51C-555.55");
        dispatch(post(LOADS + "/" + loadId + "/loading-start")).andExpect(status().isOk());
        UUID rest = jdbc.queryForObject("SELECT shipment_id FROM fulfillment.package WHERE id = ?::uuid", UUID.class,
                stays);
        assertThat(rest).isNotEqualTo(shipment);
        Object outdated = jdbc.queryForObject("SELECT label_outdated_at FROM fulfillment.package WHERE id = ?::uuid",
                java.sql.Timestamp.class, stays);

        JsonNode result = data(cancelVehicle(loadId, XE_HONG).andExpect(status().isOk()));
        assertThat(result.path("replacement").path("status").asText()).isEqualTo("PLANNED");
        String again = result.path("replacement").path("loadId").asText();
        book(again, ghtk, "51C-666.66");
        dispatch(post(LOADS + "/" + again + "/loading-start")).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT shipment_id FROM fulfillment.package WHERE id = ?::uuid", UUID.class,
                stays)).isEqualTo(rest);
        assertThat(jdbc.queryForMap("SELECT status, package_count FROM fulfillment.shipment WHERE id = ?", rest))
                .containsEntry("status", "READY_TO_DISPATCH").containsEntry("package_count", 1);
        assertThat(jdbc.queryForObject("SELECT package_count FROM fulfillment.shipment WHERE id = ?", Integer.class,
                shipment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT label_outdated_at FROM fulfillment.package WHERE id = ?::uuid",
                java.sql.Timestamp.class, stays)).isEqualTo(outdated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fulfillment.shipment WHERE order_id = ?", Integer.class,
                order.orderId())).as("no third shipment: the split is not repeated").isEqualTo(2);
        // The same after the whole plan is called off: the left-behind shipment still waits for dispatch, as a candidate.
        cancelPlan(plan, "XE_HONG", "no truck today").andExpect(status().isOk());
        assertThat(shipmentStatus(rest)).isEqualTo("READY_TO_DISPATCH");
        assertThat(data(dispatch(get(BASE + "/load-plan-candidates?warehouseId=" + HCM)).andExpect(status().isOk()))
                .findValuesAsText("shipmentId")).contains(rest.toString());
    }

    // ---- point 9 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("point 9: an order cancelled while its truck loads still goes the old way (its parcel to unload, the "
            + "vehicle leaves with a gap); calling the truck off then keeps both kinds of pending unload - the order's "
            + "without a reason code, the rest with XE_HONG - and the replacement carries only the shipment that "
            + "is still wanted; an order cancelled AFTER the controlled cancel breaks nothing: its parcel is still "
            + "scanned off")
    void orderCancelledWhileLoadingBothWays() throws Exception {
        OrderSummary doomedOrder = ftlOrder(1);
        UUID doomed = packed(doomedOrder, false)[0];
        OrderSummary laterOrder = ftlOrder(1);
        UUID later = packed(laterOrder, false)[0];
        UUID kept = packed(ftlOrder(1), false)[0];
        JsonNode plan = confirmed(List.of(doomed, later, kept), truck5t);
        assertThat(plan.path("vehicles")).hasSize(1);
        String v1 = loadOf(plan, 1);
        loadAll(v1, ghn);
        String doomedLabel = jdbc.queryForObject("SELECT label_code FROM fulfillment.package WHERE shipment_id = ?",
                String.class, doomed);
        String laterLabel = jdbc.queryForObject("SELECT label_code FROM fulfillment.package WHERE shipment_id = ?",
                String.class, later);
        String keptLabel = jdbc.queryForObject("SELECT label_code FROM fulfillment.package WHERE shipment_id = ?",
                String.class, kept);

        cancelOrder(doomedOrder, doomed);
        assertThat(load(v1).path("pendingUnloads")).singleElement().satisfies(u -> {
            assertThat(u.path("labelCode").asText()).isEqualTo(doomedLabel);
            assertThat(u.hasNonNull("reasonCode")).isFalse();
        });
        handOver(v1).andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LOAD_UNLOAD_PENDING"));

        JsonNode result = data(cancelVehicle(v1, XE_HONG).andExpect(status().isOk()));
        JsonNode cancelled = result.path("cancelled");
        assertThat(pendingLabels(cancelled)).containsExactlyInAnyOrder(doomedLabel, laterLabel, keptLabel);
        Map<String, String> codes = new HashMap<>();
        cancelled.path("pendingUnloads").forEach(u -> codes.put(u.path("labelCode").asText(),
                u.path("reasonCode").asText("-")));
        assertThat(codes).containsEntry(doomedLabel, "-").containsEntry(laterLabel, "XE_HONG")
                .containsEntry(keptLabel, "XE_HONG");
        JsonNode replacement = result.path("replacement");
        assertThat(replacement.path("shipments").findValuesAsText("shipmentId")).containsExactlyInAnyOrder(
                later.toString(), kept.toString());

        // The order is cancelled after the truck was called off: its parcel is still on the truck and is scanned off.
        cancelOrder(laterOrder, later);
        assertThat(pendingLabels(load(v1))).contains(laterLabel);
        for (String label : List.of(doomedLabel, laterLabel, keptLabel)) {
            scanOff(v1, label).andExpect(status().isOk());
        }
        assertThat(pendingLabels(load(v1))).isEmpty();
        assertThat(load(v1).path("status").asText()).isEqualTo("CANCELLED");
    }

    // ---- the transfer's parcels are unloaded too (point 10 is in TransferLoadPlanningIntegrationTest) -----------
}
